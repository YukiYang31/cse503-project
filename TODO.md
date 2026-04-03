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
