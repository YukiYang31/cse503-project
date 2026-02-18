package edu.uw.cse.purity.analysis;

import edu.uw.cse.purity.AnalysisConfig;
import sootup.core.jimple.basic.Value;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JInvokeStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootClass;
import sootup.java.core.JavaSootMethod;

import java.util.*;

/**
 * Builds an intra-file call graph and computes bottom-up analysis order.
 * For non-recursive programs, this is a reverse topological order.
 * For recursive programs (SCCs), methods in the same SCC are grouped into batches.
 */
public class CallGraphBuilder {

    /**
     * Compute the bottom-up analysis order for all concrete methods in the given classes.
     * Returns a list of "batches" where:
     * - Each batch is a list of methods that form an SCC (or a single non-recursive method)
     * - Batches are ordered bottom-up: callees before callers
     *
     * @param classes the classes to analyze
     * @param config  analysis configuration (for debug output)
     * @return batches in bottom-up order
     */
    public static List<List<JavaSootMethod>> computeBottomUpOrder(
            Collection<JavaSootClass> classes, AnalysisConfig config) {

        // Collect all concrete methods and build signature-to-method map
        Map<String, JavaSootMethod> methodBySig = new LinkedHashMap<>();
        // Also index by sub-signature for virtual dispatch resolution
        Map<String, JavaSootMethod> methodBySubSig = new LinkedHashMap<>();

        for (JavaSootClass cls : classes) {
            for (JavaSootMethod method : cls.getMethods()) {
                if (!method.isConcrete()) continue;
                String sig = method.getSignature().toString();
                methodBySig.put(sig, method);
                String subSig = method.getSignature().getSubSignature().toString();
                methodBySubSig.put(subSig, method);
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
            System.out.println("\nDebug== Call graph:");
            for (var entry : callGraph.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    System.out.println("Debug==   " + entry.getKey() + " -> " + entry.getValue());
                }
            }
        }

        // Compute SCCs using Tarjan's algorithm
        List<List<String>> sccs = tarjanSCC(callGraph, methodBySig.keySet());

        // SCCs are returned in reverse topological order by Tarjan's (leaves first)
        // so we can use them directly as bottom-up order
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

        return result;
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
