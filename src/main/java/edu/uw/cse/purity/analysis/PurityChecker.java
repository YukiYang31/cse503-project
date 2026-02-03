package edu.uw.cse.purity.analysis;

import edu.uw.cse.purity.graph.*;
import edu.uw.cse.purity.graph.PointsToGraph.EdgeTarget;
import edu.uw.cse.purity.graph.PointsToGraph.MutatedField;
import sootup.core.signatures.FieldSignature;

import java.util.*;

/**
 * Determines method purity from the exit PointsToGraph.
 *
 * Algorithm (corrected per Critical Fixes #2 and #4):
 * 1. If hasGlobalSideEffect → IMPURE (static field write)
 * 2. Compute prestate nodes (BFS from ParameterNodes via OutsideEdges)
 * 3. Check mutations: if any prestate node was mutated → IMPURE
 *    - Constructor exception: direct writes to this.f are allowed
 * 4. Check global escape of prestate nodes → IMPURE
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
        // Step 1: Immediate check for static field writes (Fix #4)
        if (exitGraph.hasGlobalSideEffect()) {
            return new MethodSummary(methodSig, exitGraph,
                MethodSummary.PurityResult.IMPURE,
                "writes to static field");
        }

        // Step 2: Compute prestate nodes
        Set<Node> prestateNodes = computePrestateNodes(exitGraph);

        // Step 3: Check mutations
        for (MutatedField mf : exitGraph.getMutatedFields()) {
            Node mutatedNode = mf.node();

            // Skip GlobalNode mutations (already handled by hasGlobalSideEffect)
            if (mutatedNode instanceof GlobalNode) continue;

            // Is this a prestate node?
            if (prestateNodes.contains(mutatedNode)) {
                // Constructor exception (Fix #2):
                // Allow direct writes to this.f (ParameterNode(0))
                // but NOT writes to objects reachable through this
                if (isConstructor && mutatedNode instanceof ParameterNode pn
                    && pn.getParamIndex() == 0) {
                    continue; // this.f = x is allowed in constructors
                }

                String fieldName = mf.field() != null ? mf.field().getName() : "array element";
                return new MethodSummary(methodSig, exitGraph,
                    MethodSummary.PurityResult.IMPURE,
                    "mutates prestate node " + mutatedNode.getId() + " via field " + fieldName);
            }
        }

        // Step 4: Check if any prestate node escaped to global scope
        for (Node prestateNode : prestateNodes) {
            if (exitGraph.isGloballyEscaped(prestateNode)) {
                return new MethodSummary(methodSig, exitGraph,
                    MethodSummary.PurityResult.IMPURE,
                    "prestate node " + prestateNode.getId() + " escapes to global scope");
            }
        }

        // Step 5: PURE
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
}
