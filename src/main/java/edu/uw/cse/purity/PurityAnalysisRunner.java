package edu.uw.cse.purity;

import edu.uw.cse.purity.analysis.MethodSummary;
import edu.uw.cse.purity.analysis.PurityChecker;
import edu.uw.cse.purity.analysis.PurityFlowAnalysis;
import edu.uw.cse.purity.graph.PointsToGraph;
import edu.uw.cse.purity.output.GraphPrinter;
import edu.uw.cse.purity.output.ResultPrinter;
import sootup.core.graph.StmtGraph;
import sootup.core.model.Body;
import sootup.java.bytecode.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.JavaSootClass;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Loads compiled classes via SootUp's JavaView, iterates methods,
 * and runs the purity analysis on each.
 */
public class PurityAnalysisRunner {

    private final AnalysisConfig config;
    private final Path classDir;

    public PurityAnalysisRunner(AnalysisConfig config, Path classDir) {
        this.config = config;
        this.classDir = classDir;
    }

    public void run() {
        // Create SootUp view pointing at compiled classes
        JavaClassPathAnalysisInputLocation inputLocation =
            new JavaClassPathAnalysisInputLocation(classDir.toString());
        JavaView view = new JavaView(inputLocation);

        // Get all classes from the input location
        Collection<JavaSootClass> classes = view.getClasses();

        if (classes.isEmpty()) {
            System.out.println("No classes found in: " + classDir);
            return;
        }

        List<MethodSummary> summaries = new ArrayList<>();

        for (JavaSootClass sootClass : classes) {
            for (JavaSootMethod method : sootClass.getMethods()) {
                if (!method.isConcrete()) continue;

                // Apply method filter if specified
                if (config.methodFilter != null
                    && !method.getName().equals(config.methodFilter)) {
                    continue;
                }

                MethodSummary summary = analyzeMethod(method);
                if (summary != null) {
                    summaries.add(summary);
                }
            }
        }

        // Print results
        ResultPrinter.print(summaries);

        // Print graphs if requested
        if (config.showGraph) {
            for (MethodSummary summary : summaries) {
                GraphPrinter.printTextSummary(summary);
                GraphPrinter.writeDotFile(summary);
            }
        }
    }

    private MethodSummary analyzeMethod(JavaSootMethod method) {
        try {
            // Fetch body and CFG once (Fix #6: View Cache Trap)
            Body body = method.getBody();
            StmtGraph<?> cfg = body.getStmtGraph();
            String sig = method.getSignature().toString();

            // Run the forward flow analysis
            PurityFlowAnalysis analysis = new PurityFlowAnalysis(cfg, body, config, method.isStatic());

            // Get the exit graph
            PointsToGraph exitGraph = analysis.getExitGraph();

            // Check purity
            boolean isConstructor = "<init>".equals(method.getName());
            MethodSummary summary = PurityChecker.check(sig, exitGraph, isConstructor);

            return summary;

        } catch (Exception e) {
            System.err.println("Error analyzing " + method.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
}
