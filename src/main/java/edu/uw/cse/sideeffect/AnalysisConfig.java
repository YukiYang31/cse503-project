package edu.uw.cse.sideeffect;

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
    public final int callGraphTimeoutSecs; // 0 = unlimited
    public final int methodTimeoutSecs;    // 0 = unlimited

    public AnalysisConfig(boolean showGraph, boolean merge, String methodFilter,
                          boolean debug, boolean timing,
                          int callGraphTimeoutSecs, int methodTimeoutSecs) {
        this.showGraph = showGraph || debug; // --debug implies --show-graph
        this.merge = merge;
        this.methodFilter = methodFilter;
        this.debug = debug;
        this.timing = timing || debug; // --debug implies --timing
        this.callGraphTimeoutSecs = callGraphTimeoutSecs;
        this.methodTimeoutSecs = methodTimeoutSecs;
    }

    public AnalysisConfig(boolean showGraph, boolean merge, String methodFilter,
                          boolean debug, boolean timing) {
        this(showGraph, merge, methodFilter, debug, timing, 0, 0);
    }

    public AnalysisConfig(boolean showGraph, boolean merge, String methodFilter, boolean debug) {
        this(showGraph, merge, methodFilter, debug, false);
    }

    public AnalysisConfig(boolean showGraph, boolean merge, String methodFilter) {
        this(showGraph, merge, methodFilter, false);
    }
}
