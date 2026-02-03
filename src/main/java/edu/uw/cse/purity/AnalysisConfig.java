package edu.uw.cse.purity;

/**
 * Holds CLI flags that control analysis behavior.
 * Passed to all analysis components.
 */
public class AnalysisConfig {
    public final boolean showGraph;
    public final boolean noMerge;
    public final String methodFilter; // null means analyze all methods

    public AnalysisConfig(boolean showGraph, boolean noMerge, String methodFilter) {
        this.showGraph = showGraph;
        this.noMerge = noMerge;
        this.methodFilter = methodFilter;
    }
}
