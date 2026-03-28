package edu.uw.cse.sideeffect.analysis;

import edu.uw.cse.sideeffect.graph.GlobalNode;
import edu.uw.cse.sideeffect.graph.InsideNode;
import edu.uw.cse.sideeffect.graph.LoadNode;
import edu.uw.cse.sideeffect.graph.Node;
import edu.uw.cse.sideeffect.graph.ParameterNode;
import edu.uw.cse.sideeffect.graph.PointsToGraph;
import edu.uw.cse.sideeffect.graph.PointsToGraph.EdgeTarget;
import edu.uw.cse.sideeffect.graph.PointsToGraph.MutatedField;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import sootup.core.signatures.FieldSignature;


/**
 * Stores the analysis result for a single method.
 * Contains the exit PointsToGraph, the side-effect verdict, and return targets
 * for inter-procedural summary instantiation.
 */
public class MethodSummary {

    public enum SideEffectResult {
        SIDE_EFFECT_FREE,
        SIDE_EFFECTING,
        GRAPH_VIOLATION
    }

    private final String methodSignature;
    private final PointsToGraph exitGraph;
    private final SideEffectResult result;
    private final List<String> reasons; // empty if SIDE_EFFECT_FREE
    private final Set<Node> returnTargets;

    public MethodSummary(String methodSignature, PointsToGraph exitGraph,
                         SideEffectResult result, List<String> reasons, Set<Node> returnTargets) {
        this.methodSignature = methodSignature;
        this.exitGraph = exitGraph;
        this.result = result;
        this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
        this.returnTargets = returnTargets == null ? Set.of() : Set.copyOf(returnTargets);
    }

    public MethodSummary(String methodSignature, PointsToGraph exitGraph,
                         SideEffectResult result, List<String> reasons) {
        this(methodSignature, exitGraph, result, reasons, Set.of());
    }

    /** Convenience constructor for a single reason string (e.g. override propagation). */
    public MethodSummary(String methodSignature, PointsToGraph exitGraph,
                         SideEffectResult result, String reason, Set<Node> returnTargets) {
        this(methodSignature, exitGraph, result,
             reason == null ? List.of() : List.of(reason), returnTargets);
    }

    /** Convenience constructor for a single reason string. */
    public MethodSummary(String methodSignature, PointsToGraph exitGraph,
                         SideEffectResult result, String reason) {
        this(methodSignature, exitGraph, result,
             reason == null ? List.of() : List.of(reason), Set.of());
    }

    public String getMethodSignature() {
        return methodSignature;
    }

    public PointsToGraph getExitGraph() {
        return exitGraph;
    }

    public SideEffectResult getResult() {
        return result;
    }

    /** Returns all reasons for the verdict. Empty for SIDE_EFFECT_FREE. */
    public List<String> getReasons() {
        return Collections.unmodifiableList(reasons);
    }

    /** Returns all reasons joined by "; " for display purposes. Null if no reasons. */
    public String getReason() {
        return reasons.isEmpty() ? null : String.join("; ", reasons);
    }

    public Set<Node> getReturnTargets() {
        return Collections.unmodifiableSet(returnTargets);
    }

    /**
     * Normalize a direct method summary into a stable contributor namespace.
     * Parameter/global nodes stay shared; inside/load nodes are renamed so later unions are
     * exact-match and idempotent for that contributor.
     */
    public MethodSummary namespacedTo(String targetMethodSig, String contributorSig) {
        PointsToGraph namespacedGraph = new PointsToGraph();
        Map<Node, Node> nodeMap = new java.util.HashMap<>();

        for (var entry : exitGraph.getEdges().entrySet()) {
            Node src = namespaceNode(entry.getKey(), contributorSig, nodeMap);
            for (var fieldEntry : entry.getValue().entrySet()) {
                FieldSignature field = fieldEntry.getKey();
                for (EdgeTarget et : fieldEntry.getValue()) {
                    Node target = namespaceNode(et.target(), contributorSig, nodeMap);
                    if (et.type() == edu.uw.cse.sideeffect.graph.EdgeType.INSIDE) {
                        namespacedGraph.addInsideEdge(src, field, target);
                    } else {
                        namespacedGraph.addOutsideEdge(src, field, target);
                    }
                }
            }
        }

        for (MutatedField mf : exitGraph.getMutatedFields()) {
            namespacedGraph.recordMutation(namespaceNode(mf.node(), contributorSig, nodeMap), mf.field());
        }

        for (Node n : exitGraph.getGlobalEscaped()) {
            namespacedGraph.markGlobalEscaped(namespaceNode(n, contributorSig, nodeMap));
        }

        Set<Node> namespacedReturns = new LinkedHashSet<>();
        for (Node n : returnTargets) {
            Node remapped = namespaceNode(n, contributorSig, nodeMap);
            namespacedReturns.add(remapped);
        }
        namespacedGraph.addReturnTargets(namespacedReturns);

        return new MethodSummary(targetMethodSig, namespacedGraph, result, reasons, namespacedReturns);
    }

    /**
     * Union two summaries into a summary stored at {@code targetMethodSig}.
     * This is used for dynamic dispatch: the declared/base method summary is the union of its
     * own implementation and all implementation summaries below it.
     */
    public static MethodSummary union(String targetMethodSig,
                                      MethodSummary first,
                                      MethodSummary second) {
        if (first == null) return rebindSignature(second, targetMethodSig);
        if (second == null) return rebindSignature(first, targetMethodSig);

        PointsToGraph merged = first.getExitGraph().copy();
        merged.mergeWith(second.getExitGraph());

        Set<Node> mergedReturns = new LinkedHashSet<>(first.getReturnTargets());
        mergedReturns.addAll(second.getReturnTargets());
        merged.addReturnTargets(mergedReturns);

        List<String> mergedReasons = new ArrayList<>(first.getReasons());
        for (String reason : second.getReasons()) {
            if (!mergedReasons.contains(reason)) {
                mergedReasons.add(reason);
            }
        }

        SideEffectResult mergedResult = combine(first.getResult(), second.getResult());
        return new MethodSummary(targetMethodSig, merged, mergedResult, mergedReasons, mergedReturns);
    }

    private static MethodSummary rebindSignature(MethodSummary summary, String targetMethodSig) {
        if (summary == null || summary.getMethodSignature().equals(targetMethodSig)) {
            return summary;
        }
        return new MethodSummary(targetMethodSig, summary.getExitGraph().copy(),
                summary.getResult(), summary.getReasons(),
                new LinkedHashSet<>(summary.getReturnTargets()));
    }

    private static SideEffectResult combine(SideEffectResult left, SideEffectResult right) {
        if (left == SideEffectResult.GRAPH_VIOLATION || right == SideEffectResult.GRAPH_VIOLATION) {
            return SideEffectResult.GRAPH_VIOLATION;
        }
        if (left == SideEffectResult.SIDE_EFFECTING || right == SideEffectResult.SIDE_EFFECTING) {
            return SideEffectResult.SIDE_EFFECTING;
        }
        return SideEffectResult.SIDE_EFFECT_FREE;
    }

    private static Node namespaceNode(Node node, String contributorSig, Map<Node, Node> nodeMap) {
        return nodeMap.computeIfAbsent(node, n -> {
            if (n instanceof ParameterNode || n instanceof GlobalNode) {
                return n;
            }
            if (n instanceof InsideNode in) {
                return new InsideNode("I[" + contributorSig + "#" + n.getId() + "]", in.getLabel());
            }
            if (n instanceof LoadNode ln) {
                return new LoadNode("L[" + contributorSig + "#" + n.getId() + "]", ln.getLabel());
            }
            return n;
        });
    }
}
