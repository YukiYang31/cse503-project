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
    public final boolean timing;

    public AnalysisConfig(boolean showGraph, boolean merge, String methodFilter,
                          boolean debug, boolean timing) {
        this.showGraph = showGraph || debug; // --debug implies --show-graph
        this.merge = merge;
        this.methodFilter = methodFilter;
        this.debug = debug;
        this.timing = timing || debug; // --debug implies --timing
    }

    public AnalysisConfig(boolean showGraph, boolean merge, String methodFilter, boolean debug) {
        this(showGraph, merge, methodFilter, debug, false);
    }

    public AnalysisConfig(boolean showGraph, boolean merge, String methodFilter) {
        this(showGraph, merge, methodFilter, false);
    }
}
