package edu.uw.cse.purity.analysis;

import edu.uw.cse.purity.graph.PointsToGraph;

/**
 * Stores the analysis result for a single method.
 * Contains the exit PointsToGraph and the purity verdict.
 *
 * This is an extensibility hook: for inter-procedural analysis,
 * the exitGraph would be used to instantiate callee summaries
 * at call sites in the caller.
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

    public MethodSummary(String methodSignature, PointsToGraph exitGraph,
                         PurityResult result, String reason) {
        this.methodSignature = methodSignature;
        this.exitGraph = exitGraph;
        this.result = result;
        this.reason = reason;
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
}
