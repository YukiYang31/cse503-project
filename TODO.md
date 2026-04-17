# TODO

## TODOs for Agent

1. Reduce the size of persisted JDK summaries in `jdk-cache/`.
   - Start from the current persistence path:
     - `SideEffectAnalysisRunner.storeSummary(...)`
     - `SideEffectAnalysisRunner.mergeIntoCache(...)`
     - `LibrarySummaryCache.put(...)`
     - `MethodSummarySerializer.serialize(...)`
   - Measure what makes the JSON large:
     - number of `InsideNode`s
     - number of `LoadNode`s
     - inside/outside edge count
     - escaped-set size
     - mutated-field count
   - Check whether the blow-up is mainly caused by:
     - repeated `MethodSummary.union(...)` during override propagation
     - `GraphInstantiator.instantiate(...)` introducing fresh `InsideNode`s / `LoadNode`s
     - serialization storing too much graph detail that is not needed for later instantiation
   - Revisit the graph simplification strategy with the current code in mind:
     - the optional 2011-style merge is currently `NodeMerger.enforceUniqueness(...)`
     - confirm whether `--merge` should also be applied before persisting JDK summaries
     - decide whether the merge should happen during analysis, before `LibrarySummaryCache.put(...)`, or in a separate cache-compaction pass
   - Re-evaluate whether `InsideNode`s are truly required in persisted summaries:
     - `TransferFunctions` currently creates them for allocations and fresh returns
     - `GraphInstantiator` treats them specially during remapping and outside-edge handling
     - if we remove or collapse them, verify that `SideEffectChecker` still gives the same verdicts on representative JDK methods
   - If removing `InsideNode`s entirely is too aggressive, try a narrower experiment first:
     - keep them during analysis
     - canonicalize or collapse them only in the final saved summary
     - compare verdicts and cache size before/after

2. Make dynamic dispatch handling explicit and testable.
   - Current behavior is mostly "pre-union in the cache":
     - `CallGraphBuilder` builds `overrideGraph` and adds override edges to `dependencyGraph`
     - `SideEffectAnalysisRunner.propagateToBases(...)` pushes each implementation summary upward to declared/base signatures
     - `TransferFunctions.handleInvoke(...)` then does an exact lookup on the declared signature and expects the base signature to already contain the union
   - Create small examples that exercise the current implementation:
     - existing override example in `src/test/resources/interfile/OverrideBase.java` and `OverrideDerived.java`
     - add a dedicated interface-dispatch example, since interface calls are one of the main cases we care about
     - add an exact-dispatch example such as `super.foo()` / constructor / private method call, where unioning all overrides would be unnecessary
   - Compare two implementation strategies:
     - save-time union:
       - keep `propagateToBases(...)` / `MethodSummary.union(...)` as the main mechanism
       - faster at call sites, but base summaries may become larger and slightly less precise
     - call-site union:
       - keep per-implementation summaries separate in the cache
       - at `TransferFunctions.handleInvoke(...)`, use dispatch information plus `overrideGraph` to union only the implementations reachable for that call
       - this may preserve precision for exact calls
   - Clarify what "more specific call" means in code:
     - `JSpecialInvokeExpr` (`super`, private, constructors) should usually not need override union
     - `JVirtualInvokeExpr` and `JInterfaceInvokeExpr` are the main cases that may need multiple targets
   - Decide whether the current call graph construction and the summary lookup policy agree:
     - `CallGraphBuilder` already adds override-based dependencies for ordering
     - `TransferFunctions` currently does not consult that graph at call time
     - either keep the current design and document it clearly, or move part of the dispatch union logic into `TransferFunctions`

3. Address the four code-vs-paper findings from the whole-codebase review.

   3.1 Critical: dynamic dispatch misses transitive overrides in inheritance chains.
   - Problem summary:
     - The README states that transitive chains like `C extends B extends A` are captured naturally.
     - Current override graph construction links only direct parent relationships.
     - In a chain where `C.m()` overrides through `B` and call sites are typed as `A`, the summary for `A.m()` may miss `C.m()`.
   - Evidence in code/docs:
     - Claim in docs: `README.md` section on override detection (transitive chains are captured).
     - Implementation: `CallGraphBuilder.buildOverrideGraph(...)` checks only `immediateParentsOf(cls)` and direct `parent.getMethod(subSig)` matches.
   - Reproduced behavior:
     - Local repro case:
       - `C extends B extendsA`; `A.m()` is empty, `C.m()` mutates `this`, `A.caller(A a){ a.m(); }`.
       - Tool output currently reports `A.caller(A)` as `SIDE_EFFECT_FREE`.
     - Expected paper-aligned behavior:
       - For virtual dispatch with multiple possible callees, effects should conservatively include all applicable targets.
   - Why this matters:
     - This is an unsoundness risk: missed side effects at call sites with base-typed receivers.
   - Implementation tasks:
     - Extend override graph construction to include transitive override relationships across full superclass/interface chains.
     - Re-check dependency-graph augmentation so callers of base signatures depend on all concrete overriding implementations.
     - Add regression tests for:
       - `A <- B <- C` transitive override chain
       - interface inheritance chain with deeper implementing classes
       - mixed class/interface hierarchies.
     - Update README/implementation docs after behavior is fixed and validated.

   3.2 Critical: array load/store modeling can miss side effects on prestate objects.
   - Problem summary:
     - Current array handling is weaker than the field rules used by the paper-style abstraction.
     - Array load creates load nodes but does not create corresponding outside edges from array base nodes.
     - Array store records mutation on the array object but does not add inside edges from array to stored elements.
   - Evidence in code:
     - `TransferFunctions.handleArrayLoad(...)` creates `LoadNode`s without `addOutsideEdge(...)`.
     - `TransferFunctions.handleArrayStore(...)` records `recordMutation(baseNode, null)` but does not add edges to RHS nodes.
     - `SideEffectChecker.computePrestateNodes(...)` identifies prestate reachability by traversing outside edges from parameter nodes.
   - Reproduced behavior:
     - Local repro case:
       - `mutateElem(Box[] arr) { Box b = arr[0]; b.v = 1; }`
       - Tool currently reports `SIDE_EFFECT_FREE`.
     - Expected:
       - Should be `SIDE_EFFECTING` because `arr[0]` can denote a prestate object.
   - Why this matters:
     - This is a direct purity/side-effect false negative on common Java code patterns.
   - Implementation tasks:
     - Model `x = arr[i]` consistently with field-load semantics:
       - include existing inside/outside targets for synthetic array-element field
       - create outside edge from prestate-reachable array nodes when needed.
     - Model `arr[i] = y` consistently with weak field-store semantics:
       - add inside edges from array node to RHS targets via synthetic array-element field
       - keep mutation recording on the array node.
     - Ensure `SideEffectChecker` can see prestate reachability through this representation.
     - Add regression tests:
       - read element then mutate element object
       - write fresh object into parameter array (should be safe if no prestate mutation)
       - mutate nested arrays/lists where applicable.

   3.3 High: SCC fixed-point stop condition is based only on verdict/reasons, not full summary stability.
   - Problem summary:
     - SCC iteration currently treats summary as changed only if verdict or reason list changes.
     - Graph and return-target changes that do not alter verdict text are ignored for convergence.
   - Evidence in code/docs:
     - `SideEffectAnalysisRunner.summaryChanged(...)` compares only `getResult()` and `getReasons()`.
     - The pseudocode/doc framing expects SCC iteration to continue until summary state stabilizes.
   - Why this matters:
     - Interprocedural precision in recursive SCCs depends on stable graph facts, not just stable verdict labels.
     - Early stop can under-propagate effects/return facts and affect downstream callers.
   - Implementation tasks:
     - Redefine SCC change detection to compare full summary semantics:
       - exit graph (I/O/L/E/W + return targets)
       - verdict + reasons as a subset of this check.
     - Consider using `MethodSummary.union(...)` output equality or explicit structural comparator.
     - Add recursive regression tests where verdict is unchanged across iterations but graph facts continue to evolve.
     - Document SCC convergence criterion explicitly in `implementation-pipeline.md`.

   3.4 Medium: persisted summary deserialization drops field signatures, reducing interprocedural precision.
   - Problem summary:
     - Serialized JSON stores field strings, but deserialization currently reconstructs edges/mutations with `field = null`.
     - Instantiation logic is field-sensitive and depends on label matching (`f`) in mu constraints.
   - Evidence in code:
     - `MethodSummarySerializer.deserializeGraph(...)` comment and implementation currently map all fields to `null`.
     - `GraphInstantiator.instantiate(...)` constraint logic compares field labels (`Objects.equals(f, f2)`).
   - Why this matters:
     - Cached library summaries can lose field precision and degrade accuracy when instantiated into caller graphs.
     - Behavior can drift between in-memory freshly analyzed summaries and disk-loaded summaries.
   - Implementation tasks:
     - Restore field signatures during deserialization:
       - parse field strings back into `FieldSignature` using identifier factory context
       - if full reconstruction is impossible for some entries, track and report fallback explicitly.
     - Add cache round-trip tests:
       - analyze -> serialize -> deserialize -> instantiate
       - verify same verdict and key graph properties as non-cached path.
     - Revisit serializer format if needed to ensure robust signature reconstruction across runs.

## TODOs for Human

1. Double-check that the two execution modes are implemented correctly.
   - User mode:
     - this is the normal compiled-source path, where `SideEffectAnalysisRunner` has a non-null `classDir`
     - cached library summaries may be loaded through `preloadReachableLibrarySummaries(...)`
     - user methods should still analyze their own bodies even if a base signature already has a propagated summary
     - no writes to `jdk-cache/` should happen in this mode
     - verify `shouldSkipAnalysisBecauseCached(...)` and `shouldPersistLibrarySummary(...)` match this intended behavior
   - Build-JDK-cache mode:
     - this is JRT mode, entered through `SideEffectAnalysisRunner.forJrt(...)` when `Main.detectJdkClasses(...)` recognizes JDK sources
     - writes to `jdk-cache/` are expected here
     - `propagateToBases(...)` is allowed to update existing cached summaries for declared/base JDK methods
   - For both modes, verify that call-graph exploration does not keep expanding once an existing cached summary is enough:
     - `CallGraphBuilder.computeBottomUpOrder(...)`
     - `LibrarySummaryCache.contains(...)`
     - `reachableCachedLibraryMethods`
   - Check that the override graph and the call graph stop for already-cached library summaries in user mode, while still allowing cache updates in JDK-cache-building mode when needed.

2. ./gradlew test is updated to include more test cases. This command is used for agent everytime it modifies the code. An agent cannot return a code edit if it does not pass ./gradlew test. We need to make sure that current ./gradlew test does include full test cases and that all intended behaviors are correct. 
