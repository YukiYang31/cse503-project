package edu.uw.cse.sideeffect.analysis;

import edu.uw.cse.sideeffect.AnalysisConfig;
import edu.uw.cse.sideeffect.graph.PointsToGraph;
import edu.uw.cse.sideeffect.output.DebugHtmlWriter;
import edu.uw.cse.sideeffect.util.NodeMerger;
import java.util.List;
import java.util.Set;
import sootup.core.graph.StmtGraph;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;
import sootup.java.core.views.JavaView;


/**
 * Forward dataflow analysis that builds a PointsToGraph for each program point.
 * Extends SootUp's ForwardFlowAnalysis framework which handles fixed-point iteration.
 *
 * The analysis processes JIdentityStmts to set up parameter mappings (Fix #7),
 * then flows through assignment and invoke statements to build the graph.
 * At join points (if/else, loops), graphs are merged (union semantics).
 * Node merging is optionally applied at join points (Fix #3).
 */
public class SideEffectFlowAnalysis extends ForwardFlowAnalysis<PointsToGraph> {

    private final AnalysisConfig config;
    private final TransferFunctions transfer;
    private final Body body;
    private final boolean isStatic;
    private final DebugHtmlWriter debugWriter;

    public SideEffectFlowAnalysis(StmtGraph<?> cfg, Body body, AnalysisConfig config,
                               boolean isStatic, DebugHtmlWriter debugWriter) {
        this(cfg, body, config, isStatic, debugWriter, null, null, null, null, null);
    }

    public SideEffectFlowAnalysis(StmtGraph<?> cfg, Body body, AnalysisConfig config,
                               boolean isStatic, DebugHtmlWriter debugWriter,
                               List<String> paramTypeNames) {
        this(cfg, body, config, isStatic, debugWriter, paramTypeNames, null, null, null, null);
    }

    public SideEffectFlowAnalysis(StmtGraph<?> cfg, Body body, AnalysisConfig config,
                               boolean isStatic, DebugHtmlWriter debugWriter,
                               List<String> paramTypeNames, SummaryCache summaryCache) {
        this(cfg, body, config, isStatic, debugWriter, paramTypeNames, summaryCache, null, null, null);
    }

    public SideEffectFlowAnalysis(StmtGraph<?> cfg, Body body, AnalysisConfig config,
                               boolean isStatic, DebugHtmlWriter debugWriter,
                               List<String> paramTypeNames, SummaryCache summaryCache,
                               JavaView view, Set<String> analyzing) {
        this(cfg, body, config, isStatic, debugWriter, paramTypeNames, summaryCache, view, analyzing, null);
    }

    public SideEffectFlowAnalysis(StmtGraph<?> cfg, Body body, AnalysisConfig config,
                               boolean isStatic, DebugHtmlWriter debugWriter,
                               List<String> paramTypeNames, SummaryCache summaryCache,
                               JavaView view, Set<String> analyzing, int[] onDemandBudget) {
        super(cfg);
        this.config = config;
        this.body = body;
        this.isStatic = isStatic;
        this.debugWriter = debugWriter;
        this.transfer = new TransferFunctions(config, isStatic, paramTypeNames, summaryCache, debugWriter,
                                              view, analyzing, onDemandBudget);
        execute();
    }

    public SideEffectFlowAnalysis(StmtGraph<?> cfg, Body body, AnalysisConfig config, boolean isStatic) {
        this(cfg, body, config, isStatic, null);
    }

    @Override
    protected PointsToGraph newInitialFlow() {
        return new PointsToGraph();
    }

    @Override
    protected void flowThrough(PointsToGraph in, Stmt stmt, PointsToGraph out) {
        if (config.debug) System.out.println("Debug== [flow] processing: " + stmt);
        // Copy in to out, then apply transfer function
        in.copyInto(out);
        transfer.apply(stmt, out);

        if (debugWriter != null && TransferFunctions.isKeyMilestone(stmt)) {
            debugWriter.addTraceEntry(stmt.toString(), out);
        }
    }

    @Override
    protected void merge(PointsToGraph in1, PointsToGraph in2, PointsToGraph out) {
        if (config.debug) System.out.println("Debug== [merge] merging at join point");
        // Union semantics at join points
        in1.copyInto(out);
        out.mergeWith(in2);

        // Fix #3: Apply node merging at join points (if enabled)
        if (config.merge) {
            NodeMerger.enforceUniqueness(out);
        }

        // Record LUB merge step in debug HTML
        if (debugWriter != null) {
            debugWriter.addLubEntry(in1, in2, out);
        }
    }

    @Override
    protected void copy(PointsToGraph src, PointsToGraph dest) {
        src.copyInto(dest);
    }

    /**
     * Get the exit graph by merging flow-after values at all tail statements.
     */
    public PointsToGraph getExitGraph() {
        PointsToGraph exitGraph = new PointsToGraph();
        List<? extends Stmt> tails = graph.getTails();

        if (config.debug) System.out.println("Debug== computing exit graph from " + tails.size() + " tail(s)");

        boolean first = true;
        for (Stmt tail : tails) {
            PointsToGraph tailFlow = getFlowAfter(tail);
            if (first) {
                tailFlow.copyInto(exitGraph);
                first = false;
            } else {
                exitGraph.mergeWith(tailFlow);
            }
        }

        // Final node merging at method exit (if enabled)
        if (config.merge) {
            NodeMerger.enforceUniqueness(exitGraph);
        }

        return exitGraph;
    }
}
