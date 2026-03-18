package edu.uw.cse.sideeffect.analysis;

import edu.uw.cse.sideeffect.AnalysisConfig;
import edu.uw.cse.sideeffect.util.SafeMethods;

import java.util.*;
import sootup.core.jimple.basic.Value;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.expr.JSpecialInvokeExpr;
import sootup.core.jimple.common.expr.JStaticInvokeExpr;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JInvokeStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.ClassType;
import sootup.java.bytecode.inputlocation.JrtFileSystemAnalysisInputLocation;
import sootup.java.core.JavaSootClass;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.types.JavaClassType;
import sootup.java.core.views.JavaView;


/**
 * Builds a call graph (user code + JDK) and computes bottom-up analysis order.
 * For non-recursive programs, this is a reverse topological order.
 * For recursive programs (SCCs), methods in the same SCC are grouped into batches.
 */
public class CallGraphBuilder {

    /** Packages to exclude from BFS discovery — implementation internals with no analysis value. */
    private static final Set<String> FORBIDDEN_PREFIXES = Set.of(
            "sun.", "com.sun.", "jdk.internal.",
            "java.awt.", "javax.swing.",
            "java.nio.", "java.security.", "javax.crypto.",
            "java.lang.invoke.", "java.lang.reflect.",
            "java.util.concurrent."
    );

    /**
     * Result of call graph construction: the bottom-up analysis order plus the raw call graph.
     * @param batches       methods grouped by SCC in bottom-up order
     * @param callGraph     caller signature → set of callee signatures
     * @param overrideGraph base method signature → set of overriding method signatures
     *                      (populated only for methods in the explicitly-passed input classes)
     */
    public record Result(
            List<List<JavaSootMethod>> batches,
            Map<String, Set<String>> callGraph,
            Map<String, Set<String>> rawCallGraph,
            Map<String, Set<String>> overrideGraph
    ) {}

    /**
     * Compute the bottom-up analysis order for all concrete methods in the given classes,
     * plus all transitively reachable JDK/library methods discoverable via the view.
     *
     * @param initialClasses the user's input classes
     * @param view           JavaView including JRT (for resolving JDK methods)
     * @param config         analysis configuration (for debug output)
     * @return batches in bottom-up order
     */
    public static Result computeBottomUpOrder(
            Collection<JavaSootClass> initialClasses, JavaView view, AnalysisConfig config) {

        // Phase A: BFS class discovery — discover JDK classes reachable from user code.
        // Creates a separate JRT view for resolving JDK methods (lazy, only when needed).
        // Uses lightweight resolution (declared class only, no CHA subtypes) to avoid
        // triggering expensive TypeHierarchy construction and class explosion.
        JavaView jrtView = new JavaView(new JrtFileSystemAnalysisInputLocation());
        Set<JavaSootClass> discoveredClasses = new LinkedHashSet<>(initialClasses);
        Queue<JavaSootClass> pendingClasses = new ArrayDeque<>(initialClasses);
        int jdkClassesDiscovered = 0;
        int MAX_DISCOVERED_CLASSES = 200; // cap to prevent runaway BFS

        while (!pendingClasses.isEmpty() && discoveredClasses.size() < MAX_DISCOVERED_CLASSES) {
            JavaSootClass cls = pendingClasses.poll();
            for (JavaSootMethod m : cls.getMethods()) {
                if (!m.isConcrete()) continue;
                Body body;
                try {
                    body = m.getBody();
                } catch (Exception e) {
                    continue;
                }
                for (Stmt stmt : body.getStmtGraph().getStmts()) {
                    AbstractInvokeExpr invokeExpr = extractInvokeExpr(stmt);
                    if (invokeExpr == null) continue;

                    MethodSignature calleeSig = invokeExpr.getMethodSignature();

                    // Stop condition: SafeMethods
                    if (SafeMethods.isSafe(calleeSig)) continue;

                    // Stop condition: already in library cache
                    if (LibrarySummaryCache.contains(calleeSig.toString())) continue;

                    // Stop condition: forbidden package (check before resolving)
                    String declClassName = calleeSig.getDeclClassType().getFullyQualifiedName();
                    if (isForbiddenClassName(declClassName)) continue;

                    // Resolve to the declared class only (no CHA/subtypes)
                    // Try user view first, then JRT view
                    JavaSootMethod target = resolveDirectTarget(calleeSig, view);
                    if (target == null) {
                        target = resolveDirectTarget(calleeSig, jrtView);
                    }
                    if (target == null || !target.isConcrete()) continue;
                    if (SafeMethods.isSafe(target.getSignature())) continue;
                    if (isForbiddenPackage(target)) continue;

                    // Resolve the class from the appropriate view
                    JavaSootClass targetCls = null;
                    try {
                        var opt = view.getClass(target.getDeclaringClassType());
                        if (opt.isPresent()) {
                            targetCls = opt.get();
                        } else {
                            var jrtOpt = jrtView.getClass(target.getDeclaringClassType());
                            if (jrtOpt.isPresent()) targetCls = jrtOpt.get();
                        }
                    } catch (Exception e) {
                        continue;
                    }
                    if (targetCls != null && discoveredClasses.add(targetCls)) {
                        pendingClasses.add(targetCls);
                        jdkClassesDiscovered++;
                    }
                    if (discoveredClasses.size() >= MAX_DISCOVERED_CLASSES) break;
                }
                if (discoveredClasses.size() >= MAX_DISCOVERED_CLASSES) break;
            }
        }

        if (config.debug && jdkClassesDiscovered > 0) {
            System.out.println("Debug== BFS discovered " + jdkClassesDiscovered
                    + " additional JDK/library classes (total: " + discoveredClasses.size() + ")");
        }

        // Now run the existing call graph construction on the expanded class set
        return buildCallGraphAndOrder(discoveredClasses, initialClasses, view, config);
    }

    /**
     * Backward-compatible overload: no view, no BFS — only user classes.
     */
    public static Result computeBottomUpOrder(
            Collection<JavaSootClass> classes, AnalysisConfig config) {
        return buildCallGraphAndOrder(classes, classes, null, config);
    }

    /**
     * Core call graph construction + Tarjan SCC ordering.
     */
    private static Result buildCallGraphAndOrder(
            Collection<? extends JavaSootClass> allClasses,
            Collection<? extends JavaSootClass> inputClasses,
            JavaView view,
            AnalysisConfig config) {

        // Collect all concrete methods and build signature-to-method map
        Map<String, JavaSootMethod> methodBySig = new LinkedHashMap<>();
        Map<String, JavaSootMethod> methodBySubSig = new LinkedHashMap<>();

        for (JavaSootClass cls : allClasses) {
            for (JavaSootMethod method : cls.getMethods()) {
                if (!method.isConcrete()) continue;
                String sig = method.getSignature().toString();
                methodBySig.put(sig, method);
                String subSig = method.getSignature().getSubSignature().toString();
                methodBySubSig.put(subSig, method);
            }
        }

        // Build class-name → class lookup for override detection (input classes only)
        Map<String, JavaSootClass> classByName = new LinkedHashMap<>();
        for (JavaSootClass cls : inputClasses) {
            classByName.put(cls.getType().getFullyQualifiedName(), cls);
        }

        // Detect override relationships among the loaded (input) classes only.
        Map<String, Set<String>> overrideGraph = new LinkedHashMap<>();
        for (JavaSootClass cls : inputClasses) {
            Optional<JavaClassType> superTypeOpt = cls.getSuperclass();
            if (superTypeOpt.isEmpty()) continue;
            JavaSootClass superCls = classByName.get(superTypeOpt.get().getFullyQualifiedName());
            if (superCls == null) continue;
            for (JavaSootMethod method : cls.getMethods()) {
                if (!method.isConcrete()) continue;
                Optional<? extends JavaSootMethod> superMethod =
                        superCls.getMethod(method.getSignature().getSubSignature());
                if (superMethod.isPresent() && superMethod.get().isConcrete()) {
                    String baseSig     = superMethod.get().getSignature().toString();
                    String overrideSig = method.getSignature().toString();
                    overrideGraph.computeIfAbsent(baseSig, k -> new LinkedHashSet<>())
                                 .add(overrideSig);
                }
            }
        }

        // Build adjacency list: caller sig -> set of callee sigs
        Map<String, Set<String>> callGraph = new HashMap<>();
        for (var entry : methodBySig.entrySet()) {
            String callerSig = entry.getKey();
            JavaSootMethod method = entry.getValue();
            Set<String> callees = new HashSet<>();

            try {
                Body body = method.getBody();
                for (Stmt stmt : body.getStmtGraph().getStmts()) {
                    AbstractInvokeExpr invokeExpr = extractInvokeExpr(stmt);
                    if (invokeExpr == null) continue;

                    MethodSignature calleeMSig = invokeExpr.getMethodSignature();
                    String calleeFullSig = calleeMSig.toString();
                    String calleeSubSig = calleeMSig.getSubSignature().toString();

                    // Try exact match first
                    if (methodBySig.containsKey(calleeFullSig)) {
                        callees.add(calleeFullSig);
                    } else if (methodBySubSig.containsKey(calleeSubSig)) {
                        // Virtual/interface dispatch: resolve via sub-signature
                        callees.add(methodBySubSig.get(calleeSubSig).getSignature().toString());
                    }
                }
            } catch (Exception e) {
                // Skip methods that can't be analyzed
            }

            callGraph.put(callerSig, callees);
        }

        if (config.debug) {
            System.out.println("\nDebug== Call graph (direct invocations only):");
            for (var entry : callGraph.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    System.out.println("Debug==   " + entry.getKey() + " -> " + entry.getValue());
                }
            }
            System.out.println("\nDebug== Override graph (base -> overrides):");
            for (var entry : overrideGraph.entrySet()) {
                System.out.println("Debug==   " + entry.getKey() + " overridden by " + entry.getValue());
            }
        }

        // Snapshot the raw call graph before override augmentation
        Map<String, Set<String>> rawCallGraph = new HashMap<>();
        for (var e : callGraph.entrySet()) {
            rawCallGraph.put(e.getKey(), new HashSet<>(e.getValue()));
        }

        // Augment call graph with override edges
        for (Map.Entry<String, Set<String>> entry : callGraph.entrySet()) {
            Set<String> extras = new LinkedHashSet<>();
            for (String calleeSig : entry.getValue()) {
                Set<String> overrides = overrideGraph.getOrDefault(calleeSig, Collections.emptySet());
                extras.addAll(overrides);
            }
            entry.getValue().addAll(extras);
        }

        // Add direct edges from each base method to its overrides
        for (Map.Entry<String, Set<String>> entry : overrideGraph.entrySet()) {
            callGraph.computeIfAbsent(entry.getKey(), k -> new HashSet<>())
                     .addAll(entry.getValue());
        }

        if (config.debug) {
            System.out.println("\nDebug== Merged graph (call + override edges, used for analysis order):");
            for (var entry : callGraph.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    System.out.println("Debug==   " + entry.getKey() + " -> " + entry.getValue());
                }
            }
        }

        // Compute SCCs using Tarjan's algorithm
        List<List<String>> sccs = tarjanSCC(callGraph, methodBySig.keySet());

        // SCCs are returned in reverse topological order by Tarjan's (leaves first)
        List<List<JavaSootMethod>> result = new ArrayList<>();
        for (List<String> scc : sccs) {
            List<JavaSootMethod> batch = new ArrayList<>();
            for (String sig : scc) {
                JavaSootMethod m = methodBySig.get(sig);
                if (m != null) batch.add(m);
            }
            if (!batch.isEmpty()) {
                result.add(batch);
            }
        }

        if (config.debug) {
            System.out.println("Debug== Bottom-up analysis order (" + result.size() + " batches):");
            for (int i = 0; i < result.size(); i++) {
                List<String> names = result.get(i).stream()
                        .map(m -> m.getDeclaringClassType().getClassName() + "." + m.getName())
                        .toList();
                System.out.println("Debug==   batch " + i + ": " + names
                        + (result.get(i).size() > 1 ? " (SCC)" : ""));
            }
        }

        return new Result(result, callGraph, rawCallGraph, overrideGraph);
    }

    // --- BFS target resolution ---

    /**
     * Lightweight resolution: resolve to the method's declared class only.
     * Used during BFS discovery to avoid triggering TypeHierarchy construction.
     */
    private static JavaSootMethod resolveDirectTarget(MethodSignature sig, JavaView view) {
        try {
            return view.getClass(sig.getDeclClassType())
                    .flatMap(c -> c.getMethod(sig.getSubSignature()))
                    .filter(m -> ((JavaSootMethod) m).isConcrete())
                    .map(m -> (JavaSootMethod) m)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolve call targets from an invoke expression.
     * For static/special calls: single concrete target.
     * For virtual/interface calls: intercept java.lang.Object to avoid explosion,
     * otherwise use SootUp TypeHierarchy for CHA resolution.
     */
    private static List<JavaSootMethod> resolveTargets(AbstractInvokeExpr invoke, JavaView view) {
        MethodSignature sig = invoke.getMethodSignature();
        List<JavaSootMethod> targets = new ArrayList<>();

        if (invoke instanceof JStaticInvokeExpr || invoke instanceof JSpecialInvokeExpr) {
            // Single concrete target
            try {
                view.getClass(sig.getDeclClassType())
                    .flatMap(c -> c.getMethod(sig.getSubSignature()))
                    .filter(m -> ((JavaSootMethod) m).isConcrete())
                    .ifPresent(m -> targets.add((JavaSootMethod) m));
            } catch (Exception e) {
                // skip unresolvable
            }
        } else {
            // Virtual or interface call
            String declClass = sig.getDeclClassType().getFullyQualifiedName();

            // Object explosion trap: subtypesOf(Object) returns every class in the JDK
            if (declClass.equals("java.lang.Object")) {
                try {
                    view.getClass(sig.getDeclClassType())
                        .flatMap(c -> c.getMethod(sig.getSubSignature()))
                        .ifPresent(m -> targets.add((JavaSootMethod) m));
                } catch (Exception e) {
                    // skip
                }
                return targets;
            }

            // Also guard against other very broad types
            if (declClass.equals("java.lang.Comparable")
                    || declClass.equals("java.io.Serializable")
                    || declClass.equals("java.lang.Iterable")) {
                try {
                    view.getClass(sig.getDeclClassType())
                        .flatMap(c -> c.getMethod(sig.getSubSignature()))
                        .ifPresent(m -> targets.add((JavaSootMethod) m));
                } catch (Exception e) {
                    // skip
                }
                return targets;
            }

            // CHA via SootUp TypeHierarchy — with a cap to prevent runaway resolution
            try {
                var hierarchy = view.getTypeHierarchy();
                int count = 0;
                int MAX_SUBTYPES = 50;
                for (ClassType subtype : hierarchy.subtypesOf(sig.getDeclClassType()).toList()) {
                    if (count++ >= MAX_SUBTYPES) break;
                    try {
                        view.getClass(subtype)
                            .flatMap(c -> c.getMethod(sig.getSubSignature()))
                            .filter(m -> ((JavaSootMethod) m).isConcrete())
                            .ifPresent(m -> targets.add((JavaSootMethod) m));
                    } catch (Exception e) {
                        // skip individual unresolvable subtypes
                    }
                }
            } catch (Exception e) {
                // TypeHierarchy may fail for some types — fall back to declared class only
                try {
                    view.getClass(sig.getDeclClassType())
                        .flatMap(c -> c.getMethod(sig.getSubSignature()))
                        .filter(m -> ((JavaSootMethod) m).isConcrete())
                        .ifPresent(m -> targets.add((JavaSootMethod) m));
                } catch (Exception ex) {
                    // skip
                }
            }
        }
        return targets;
    }

    /** Check if a method belongs to a forbidden package. */
    private static boolean isForbiddenPackage(JavaSootMethod method) {
        return isForbiddenClassName(method.getDeclaringClassType().getFullyQualifiedName());
    }

    /** Check if a class name belongs to a forbidden package. */
    private static boolean isForbiddenClassName(String className) {
        for (String prefix : FORBIDDEN_PREFIXES) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Extract an invoke expression from a statement, if present */
    private static AbstractInvokeExpr extractInvokeExpr(Stmt stmt) {
        if (stmt instanceof JInvokeStmt invokeStmt) {
            return invokeStmt.getInvokeExpr();
        }
        if (stmt instanceof JAssignStmt assignStmt) {
            Value rhs = assignStmt.getRightOp();
            if (rhs instanceof AbstractInvokeExpr invokeExpr) {
                return invokeExpr;
            }
        }
        return null;
    }

    // --- Tarjan's SCC algorithm ---

    private static List<List<String>> tarjanSCC(Map<String, Set<String>> graph, Set<String> allNodes) {
        List<List<String>> result = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> lowlink = new HashMap<>();
        Set<String> onStack = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        int[] counter = {0};

        for (String node : allNodes) {
            if (!index.containsKey(node)) {
                tarjanDFS(node, graph, index, lowlink, onStack, stack, counter, result);
            }
        }

        return result;
    }

    private static void tarjanDFS(String v, Map<String, Set<String>> graph,
                                   Map<String, Integer> index, Map<String, Integer> lowlink,
                                   Set<String> onStack, Deque<String> stack,
                                   int[] counter, List<List<String>> result) {
        index.put(v, counter[0]);
        lowlink.put(v, counter[0]);
        counter[0]++;
        stack.push(v);
        onStack.add(v);

        for (String w : graph.getOrDefault(v, Set.of())) {
            if (!index.containsKey(w)) {
                tarjanDFS(w, graph, index, lowlink, onStack, stack, counter, result);
                lowlink.put(v, Math.min(lowlink.get(v), lowlink.get(w)));
            } else if (onStack.contains(w)) {
                lowlink.put(v, Math.min(lowlink.get(v), index.get(w)));
            }
        }

        if (lowlink.get(v).equals(index.get(v))) {
            List<String> scc = new ArrayList<>();
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                scc.add(w);
            } while (!w.equals(v));
            result.add(scc);
        }
    }
}
