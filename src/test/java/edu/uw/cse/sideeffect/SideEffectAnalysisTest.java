package edu.uw.cse.sideeffect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import edu.uw.cse.sideeffect.analysis.CallGraphBuilder;
import edu.uw.cse.sideeffect.analysis.MethodSummary;
import edu.uw.cse.sideeffect.analysis.MethodSummary.SideEffectResult;
import edu.uw.cse.sideeffect.analysis.SideEffectChecker;
import edu.uw.cse.sideeffect.analysis.SideEffectFlowAnalysis;
import edu.uw.cse.sideeffect.analysis.SummaryCache;
import edu.uw.cse.sideeffect.graph.PointsToGraph;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import sootup.core.graph.StmtGraph;
import sootup.core.model.Body;
import sootup.core.types.Type;
import sootup.java.bytecode.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.JavaSootClass;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

/**
 * Integration tests for the side-effect analysis.
 * Compiles resource fixtures, runs the analysis, and checks verdicts/reasons.
 */
public class SideEffectAnalysisTest {

    private static final AnalysisConfig CONFIG = new AnalysisConfig(false, false, null);
    private static final AnalysisConfig MERGE_CONFIG = new AnalysisConfig(false, true, null);

    private static final String[] CORE_TEST_FILES = {
        "src/test/resources/testcases/SideEffectFreeMethods.java",
        "src/test/resources/testcases/SideEffectingMethods.java",
        "src/test/resources/testcases/NewObjectMutation.java",
        "src/test/resources/testcases/StaticFieldEscape.java",
        "src/test/resources/testcases/ConditionalSideEffects.java",
        "src/test/resources/testcases/InnerClassAndJdk.java",
        "src/test/resources/testcases/MultipleReasons.java",
        "src/test/resources/testcases/MutualRecursion.java",
        "src/test/resources/testcases/StaticFieldEscape2.java",
        "src/test/resources/testcases/StaticWrite.java",
        "src/test/resources/testcases/reportBankExample.java",
        "src/test/resources/testcases/reportBankExample2.java",
        "src/test/resources/testcases/PaperExample.java"
    };

    private static final String[] INTRAPROCEDURAL_TEST_FILES = {
        "src/test/resources/intraprocedural/SideEffectFreeExamples.java",
        "src/test/resources/intraprocedural/SideEffectingExamples.java",
        "src/test/resources/intraprocedural/EdgeCases.java",
        "src/test/resources/intraprocedural/ComplexSideEffectFreeExample.java",
        "src/test/resources/intraprocedural/ConstructorExamples.java"
    };

    private static final String[] INTERPROCEDURAL_TEST_FILES = {
        "src/test/resources/interprocedural/InterProceduralTest.java"
    };

    private static final String[] INTERFILE_TEST_FILES = {
        "src/test/resources/interfile/InterFileMain.java",
        "src/test/resources/interfile/InterFileHelper.java"
    };

    private static final String[] OVERRIDE_TEST_FILES = {
        "src/test/resources/interfile/OverrideBase.java",
        "src/test/resources/interfile/OverrideDerived.java"
    };

    private static AnalysisFixture coreFixture;
    private static AnalysisFixture intraproceduralFixture;
    private static AnalysisFixture interproceduralFixture;
    private static AnalysisFixture interfileFixture;
    private static AnalysisFixture overrideFixture;

    private static final class AnalysisFixture {
        private final Map<String, Map<String, MethodSummary>> summariesByClass = new HashMap<>();

        void store(JavaSootMethod method, MethodSummary summary) {
            if (summary == null) return;
            String className = method.getDeclaringClassType().getClassName();
            summariesByClass.computeIfAbsent(className, k -> new HashMap<>())
                    .put(method.getName(), summary);
        }

        SideEffectResult getResult(String className, String methodName) {
            return getSummary(className, methodName).getResult();
        }

        MethodSummary getSummary(String className, String methodName) {
            Map<String, MethodSummary> classSummaries = summariesByClass.get(className);
            if (classSummaries == null) {
                fail("Class not found in results: " + className);
            }
            MethodSummary summary = classSummaries.get(methodName);
            if (summary == null) {
                fail("Method not found in results: " + className + "." + methodName);
            }
            return summary;
        }
    }

    private static final class ExpectedResult {
        final String className;
        final String methodName;
        final SideEffectResult expected;

        ExpectedResult(String className, String methodName, SideEffectResult expected) {
            this.className = className;
            this.methodName = methodName;
            this.expected = expected;
        }
    }

    private static ExpectedResult expect(String className, String methodName, SideEffectResult expected) {
        return new ExpectedResult(className, methodName, expected);
    }

    private static AnalysisFixture core() throws Exception {
        if (coreFixture == null) {
            coreFixture = analyzeFiles(CONFIG, CORE_TEST_FILES);
        }
        return coreFixture;
    }

    private static AnalysisFixture intraprocedural() throws Exception {
        if (intraproceduralFixture == null) {
            intraproceduralFixture = analyzeFiles(CONFIG, INTRAPROCEDURAL_TEST_FILES);
        }
        return intraproceduralFixture;
    }

    private static AnalysisFixture interprocedural() throws Exception {
        if (interproceduralFixture == null) {
            interproceduralFixture = analyzeFiles(CONFIG, INTERPROCEDURAL_TEST_FILES);
        }
        return interproceduralFixture;
    }

    private static AnalysisFixture interfile() throws Exception {
        if (interfileFixture == null) {
            interfileFixture = analyzeFiles(CONFIG, INTERFILE_TEST_FILES);
        }
        return interfileFixture;
    }

    private static AnalysisFixture overrideFixture() throws Exception {
        if (overrideFixture == null) {
            overrideFixture = analyzeFiles(CONFIG, OVERRIDE_TEST_FILES);
        }
        return overrideFixture;
    }

    private static AnalysisFixture analyzeFiles(AnalysisConfig config, String... testFiles) throws Exception {
        Path classDir = JavaCompiler.compile(Arrays.asList(testFiles));
        JavaClassPathAnalysisInputLocation inputLocation =
                new JavaClassPathAnalysisInputLocation(classDir.toString());
        JavaView view = new JavaView(inputLocation);

        Collection<JavaSootClass> classes = view.getClasses();
        CallGraphBuilder.Result cgResult = CallGraphBuilder.computeBottomUpOrder(classes, config);
        List<List<JavaSootMethod>> batches = cgResult.batches();
        Map<String, Set<String>> reverseOverrideGraph = invertGraph(cgResult.overrideGraph());
        SummaryCache cache = new SummaryCache();
        AnalysisFixture fixture = new AnalysisFixture();

        for (List<JavaSootMethod> batch : batches) {
            analyzeBatch(batch, cache, reverseOverrideGraph, config, fixture);
        }

        return fixture;
    }

    private static void analyzeBatch(List<JavaSootMethod> batch,
                                     SummaryCache cache,
                                     Map<String, Set<String>> reverseOverrideGraph,
                                     AnalysisConfig config,
                                     AnalysisFixture fixture) {
        if (batch.size() == 1) {
            JavaSootMethod method = batch.get(0);
            if (!method.isConcrete()) return;

            MethodSummary summary = analyzeMethod(method, cache, config);
            if (summary == null) return;

            storeSummary(method, summary, cache, reverseOverrideGraph);
            fixture.store(method, cache.lookup(method.getSignature().toString()));
            return;
        }

        for (int iter = 0; iter < 5; iter++) {
            boolean anyChanged = false;
            for (JavaSootMethod method : batch) {
                if (!method.isConcrete()) continue;

                MethodSummary old = cache.lookup(method.getSignature().toString());
                MethodSummary summary = analyzeMethod(method, cache, config);
                if (summary == null) continue;

                storeSummary(method, summary, cache, reverseOverrideGraph);
                if (old == null || old.getResult() != summary.getResult()
                        || !old.getReasons().equals(summary.getReasons())) {
                    anyChanged = true;
                }
            }
            if (!anyChanged) break;
        }

        for (JavaSootMethod method : batch) {
            if (!method.isConcrete()) continue;
            fixture.store(method, cache.lookup(method.getSignature().toString()));
        }
    }

    private static MethodSummary analyzeMethod(JavaSootMethod method,
                                               SummaryCache cache,
                                               AnalysisConfig config) {
        try {
            Body body = method.getBody();
            StmtGraph<?> cfg = body.getStmtGraph();
            List<String> paramTypeNames = method.getSignature().getParameterTypes()
                    .stream()
                    .map(Type::toString)
                    .map(t -> {
                        int dot = t.lastIndexOf('.');
                        return dot >= 0 ? t.substring(dot + 1) : t;
                    })
                    .toList();

            SideEffectFlowAnalysis analysis = new SideEffectFlowAnalysis(
                    cfg, body, config, method.isStatic(), null, paramTypeNames, cache);
            PointsToGraph exitGraph = analysis.getExitGraph();
            boolean isConstructor = "<init>".equals(method.getName());
            MethodSummary verdict = SideEffectChecker.check(
                    method.getSignature().toString(), exitGraph, isConstructor);
            return new MethodSummary(method.getSignature().toString(), exitGraph,
                    verdict.getResult(), verdict.getReasons(), exitGraph.getReturnTargets());
        } catch (Exception e) {
            return null;
        }
    }

    private static void storeSummary(JavaSootMethod method,
                                     MethodSummary summary,
                                     SummaryCache cache,
                                     Map<String, Set<String>> reverseOverrideGraph) {
        String fullSig = method.getSignature().toString();
        MethodSummary namespaced = summary.namespacedTo(fullSig, fullSig);
        mergeIntoCache(fullSig, namespaced, cache);
        propagateToBases(fullSig, cache, reverseOverrideGraph);
    }

    private static void propagateToBases(String changedSig,
                                         SummaryCache cache,
                                         Map<String, Set<String>> reverseOverrideGraph) {
        Deque<String> work = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        work.add(changedSig);

        while (!work.isEmpty()) {
            String childSig = work.pop();
            if (!seen.add(childSig)) continue;

            MethodSummary childSummary = cache.lookup(childSig);
            if (childSummary == null) continue;

            for (String baseSig : reverseOverrideGraph.getOrDefault(childSig, Set.of())) {
                MethodSummary rebased = new MethodSummary(
                        baseSig,
                        childSummary.getExitGraph().copy(),
                        childSummary.getResult(),
                        childSummary.getReasons(),
                        new LinkedHashSet<>(childSummary.getReturnTargets()));
                mergeIntoCache(baseSig, rebased, cache);
                work.add(baseSig);
            }
        }
    }

    private static void mergeIntoCache(String fullSig, MethodSummary incoming, SummaryCache cache) {
        MethodSummary merged = MethodSummary.union(fullSig, cache.lookup(fullSig), incoming);
        cache.put(fullSig, merged);
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

    private static void assertAllResults(AnalysisFixture fixture, ExpectedResult... expectations) {
        for (ExpectedResult expectation : expectations) {
            assertEquals(
                    expectation.className + "." + expectation.methodName,
                    expectation.expected,
                    fixture.getResult(expectation.className, expectation.methodName));
        }
    }

    private static void assertReasonsContain(AnalysisFixture fixture,
                                             String className,
                                             String methodName,
                                             String... expectedFragments) {
        MethodSummary summary = fixture.getSummary(className, methodName);
        assertEquals(SideEffectResult.SIDE_EFFECTING, summary.getResult());

        List<String> remaining = new ArrayList<>(summary.getReasons());
        assertEquals(className + "." + methodName + " reason count",
                expectedFragments.length, remaining.size());
        for (String fragment : expectedFragments) {
            int matchIndex = -1;
            for (int i = 0; i < remaining.size(); i++) {
                if (remaining.get(i).contains(fragment)) {
                    matchIndex = i;
                    break;
                }
            }
            assertTrue(
                    "Expected reason containing '" + fragment + "' in " + className + "." + methodName
                            + " but found " + summary.getReasons(),
                    matchIndex >= 0);
            remaining.remove(matchIndex);
        }
        assertTrue("Unexpected extra reasons: " + remaining, remaining.isEmpty());
    }

    @Test
    public void testCoreSideEffectExamplesAndPaperExample() throws Exception {
        assertAllResults(core(),
                expect("SideEffectFreeMethods", "add", SideEffectResult.SIDE_EFFECT_FREE),
                expect("SideEffectFreeMethods", "createArray", SideEffectResult.SIDE_EFFECT_FREE),
                expect("SideEffectingMethods", "setX", SideEffectResult.SIDE_EFFECTING),
                expect("SideEffectingMethods", "increment", SideEffectResult.SIDE_EFFECTING),
                expect("SideEffectingMethods", "getAndIncrement", SideEffectResult.SIDE_EFFECTING),
                expect("NewObjectMutation", "createList", SideEffectResult.SIDE_EFFECT_FREE),
                expect("NewObjectMutation", "addToList", SideEffectResult.SIDE_EFFECTING),
                expect("NewObjectMutation", "copyFirst", SideEffectResult.SIDE_EFFECT_FREE),
                expect("StaticFieldEscape", "setShared", SideEffectResult.SIDE_EFFECTING),
                expect("StaticFieldEscape", "createAndEscape", SideEffectResult.SIDE_EFFECTING),
                expect("StaticFieldEscape", "getShared", SideEffectResult.SIDE_EFFECT_FREE),
                expect("Point", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("Cell", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("ListItr", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("List", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("ListItr", "hasNext", SideEffectResult.SIDE_EFFECT_FREE),
                expect("Point", "flip", SideEffectResult.SIDE_EFFECTING),
                expect("ListItr", "next", SideEffectResult.SIDE_EFFECTING),
                expect("List", "add", SideEffectResult.SIDE_EFFECTING),
                expect("List", "iterator", SideEffectResult.SIDE_EFFECT_FREE),
                expect("PaperMain", "sumX", SideEffectResult.SIDE_EFFECT_FREE),
                expect("PaperMain", "flipAll", SideEffectResult.SIDE_EFFECTING));
    }

    @Test
    public void testConditionalSideEffects() throws Exception {
        assertAllResults(core(),
                expect("ConditionalSideEffects", "mayAliasParam", SideEffectResult.SIDE_EFFECT_FREE),
                expect("ConditionalSideEffects", "conditionalMutate", SideEffectResult.SIDE_EFFECTING),
                expect("ConditionalSideEffects", "alwaysFresh", SideEffectResult.SIDE_EFFECT_FREE),
                expect("ConditionalSideEffects", "mayEscapeToStatic", SideEffectResult.SIDE_EFFECTING));
    }

    @Test
    public void testInnerClassAndJdk() throws Exception {
        assertAllResults(core(),
                expect("InnerClassAndJdk$Shelf", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("InnerClassAndJdk$Shelf", "count", SideEffectResult.SIDE_EFFECT_FREE),
                expect("InnerClassAndJdk$Shelf", "put", SideEffectResult.SIDE_EFFECTING),
                expect("InnerClassAndJdk$Shelf", "copyItems", SideEffectResult.SIDE_EFFECT_FREE),
                expect("InnerClassAndJdk$Shelf$ItemView", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("InnerClassAndJdk$Shelf$ItemView", "label", SideEffectResult.SIDE_EFFECT_FREE),
                expect("InnerClassAndJdk$Shelf$ItemView", "setLabel", SideEffectResult.SIDE_EFFECTING),
                expect("InnerClassAndJdk$Container", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("InnerClassAndJdk$Container", "size", SideEffectResult.SIDE_EFFECT_FREE),
                expect("InnerClassAndJdk$Container", "addItem", SideEffectResult.SIDE_EFFECTING),
                expect("InnerClassAndJdk$Container", "getSnapshot", SideEffectResult.SIDE_EFFECT_FREE),
                expect("InnerClassAndJdk$Container", "clear", SideEffectResult.SIDE_EFFECTING),
                expect("InnerClassAndJdk", "summarize", SideEffectResult.SIDE_EFFECT_FREE),
                expect("InnerClassAndJdk", "mutateViaInner", SideEffectResult.SIDE_EFFECTING));
    }

    @Test
    public void testMultipleReasons() throws Exception {
        assertAllResults(core(),
                expect("MultipleReasons", "writeTwoStaticFields", SideEffectResult.SIDE_EFFECTING),
                expect("MultipleReasons", "mutateTwoParams", SideEffectResult.SIDE_EFFECTING),
                expect("MultipleReasons", "staticWriteAndParamMutation", SideEffectResult.SIDE_EFFECTING),
                expect("MultipleReasons", "allThreeViolations", SideEffectResult.SIDE_EFFECTING),
                expect("MultipleReasons", "createBox", SideEffectResult.SIDE_EFFECT_FREE));

        assertReasonsContain(core(), "MultipleReasons", "writeTwoStaticFields",
                "writes to static field cache1",
                "writes to static field cache2",
                "Object parameter escapes to global scope");
        assertReasonsContain(core(), "MultipleReasons", "mutateTwoParams",
                "mutates int[] parameter via field array element",
                "mutates int[] parameter via field array element");
        assertReasonsContain(core(), "MultipleReasons", "staticWriteAndParamMutation",
                "writes to static field cache1",
                "Object parameter escapes to global scope",
                "mutates MultipleReasons$Box parameter via field value");
        assertReasonsContain(core(), "MultipleReasons", "allThreeViolations",
                "writes to static field cache1",
                "writes to static field cache2",
                "Object parameter escapes to global scope",
                "mutates MultipleReasons$Box parameter via field value");
    }

    @Test
    public void testStaticRecursionAndBankExamples() throws Exception {
        assertAllResults(core(),
                expect("StaticFieldEscape2", "incrementCounter", SideEffectResult.SIDE_EFFECTING),
                expect("StaticWrite", "storeToGlobal", SideEffectResult.SIDE_EFFECTING),
                expect("MutualRecursion", "foo", SideEffectResult.SIDE_EFFECT_FREE),
                expect("MutualRecursion", "bar", SideEffectResult.SIDE_EFFECT_FREE),
                expect("BankAccount", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("BankAccount", "deposit", SideEffectResult.SIDE_EFFECTING),
                expect("Wallet", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("Wallet", "addFunds", SideEffectResult.SIDE_EFFECTING),
                expect("reportBankExample2$BankAccount", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("reportBankExample2$Wallet", "swapAccount", SideEffectResult.SIDE_EFFECTING));
    }

    @Test
    public void testMergeEquivalence() throws Exception {
        AnalysisFixture mergedFixture = analyzeFiles(
                MERGE_CONFIG,
                "src/test/resources/testcases/SideEffectFreeMethods.java",
                "src/test/resources/testcases/SideEffectingMethods.java");

        assertAllResults(mergedFixture,
                expect("SideEffectFreeMethods", "add", SideEffectResult.SIDE_EFFECT_FREE),
                expect("SideEffectFreeMethods", "createArray", SideEffectResult.SIDE_EFFECT_FREE),
                expect("SideEffectingMethods", "setX", SideEffectResult.SIDE_EFFECTING),
                expect("SideEffectingMethods", "increment", SideEffectResult.SIDE_EFFECTING),
                expect("SideEffectingMethods", "getAndIncrement", SideEffectResult.SIDE_EFFECTING));
    }

    @Test
    public void testIntraproceduralSideEffectFreeExamples() throws Exception {
        assertAllResults(intraprocedural(),
                expect("Pair", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("SideEffectFreeExamples", "sum", SideEffectResult.SIDE_EFFECT_FREE),
                expect("SideEffectFreeExamples", "maxOfThree", SideEffectResult.SIDE_EFFECT_FREE),
                expect("SideEffectFreeExamples", "absoluteDiff", SideEffectResult.SIDE_EFFECT_FREE),
                expect("SideEffectFreeExamples", "greet", SideEffectResult.SIDE_EFFECT_FREE),
                expect("SideEffectFreeExamples", "getX", SideEffectResult.SIDE_EFFECT_FREE),
                expect("SideEffectFreeExamples", "distanceSquared", SideEffectResult.SIDE_EFFECT_FREE),
                expect("SideEffectFreeExamples", "createPair", SideEffectResult.SIDE_EFFECT_FREE),
                expect("SideEffectFreeExamples", "buildMessage", SideEffectResult.SIDE_EFFECT_FREE),
                expect("SideEffectFreeExamples", "createAndPopulateList", SideEffectResult.SIDE_EFFECT_FREE),
                expect("SideEffectFreeExamples", "localSwap", SideEffectResult.SIDE_EFFECT_FREE),
                expect("SideEffectFreeExamples", "readArrayLength", SideEffectResult.SIDE_EFFECT_FREE),
                expect("SideEffectFreeExamples", "conditionalCreate", SideEffectResult.SIDE_EFFECT_FREE));
    }

    @Test
    public void testIntraproceduralSideEffectingExamples() throws Exception {
        assertAllResults(intraprocedural(),
                expect("Counter", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("Counter", "resetCount", SideEffectResult.SIDE_EFFECTING),
                expect("Wallet", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("Account", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("IntPair", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("SideEffectingExamples", "incrementField", SideEffectResult.SIDE_EFFECTING),
                expect("SideEffectingExamples", "resetArray", SideEffectResult.SIDE_EFFECTING),
                expect("SideEffectingExamples", "swapFields", SideEffectResult.SIDE_EFFECTING),
                expect("SideEffectingExamples", "escapeToStatic", SideEffectResult.SIDE_EFFECTING),
                expect("SideEffectingExamples", "storeCounterGlobally", SideEffectResult.SIDE_EFFECTING),
                expect("SideEffectingExamples", "escapeNested", SideEffectResult.SIDE_EFFECTING),
                expect("SideEffectingExamples", "mutateNested", SideEffectResult.SIDE_EFFECTING));
    }

    @Test
    public void testIntraproceduralEdgeCases() throws Exception {
        assertAllResults(intraprocedural(),
                expect("Point", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("EdgeCases", "createMutateReturn", SideEffectResult.SIDE_EFFECT_FREE),
                expect("EdgeCases", "createChain", SideEffectResult.SIDE_EFFECT_FREE),
                expect("EdgeCases", "readStaticOnly", SideEffectResult.SIDE_EFFECT_FREE),
                expect("EdgeCases", "identityFunction", SideEffectResult.SIDE_EFFECT_FREE),
                expect("EdgeCases", "conditionalMutation", SideEffectResult.SIDE_EFFECTING),
                expect("EdgeCases", "unusedAllocation", SideEffectResult.SIDE_EFFECTING),
                expect("EdgeCases", "castAndReturn", SideEffectResult.SIDE_EFFECT_FREE));
    }

    @Test
    public void testConstructorExamples() throws Exception {
        assertAllResults(intraprocedural(),
                expect("Vec2", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("Vec2", "set", SideEffectResult.SIDE_EFFECTING),
                expect("Vec2", "getX", SideEffectResult.SIDE_EFFECT_FREE),
                expect("Vec2", "negate", SideEffectResult.SIDE_EFFECTING),
                expect("Container", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("Container", "setData", SideEffectResult.SIDE_EFFECTING),
                expect("Container", "getData", SideEffectResult.SIDE_EFFECT_FREE),
                expect("LinkedNode", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("LinkedNode", "setValue", SideEffectResult.SIDE_EFFECTING),
                expect("LinkedNode", "getValue", SideEffectResult.SIDE_EFFECT_FREE));
    }

    @Test
    public void testComplexSideEffectFreeExample() throws Exception {
        assertAllResults(intraprocedural(),
                expect("DataNode", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("Registry", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("ComplexSideEffectFreeExample", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("ComplexSideEffectFreeExample", "complexSideEffectFree", SideEffectResult.SIDE_EFFECT_FREE),
                expect("ComplexSideEffectFreeExample", "instanceSideEffectFree", SideEffectResult.SIDE_EFFECT_FREE));
    }

    @Test
    public void testInterproceduralExamples() throws Exception {
        assertAllResults(interprocedural(),
                expect("IPWrapper", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("IPReader", "getValue", SideEffectResult.SIDE_EFFECT_FREE),
                expect("IPReader", "readViaHelper", SideEffectResult.SIDE_EFFECT_FREE),
                expect("IPFactory", "create", SideEffectResult.SIDE_EFFECT_FREE),
                expect("IPConsumer", "makeAndRead", SideEffectResult.SIDE_EFFECT_FREE),
                expect("IPMutator", "modify", SideEffectResult.SIDE_EFFECTING),
                expect("IPSideEffectingCaller", "doModify", SideEffectResult.SIDE_EFFECTING),
                expect("IPNode", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("IPIter", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("IPIter", "hasNext", SideEffectResult.SIDE_EFFECT_FREE),
                expect("IPIter", "next", SideEffectResult.SIDE_EFFECTING),
                expect("IPLinkedList", "iterator", SideEffectResult.SIDE_EFFECT_FREE),
                expect("IPSum", "sum", SideEffectResult.SIDE_EFFECT_FREE));
    }

    @Test
    public void testInterfileExamples() throws Exception {
        assertAllResults(interfile(),
                expect("Container", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("Container", "getData", SideEffectResult.SIDE_EFFECT_FREE),
                expect("Container", "copy", SideEffectResult.SIDE_EFFECT_FREE),
                expect("Container", "isEmpty", SideEffectResult.SIDE_EFFECT_FREE),
                expect("Container", "setData", SideEffectResult.SIDE_EFFECTING),
                expect("Container", "increment", SideEffectResult.SIDE_EFFECTING),
                expect("Pair", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("Pair", "swap", SideEffectResult.SIDE_EFFECT_FREE),
                expect("Pair", "setFirst", SideEffectResult.SIDE_EFFECTING),
                expect("Reader", "readFromContainer", SideEffectResult.SIDE_EFFECT_FREE),
                expect("Reader", "copyContainer", SideEffectResult.SIDE_EFFECT_FREE),
                expect("Reader", "checkEmpty", SideEffectResult.SIDE_EFFECT_FREE),
                expect("Reader", "swapPair", SideEffectResult.SIDE_EFFECT_FREE),
                expect("Mutator", "updateContainer", SideEffectResult.SIDE_EFFECTING),
                expect("Mutator", "growContainer", SideEffectResult.SIDE_EFFECTING),
                expect("Mutator", "replaceFirst", SideEffectResult.SIDE_EFFECTING),
                expect("Holder", "swapContainer", SideEffectResult.SIDE_EFFECTING),
                expect("Holder", "snapshotAndReplace", SideEffectResult.SIDE_EFFECTING),
                expect("Holder", "readThroughHolder", SideEffectResult.SIDE_EFFECT_FREE),
                expect("WrappedPair", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("WrappedPair", "getPair", SideEffectResult.SIDE_EFFECT_FREE),
                expect("WrappedPair", "getSwapped", SideEffectResult.SIDE_EFFECT_FREE));
    }

    @Test
    public void testOverrideExamples() throws Exception {
        assertAllResults(overrideFixture(),
                expect("OverrideBase", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("OverrideBase", "process", SideEffectResult.SIDE_EFFECTING),
                expect("OverrideBase", "caller", SideEffectResult.SIDE_EFFECTING),
                expect("OverrideDerived", "<init>", SideEffectResult.SIDE_EFFECT_FREE),
                expect("OverrideDerived", "process", SideEffectResult.SIDE_EFFECTING));
    }
}
