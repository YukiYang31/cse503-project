package edu.uw.cse.purity.analysis;

import edu.uw.cse.purity.AnalysisConfig;
import edu.uw.cse.purity.graph.PointsToGraph;
import edu.uw.cse.purity.output.DebugHtmlWriter;
import edu.uw.cse.purity.util.NodeMerger;
import sootup.analysis.intraprocedural.ForwardFlowAnalysis;
import sootup.core.graph.StmtGraph;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;

import java.util.List;

/**
 * Forward dataflow analysis that builds a PointsToGraph for each program point.
 * Extends SootUp's ForwardFlowAnalysis framework which handles fixed-point iteration.
 *
 * The analysis processes JIdentityStmts to set up parameter mappings (Fix #7),
 * then flows through assignment and invoke statements to build the graph.
 * At join points (if/else, loops), graphs are merged (union semantics).
 * Node merging is optionally applied at join points (Fix #3).
 */
public class PurityFlowAnalysis extends ForwardFlowAnalysis<PointsToGraph> {

    private final AnalysisConfig config;
    private final TransferFunctions transfer;
    private final Body body;
    private final boolean isStatic;
    private final DebugHtmlWriter debugWriter;

    public PurityFlowAnalysis(StmtGraph<?> cfg, Body body, AnalysisConfig config,
                               boolean isStatic, DebugHtmlWriter debugWriter) {
        super(cfg);
        this.config = config;
        this.body = body;
        this.isStatic = isStatic;
        this.debugWriter = debugWriter;
        this.transfer = new TransferFunctions(config, isStatic);
        execute();
    }

    public PurityFlowAnalysis(StmtGraph<?> cfg, Body body, AnalysisConfig config, boolean isStatic) {
        this(cfg, body, config, isStatic, null);
    }

    @Override
    protected PointsToGraph newInitialFlow() {
        return new PointsToGraph();
    }

    @Override
    protected void flowThrough(PointsToGraph in, Stmt stmt, PointsToGraph out) {
        // Copy in to out, then apply transfer function
        in.copyInto(out);
        transfer.apply(stmt, out);

        if (debugWriter != null && TransferFunctions.isKeyMilestone(stmt)) {
            debugWriter.addTraceEntry(stmt.toString(), out);
        }
    }

    @Override
    protected void merge(PointsToGraph in1, PointsToGraph in2, PointsToGraph out) {
        // Union semantics at join points
        in1.copyInto(out);
        out.mergeWith(in2);

        // Fix #3: Apply node merging at join points (if enabled)
        if (config.merge) {
            NodeMerger.enforceUniqueness(out);
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
