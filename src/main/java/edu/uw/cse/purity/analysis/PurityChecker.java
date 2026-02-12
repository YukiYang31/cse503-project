package edu.uw.cse.purity.analysis;

import edu.uw.cse.purity.graph.*;
import edu.uw.cse.purity.graph.PointsToGraph.EdgeTarget;
import edu.uw.cse.purity.graph.PointsToGraph.MutatedField;
import sootup.core.signatures.FieldSignature;

import java.util.*;

/**
 * Determines method purity from the exit PointsToGraph.
 *
 * Algorithm (Sălcianu &amp; Rinard 2005):
 * 1. Compute set A = prestate nodes (BFS from ParameterNodes via OutsideEdges)
 * 2. Compute set B = globally escaped closure (BFS from E ∪ {nGBL} via all edges)
 * 3. Compute set W = mutated fields
 *    - If ⟨GlobalNode, f⟩ ∈ W → IMPURE (static field write)
 * 4. For each n ∈ A: (a) n ∉ B and (b) no ⟨n,f⟩ ∈ W
 *    - Constructor exception: ignore mutations for P0
 * 5. Otherwise → PURE
 */
public class PurityChecker {

    /**
     * Check purity of a method given its exit graph.
     *
     * @param exitGraph the points-to graph at method exit
     * @param isConstructor true if the method is a constructor (<init>)
     * @return a MethodSummary with the purity result and reason
     */
    public static MethodSummary check(String methodSig, PointsToGraph exitGraph,
                                       boolean isConstructor) {
        return check(methodSig, exitGraph, isConstructor, false);
    }

    public static MethodSummary check(String methodSig, PointsToGraph exitGraph,
                                       boolean isConstructor, boolean debug) {
        // Step 0: Validate graph invariants
        List<String> violations = exitGraph.validateInvariants();
        if (!violations.isEmpty()) {
            if (debug) System.out.println("Debug== [purity] graph invariant violation: " + String.join("; ", violations));
            return new MethodSummary(methodSig, exitGraph,
                MethodSummary.PurityResult.GRAPH_VIOLATION,
                String.join("; ", violations));
        }

        // Step 1: Compute set A (prestate nodes)
        Set<Node> setA = computePrestateNodes(exitGraph);

        // Step 2: Compute set B (globally escaped closure)
        Set<Node> setB = computeGloballyEscapedNodes(exitGraph);

        // Step 3: Compute set W
        Set<MutatedField> setW = exitGraph.getMutatedFields();

        // Step 3a: Check for static field writes (GlobalNode mutations in W)
        for (MutatedField mf : setW) {
            if (mf.node() instanceof GlobalNode) {
                String fieldName = mf.field() != null ? mf.field().getName() : "unknown";
                if (debug) System.out.println("Debug== [purity] GlobalNode mutation in W => IMPURE (writes to static field " + fieldName + ")");
                return new MethodSummary(methodSig, exitGraph,
                    MethodSummary.PurityResult.IMPURE,
                    "writes to static field " + fieldName);
            }
        }

        if (debug) {
            System.out.println("Debug== [purity] set A (prestate nodes): " + nodeSetStr(setA));
            System.out.println("Debug== [purity] set B (globally escaped): " + nodeSetStr(setB));
            System.out.println("Debug== [purity] set W (mutated fields): " + mutatedFieldsStr(setW));
        }

        // Step 5: For each n ∈ A, check (a) n ∉ B and (b) no ⟨n,f⟩ ∈ W
        for (Node n : setA) {
            // Check (a): n ∉ B
            if (setB.contains(n)) {
                if (debug) System.out.println("Debug== [purity] prestate node " + n.getId() + " ∈ set B (globally escaped) => IMPURE");
                return new MethodSummary(methodSig, exitGraph,
                    MethodSummary.PurityResult.IMPURE,
                    "prestate node " + n.getId() + " escapes to global scope");
            }

            // Check (b): no ⟨n,f⟩ ∈ W (with constructor exception for P0)
            for (MutatedField mf : setW) {
                if (mf.node().equals(n)) {
                    // Constructor exception: allow direct writes to this.f (P0)
                    if (isConstructor && n instanceof ParameterNode pn
                        && pn.getParamIndex() == 0) {
                        if (debug) System.out.println("Debug== [purity] constructor exception: allowing mutation of P0");
                        continue;
                    }

                    String fieldName = mf.field() != null ? mf.field().getName() : "array element";
                    if (debug) System.out.println("Debug== [purity] prestate node " + n.getId() + " mutated via " + fieldName + " => IMPURE");
                    return new MethodSummary(methodSig, exitGraph,
                        MethodSummary.PurityResult.IMPURE,
                        "mutates prestate node " + n.getId() + " via field " + fieldName);
                }
            }
        }

        // Step 6: PURE
        if (debug) System.out.println("Debug== [purity] no prestate mutations, no global escape => PURE");
        return new MethodSummary(methodSig, exitGraph,
            MethodSummary.PurityResult.PURE, null);
    }

    /**
     * Compute the set of prestate nodes: ParameterNodes plus all nodes
     * reachable from them via OutsideEdges (BFS).
     *
     * Prestate nodes represent objects that existed before the method was called.
     * Mutations to these nodes indicate impurity.
     */
    public static Set<Node> computePrestateNodes(PointsToGraph graph) {
        Set<Node> prestate = new HashSet<>();
        Queue<Node> worklist = new LinkedList<>();

        // Seed: all ParameterNodes
        for (Node n : graph.getAllNodes()) {
            if (n instanceof ParameterNode) {
                prestate.add(n);
                worklist.add(n);
            }
        }

        // BFS along outside edges
        while (!worklist.isEmpty()) {
            Node current = worklist.poll();
            Map<FieldSignature, Set<EdgeTarget>> fieldMap =
                graph.getEdges().getOrDefault(current, Map.of());

            for (Set<EdgeTarget> targets : fieldMap.values()) {
                for (EdgeTarget et : targets) {
                    if (et.type() == EdgeType.OUTSIDE) {
                        if (prestate.add(et.target())) {
                            worklist.add(et.target());
                        }
                    }
                }
            }
        }

        return prestate;
    }

    /**
     * Compute set B: all nodes reachable from E ∪ {nGBL} via any edges.
     * These are nodes potentially accessible (and mutable) by the entire program.
     */
    public static Set<Node> computeGloballyEscapedNodes(PointsToGraph graph) {
        Set<Node> escaped = new HashSet<>();
        Queue<Node> worklist = new LinkedList<>();

        // Seed: E (directly escaped nodes) ∪ {nGBL}
        for (Node n : graph.getGlobalEscaped()) {
            if (escaped.add(n)) worklist.add(n);
        }
        if (escaped.add(GlobalNode.INSTANCE)) {
            worklist.add(GlobalNode.INSTANCE);
        }

        // BFS along all edges (inside + outside)
        while (!worklist.isEmpty()) {
            Node current = worklist.poll();
            Map<FieldSignature, Set<EdgeTarget>> fieldMap =
                graph.getEdges().getOrDefault(current, Map.of());
            for (Set<EdgeTarget> targets : fieldMap.values()) {
                for (EdgeTarget et : targets) {
                    if (escaped.add(et.target())) {
                        worklist.add(et.target());
                    }
                }
            }
        }

        return escaped;
    }

    /** Format a set of nodes as "{id1, id2, ...}" for debug output. */
    static String nodeSetStr(Set<Node> nodes) {
        List<String> ids = nodes.stream().map(Node::getId).sorted().toList();
        return "{" + String.join(", ", ids) + "}";
    }

    /** Format mutated fields as "{(node, field), ...}" for debug output. */
    static String mutatedFieldsStr(Set<MutatedField> mutations) {
        List<String> strs = new ArrayList<>();
        for (MutatedField mf : mutations) {
            String fieldName = mf.field() != null ? mf.field().getName() : "[]";
            strs.add("⟨" + mf.node().getId() + ", " + fieldName + "⟩");
        }
        strs.sort(String::compareTo);
        return "{" + String.join(", ", strs) + "}";
    }
}
