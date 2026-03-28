package edu.uw.cse.sideeffect.analysis;

import edu.uw.cse.sideeffect.AnalysisConfig;
import edu.uw.cse.sideeffect.util.SafeMethods;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import sootup.core.jimple.basic.Value;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.expr.JInterfaceInvokeExpr;
import sootup.core.jimple.common.expr.JSpecialInvokeExpr;
import sootup.core.jimple.common.expr.JStaticInvokeExpr;
import sootup.core.jimple.common.expr.JVirtualInvokeExpr;
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
 * Builds the graphs used for analysis ordering:
 * a raw call graph, an override graph, and their merged dependency graph.
 * Computes bottom-up analysis order from that merged dependency graph.
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
     * Result of graph construction: the bottom-up analysis order plus the component graphs.
     * @param batches          methods grouped by SCC in bottom-up order
     * @param dependencyGraph  merged graph used for analysis order and Tarjan SCC
     * @param rawCallGraph     direct invocation graph only
     * @param overrideGraph    base/declared method signature → set of direct overriding signatures
     * @param reachableCachedLibraryMethods reachable library methods whose summaries already exist on disk
     */
    public record Result(
            List<List<JavaSootMethod>> batches,
            Map<String, Set<String>> dependencyGraph,
            Map<String, Set<String>> rawCallGraph,
            Map<String, Set<String>> overrideGraph,
            Set<String> reachableCachedLibraryMethods
    ) {}

    /**
     * Compute the bottom-up analysis order for all concrete methods in the given classes,
     * plus all transitively reachable uncached JDK/library methods discoverable via method-based BFS.
     */
    public static Result computeBottomUpOrder(
            Collection<JavaSootClass> initialClasses, JavaView view, AnalysisConfig config) {

        JavaView jrtView = new JavaView(new JrtFileSystemAnalysisInputLocation());
        Set<JavaSootClass> discoveredClasses = new LinkedHashSet<>(initialClasses);
        Set<String> initialClassNames = new LinkedHashSet<>();
        for (JavaSootClass cls : initialClasses) {
            initialClassNames.add(cls.getType().getFullyQualifiedName());
        }
        Queue<JavaSootMethod> pendingMethods = new ArrayDeque<>();
        Set<String> queuedMethodSignatures = new HashSet<>();
        for (JavaSootClass cls : initialClasses) {
            for (JavaSootMethod method : cls.getMethods()) {
                if (!method.isConcrete()) continue;
                String methodSig = method.getSignature().toString();
                if (queuedMethodSignatures.add(methodSig)) {
                    pendingMethods.add(method);
                }
            }
        }
        Set<String> reachableLibraryMethods = new HashSet<>();
        Set<String> reachableCachedLibraryMethods = new HashSet<>();
        Map<String, Set<String>> externalEdges = new HashMap<>();
        int jdkMethodsDiscovered = 0;
        int maxDiscoveredLibraryMethods = 200;

        while (!pendingMethods.isEmpty() && reachableLibraryMethods.size() < maxDiscoveredLibraryMethods) {
            JavaSootMethod method = pendingMethods.poll();
            if (!method.isConcrete()) continue;

            Body body;
            try {
                body = method.getBody();
            } catch (Exception e) {
                continue;
            }

            String callerSig = method.getSignature().toString();
            for (Stmt stmt : body.getStmtGraph().getStmts()) {
                AbstractInvokeExpr invokeExpr = extractInvokeExpr(stmt);
                if (invokeExpr == null) continue;

                MethodSignature calleeSig = invokeExpr.getMethodSignature();

                if (SafeMethods.isSafe(calleeSig)) {
                    externalEdges.computeIfAbsent(callerSig, k -> new LinkedHashSet<>())
                            .add(calleeSig + " [safe]");
                    continue;
                }

                if (LibrarySummaryCache.contains(calleeSig.toString())) {
                    reachableCachedLibraryMethods.add(calleeSig.toString());
                    externalEdges.computeIfAbsent(callerSig, k -> new LinkedHashSet<>())
                            .add(calleeSig + " [cached]");
                    continue;
                }

                String declClassName = calleeSig.getDeclClassType().getFullyQualifiedName();
                if (isForbiddenClassName(declClassName)) {
                    externalEdges.computeIfAbsent(callerSig, k -> new LinkedHashSet<>())
                            .add(calleeSig + " [forbidden]");
                    continue;
                }

                Set<JavaSootMethod> targets = new LinkedHashSet<>();
                JavaSootMethod directTarget = resolveDirectTarget(calleeSig, view);
                if (directTarget == null) {
                    directTarget = resolveDirectTarget(calleeSig, jrtView);
                }
                if (directTarget != null && directTarget.isConcrete()) {
                    targets.add(directTarget);
                }

                if (isVirtualDispatch(invokeExpr)) {
                    if (canResolveDeclClass(view, calleeSig)) {
                        targets.addAll(resolveTargets(invokeExpr, view));
                    }
                    if (canResolveDeclClass(jrtView, calleeSig)) {
                        targets.addAll(resolveTargets(invokeExpr, jrtView));
                    }
                }

                for (JavaSootMethod target : targets) {
                    if (SafeMethods.isSafe(target.getSignature()) || isForbiddenPackage(target)) {
                        continue;
                    }

                    String targetSig = target.getSignature().toString();
                    if (LibrarySummaryCache.contains(targetSig)) {
                        reachableCachedLibraryMethods.add(targetSig);
                        continue;
                    }

                    JavaSootClass targetCls = resolveClass(target.getDeclaringClassType(), view, jrtView);
                    if (targetCls != null) {
                        discoveredClasses.add(targetCls);
                    }

                    String targetClassName = target.getDeclaringClassType().getFullyQualifiedName();
                    if (!initialClassNames.contains(targetClassName) && reachableLibraryMethods.add(targetSig)) {
                        jdkMethodsDiscovered++;
                    }

                    if (queuedMethodSignatures.add(targetSig)) {
                        pendingMethods.add(target);
                    }
                    if (reachableLibraryMethods.size() >= maxDiscoveredLibraryMethods) break;
                }
                if (reachableLibraryMethods.size() >= maxDiscoveredLibraryMethods) break;
            }
        }

        if (config.debug && jdkMethodsDiscovered > 0) {
            System.out.println("Debug== BFS discovered " + jdkMethodsDiscovered
                    + " additional JDK/library methods across " + discoveredClasses.size() + " classes");
        }

        return buildCallGraphAndOrder(
                discoveredClasses, initialClassNames, reachableLibraryMethods,
                reachableCachedLibraryMethods, view, config, externalEdges);
    }

    /** Backward-compatible overload: no view, no BFS — only user classes. */
    public static Result computeBottomUpOrder(
            Collection<JavaSootClass> classes, AnalysisConfig config) {
        Set<String> initialClassNames = new LinkedHashSet<>();
        for (JavaSootClass cls : classes) {
            initialClassNames.add(cls.getType().getFullyQualifiedName());
        }
        return buildCallGraphAndOrder(
                classes, initialClassNames, Collections.emptySet(), Collections.emptySet(),
                null, config, Collections.emptyMap());
    }

    /** Core call graph construction + Tarjan SCC ordering. */
    private static Result buildCallGraphAndOrder(
            Collection<? extends JavaSootClass> seedClasses,
            Set<String> initialClassNames,
            Set<String> reachableLibraryMethods,
            Set<String> reachableCachedLibraryMethods,
            JavaView view,
            AnalysisConfig config,
            Map<String, Set<String>> externalEdges) {

        JavaView jrtViewForResolution = view != null
                ? new JavaView(new JrtFileSystemAnalysisInputLocation())
                : null;
        Set<JavaSootClass> allClasses = expandHierarchy(seedClasses, view, jrtViewForResolution);

        Map<String, JavaSootMethod> concreteMethodBySig = new LinkedHashMap<>();
        for (JavaSootClass cls : allClasses) {
            for (JavaSootMethod method : cls.getMethods()) {
                if (!method.isConcrete()) continue;

                String methodSig = method.getSignature().toString();
                if (SafeMethods.isSafe(method.getSignature()) || LibrarySummaryCache.contains(methodSig)) {
                    continue;
                }

                String className = cls.getType().getFullyQualifiedName();
                boolean isUserClass = initialClassNames.contains(className);
                if (isUserClass || reachableLibraryMethods.contains(methodSig)) {
                    concreteMethodBySig.put(methodSig, method);
                }
            }
        }

        Map<String, JavaSootClass> classByName = new LinkedHashMap<>();
        for (JavaSootClass cls : allClasses) {
            classByName.put(cls.getType().getFullyQualifiedName(), cls);
        }

        Map<String, Set<String>> overrideGraph =
                buildOverrideGraph(allClasses, classByName, view, jrtViewForResolution);

        Map<String, Set<String>> dependencyGraph = new HashMap<>();
        for (var entry : concreteMethodBySig.entrySet()) {
            String callerSig = entry.getKey();
            JavaSootMethod method = entry.getValue();
            Set<String> callees = new LinkedHashSet<>();

            try {
                Body body = method.getBody();
                for (Stmt stmt : body.getStmtGraph().getStmts()) {
                    AbstractInvokeExpr invokeExpr = extractInvokeExpr(stmt);
                    if (invokeExpr == null) continue;

                    MethodSignature calleeMSig = invokeExpr.getMethodSignature();
                    String calleeFullSig = calleeMSig.toString();

                    Set<String> resolvedTargets = new LinkedHashSet<>();
                    if (view != null) {
                        if (canResolveDeclClass(view, calleeMSig)) {
                            for (JavaSootMethod t : resolveTargets(invokeExpr, view)) {
                                resolvedTargets.add(t.getSignature().toString());
                            }
                        }
                        if (jrtViewForResolution != null && canResolveDeclClass(jrtViewForResolution, calleeMSig)) {
                            for (JavaSootMethod t : resolveTargets(invokeExpr, jrtViewForResolution)) {
                                resolvedTargets.add(t.getSignature().toString());
                            }
                        }
                    }

                    if (resolvedTargets.isEmpty() && concreteMethodBySig.containsKey(calleeFullSig)) {
                        resolvedTargets.add(calleeFullSig);
                    }

                    Set<String> concreteTargets = new LinkedHashSet<>();
                    for (String targetSig : resolvedTargets) {
                        if (concreteMethodBySig.containsKey(targetSig)) {
                            concreteTargets.add(targetSig);
                        }
                    }

                    callees.addAll(concreteTargets);

                    Set<String> overrides = overrideGraph.getOrDefault(calleeFullSig, Set.of());
                    for (String overrideSig : overrides) {
                        if (concreteMethodBySig.containsKey(overrideSig)) {
                            callees.add(overrideSig);
                        }
                    }
                }
            } catch (Exception e) {
                // Skip methods that can't be analyzed
            }

            dependencyGraph.put(callerSig, callees);
        }

        Map<String, Set<String>> rawCallGraph = new HashMap<>();
        for (var e : dependencyGraph.entrySet()) {
            rawCallGraph.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
        }
        for (var e : externalEdges.entrySet()) {
            rawCallGraph.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(e.getValue());
        }

        for (Map.Entry<String, Set<String>> entry : overrideGraph.entrySet()) {
            Set<String> concreteOverrides = new LinkedHashSet<>();
            for (String overrideSig : entry.getValue()) {
                if (concreteMethodBySig.containsKey(overrideSig)) {
                    concreteOverrides.add(overrideSig);
                }
            }
            if (!concreteOverrides.isEmpty()) {
                dependencyGraph.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<>()).addAll(concreteOverrides);
            }
        }

        if (config.debug) {
            System.out.println("\nDebug== Call graph (direct invocations only):");
            for (var entry : rawCallGraph.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    System.out.println("Debug==   " + entry.getKey() + " -> " + entry.getValue());
                }
            }
            System.out.println("\nDebug== Override graph (base -> direct overrides):");
            for (var entry : overrideGraph.entrySet()) {
                System.out.println("Debug==   " + entry.getKey() + " overridden by " + entry.getValue());
            }
            System.out.println("\nDebug== Dependency graph (raw call graph + override edges, used for analysis order):");
            for (var entry : dependencyGraph.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    System.out.println("Debug==   " + entry.getKey() + " -> " + entry.getValue());
                }
            }
        }

        // Tarjan runs on the merged dependency graph, not on the raw call graph alone.
        List<List<String>> sccs = tarjanSCC(dependencyGraph, concreteMethodBySig.keySet());

        List<List<JavaSootMethod>> result = new ArrayList<>();
        for (List<String> scc : sccs) {
            List<JavaSootMethod> batch = new ArrayList<>();
            for (String sig : scc) {
                JavaSootMethod method = concreteMethodBySig.get(sig);
                if (method != null) {
                    batch.add(method);
                }
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

        return new Result(result, dependencyGraph, rawCallGraph, overrideGraph,
                Set.copyOf(reachableCachedLibraryMethods));
    }

    private static Set<JavaSootClass> expandHierarchy(Collection<? extends JavaSootClass> seedClasses,
                                                       JavaView view,
                                                       JavaView jrtView) {
        Set<JavaSootClass> allClasses = new LinkedHashSet<>(seedClasses);
        Deque<JavaSootClass> work = new ArrayDeque<>(seedClasses);

        while (!work.isEmpty()) {
            JavaSootClass cls = work.pop();
            for (ClassType parentType : immediateParentsOf(cls)) {
                JavaSootClass parent = resolveClass(parentType, view, jrtView);
                if (parent != null && allClasses.add(parent)) {
                    work.push(parent);
                }
            }
        }
        return allClasses;
    }

    private static Map<String, Set<String>> buildOverrideGraph(
            Collection<JavaSootClass> allClasses,
            Map<String, JavaSootClass> classByName,
            JavaView view,
            JavaView jrtView) {

        Map<String, Set<String>> overrideGraph = new LinkedHashMap<>();
        for (JavaSootClass cls : allClasses) {
            List<JavaSootClass> parents = new ArrayList<>();
            for (ClassType parentType : immediateParentsOf(cls)) {
                JavaSootClass parentCls = classByName.get(parentType.getFullyQualifiedName());
                if (parentCls == null) {
                    parentCls = resolveClass(parentType, view, jrtView);
                }
                if (parentCls != null) {
                    parents.add(parentCls);
                }
            }

            for (JavaSootMethod childMethod : cls.getMethods()) {
                if (!isDispatchCandidate(childMethod)) continue;
                for (JavaSootClass parentCls : parents) {
                    parentCls.getMethod(childMethod.getSignature().getSubSignature())
                            .filter(CallGraphBuilder::isDispatchCandidate)
                            .ifPresent(parentMethod -> overrideGraph
                                    .computeIfAbsent(parentMethod.getSignature().toString(), k -> new LinkedHashSet<>())
                                    .add(childMethod.getSignature().toString()));
                }
            }
        }
        return overrideGraph;
    }

    private static List<ClassType> immediateParentsOf(JavaSootClass cls) {
        List<ClassType> parents = new ArrayList<>();
        cls.getSuperclass().ifPresent(parents::add);
        for (ClassType iface : cls.getInterfaces()) {
            parents.add(iface);
        }
        return parents;
    }

    private static boolean isDispatchCandidate(JavaSootMethod method) {
        return !method.isStatic()
                && !method.isPrivate()
                && !Objects.equals(method.getName(), "<init>")
                && !Objects.equals(method.getName(), "<clinit>");
    }

    private static JavaSootClass resolveClass(ClassType classType, JavaView view, JavaView jrtView) {
        if (view != null) {
            try {
                Optional<JavaSootClass> resolved = view.getClass(classType);
                if (resolved.isPresent()) {
                    return resolved.get();
                }
            } catch (Exception ignored) {
            }
        }
        if (jrtView != null) {
            try {
                return jrtView.getClass(classType).orElse(null);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** Lightweight resolution: resolve to the method's declared class only. */
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
     * For virtual/interface calls: use capped CHA and resolve inherited implementations too.
     */
    private static List<JavaSootMethod> resolveTargets(AbstractInvokeExpr invoke, JavaView view) {
        MethodSignature sig = invoke.getMethodSignature();
        LinkedHashSet<JavaSootMethod> targets = new LinkedHashSet<>();

        if (invoke instanceof JStaticInvokeExpr || invoke instanceof JSpecialInvokeExpr) {
            try {
                view.getClass(sig.getDeclClassType())
                        .flatMap(c -> c.getMethod(sig.getSubSignature()))
                        .filter(m -> ((JavaSootMethod) m).isConcrete())
                        .ifPresent(m -> targets.add((JavaSootMethod) m));
            } catch (Exception e) {
                // skip unresolvable
            }
            return new ArrayList<>(targets);
        }

        String declClass = sig.getDeclClassType().getFullyQualifiedName();
        if (declClass.equals("java.lang.Object")
                || declClass.equals("java.lang.Comparable")
                || declClass.equals("java.io.Serializable")
                || declClass.equals("java.lang.Iterable")) {
            JavaSootMethod inherited = resolveConcreteImplementation(sig.getDeclClassType(), sig, view);
            if (inherited != null) {
                targets.add(inherited);
            }
            return new ArrayList<>(targets);
        }

        try {
            var hierarchy = view.getTypeHierarchy();
            int count = 0;
            int maxSubtypes = 50;
            for (ClassType subtype : hierarchy.subtypesOf(sig.getDeclClassType()).toList()) {
                if (count++ >= maxSubtypes) break;
                JavaSootMethod target = resolveConcreteImplementation(subtype, sig, view);
                if (target != null) {
                    targets.add(target);
                }
            }
        } catch (Exception e) {
            JavaSootMethod fallback = resolveConcreteImplementation(sig.getDeclClassType(), sig, view);
            if (fallback != null) {
                targets.add(fallback);
            }
        }
        return new ArrayList<>(targets);
    }

    private static JavaSootMethod resolveConcreteImplementation(ClassType startType,
                                                                 MethodSignature targetSig,
                                                                 JavaView view) {
        ClassType current = startType;
        Set<String> seen = new HashSet<>();
        while (current != null && seen.add(current.getFullyQualifiedName())) {
            try {
                Optional<JavaSootClass> clsOpt = view.getClass(current);
                if (clsOpt.isEmpty()) return null;
                JavaSootClass cls = clsOpt.get();
                Optional<JavaSootMethod> methodOpt = cls.getMethod(targetSig.getSubSignature());
                if (methodOpt.isPresent() && methodOpt.get().isConcrete()) {
                    return methodOpt.get();
                }
                current = cls.getSuperclass().orElse(null);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static boolean isVirtualDispatch(AbstractInvokeExpr invokeExpr) {
        return (invokeExpr instanceof JVirtualInvokeExpr)
                || (invokeExpr instanceof JInterfaceInvokeExpr);
    }

    private static boolean canResolveDeclClass(JavaView view, MethodSignature sig) {
        try {
            return view.getClass(sig.getDeclClassType()).isPresent();
        } catch (Exception e) {
            return false;
        }
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

    /** Extract an invoke expression from a statement, if present. */
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
