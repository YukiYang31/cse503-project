package edu.uw.cse.purity;

import edu.uw.cse.purity.analysis.MethodSummary;
import edu.uw.cse.purity.analysis.MethodSummary.PurityResult;
import edu.uw.cse.purity.analysis.PurityChecker;
import edu.uw.cse.purity.analysis.PurityFlowAnalysis;
import edu.uw.cse.purity.graph.PointsToGraph;
import org.junit.BeforeClass;
import org.junit.Test;
import sootup.core.graph.StmtGraph;
import sootup.core.model.Body;
import sootup.java.bytecode.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.JavaSootClass;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

import java.nio.file.Path;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Integration tests for the purity analysis.
 * Compiles test case Java files, runs the analysis, and checks results.
 */
public class PurityAnalysisTest {

    private static final AnalysisConfig CONFIG = new AnalysisConfig(false, false, null);
    private static Map<String, Map<String, PurityResult>> results;

    @BeforeClass
    public static void setUp() throws Exception {
        results = new HashMap<>();

        String[] testFiles = {
            "src/test/resources/testcases/PureMethods.java",
            "src/test/resources/testcases/ImpureMethods.java",
            "src/test/resources/testcases/NewObjectMutation.java",
            "src/test/resources/testcases/StaticFieldEscape.java",
            "src/test/resources/testcases/PaperExample.java"
        };

        Path classDir = JavaCompiler.compile(Arrays.asList(testFiles));

        JavaClassPathAnalysisInputLocation inputLocation =
            new JavaClassPathAnalysisInputLocation(classDir.toString());
        JavaView view = new JavaView(inputLocation);

        for (JavaSootClass sootClass : view.getClasses()) {
            String className = sootClass.getName();
            Map<String, PurityResult> methodResults = new HashMap<>();

            for (JavaSootMethod method : sootClass.getMethods()) {
                if (!method.isConcrete()) continue;

                Body body = method.getBody();
                StmtGraph<?> cfg = body.getStmtGraph();
                String methodName = method.getName();

                PurityFlowAnalysis analysis = new PurityFlowAnalysis(cfg, body, CONFIG, method.isStatic());
                PointsToGraph exitGraph = analysis.getExitGraph();
                boolean isConstructor = "<init>".equals(methodName);
                MethodSummary summary = PurityChecker.check(
                    method.getSignature().toString(), exitGraph, isConstructor);

                methodResults.put(methodName, summary.getResult());
            }

            results.put(className, methodResults);
        }
    }

    // --- PureMethods ---

    @Test
    public void testPureAdd() {
        assertEquals(PurityResult.PURE, getResult("PureMethods", "add"));
    }

    @Test
    public void testPureCreateArray() {
        assertEquals(PurityResult.PURE, getResult("PureMethods", "createArray"));
    }

    // --- ImpureMethods ---

    @Test
    public void testImpureSetX() {
        assertEquals(PurityResult.IMPURE, getResult("ImpureMethods", "setX"));
    }

    @Test
    public void testImpureIncrement() {
        assertEquals(PurityResult.IMPURE, getResult("ImpureMethods", "increment"));
    }

    @Test
    public void testImpureGetAndIncrement() {
        assertEquals(PurityResult.IMPURE, getResult("ImpureMethods", "getAndIncrement"));
    }

    // --- NewObjectMutation ---

    @Test
    public void testPureCopyFirst() {
        assertEquals(PurityResult.PURE, getResult("NewObjectMutation", "copyFirst"));
    }

    // --- StaticFieldEscape ---

    @Test
    public void testImpureSetShared() {
        assertEquals(PurityResult.IMPURE, getResult("StaticFieldEscape", "setShared"));
    }

    @Test
    public void testImpureCreateAndEscape() {
        assertEquals(PurityResult.IMPURE, getResult("StaticFieldEscape", "createAndEscape"));
    }

    @Test
    public void testGetSharedIsPure() {
        assertEquals(PurityResult.PURE, getResult("StaticFieldEscape", "getShared"));
    }

    // ===================================================================
    // Paper Example Tests (Salcianu & Rinard 2005, Figure 1)
    // ===================================================================

    // --- Constructors should all be PURE (constructor exception for this.f writes) ---

    @Test
    public void testPaperPointConstructorPure() {
        // Paper Figure 2.a: Point constructor mutates this.x and this.y
        // Constructor exception: direct this.f writes are allowed → PURE
        assertEquals(PurityResult.PURE, getResult("Point", "<init>"));
    }

    @Test
    public void testPaperCellConstructorPure() {
        // Paper Figure 2.b: Cell constructor mutates this.data and this.next
        // Constructor exception → PURE
        assertEquals(PurityResult.PURE, getResult("Cell", "<init>"));
    }

    @Test
    public void testPaperListItrConstructorPure() {
        // Paper Figure 3.d: ListItr constructor mutates this.cell
        // Constructor exception → PURE
        assertEquals(PurityResult.PURE, getResult("ListItr", "<init>"));
    }

    @Test
    public void testPaperListConstructorPure() {
        // List default constructor initializes this.head = null
        // Constructor exception → PURE
        assertEquals(PurityResult.PURE, getResult("List", "<init>"));
    }

    // --- Pure methods ---

    @Test
    public void testPaperListItrHasNextPure() {
        // Paper Figure 3.e: hasNext only reads this.cell, no mutations → PURE
        assertEquals(PurityResult.PURE, getResult("ListItr", "hasNext"));
    }

    // --- Impure methods ---

    @Test
    public void testPaperPointFlipImpure() {
        // Paper: Point.flip() mutates this.x and this.y (prestate fields) → IMPURE
        assertEquals(PurityResult.IMPURE, getResult("Point", "flip"));
    }

    @Test
    public void testPaperListItrNextImpure() {
        // Paper Figure 3.f: ListItr.next() mutates this.cell → IMPURE
        assertEquals(PurityResult.IMPURE, getResult("ListItr", "next"));
    }

    @Test
    public void testPaperListAddImpure() {
        // List.add() mutates this.head (prestate) AND calls unknown Cell constructor
        // Intra-procedural: conservatively IMPURE
        assertEquals(PurityResult.IMPURE, getResult("List", "add"));
    }

    @Test
    public void testPaperListIteratorImpure() {
        // List.iterator() calls unknown ListItr constructor
        // Intra-procedural: conservatively IMPURE
        // NOTE: With inter-procedural analysis (paper), this would be PURE
        assertEquals(PurityResult.IMPURE, getResult("List", "iterator"));
    }

    @Test
    public void testPaperSumXImpure() {
        // Paper Section 2.4: sumX is PURE with inter-procedural analysis
        // Our intra-procedural tool: conservatively IMPURE (unknown calls to
        // list.iterator(), it.hasNext(), it.next())
        assertEquals(PurityResult.IMPURE, getResult("PaperMain", "sumX"));
    }

    @Test
    public void testPaperFlipAllImpure() {
        // Paper Section 2.4: flipAll is IMPURE (mutates Point.x and Point.y
        // reachable from the list parameter)
        // Our tool: also IMPURE (conservative due to unknown calls)
        assertEquals(PurityResult.IMPURE, getResult("PaperMain", "flipAll"));
    }

    // --- Merge equivalence: results should be the same with merging enabled ---

    @Test
    public void testMergeEquivalence() throws Exception {
        AnalysisConfig mergeConfig = new AnalysisConfig(false, true, null);

        String[] testFiles = {
            "src/test/resources/testcases/PureMethods.java",
            "src/test/resources/testcases/ImpureMethods.java"
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

                PurityFlowAnalysis analysis = new PurityFlowAnalysis(cfg, body, mergeConfig, method.isStatic());
                PointsToGraph exitGraph = analysis.getExitGraph();
                boolean isConstructor = "<init>".equals(methodName);
                MethodSummary summary = PurityChecker.check(
                    method.getSignature().toString(), exitGraph, isConstructor);

                PurityResult expected = getResult(className, methodName);
                if (expected != null) {
                    assertEquals("No-merge equivalence failed for " + className + "." + methodName,
                        expected, summary.getResult());
                }
            }
        }
    }

    // --- Helper ---

    private static PurityResult getResult(String className, String methodName) {
        Map<String, PurityResult> classResults = results.get(className);
        if (classResults == null) {
            fail("Class not found in results: " + className);
        }
        return classResults.get(methodName);
    }
}
