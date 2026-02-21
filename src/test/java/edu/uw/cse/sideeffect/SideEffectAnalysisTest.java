package edu.uw.cse.sideeffect;

import edu.uw.cse.sideeffect.analysis.CallGraphBuilder;
import edu.uw.cse.sideeffect.analysis.MethodSummary;
import edu.uw.cse.sideeffect.analysis.MethodSummary.SideEffectResult;
import edu.uw.cse.sideeffect.analysis.SideEffectChecker;
import edu.uw.cse.sideeffect.analysis.SideEffectFlowAnalysis;
import edu.uw.cse.sideeffect.analysis.SummaryCache;
import edu.uw.cse.sideeffect.graph.PointsToGraph;
import java.nio.file.Path;
import java.util.*;
import org.junit.BeforeClass;
import org.junit.Test;
import sootup.core.graph.StmtGraph;
import sootup.core.model.Body;
import sootup.core.types.Type;
import sootup.java.bytecode.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.JavaSootClass;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;


import static org.junit.Assert.*;

/**
 * Integration tests for the side-effect analysis.
 * Compiles test case Java files, runs the analysis, and checks results.
 */
public class SideEffectAnalysisTest {

    private static final AnalysisConfig CONFIG = new AnalysisConfig(false, false, null);
    private static Map<String, Map<String, SideEffectResult>> results;

    @BeforeClass
    public static void setUp() throws Exception {
        results = new HashMap<>();

        String[] testFiles = {
            "src/test/resources/testcases/SideEffectFreeMethods.java",
            "src/test/resources/testcases/SideEffectingMethods.java",
            "src/test/resources/testcases/NewObjectMutation.java",
            "src/test/resources/testcases/StaticFieldEscape.java",
            "src/test/resources/testcases/PaperExample.java"
        };

        Path classDir = JavaCompiler.compile(Arrays.asList(testFiles));

        JavaClassPathAnalysisInputLocation inputLocation =
            new JavaClassPathAnalysisInputLocation(classDir.toString());
        JavaView view = new JavaView(inputLocation);

        Collection<JavaSootClass> classes = view.getClasses();

        // Build call graph and compute bottom-up order for inter-procedural analysis
        List<List<JavaSootMethod>> batches = CallGraphBuilder.computeBottomUpOrder(classes, CONFIG).batches();
        SummaryCache cache = new SummaryCache();

        for (List<JavaSootMethod> batch : batches) {
            if (batch.size() == 1) {
                JavaSootMethod method = batch.get(0);
                if (!method.isConcrete()) continue;
                MethodSummary summary = analyzeWithCache(method, cache);
                if (summary != null) {
                    storeSummary(method, summary, cache);
                    storeResult(method, summary);
                }
            } else {
                // SCC: iterate until stable
                for (int iter = 0; iter < 5; iter++) {
                    boolean anyChanged = false;
                    for (JavaSootMethod method : batch) {
                        if (!method.isConcrete()) continue;
                        MethodSummary old = cache.lookup(
                                method.getSignature().toString(),
                                method.getSignature().getSubSignature().toString());
                        MethodSummary summary = analyzeWithCache(method, cache);
                        if (summary != null) {
                            storeSummary(method, summary, cache);
                            if (old == null || old.getResult() != summary.getResult()) anyChanged = true;
                        }
                    }
                    if (!anyChanged) break;
                }
                for (JavaSootMethod method : batch) {
                    if (!method.isConcrete()) continue;
                    MethodSummary summary = cache.lookup(
                            method.getSignature().toString(),
                            method.getSignature().getSubSignature().toString());
                    if (summary != null) storeResult(method, summary);
                }
            }
        }
    }

    private static MethodSummary analyzeWithCache(JavaSootMethod method, SummaryCache cache) {
        try {
            Body body = method.getBody();
            StmtGraph<?> cfg = body.getStmtGraph();
            List<String> paramTypeNames = method.getSignature().getParameterTypes()
                    .stream().map(Type::toString)
                    .map(t -> { int dot = t.lastIndexOf('.'); return dot >= 0 ? t.substring(dot + 1) : t; })
                    .toList();

            SideEffectFlowAnalysis analysis = new SideEffectFlowAnalysis(
                    cfg, body, CONFIG, method.isStatic(), null, paramTypeNames, cache);
            PointsToGraph exitGraph = analysis.getExitGraph();
            boolean isConstructor = "<init>".equals(method.getName());
            MethodSummary sideEffectResult = SideEffectChecker.check(
                    method.getSignature().toString(), exitGraph, isConstructor);
            return new MethodSummary(method.getSignature().toString(), exitGraph,
                    sideEffectResult.getResult(), sideEffectResult.getReason(),
                    exitGraph.getReturnTargets());
        } catch (Exception e) {
            return null;
        }
    }

    private static void storeSummary(JavaSootMethod method, MethodSummary summary, SummaryCache cache) {
        cache.put(method.getSignature().toString(),
                method.getSignature().getSubSignature().toString(), summary);
    }

    private static void storeResult(JavaSootMethod method, MethodSummary summary) {
        String className = method.getDeclaringClassType().getClassName();
        results.computeIfAbsent(className, k -> new HashMap<>())
                .put(method.getName(), summary.getResult());
    }

    // --- SideEffectFreeMethods ---

    @Test
    public void testSideEffectFreeAdd() {
        assertEquals(SideEffectResult.SIDE_EFFECT_FREE, getResult("SideEffectFreeMethods", "add"));
    }

    @Test
    public void testSideEffectFreeCreateArray() {
        assertEquals(SideEffectResult.SIDE_EFFECT_FREE, getResult("SideEffectFreeMethods", "createArray"));
    }

    // --- SideEffectingMethods ---

    @Test
    public void testSideEffectingSetX() {
        assertEquals(SideEffectResult.SIDE_EFFECTING, getResult("SideEffectingMethods", "setX"));
    }

    @Test
    public void testSideEffectingIncrement() {
        assertEquals(SideEffectResult.SIDE_EFFECTING, getResult("SideEffectingMethods", "increment"));
    }

    @Test
    public void testSideEffectingGetAndIncrement() {
        assertEquals(SideEffectResult.SIDE_EFFECTING, getResult("SideEffectingMethods", "getAndIncrement"));
    }

    // --- NewObjectMutation ---

    @Test
    public void testSideEffectFreeCopyFirst() {
        assertEquals(SideEffectResult.SIDE_EFFECT_FREE, getResult("NewObjectMutation", "copyFirst"));
    }

    // --- StaticFieldEscape ---

    @Test
    public void testSideEffectingSetShared() {
        assertEquals(SideEffectResult.SIDE_EFFECTING, getResult("StaticFieldEscape", "setShared"));
    }

    @Test
    public void testSideEffectingCreateAndEscape() {
        assertEquals(SideEffectResult.SIDE_EFFECTING, getResult("StaticFieldEscape", "createAndEscape"));
    }

    @Test
    public void testGetSharedIsSideEffectFree() {
        assertEquals(SideEffectResult.SIDE_EFFECT_FREE, getResult("StaticFieldEscape", "getShared"));
    }

    // ===================================================================
    // Paper Example Tests (Salcianu & Rinard 2005, Figure 1)
    // ===================================================================

    // --- Constructors should all be SIDE_EFFECT_FREE (constructor exception for this.f writes) ---

    @Test
    public void testPaperPointConstructorSideEffectFree() {
        // Paper Figure 2.a: Point constructor mutates this.x and this.y
        // Constructor exception: direct this.f writes are allowed → SIDE_EFFECT_FREE
        assertEquals(SideEffectResult.SIDE_EFFECT_FREE, getResult("Point", "<init>"));
    }

    @Test
    public void testPaperCellConstructorSideEffectFree() {
        // Paper Figure 2.b: Cell constructor mutates this.data and this.next
        // Constructor exception → SIDE_EFFECT_FREE
        assertEquals(SideEffectResult.SIDE_EFFECT_FREE, getResult("Cell", "<init>"));
    }

    @Test
    public void testPaperListItrConstructorSideEffectFree() {
        // Paper Figure 3.d: ListItr constructor mutates this.cell
        // Constructor exception → SIDE_EFFECT_FREE
        assertEquals(SideEffectResult.SIDE_EFFECT_FREE, getResult("ListItr", "<init>"));
    }

    @Test
    public void testPaperListConstructorSideEffectFree() {
        // List default constructor initializes this.head = null
        // Constructor exception → SIDE_EFFECT_FREE
        assertEquals(SideEffectResult.SIDE_EFFECT_FREE, getResult("List", "<init>"));
    }

    // --- Side-effect-free methods ---

    @Test
    public void testPaperListItrHasNextSideEffectFree() {
        // Paper Figure 3.e: hasNext only reads this.cell, no mutations → SIDE_EFFECT_FREE
        assertEquals(SideEffectResult.SIDE_EFFECT_FREE, getResult("ListItr", "hasNext"));
    }

    // --- Side-effecting methods ---

    @Test
    public void testPaperPointFlipSideEffecting() {
        // Paper: Point.flip() mutates this.x and this.y (prestate fields) → SIDE_EFFECTING
        assertEquals(SideEffectResult.SIDE_EFFECTING, getResult("Point", "flip"));
    }

    @Test
    public void testPaperListItrNextSideEffecting() {
        // Paper Figure 3.f: ListItr.next() mutates this.cell → SIDE_EFFECTING
        assertEquals(SideEffectResult.SIDE_EFFECTING, getResult("ListItr", "next"));
    }

    @Test
    public void testPaperListAddSideEffecting() {
        // List.add() mutates this.head (prestate) AND calls unknown Cell constructor
        // Intra-procedural: conservatively SIDE_EFFECTING
        assertEquals(SideEffectResult.SIDE_EFFECTING, getResult("List", "add"));
    }

    @Test
    public void testPaperListIteratorSideEffectFree() {
        // List.iterator() calls ListItr constructor which is SIDE_EFFECT_FREE
        // Inter-procedural: ListItr.<init> summary shows only InsideNode mutation → SIDE_EFFECT_FREE
        assertEquals(SideEffectResult.SIDE_EFFECT_FREE, getResult("List", "iterator"));
    }

    @Test
    public void testPaperSumXSideEffectFree() {
        // Paper Section 2.4: sumX is SIDE_EFFECT_FREE with inter-procedural analysis
        // Inter-procedural: iterator(), hasNext(), next() summaries are instantiated
        assertEquals(SideEffectResult.SIDE_EFFECT_FREE, getResult("PaperMain", "sumX"));
    }

    @Test
    public void testPaperFlipAllSideEffecting() {
        // Paper Section 2.4: flipAll is SIDE_EFFECTING (mutates Point.x and Point.y
        // reachable from the list parameter)
        // Our tool: also SIDE_EFFECTING (conservative due to unknown calls)
        assertEquals(SideEffectResult.SIDE_EFFECTING, getResult("PaperMain", "flipAll"));
    }

    // --- Merge equivalence: results should be the same with merging enabled ---

    @Test
    public void testMergeEquivalence() throws Exception {
        AnalysisConfig mergeConfig = new AnalysisConfig(false, true, null);

        String[] testFiles = {
            "src/test/resources/testcases/SideEffectFreeMethods.java",
            "src/test/resources/testcases/SideEffectingMethods.java"
        };

        Path classDir = JavaCompiler.compile(Arrays.asList(testFiles));
        JavaClassPathAnalysisInputLocation inputLocation =
            new JavaClassPathAnalysisInputLocation(classDir.toString());
        JavaView view = new JavaView(inputLocation);

        for (JavaSootClass sootClass : view.getClasses()) {
            String className = sootClass.getName();
            for (JavaSootMethod method : sootClass.getMethods()) {
                if (!method.isConcrete()) continue;

                Body body = method.getBody();
                StmtGraph<?> cfg = body.getStmtGraph();
                String methodName = method.getName();

                SideEffectFlowAnalysis analysis = new SideEffectFlowAnalysis(cfg, body, mergeConfig, method.isStatic());
                PointsToGraph exitGraph = analysis.getExitGraph();
                boolean isConstructor = "<init>".equals(methodName);
                MethodSummary summary = SideEffectChecker.check(
                    method.getSignature().toString(), exitGraph, isConstructor);

                SideEffectResult expected = getResult(className, methodName);
                if (expected != null) {
                    assertEquals("No-merge equivalence failed for " + className + "." + methodName,
                        expected, summary.getResult());
                }
            }
        }
    }

    // ===================================================================
    // Inter-Procedural Tests (Section 5.3)
    // ===================================================================

    private static Map<String, Map<String, SideEffectResult>> ipResults;

    private static void setUpInterProcedural() throws Exception {
        if (ipResults != null) return; // already initialized
        ipResults = new HashMap<>();

        String[] testFiles = {
            "src/test/resources/interprocedural/InterProceduralTest.java"
        };

        Path classDir = JavaCompiler.compile(Arrays.asList(testFiles));
        JavaClassPathAnalysisInputLocation inputLocation =
            new JavaClassPathAnalysisInputLocation(classDir.toString());
        JavaView view = new JavaView(inputLocation);

        Collection<JavaSootClass> classes = view.getClasses();
        List<List<JavaSootMethod>> batches = CallGraphBuilder.computeBottomUpOrder(classes, CONFIG).batches();
        SummaryCache cache = new SummaryCache();

        for (List<JavaSootMethod> batch : batches) {
            if (batch.size() == 1) {
                JavaSootMethod method = batch.get(0);
                if (!method.isConcrete()) continue;
                MethodSummary summary = analyzeWithCache(method, cache);
                if (summary != null) {
                    storeSummary(method, summary, cache);
                    String className = method.getDeclaringClassType().getClassName();
                    ipResults.computeIfAbsent(className, k -> new HashMap<>())
                            .put(method.getName(), summary.getResult());
                }
            } else {
                for (int iter = 0; iter < 5; iter++) {
                    boolean anyChanged = false;
                    for (JavaSootMethod method : batch) {
                        if (!method.isConcrete()) continue;
                        MethodSummary old = cache.lookup(method.getSignature().toString(),
                                method.getSignature().getSubSignature().toString());
                        MethodSummary summary = analyzeWithCache(method, cache);
                        if (summary != null) {
                            storeSummary(method, summary, cache);
                            if (old == null || old.getResult() != summary.getResult()) anyChanged = true;
                        }
                    }
                    if (!anyChanged) break;
                }
                for (JavaSootMethod method : batch) {
                    if (!method.isConcrete()) continue;
                    MethodSummary summary = cache.lookup(method.getSignature().toString(),
                            method.getSignature().getSubSignature().toString());
                    if (summary != null) {
                        String className = method.getDeclaringClassType().getClassName();
                        ipResults.computeIfAbsent(className, k -> new HashMap<>())
                                .put(method.getName(), summary.getResult());
                    }
                }
            }
        }
    }

    private SideEffectResult getIPResult(String className, String methodName) throws Exception {
        setUpInterProcedural();
        Map<String, SideEffectResult> classResults = ipResults.get(className);
        if (classResults == null) fail("Class not found in IP results: " + className);
        return classResults.get(methodName);
    }

    @Test
    public void testIPReaderGetValueSideEffectFree() throws Exception {
        assertEquals(SideEffectResult.SIDE_EFFECT_FREE, getIPResult("IPReader", "getValue"));
    }

    @Test
    public void testIPReaderReadViaHelperSideEffectFree() throws Exception {
        assertEquals(SideEffectResult.SIDE_EFFECT_FREE, getIPResult("IPReader", "readViaHelper"));
    }

    @Test
    public void testIPFactoryCreateSideEffectFree() throws Exception {
        assertEquals(SideEffectResult.SIDE_EFFECT_FREE, getIPResult("IPFactory", "create"));
    }

    @Test
    public void testIPConsumerMakeAndReadSideEffectFree() throws Exception {
        assertEquals(SideEffectResult.SIDE_EFFECT_FREE, getIPResult("IPConsumer", "makeAndRead"));
    }

    @Test
    public void testIPMutatorModifySideEffecting() throws Exception {
        assertEquals(SideEffectResult.SIDE_EFFECTING, getIPResult("IPMutator", "modify"));
    }

    @Test
    public void testIPSideEffectingCallerDoModifySideEffecting() throws Exception {
        assertEquals(SideEffectResult.SIDE_EFFECTING, getIPResult("IPSideEffectingCaller", "doModify"));
    }

    @Test
    public void testIPIterHasNextSideEffectFree() throws Exception {
        assertEquals(SideEffectResult.SIDE_EFFECT_FREE, getIPResult("IPIter", "hasNext"));
    }

    @Test
    public void testIPLinkedListIteratorSideEffectFree() throws Exception {
        assertEquals(SideEffectResult.SIDE_EFFECT_FREE, getIPResult("IPLinkedList", "iterator"));
    }

    @Test
    public void testIPSumSumSideEffectFree() throws Exception {
        assertEquals(SideEffectResult.SIDE_EFFECT_FREE, getIPResult("IPSum", "sum"));
    }

    // --- Helper ---

    private static SideEffectResult getResult(String className, String methodName) {
        Map<String, SideEffectResult> classResults = results.get(className);
        if (classResults == null) {
            fail("Class not found in results: " + className);
        }
        return classResults.get(methodName);
    }
}
