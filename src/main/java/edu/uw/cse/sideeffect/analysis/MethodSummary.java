package edu.uw.cse.sideeffect.analysis;

import edu.uw.cse.sideeffect.graph.Node;
import edu.uw.cse.sideeffect.graph.PointsToGraph;
import java.util.Collections;
import java.util.List;
import java.util.Set;


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
        this.returnTargets = returnTargets;
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
        return returnTargets;
    }
}
