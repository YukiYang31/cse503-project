# Implementation Pipeline

This note explains the current end-to-end workflow of the side-effect analysis implementation.
It follows the code path from input source files to the final `MethodSummary` verdict for each method.

## 1. Entry Point and Setup

The CLI entry point is `src/main/java/edu/uw/cse/sideeffect/Main.java`.

The workflow starts by:

1. parsing CLI flags into an `AnalysisConfig`
2. deciding whether the input is ordinary user source or JDK source
3. either compiling the source files with `JavaCompiler` or loading JDK classes from `jrt:/`
4. constructing a `SideEffectAnalysisRunner`
5. calling `runner.run()`

For normal user code, `JavaCompiler` compiles `.java` files to a temporary directory of `.class` files.
For JDK source files, compilation is skipped and the runner loads the already compiled runtime classes instead.

## 2. Runner-Level Workflow

The main orchestration logic lives in `src/main/java/edu/uw/cse/sideeffect/SideEffectAnalysisRunner.java`.

At a high level, `run()` does the following:

1. creates a SootUp `JavaView` over either the compiled class directory or the JDK runtime image
2. loads previously persisted library summaries from disk through `LibrarySummaryCache`
3. resolves the set of classes that should be analyzed
4. builds a raw call graph and an override graph, then merges them into a dependency graph
5. computes a bottom-up analysis order from that dependency graph using SCC batches
6. pre-populates an in-memory `SummaryCache`
7. analyzes methods batch by batch
8. stores and propagates summaries for dynamic dispatch
9. prints final results

The important design point is that method analysis is not done in arbitrary order.
The runner tries to analyze callees before callers so that call sites can reuse already computed callee summaries.

## 3. Graph Construction and Batch Order

Graph construction is handled by `src/main/java/edu/uw/cse/sideeffect/analysis/CallGraphBuilder.java`.

This phase has two jobs:

1. discover reachable methods and classes, including some JDK/library code
2. compute an analysis order where callees appear before callers when possible

### 3.1 BFS Class Discovery

Starting from the user classes, the builder scans method bodies for invocations.
For each call, it tries to resolve targets using SootUp.

Some calls are intentionally not expanded:

- methods in `SafeMethods`
- methods already present in the disk-backed library cache
- classes in forbidden package prefixes

For virtual and interface calls, it also performs bounded target resolution so likely runtime implementations can be discovered.
This is especially important for library code and dynamic dispatch.

### 3.2 Override Graph

The builder constructs an override graph from declared/base methods to direct overriding methods.
This graph is used in two ways:

- to enrich the analysis ordering
- to propagate implementation summaries upward to base signatures

That means a declared method such as an interface method can end up with a summary equal to the union of its concrete implementations.

### 3.3 Dependency Graph and SCC Batches

After the raw call graph and override graph are built, the implementation merges them into a single dependency graph.
This dependency graph is the graph used for analysis order.
Tarjan SCC runs on this merged dependency graph, not on the raw call graph alone and not on the override graph alone.

- a singleton batch means a non-recursive method that can be analyzed once
- a multi-method batch means mutual recursion, so the methods are analyzed repeatedly until summaries stabilize

This produces the bottom-up worklist used by the runner.

## 4. Summary Cache and Dynamic Dispatch

Interprocedural analysis is centered around `src/main/java/edu/uw/cse/sideeffect/analysis/SummaryCache.java` and `src/main/java/edu/uw/cse/sideeffect/analysis/MethodSummary.java`.

A `MethodSummary` contains:

- the method signature
- the exit `PointsToGraph`
- the final side-effect result
- explanatory reasons
- the set of return targets

The cache is keyed by exact full method signature.

When a method is analyzed, the runner does not simply store the raw summary directly.
Instead it:

1. namespaces the summary so its inside/load nodes are stable and contributor-specific
2. merges it into the cache entry for that exact signature
3. propagates the merged result upward through the reverse override graph

This is how dynamic dispatch is handled in the current implementation.
The transfer function does exact signature lookup only.
The union over implementations has already been baked into the cached summary for the declared/base method.

## 4.1 JDK Cache Update Policy

There is an explicit distinction between:

- analyzing JDK input classes in JRT mode
- analyzing ordinary user code

This distinction controls whether the disk-backed JDK cache in `jdk-cache/` may be updated.

### Detecting JDK Input

In `Main.java`, the tool calls `detectJdkClasses(sourceFiles)`.

- if the input paths match JDK source layout, the run is treated as JDK-mode analysis
- the runner is then created with `SideEffectAnalysisRunner.forJrt(...)`
- in this mode, the runner does not use a compiled user `classDir`

Otherwise:

- the input is treated as normal user code
- the files are compiled first
- the runner is created with a real `classDir`

### Persistence Gate

The actual write policy is enforced in `SideEffectAnalysisRunner`.

When a summary is stored, the runner eventually calls:

- `mergeIntoCache(fullSig, incoming, cache, persist)`

That method always updates the in-memory `SummaryCache`, but it writes to `LibrarySummaryCache` only if `persist` is true.

The `persist` flag comes from:

- `shouldPersistLibrarySummary(JavaSootMethod method)`
- `shouldPersistLibrarySummary(String fullSig)`

These methods return true only when both conditions hold:

1. `classDir == null`
2. the method signature belongs to a library/JDK class such as `java.*`, `javax.*`, `sun.*`, `com.sun.*`, or `jdk.*`

So the rule is:

- if the run is in JRT/JDK mode, JDK summaries may be written back to `jdk-cache/`
- if the run is analyzing ordinary user code, the existing JDK cache may be loaded and reused, but it is not updated on disk

### Practical Effect

This prevents user-specific override information from contaminating the shared JDK cache.

During a normal user-code run:

- cached JDK summaries are loaded from disk at startup
- those summaries may be merged with user-relevant information in memory for the current run
- but no updated JDK summary is written back to disk

During an explicit JDK/JRT run:

- the tool is allowed to refine and persist library summaries
- those persisted summaries become available to future runs

## 5. Per-Method Analysis

Once the runner selects a method, it calls `analyzeMethod(...)`.

That method:

1. gets the method body and Jimple CFG from SootUp
2. creates a `SideEffectFlowAnalysis`
3. runs forward dataflow to compute the exit `PointsToGraph`
4. passes the exit graph to `SideEffectChecker`
5. packages the result into a `MethodSummary`

If debug mode is enabled, the runner also records HTML traces, source snippets, bytecode, Jimple, and intermediate graphs.

## 6. Core Data Structure: PointsToGraph

The abstract program state is `src/main/java/edu/uw/cse/sideeffect/graph/PointsToGraph.java`.

It stores:

- `L`: local variable to abstract node points-to sets
- `I`: inside edges, representing references created by writes
- `O`: outside edges, representing references read from pre-existing heap state
- `E`: globally escaped nodes
- `W`: mutated `(node, field)` pairs
- return targets, for interprocedural summary reuse

It also stores counters for inside nodes and load nodes so fixed-point iteration produces deterministic node identities.

At CFG join points, graphs are merged by union.
If node merging is enabled, `NodeMerger` may collapse equivalent nodes after joins and at method exit.

## 7. Intraprocedural Dataflow

`src/main/java/edu/uw/cse/sideeffect/analysis/SideEffectFlowAnalysis.java` extends SootUp's `ForwardFlowAnalysis`.

The flow analysis:

1. starts from an empty `PointsToGraph`
2. copies input state to output state at each statement
3. applies statement semantics through `TransferFunctions`
4. unions incoming states at merge points
5. builds the final exit graph by merging all tail states

This means loops and branches are handled through ordinary fixed-point iteration.

## 8. Statement Semantics in TransferFunctions

The main abstract semantics live in `src/main/java/edu/uw/cse/sideeffect/analysis/TransferFunctions.java`.

### 8.1 Identity Statements

`@this` and `@parameter` values are mapped to `ParameterNode`s.

- for instance methods, `P0` is `this`
- later parameters are shifted by one
- for static methods, parameter numbering starts at zero

These parameter nodes are the seeds of the prestate heap.

### 8.2 Allocation and Local Flow

- `x = new T` creates a fresh `InsideNode`
- `x = new T[]` creates a fresh array `InsideNode`
- `x = y` and casts perform strong updates on local points-to sets

Fresh inside nodes represent objects created within the current method execution.

### 8.3 Field Loads

For `x = y.f`, the transfer function:

1. collects known inside-edge targets already stored for field `f`
2. collects any existing outside-edge targets
3. if `y` may point to a prestate-reachable node, creates a fresh `LoadNode` and an outside edge

Prestate-reachable nodes are:

- `ParameterNode`
- `LoadNode`
- `GlobalNode`

The idea is that reading from a pre-existing object may reveal objects that existed before method entry, even if the analysis did not previously know them.

### 8.4 Field Stores

For `x.f = y`, the transfer function performs a weak update:

- it adds inside edges from every base target of `x` to every target of `y`
- it records `(baseNode, field)` in the mutated set `W`

Weak updates are used because the analysis is flow-sensitive but still may have alias sets rather than single concrete objects.

### 8.5 Static Fields

Static fields are modeled using a distinguished `GlobalNode`.

- loading a static field creates or reuses outside edges from `GlobalNode`
- storing into a static field marks the RHS nodes as globally escaped and records a mutation on `GlobalNode`

This is why static writes are always treated as side effects later.

### 8.6 Arrays

Arrays are handled with a simplified model.

- array loads may create load nodes for prestate arrays
- array stores record mutation on the array object, using `null` as the synthetic field marker

The implementation explicitly treats array handling as a simplification rather than a precise element-sensitive model.

### 8.7 Method Calls

Method calls follow a tiered strategy.

#### Safe methods

If `SafeMethods.isSafe(...)` says the callee is harmless, the analysis assumes no side effects.
If the method returns a reference, the return value is modeled as a fresh inside node.

#### Cached summaries

If a matching summary exists in `SummaryCache`, the analysis applies that summary interprocedurally through `GraphInstantiator`.

#### Unknown methods

If no summary is available, the analysis falls back conservatively:

- all reference arguments are marked globally escaped
- the receiver is also marked escaped for instance calls
- the return value, if any, is set to `GlobalNode`

This makes unknown calls pessimistic, which is intentional for soundness.

### 8.8 Return Statements

When a method returns a reference local, its current points-to targets are added to the graph's `returnTargets`.
These are later stored in the `MethodSummary` and used when callers instantiate the summary.

## 9. Interprocedural Summary Instantiation

When a call site has a cached summary, `TransferFunctions` delegates to `src/main/java/edu/uw/cse/sideeffect/analysis/GraphInstantiator.java`.

This class imports the callee summary into the caller in several steps.

### 9.1 Node Remapping

First, the callee's inside and load nodes are renamed to fresh caller-side nodes so there are no ID collisions.
`ParameterNode`s are not simply copied, because they must be related to actual caller arguments.

### 9.2 Computing mu and mu'

The instantiator computes a least fixed-point mapping `mu` from callee-side nodes to caller-side nodes.

It starts by mapping formal parameters to the caller's actual argument points-to sets.
Then it repeatedly propagates information using callee outside edges and aliasing relationships.

After convergence it builds `mu'`, which extends `mu` by also including each non-parameter node itself.

### 9.3 Combining Graphs

Using `mu'`, the instantiator:

- projects callee inside edges into the caller
- projects callee outside edges into the caller, with checks that preserve graph invariants
- updates the caller's return variable
- propagates escaped nodes

### 9.4 Simplification

After combination, it removes captured load nodes that are no longer reachable from any live root in the caller.
It also removes some outside edges from dead nodes.

### 9.5 Mutated Fields

Finally, it projects the callee's mutated-field set back into the caller graph.
Mutations that map only to fresh inside nodes are not re-recorded as effects on caller-visible prestate.

## 10. Final Side-Effect Verdict

The final verdict is computed by `src/main/java/edu/uw/cse/sideeffect/analysis/SideEffectChecker.java`.

It checks the exit graph in three main stages.

### 10.1 Graph Invariants

Before anything else, the checker validates graph invariants.
For example, outside edges should not violate the inside/outside discipline.
If an invariant is broken, the method is classified as `GRAPH_VIOLATION`.

### 10.2 Set A: Prestate Nodes

The checker computes the set of prestate nodes by:

1. starting from all `ParameterNode`s
2. following outside edges outward

These nodes represent objects that existed before the method call.

### 10.3 Set B: Globally Escaped Closure

It computes the globally escaped closure by:

1. starting from the explicit escaped set `E`
2. also including `GlobalNode`
3. traversing all edges

These nodes may be accessible by the rest of the program.

### 10.4 Set W: Mutated Fields

The checker reads the graph's mutated-field set `W`.

It then concludes:

- if `W` contains a mutation on `GlobalNode`, the method is side-effecting because it wrote a static field
- if a prestate node is in the global escaped closure, the method is side-effecting
- if a prestate node appears in `W`, the method is side-effecting

There is one special exception:

- constructors are allowed to mutate `P0` (`this`) directly

If none of those bad cases occurs, the method is classified as `SIDE_EFFECT_FREE`.

## 11. Recursive Methods

If the batch contains multiple methods in one SCC, the runner re-analyzes the methods until the summaries stop changing.

This allows mutually recursive methods to converge toward a stable interprocedural result.
The test harness uses a bounded number of iterations; the main runner uses "until no change" behavior for SCCs.

## 12. Test Workflow

The integration test in `src/test/java/edu/uw/cse/sideeffect/SideEffectAnalysisTest.java` mirrors the same pipeline in a reduced form.

It:

1. compiles test resource files
2. loads them through SootUp
3. builds bottom-up batches
4. analyzes each method with a shared summary cache
5. stores and propagates summaries
6. checks the resulting verdicts with JUnit assertions

So the test is not just checking isolated helpers.
It is exercising the same overall analysis structure used by the main implementation.

## 13. Current High-Level Picture

In one sentence, the implementation works like this:

source files are compiled or loaded, converted to Jimple, ordered bottom-up using a call graph plus override graph, analyzed by forward points-to and escape-style dataflow, composed interprocedurally through cached method summaries, and finally classified as side-effect-free or side-effecting based on whether any prestate object was mutated or globally exposed.
