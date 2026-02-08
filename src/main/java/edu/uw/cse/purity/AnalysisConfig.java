package edu.uw.cse.purity;

/**
 * Holds CLI flags that control analysis behavior.
 * Passed to all analysis components.
 */
public class AnalysisConfig {
    public final boolean showGraph;
    public final boolean merge;
    public final String methodFilter; // null means analyze all methods
    public final boolean debug;

    public AnalysisConfig(boolean showGraph, boolean merge, String methodFilter, boolean debug) {
        this.showGraph = showGraph || debug; // --debug implies --show-graph
        this.merge = merge;
        this.methodFilter = methodFilter;
        this.debug = debug;
    }

    public AnalysisConfig(boolean showGraph, boolean merge, String methodFilter) {
        this(showGraph, merge, methodFilter, false);
    }
}
