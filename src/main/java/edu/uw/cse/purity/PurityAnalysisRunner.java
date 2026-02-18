package edu.uw.cse.purity;

import edu.uw.cse.purity.analysis.CallGraphBuilder;
import edu.uw.cse.purity.analysis.MethodSummary;
import edu.uw.cse.purity.analysis.PurityChecker;
import edu.uw.cse.purity.analysis.PurityFlowAnalysis;
import edu.uw.cse.purity.analysis.SummaryCache;
import edu.uw.cse.purity.graph.PointsToGraph;
import edu.uw.cse.purity.output.DebugHtmlWriter;
import edu.uw.cse.purity.output.GraphPrinter;
import edu.uw.cse.purity.output.ResultPrinter;
import edu.uw.cse.purity.util.TimingRecorder;
import sootup.core.graph.StmtGraph;
import sootup.core.jimple.basic.NoPositionInformation;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;
import sootup.core.model.Position;
import sootup.java.bytecode.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.core.types.Type;
import sootup.java.core.JavaSootClass;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads compiled classes via SootUp's JavaView, iterates methods,
 * and runs the purity analysis on each.
 */
public class PurityAnalysisRunner {

    private final AnalysisConfig config;
    private final Path classDir;
    private final List<Path> sourceFiles;
    private final TimingRecorder timer;

    public PurityAnalysisRunner(AnalysisConfig config, Path classDir, List<Path> sourceFiles,
                                TimingRecorder timer) {
        this.config = config;
        this.classDir = classDir;
        this.sourceFiles = sourceFiles;
        this.timer = timer;
    }

    public PurityAnalysisRunner(AnalysisConfig config, Path classDir, List<Path> sourceFiles) {
        this(config, classDir, sourceFiles, TimingRecorder.NOOP);
    }

    public PurityAnalysisRunner(AnalysisConfig config, Path classDir) {
        this(config, classDir, List.of());
    }

    public void run() {
        // Create SootUp view pointing at compiled classes
        long irStart = 0;
        if (config.timing) irStart = System.nanoTime();

        JavaClassPathAnalysisInputLocation inputLocation =
            new JavaClassPathAnalysisInputLocation(classDir.toString());
        JavaView view = new JavaView(inputLocation);

        // Get all classes from the input location
        Collection<JavaSootClass> classes = view.getClasses();

        if (config.timing) {
            timer.recordIrLoading(System.nanoTime() - irStart);
        }

        if (classes.isEmpty()) {
            System.out.println("No classes found in: " + classDir);
            return;
        }

        // Read source files once for debug output
        List<DebugHtmlWriter.SourceFile> sourceContents = List.of();
        if (config.debug) {
            System.out.println("Debug mode: writing HTML trace files to debug/ directory");
            sourceContents = readSourceFiles();
        }

        // Build call graph and compute bottom-up analysis order
        long cgStart = 0;
        if (config.timing) cgStart = System.nanoTime();

        CallGraphBuilder.Result cgResult = CallGraphBuilder.computeBottomUpOrder(classes, config);
        List<List<JavaSootMethod>> batches = cgResult.batches();
        Map<String, Set<String>> callGraph = cgResult.callGraph();

        if (config.timing) {
            timer.recordCallGraph(System.nanoTime() - cgStart);
        }

        // Analyze methods bottom-up with inter-procedural summary cache
        SummaryCache cache = new SummaryCache();
        List<MethodSummary> summaries = new ArrayList<>();

        for (List<JavaSootMethod> batch : batches) {
            if (batch.size() == 1) {
                // Single method (non-recursive): analyze once with cache
                JavaSootMethod method = batch.get(0);
                if (!method.isConcrete()) continue;
                if (config.methodFilter != null && !method.getName().equals(config.methodFilter)) continue;

                MethodSummary summary = analyzeMethod(method, sourceContents, cache, callGraph);
                if (summary != null) {
                    storeSummary(method, summary, cache);
                    summaries.add(summary);
                }
            } else {
                // SCC (mutually recursive methods): iterate until summaries stabilize
                if (config.debug) {
                    List<String> names = batch.stream()
                            .map(m -> m.getDeclaringClassType().getClassName() + "." + m.getName())
                            .toList();
                    System.out.println("Debug== Analyzing SCC: " + names);
                }

                int maxIterations = 5;
                for (int iter = 0; iter < maxIterations; iter++) {
                    boolean anyChanged = false;
                    for (JavaSootMethod method : batch) {
                        if (!method.isConcrete()) continue;

                        MethodSummary oldSummary = cache.lookup(
                                method.getSignature().toString(),
                                method.getSignature().getSubSignature().toString());

                        MethodSummary newSummary = analyzeMethod(method, sourceContents, cache, callGraph);
                        if (newSummary != null) {
                            storeSummary(method, newSummary, cache);
                            if (oldSummary == null || oldSummary.getResult() != newSummary.getResult()) {
                                anyChanged = true;
                            }
                        }
                    }
                    if (!anyChanged) {
                        if (config.debug) System.out.println("Debug== SCC stabilized after " + (iter + 1) + " iterations");
                        break;
                    }
                }

                // Collect final summaries for the SCC methods
                for (JavaSootMethod method : batch) {
                    if (!method.isConcrete()) continue;
                    if (config.methodFilter != null && !method.getName().equals(config.methodFilter)) continue;
                    MethodSummary summary = cache.lookup(
                            method.getSignature().toString(),
                            method.getSignature().getSubSignature().toString());
                    if (summary != null) {
                        summaries.add(summary);
                    }
                }
            }
        }

        // Print graphs if requested
        if (config.showGraph) {
            for (MethodSummary summary : summaries) {
                GraphPrinter.printTextSummary(summary);
                GraphPrinter.writeDotFile(summary);
            }
        }

        // Print results
        ResultPrinter.print(summaries);
    }

    /** Store a summary in the cache, keyed by both full and sub signature */
    private void storeSummary(JavaSootMethod method, MethodSummary summary, SummaryCache cache) {
        String fullSig = method.getSignature().toString();
        String subSig = method.getSignature().getSubSignature().toString();
        cache.put(fullSig, subSig, summary);
    }

    private MethodSummary analyzeMethod(JavaSootMethod method,
                                        List<DebugHtmlWriter.SourceFile> sourceContents,
                                        SummaryCache cache,
                                        Map<String, Set<String>> callGraph) {
        try {
            // Fetch body and CFG once (Fix #6: View Cache Trap)
            Body body = method.getBody();
            StmtGraph<?> cfg = body.getStmtGraph();
            String sig = method.getSignature().toString();

            // Set up debug writer if debug mode is enabled
            DebugHtmlWriter debugWriter = null;
            if (config.debug) {
                debugWriter = DebugHtmlWriter.create(sig);
                debugWriter.setSourceCode(
                    extractMethodSource(cfg, sourceContents));
                debugWriter.setBytecode(extractBytecode(method));
                for (Stmt stmt : cfg.getStmts()) {
                    debugWriter.addJimpleStatement(stmt.toString());
                }
                debugWriter.setCallGraph(sig, callGraph);
            }

            try {
                if (config.debug) System.out.println("\nDebug== ===== Analyzing method: " + sig + " =====");

                // Extract simple type names for parameter labels
                List<String> paramTypeNames = method.getSignature().getParameterTypes()
                    .stream()
                    .map(Type::toString)
                    .map(t -> { int dot = t.lastIndexOf('.'); return dot >= 0 ? t.substring(dot + 1) : t; })
                    .toList();

                // --- Dataflow timing ---
                long dataflowStart = 0;
                if (config.timing) dataflowStart = System.nanoTime();

                // Run the forward flow analysis (with inter-procedural cache)
                PurityFlowAnalysis analysis = new PurityFlowAnalysis(
                    cfg, body, config, method.isStatic(), debugWriter, paramTypeNames, cache);

                // Get the exit graph
                PointsToGraph exitGraph = analysis.getExitGraph();

                long dataflowNs = 0;
                if (config.timing) dataflowNs = System.nanoTime() - dataflowStart;

                // --- Purity check timing ---
                long purityStart = 0;
                if (config.timing) purityStart = System.nanoTime();

                // Check purity (include return targets for inter-procedural summaries)
                boolean isConstructor = "<init>".equals(method.getName());
                MethodSummary purityResult = PurityChecker.check(sig, exitGraph, isConstructor, config.debug);
                MethodSummary summary = new MethodSummary(sig, exitGraph,
                        purityResult.getResult(), purityResult.getReason(),
                        exitGraph.getReturnTargets());

                long purityNs = 0;
                if (config.timing) purityNs = System.nanoTime() - purityStart;

                // --- Record timing data ---
                if (config.timing) {
                    int stmtCount = cfg.getStmts().size();
                    int nodeCount = exitGraph.getAllNodes().size();
                    int edgeCount = countEdges(exitGraph);
                    timer.addMethodTiming(new TimingRecorder.MethodTiming(
                            sig, dataflowNs, purityNs, stmtCount, nodeCount, edgeCount));
                }

                // Write debug output
                if (debugWriter != null) {
                    debugWriter.setExitGraph(exitGraph);
                    debugWriter.setInsideEdges(exitGraph);
                    debugWriter.setOutsideEdges(exitGraph);
                    debugWriter.setLocalVariables(exitGraph);
                    debugWriter.setEscapedNodes(exitGraph.getGlobalEscaped());
                    debugWriter.setPrestateNodes(PurityChecker.computePrestateNodes(exitGraph));
                    debugWriter.setGloballyEscapedNodes(PurityChecker.computeGloballyEscapedNodes(exitGraph));
                    debugWriter.setMutatedFields(exitGraph.getMutatedFields());
                    debugWriter.setPurityResult(
                        summary.getResult().name(), summary.getReason());
                }

                return summary;
            } finally {
                if (debugWriter != null) {
                    debugWriter.close();
                }
            }

        } catch (Exception e) {
            System.err.println("Error analyzing " + method.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /** Count total edges in a PointsToGraph by iterating the edges map. */
    private static int countEdges(PointsToGraph graph) {
        int count = 0;
        for (var fieldMap : graph.getEdges().values()) {
            for (var targets : fieldMap.values()) {
                count += targets.size();
            }
        }
        return count;
    }

    /**
     * Extract only the source lines corresponding to this method, using Jimple
     * statement line numbers. Falls back to the full source if no line info is available.
     */
    private List<DebugHtmlWriter.SourceFile> extractMethodSource(
            StmtGraph<?> cfg, List<DebugHtmlWriter.SourceFile> fullSources) {
        // Collect min/max source line numbers from Jimple statements
        int minLine = Integer.MAX_VALUE;
        int maxLine = Integer.MIN_VALUE;
        for (Stmt stmt : cfg.getStmts()) {
            Position pos = stmt.getPositionInfo().getStmtPosition();
            if (pos instanceof NoPositionInformation) continue;
            int first = pos.getFirstLine();
            int last = pos.getLastLine();
            if (first > 0) minLine = Math.min(minLine, first);
            if (last > 0) maxLine = Math.max(maxLine, last);
        }

        // No valid line numbers found — fall back to full source
        if (minLine == Integer.MAX_VALUE) {
            return fullSources;
        }

        // Add a small buffer before the first line to capture the method signature
        // (Jimple body lines start inside the method, the declaration is typically 1-2 lines before)
        int startLine = Math.max(1, minLine - 2);
        int endLine = maxLine + 1; // include the closing brace

        List<DebugHtmlWriter.SourceFile> result = new ArrayList<>();
        for (DebugHtmlWriter.SourceFile sf : fullSources) {
            String[] allLines = sf.content().split("\n", -1);
            int from = Math.max(0, startLine - 1); // convert to 0-based
            int to = Math.min(allLines.length, endLine);
            if (from >= to) continue;

            StringBuilder snippet = new StringBuilder();
            for (int i = from; i < to; i++) {
                if (snippet.length() > 0) snippet.append("\n");
                snippet.append(allLines[i]);
            }

            String label = sf.fileName() + " (lines " + startLine + "-" + endLine + ")";
            result.add(new DebugHtmlWriter.SourceFile(label, snippet.toString()));
        }
        return result;
    }

    /**
     * Use ASM to extract disassembled bytecode for a specific method from its .class file.
     */
    private List<String> extractBytecode(JavaSootMethod method) {
        try {
            // Resolve .class file path from classDir + fully qualified class name
            String className = method.getDeclaringClassType().getFullyQualifiedName();
            Path classFile = classDir.resolve(className.replace('.', '/') + ".class");
            if (!Files.exists(classFile)) {
                return List.of("// .class file not found: " + classFile);
            }

            byte[] classBytes = Files.readAllBytes(classFile);

            // Build JVM method descriptor from SootUp types
            String methodName = method.getName();
            String descriptor = buildDescriptor(method);

            // Use a custom ClassVisitor to only trace the matching method
            Textifier textifier = new Textifier();
            ClassReader reader = new ClassReader(classBytes);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                  String signature, String[] exceptions) {
                    if (name.equals(methodName) && desc.equals(descriptor)) {
                        // Trace this method
                        return new TraceMethodVisitor(textifier);
                    }
                    return null; // skip other methods
                }
            }, 0);

            // Convert Textifier output to lines
            StringWriter sw = new StringWriter();
            try (PrintWriter pw = new PrintWriter(sw)) {
                textifier.print(pw);
            }
            String output = sw.toString();
            if (output.isBlank()) {
                return List.of("// Could not find method " + methodName + descriptor + " in bytecode");
            }

            // Split into lines, trimming trailing empty lines
            List<String> lines = new ArrayList<>(Arrays.asList(output.split("\n", -1)));
            while (!lines.isEmpty() && lines.getLast().isBlank()) {
                lines.removeLast();
            }
            return lines;

        } catch (IOException e) {
            return List.of("// Error reading bytecode: " + e.getMessage());
        }
    }

    /**
     * Build a JVM method descriptor (e.g., "(ILjava/lang/String;)V") from SootUp method types.
     */
    private static String buildDescriptor(JavaSootMethod method) {
        StringBuilder sb = new StringBuilder("(");
        for (Type paramType : method.getSignature().getParameterTypes()) {
            sb.append(toJvmType(paramType.toString()));
        }
        sb.append(")");
        sb.append(toJvmType(method.getSignature().getType().toString()));
        return sb.toString();
    }

    private static String toJvmType(String sootType) {
        // Handle array types
        if (sootType.endsWith("[]")) {
            return "[" + toJvmType(sootType.substring(0, sootType.length() - 2));
        }
        return switch (sootType) {
            case "void"    -> "V";
            case "boolean" -> "Z";
            case "byte"    -> "B";
            case "char"    -> "C";
            case "short"   -> "S";
            case "int"     -> "I";
            case "long"    -> "J";
            case "float"   -> "F";
            case "double"  -> "D";
            default        -> "L" + sootType.replace('.', '/') + ";";
        };
    }

    private List<DebugHtmlWriter.SourceFile> readSourceFiles() {
        List<DebugHtmlWriter.SourceFile> result = new ArrayList<>();
        for (Path path : sourceFiles) {
            try {
                String content = Files.readString(path);
                result.add(new DebugHtmlWriter.SourceFile(path.getFileName().toString(), content));
            } catch (IOException e) {
                System.err.println("Warning: could not read source file " + path + ": " + e.getMessage());
            }
        }
        return result;
    }
}
