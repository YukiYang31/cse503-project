package edu.uw.cse.purity.analysis;

import edu.uw.cse.purity.graph.Node;
import edu.uw.cse.purity.graph.PointsToGraph;

import java.util.Set;

/**
 * Stores the analysis result for a single method.
 * Contains the exit PointsToGraph, the purity verdict, and return targets
 * for inter-procedural summary instantiation.
 */
public class MethodSummary {

    public enum PurityResult {
        PURE,
        IMPURE,
        GRAPH_VIOLATION
    }

    private final String methodSignature;
    private final PointsToGraph exitGraph;
    private final PurityResult result;
    private final String reason; // null if PURE
    private final Set<Node> returnTargets;

    public MethodSummary(String methodSignature, PointsToGraph exitGraph,
                         PurityResult result, String reason, Set<Node> returnTargets) {
        this.methodSignature = methodSignature;
        this.exitGraph = exitGraph;
        this.result = result;
        this.reason = reason;
        this.returnTargets = returnTargets;
    }

    public MethodSummary(String methodSignature, PointsToGraph exitGraph,
                         PurityResult result, String reason) {
        this(methodSignature, exitGraph, result, reason, Set.of());
    }

    public String getMethodSignature() {
        return methodSignature;
    }

    public PointsToGraph getExitGraph() {
        return exitGraph;
    }

    public PurityResult getResult() {
        return result;
    }

    public String getReason() {
        return reason;
    }

    public Set<Node> getReturnTargets() {
        return returnTargets;
    }
}
