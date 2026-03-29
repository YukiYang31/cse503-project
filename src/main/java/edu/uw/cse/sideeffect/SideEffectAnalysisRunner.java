package edu.uw.cse.sideeffect;

import java.util.HashSet;
import java.util.Deque;
import java.util.ArrayDeque;

import edu.uw.cse.sideeffect.analysis.CallGraphBuilder;
import edu.uw.cse.sideeffect.analysis.LibrarySummaryCache;
import edu.uw.cse.sideeffect.analysis.MethodSummary;
import edu.uw.cse.sideeffect.analysis.SideEffectChecker;
import edu.uw.cse.sideeffect.analysis.SideEffectFlowAnalysis;
import edu.uw.cse.sideeffect.analysis.SummaryCache;
import edu.uw.cse.sideeffect.graph.PointsToGraph;
import edu.uw.cse.sideeffect.output.DebugHtmlWriter;
import edu.uw.cse.sideeffect.output.GraphPrinter;
import edu.uw.cse.sideeffect.output.ResultPrinter;
import edu.uw.cse.sideeffect.util.TimingRecorder;
import sootup.core.graph.StmtGraph;
import sootup.core.jimple.basic.NoPositionInformation;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;
import sootup.core.model.Position;
import sootup.java.bytecode.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.bytecode.inputlocation.JrtFileSystemAnalysisInputLocation;
import sootup.core.types.ClassType;
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
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Loads compiled classes via SootUp's JavaView, iterates methods,
 * and runs the side-effect analysis on each.
 */
public class SideEffectAnalysisRunner {

    private final AnalysisConfig config;
    private final Path classDir;          // null when using JRT mode
    private final List<Path> sourceFiles;
    private final TimingRecorder timer;
    private final Set<String> jrtClassNames;  // non-empty = JRT mode
    private JavaView view;                // set in run()
    private Map<String, Set<String>> rawCallGraph;   // direct invocations only (for debug)
    private Map<String, Set<String>> overrideGraph;  // base -> overrides (for debug)
    private Map<String, Set<String>> reverseOverrideGraph; // override -> direct bases

    private record AnalysisResult(MethodSummary summary, DebugHtmlWriter debugWriter) {}

    public SideEffectAnalysisRunner(AnalysisConfig config, Path classDir, List<Path> sourceFiles,
                                TimingRecorder timer) {
        this.config = config;
        this.classDir = classDir;
        this.sourceFiles = sourceFiles;
        this.timer = timer;
        this.jrtClassNames = Set.of();
    }

    private SideEffectAnalysisRunner(AnalysisConfig config, Set<String> jrtClassNames,
                                     List<Path> sourceFiles, TimingRecorder timer) {
        this.config = config;
        this.classDir = null;
        this.sourceFiles = sourceFiles;
        this.timer = timer;
        this.jrtClassNames = jrtClassNames;
    }

    /** Create a runner that loads classes from the JDK runtime (jrt:/ filesystem). */
    public static SideEffectAnalysisRunner forJrt(AnalysisConfig config, Set<String> classNames,
                                                   List<Path> sourceFiles, TimingRecorder timer) {
        return new SideEffectAnalysisRunner(config, classNames, sourceFiles, timer);
    }

    public SideEffectAnalysisRunner(AnalysisConfig config, Path classDir, List<Path> sourceFiles) {
        this(config, classDir, sourceFiles, TimingRecorder.NOOP);
    }

    public SideEffectAnalysisRunner(AnalysisConfig config, Path classDir) {
        this(config, classDir, List.of());
    }

    public void run() {
        // Create SootUp view pointing at compiled classes (or JDK runtime)
        long irStart = 0;
        if (config.timing) irStart = System.nanoTime();

        if (!jrtClassNames.isEmpty()) {
            // JRT mode: load from the running JDK's module image
            JrtFileSystemAnalysisInputLocation jrtInput = new JrtFileSystemAnalysisInputLocation();
            this.view = new JavaView(jrtInput);
        } else {
            // User mode: load user classes only (JRT view created lazily in CallGraphBuilder)
            JavaClassPathAnalysisInputLocation inputLocation =
                new JavaClassPathAnalysisInputLocation(classDir.toString());
            this.view = new JavaView(inputLocation);
        }

        // Load the on-disk library summary index so reachable JDK summaries can be fetched lazily.
        int diskCacheIndexed = LibrarySummaryCache.loadFromDisk();
        if (diskCacheIndexed > 0 && config.debug) {
            System.out.println("Debug== Indexed " + diskCacheIndexed + " cached library summaries from disk");
        }

        // Get classes — filter to targets when using JRT mode
        Collection<JavaSootClass> classes;
        if (!jrtClassNames.isEmpty()) {
            // Resolve only the requested classes from the huge JRT class set
            classes = new ArrayList<>();
            for (String fqcn : jrtClassNames) {
                ClassType classType = view.getIdentifierFactory().getClassType(fqcn);
                view.getClass(classType).ifPresent(classes::add);
            }
            if (classes.isEmpty()) {
                System.out.println("Could not resolve JDK classes: " + jrtClassNames);
                return;
            }
            System.out.println("Loaded " + classes.size() + " class(es) from JDK runtime.");
        } else {
            classes = view.getClasses();
        }

        if (config.timing) {
            timer.recordIrLoading(System.nanoTime() - irStart);
        }

        if (classes.isEmpty()) {
            System.out.println("No classes found" +
                (classDir != null ? " in: " + classDir : " via JRT"));
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

        CallGraphBuilder.Result cgResult;
        if (config.callGraphTimeoutSecs > 0) {
            ExecutorService cgExec = Executors.newSingleThreadExecutor();
            final Collection<JavaSootClass> classesFinal = classes;
            final JavaView viewFinal = this.view;
            Future<CallGraphBuilder.Result> cgFuture = cgExec.submit(
                    () -> CallGraphBuilder.computeBottomUpOrder(classesFinal, viewFinal, config));
            try {
                cgResult = cgFuture.get(config.callGraphTimeoutSecs, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                cgFuture.cancel(true);
                cgExec.shutdownNow();
                System.err.println("TIMEOUT: call graph construction exceeded " +
                        config.callGraphTimeoutSecs + "s — marking all methods as TIMEOUT");
                if (config.timing) {
                    timer.recordCallGraph(System.nanoTime() - cgStart);
                    // Emit a TIMEOUT MethodTiming for every concrete method we can find
                    for (JavaSootClass cls : classes) {
                        for (JavaSootMethod m : cls.getMethods()) {
                            if (m.isConcrete()) {
                                timer.addMethodTiming(new TimingRecorder.MethodTiming(
                                        m.getSignature().toString(), "TIMEOUT",
                                        "timeout at building call graph", 0, 0, 0, 0, 0));
                            }
                        }
                    }
                }
                return; // Main.java will call timer.endTotal() + timer.saveJson()
            } catch (InterruptedException | ExecutionException e) {
                cgExec.shutdownNow();
                throw new RuntimeException("Call graph computation failed: " + e.getMessage(), e);
            } finally {
                cgExec.shutdown();
            }
        } else {
            cgResult = CallGraphBuilder.computeBottomUpOrder(classes, view, config);
        }

        List<List<JavaSootMethod>> batches = cgResult.batches();
        Map<String, Set<String>> dependencyGraph = cgResult.dependencyGraph();
        Map<String, Set<String>> overrideGraph = cgResult.overrideGraph();
        Set<String> reachableCachedLibraryMethods = cgResult.reachableCachedLibraryMethods();
        this.rawCallGraph = cgResult.rawCallGraph();
        this.overrideGraph = overrideGraph;
        this.reverseOverrideGraph = invertGraph(overrideGraph);

        if (config.timing) {
            timer.recordCallGraph(System.nanoTime() - cgStart);
            timer.recordCallGraphBreakdown(
                    cgResult.timing().directCallGraphNs(),
                    cgResult.timing().overrideGraphNs(),
                    cgResult.timing().mergeNs());
        }

        // Build set of user-class method signatures (for output filtering — don't show
        // JDK library methods discovered via BFS in the results table)
        Set<String> userMethodSigs = buildUserMethodSignatures(classes);

        // Analyze only methods reachable from the target (if methodFilter is set)
        SummaryCache cache = new SummaryCache();

        // Lazily load only the cached library summaries that are reachable in this run.
        preloadReachableLibrarySummaries(cache, reachableCachedLibraryMethods);
        if (config.debug && !reachableCachedLibraryMethods.isEmpty()) {
            System.out.println("Debug== Pre-populated SummaryCache with "
                    + reachableCachedLibraryMethods.size() + " reachable cached library summaries");
        }

        List<MethodSummary> summaries = new ArrayList<>();

        Set<String> reachable = computeReachableMethods(batches, dependencyGraph);

        // Pre-count total concrete methods to analyze (for progress bar)
        int totalMethods = countConcreteMethods(batches, reachable);
        int methodsDone = 0;
        if (totalMethods > 0) printProgress(methodsDone, totalMethods);

        for (List<JavaSootMethod> batch : batches) {
            List<JavaSootMethod> filteredBatch = filterBatch(batch, reachable);
            if (filteredBatch.isEmpty()) continue;

            if (filteredBatch.size() == 1) {
                JavaSootMethod method = filteredBatch.get(0);
                if (!method.isConcrete()) continue;

                // Check cache first
                String fullSig = method.getSignature().toString();
                MethodSummary cached = cache.lookup(fullSig);

                if (cached != null && shouldSkipAnalysisBecauseCached(fullSig, userMethodSigs)) {
                    if (config.debug) System.out.println("Debug== CACHE-HIT: " + fullSig);
                    methodsDone++;
                    printProgress(methodsDone, totalMethods);

                    String methodSig = method.getSignature().toString();
                    if (userMethodSigs.contains(methodSig)
                            && (config.methodFilter == null || method.getName().equals(config.methodFilter))) {
                        summaries.add(cached);
                    }
                    if (config.timing) {
                        timer.addMethodTiming(new TimingRecorder.MethodTiming(
                                fullSig, cached.getResult().name(), "cached", 0, 0, 0, 0, 0));
                    }
                    continue;
                }

                AnalysisResult analysisResult =
                        analyzeMethodWithOptionalTimeout(method, sourceContents, cache, dependencyGraph);
                methodsDone++;
                printProgress(methodsDone, totalMethods);
                if (analysisResult != null) {
                    MethodSummary effectiveSummary = storeAndFinalize(method, analysisResult, cache);
                    if (shouldIncludeInResults(method, userMethodSigs)) {
                        if (effectiveSummary != null) {
                            summaries.add(effectiveSummary);
                        }
                    }
                }
            } else {
                if (isFullyCachedScc(filteredBatch, cache, userMethodSigs)) {
                    if (config.debug) System.out.println("Debug== CACHE-HIT: SCC batch of size " + filteredBatch.size());
                    for (JavaSootMethod method : filteredBatch) {
                        if (!method.isConcrete()) continue;
                        methodsDone++;

                        String mSig = method.getSignature().toString();
                        MethodSummary cached = cache.lookup(mSig);

                        if (shouldIncludeInResults(method, userMethodSigs)) {
                            if (cached != null) summaries.add(cached);
                        }
                        if (config.timing) {
                            timer.addMethodTiming(new TimingRecorder.MethodTiming(
                                    mSig, cached != null ? cached.getResult().name() : "UNKNOWN", "cached", 0, 0, 0, 0, 0));
                        }
                    }
                    printProgress(methodsDone, totalMethods);
                    continue;
                }

                if (config.debug) {
                    List<String> names = filteredBatch.stream()
                            .map(m -> m.getDeclaringClassType().getClassName() + "." + m.getName())
                            .toList();
                    System.out.println("Debug== Analyzing SCC: " + names);
                }
                boolean sccTimedOut = analyzeScc(filteredBatch, sourceContents, cache, dependencyGraph);
                if (!sccTimedOut) {
                    for (JavaSootMethod method : filteredBatch) {
                        if (!method.isConcrete()) continue;
                        methodsDone++;
                        if (!shouldIncludeInResults(method, userMethodSigs)) continue;
                        MethodSummary summary = cache.lookup(method.getSignature().toString());
                        if (summary != null) {
                            summaries.add(summary);
                        }
                    }
                } else {
                    for (JavaSootMethod method : filteredBatch) {
                        if (method.isConcrete()) methodsDone++;
                    }
                }
                printProgress(methodsDone, totalMethods);
            }
        }

        // Print graphs if requested
        if (config.showGraph) {
            for (MethodSummary summary : summaries) {
                GraphPrinter.printTextSummary(summary);
                GraphPrinter.writeDotFile(summary);
            }
            if (!overrideGraph.isEmpty()) {
                GraphPrinter.writeOverrideDependencyDot(dependencyGraph, overrideGraph);
            }
        }

        // Print results
        ResultPrinter.print(summaries);
    }

    private static Set<String> buildUserMethodSignatures(Collection<JavaSootClass> classes) {
        Set<String> userMethodSigs = new HashSet<>();
        for (JavaSootClass cls : classes) {
            for (JavaSootMethod method : cls.getMethods()) {
                userMethodSigs.add(method.getSignature().toString());
            }
        }
        return userMethodSigs;
    }

    private Set<String> computeReachableMethods(List<List<JavaSootMethod>> batches,
                                                Map<String, Set<String>> dependencyGraph) {
        if (config.methodFilter == null) return null;

        Set<String> startMethods = new HashSet<>();
        for (List<JavaSootMethod> batch : batches) {
            for (JavaSootMethod method : batch) {
                if (method.getName().equals(config.methodFilter)) {
                    startMethods.add(method.getSignature().toString());
                }
            }
        }

        Set<String> reachable = new HashSet<>();
        Deque<String> work = new ArrayDeque<>(startMethods);
        while (!work.isEmpty()) {
            String sig = work.pop();
            if (!reachable.add(sig)) continue;
            for (String callee : dependencyGraph.getOrDefault(sig, Set.of())) {
                if (!reachable.contains(callee)) {
                    work.add(callee);
                }
            }
        }
        return reachable;
    }

    private static List<JavaSootMethod> filterBatch(List<JavaSootMethod> batch, Set<String> reachable) {
        if (reachable == null) return batch;
        return batch.stream()
                .filter(m -> reachable.contains(m.getSignature().toString()))
                .toList();
    }

    private static int countConcreteMethods(List<List<JavaSootMethod>> batches, Set<String> reachable) {
        int totalMethods = 0;
        for (List<JavaSootMethod> batch : batches) {
            for (JavaSootMethod method : filterBatch(batch, reachable)) {
                if (method.isConcrete()) {
                    totalMethods++;
                }
            }
        }
        return totalMethods;
    }

    private boolean shouldIncludeInResults(JavaSootMethod method, Set<String> userMethodSigs) {
        String methodSig = method.getSignature().toString();
        return userMethodSigs.contains(methodSig)
                && (config.methodFilter == null || method.getName().equals(config.methodFilter));
    }

    private boolean isFullyCachedScc(List<JavaSootMethod> batch,
                                     SummaryCache cache,
                                     Set<String> userMethodSigs) {
        for (JavaSootMethod method : batch) {
            if (!method.isConcrete()) continue;
            String methodSig = method.getSignature().toString();
            if (cache.lookup(methodSig) == null
                    || !shouldSkipAnalysisBecauseCached(methodSig, userMethodSigs)) {
                return false;
            }
        }
        return true;
    }

    private AnalysisResult analyzeMethodWithOptionalTimeout(JavaSootMethod method,
                                                            List<DebugHtmlWriter.SourceFile> sourceContents,
                                                            SummaryCache cache,
                                                            Map<String, Set<String>> dependencyGraph) {
        if (config.methodTimeoutSecs <= 0) {
            return analyzeMethod(method, sourceContents, cache, dependencyGraph);
        }

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<AnalysisResult> future = exec.submit(
                    () -> analyzeMethod(method, sourceContents, cache, dependencyGraph));
            return future.get(config.methodTimeoutSecs, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            recordMethodTimeout(method.getSignature().toString(), config.methodTimeoutSecs);
            return null;
        } catch (InterruptedException | ExecutionException e) {
            return null;
        } finally {
            exec.shutdownNow();
        }
    }

    private void recordMethodTimeout(String sig, long timeoutSecs) {
        System.err.println("\nTIMEOUT: method analysis exceeded " + timeoutSecs + "s for " + sig);
        if (config.timing) {
            timer.addMethodTiming(new TimingRecorder.MethodTiming(
                    sig, "TIMEOUT", "method analysis timed out", 0, 0, 0, 0, 0));
        }
    }

    private MethodSummary storeAndFinalize(JavaSootMethod method,
                                           AnalysisResult analysisResult,
                                           SummaryCache cache) {
        storeSummary(method, analysisResult.summary(), cache);
        MethodSummary effectiveSummary = cache.lookup(method.getSignature().toString());
        finalizeDebugWriter(analysisResult.debugWriter(), effectiveSummary);
        return effectiveSummary;
    }

    private boolean analyzeScc(List<JavaSootMethod> batch,
                               List<DebugHtmlWriter.SourceFile> sourceContents,
                               SummaryCache cache,
                               Map<String, Set<String>> dependencyGraph) {
        if (config.methodTimeoutSecs <= 0) {
            iterateUntilStable(batch, sourceContents, cache, dependencyGraph);
            return false;
        }

        long sccTimeoutSecs = (long) batch.size() * config.methodTimeoutSecs;
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = exec.submit(
                    () -> iterateUntilStable(batch, sourceContents, cache, dependencyGraph));
            future.get(sccTimeoutSecs, TimeUnit.SECONDS);
            return false;
        } catch (TimeoutException e) {
            System.err.println("\nTIMEOUT: SCC batch analysis exceeded " + sccTimeoutSecs + "s");
            if (config.timing) {
                for (JavaSootMethod method : batch) {
                    if (!method.isConcrete()) continue;
                    timer.addMethodTiming(new TimingRecorder.MethodTiming(
                            method.getSignature().toString(), "TIMEOUT",
                            "method analysis timed out", 0, 0, 0, 0, 0));
                }
            }
            return true;
        } catch (InterruptedException | ExecutionException e) {
            return false;
        } finally {
            exec.shutdownNow();
        }
    }

    private void iterateUntilStable(List<JavaSootMethod> batch,
                                    List<DebugHtmlWriter.SourceFile> sourceContents,
                                    SummaryCache cache,
                                    Map<String, Set<String>> dependencyGraph) {
        boolean anyChanged;
        int iter = 0;
        do {
            anyChanged = false;
            iter++;
            for (JavaSootMethod method : batch) {
                if (!method.isConcrete()) continue;
                MethodSummary oldSummary = cache.lookup(method.getSignature().toString());
                AnalysisResult analysisResult =
                        analyzeMethod(method, sourceContents, cache, dependencyGraph);
                if (analysisResult == null) continue;

                MethodSummary effectiveSummary = storeAndFinalize(method, analysisResult, cache);
                if (summaryChanged(oldSummary, effectiveSummary)) {
                    anyChanged = true;
                }
            }
        } while (anyChanged);
        if (config.debug) System.out.println("Debug== SCC stabilized after " + iter + " iterations");
    }

    private static boolean summaryChanged(MethodSummary oldSummary, MethodSummary newSummary) {
        if (oldSummary == null || newSummary == null) return true;
        return oldSummary.getResult() != newSummary.getResult()
                || !oldSummary.getReasons().equals(newSummary.getReasons());
    }

    /** Print an in-place progress bar to stderr: [####----] done/total methods */
    private static void printProgress(int done, int total) {
        int barWidth = 30;
        double frac = total > 0 ? (double) done / total : 0.0;
        int filled = (int) (barWidth * frac);
        String bar = "#".repeat(filled) + "-".repeat(barWidth - filled);
        System.err.printf("\r  Analyzing: [%s] %d/%d methods", bar, done, total);
        System.err.flush();
        if (done >= total) System.err.println();
    }

    /** Store an analyzed summary, then propagate it to all declared base methods. */
    private void storeSummary(JavaSootMethod method, MethodSummary summary, SummaryCache cache) {
        String fullSig = method.getSignature().toString();
        MethodSummary namespaced = summary.namespacedTo(fullSig, fullSig);
        mergeIntoCache(fullSig, namespaced, cache, shouldPersistLibrarySummary(fullSig));
        propagateToBases(fullSig, cache);
    }

    private void preloadReachableLibrarySummaries(SummaryCache cache, Set<String> reachableCachedLibraryMethods) {
        for (String methodSig : reachableCachedLibraryMethods) {
            MethodSummary summary = LibrarySummaryCache.get(methodSig);
            if (summary == null) continue;

            mergeIntoCache(methodSig, summary, cache, false);
            propagateToBases(methodSig, cache);
        }
    }

    private void propagateToBases(String changedSig, SummaryCache cache) {
        if (reverseOverrideGraph == null || reverseOverrideGraph.isEmpty()) return;

        Deque<String> work = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        work.add(changedSig);

        while (!work.isEmpty()) {
            String childSig = work.pop();
            if (!seen.add(childSig)) continue;

            MethodSummary childSummary = cache.lookup(childSig);
            if (childSummary == null) continue;

            for (String baseSig : reverseOverrideGraph.getOrDefault(childSig, Set.of())) {
                MethodSummary rebased = rebindForBase(baseSig, childSummary);
                mergeIntoCache(baseSig, rebased, cache, shouldPersistLibrarySummary(baseSig));
                work.add(baseSig);
            }
        }
    }

    private MethodSummary rebindForBase(String baseSig, MethodSummary summary) {
        return new MethodSummary(baseSig, summary.getExitGraph().copy(),
                summary.getResult(), summary.getReasons(),
                new LinkedHashSet<>(summary.getReturnTargets()));
    }

    private void mergeIntoCache(String fullSig, MethodSummary incoming, SummaryCache cache, boolean persist) {
        MethodSummary existing = cache.lookup(fullSig);
        MethodSummary merged = MethodSummary.union(fullSig, existing, incoming);
        cache.put(fullSig, merged);
        if (persist) {
            LibrarySummaryCache.put(fullSig, merged);
        }
    }

    private static Map<String, Set<String>> invertGraph(Map<String, Set<String>> graph) {
        Map<String, Set<String>> reverse = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : graph.entrySet()) {
            for (String child : entry.getValue()) {
                reverse.computeIfAbsent(child, k -> new LinkedHashSet<>()).add(entry.getKey());
            }
        }
        return reverse;
    }

    /** Persist only when we are explicitly building the JDK/JRT cache. */
    private boolean shouldPersistLibrarySummary(String fullSig) {
        return classDir == null && isLibrarySignature(fullSig);
    }

    private boolean isLibrarySignature(String fullSig) {
        String className = declaringClassName(fullSig);
        return className.startsWith("java.") || className.startsWith("javax.")
                || className.startsWith("sun.") || className.startsWith("com.sun.")
                || className.startsWith("jdk.");
    }

    /**
     * User methods should still analyze their own bodies even if an override-propagated summary
     * already occupies the base signature in the cache. Cached summaries only short-circuit
     * library methods or explicitly preloaded disk-backed summaries.
     */
    private static boolean shouldSkipAnalysisBecauseCached(String fullSig, Set<String> userMethodSigs) {
        return !userMethodSigs.contains(fullSig);
    }

    private static String declaringClassName(String fullSig) {
        if (fullSig == null || fullSig.length() < 3) return "";
        int start = fullSig.indexOf('<');
        int colon = fullSig.indexOf(':');
        if (start >= 0 && colon > start) {
            return fullSig.substring(start + 1, colon).trim();
        }
        return "";
    }

    private AnalysisResult analyzeMethod(JavaSootMethod method,
                                         List<DebugHtmlWriter.SourceFile> sourceContents,
                                         SummaryCache cache,
                                         Map<String, Set<String>> dependencyGraph) {
        DebugHtmlWriter debugWriter = null;
        try {
            // Fetch body and CFG once (Fix #6: View Cache Trap)
            Body body = method.getBody();
            StmtGraph<?> cfg = body.getStmtGraph();
            String sig = method.getSignature().toString();

            // Set up debug writer if debug mode is enabled
            if (config.debug) {
                debugWriter = DebugHtmlWriter.create(sig);
                debugWriter.setSourceCode(
                    extractMethodSource(cfg, sourceContents));
                debugWriter.setBytecode(extractBytecode(method));
                for (Stmt stmt : cfg.getStmts()) {
                    debugWriter.addJimpleStatement(stmt.toString());
                }
                debugWriter.setGraphs(sig, rawCallGraph, overrideGraph, dependencyGraph);
            }

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
            SideEffectFlowAnalysis analysis = new SideEffectFlowAnalysis(
                cfg, body, config, method.isStatic(), debugWriter, paramTypeNames, cache);

            // Get the exit graph
            PointsToGraph exitGraph = analysis.getExitGraph();

            long dataflowNs = 0;
            if (config.timing) dataflowNs = System.nanoTime() - dataflowStart;

            // --- side-effect check timing ---
            long sideEffectStart = 0;
            if (config.timing) sideEffectStart = System.nanoTime();

            // Check side-effect (include return targets for inter-procedural summaries)
            boolean isConstructor = "<init>".equals(method.getName());
            MethodSummary sideEffectResult = SideEffectChecker.check(sig, exitGraph, isConstructor, config.debug);
            MethodSummary summary = new MethodSummary(sig, exitGraph,
                    sideEffectResult.getResult(), sideEffectResult.getReasons(),
                    exitGraph.getReturnTargets());

            long sideEffectNs = 0;
            if (config.timing) sideEffectNs = System.nanoTime() - sideEffectStart;
            // --- Record timing data ---
            if (config.timing) {
                int stmtCount = cfg.getStmts().size();
                int nodeCount = exitGraph.getAllNodes().size();
                int edgeCount = countEdges(exitGraph);
                timer.addMethodTiming(new TimingRecorder.MethodTiming(
                        sig, summary.getResult().name(), summary.getReason(),
                        dataflowNs, sideEffectNs, stmtCount, nodeCount, edgeCount));
            }

            return new AnalysisResult(summary, debugWriter);
        } catch (Exception e) {
            closeDebugWriterQuietly(debugWriter);
            System.err.println("Error analyzing " + method.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private void finalizeDebugWriter(DebugHtmlWriter debugWriter, MethodSummary effectiveSummary) {
        if (debugWriter == null) return;
        try {
            if (effectiveSummary != null) {
                PointsToGraph exitGraph = effectiveSummary.getExitGraph();
                debugWriter.setExitGraph(exitGraph);
                debugWriter.setInsideEdges(exitGraph);
                debugWriter.setOutsideEdges(exitGraph);
                debugWriter.setLocalVariables(exitGraph);
                debugWriter.setEscapedNodes(exitGraph.getGlobalEscaped());
                debugWriter.setPrestateNodes(SideEffectChecker.computePrestateNodes(exitGraph));
                debugWriter.setGloballyEscapedNodes(SideEffectChecker.computeGloballyEscapedNodes(exitGraph));
                debugWriter.setMutatedFields(exitGraph.getMutatedFields());
                debugWriter.setSideEffectResult(
                        effectiveSummary.getResult().name(), effectiveSummary.getReason());
            }
        } finally {
            closeDebugWriterQuietly(debugWriter);
        }
    }

    private static void closeDebugWriterQuietly(DebugHtmlWriter debugWriter) {
        if (debugWriter == null) return;
        try {
            debugWriter.close();
        } catch (IOException ignored) {
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
     * Supports both classpath mode (classDir) and JRT mode (jrt:/ filesystem).
     */
    private List<String> extractBytecode(JavaSootMethod method) {
        try {
            String className = method.getDeclaringClassType().getFullyQualifiedName();
            byte[] classBytes;

            if (classDir != null) {
                // Normal mode: read from compiled class directory
                Path classFile = classDir.resolve(className.replace('.', '/') + ".class");
                if (!Files.exists(classFile)) {
                    return List.of("// .class file not found: " + classFile);
                }
                classBytes = Files.readAllBytes(classFile);
            } else {
                // JRT mode: read from the JDK runtime image
                try {
                    FileSystem jrtFs = FileSystems.getFileSystem(URI.create("jrt:/"));
                    // JRT class files are at /modules/<module>/<package>/<Class>.class
                    // Try to find the class in any module
                    String classPath = className.replace('.', '/') + ".class";
                    Path jrtPath = jrtFs.getPath("/modules/java.base/" + classPath);
                    if (!Files.exists(jrtPath)) {
                        // Search across all modules
                        jrtPath = null;
                        for (Path moduleRoot : Files.list(jrtFs.getPath("/modules")).toList()) {
                            Path candidate = moduleRoot.resolve(classPath);
                            if (Files.exists(candidate)) {
                                jrtPath = candidate;
                                break;
                            }
                        }
                    }
                    if (jrtPath == null) {
                        return List.of("// .class file not found in JRT for: " + className);
                    }
                    classBytes = Files.readAllBytes(jrtPath);
                } catch (Exception e) {
                    return List.of("// Error reading from JRT: " + e.getMessage());
                }
            }

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
