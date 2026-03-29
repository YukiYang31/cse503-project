# AGENT.md

This file is a project memory note for future coding agents.
It records the current architecture, terminology, and implementation details that were confirmed while working in this repository.

## Project Purpose

This project is a Java static analysis tool that determines whether a method is side-effect-free.

The working definition is:

- allowed: allocate new objects and mutate those newly created objects
- forbidden: mutate parameters
- forbidden: mutate objects reachable from parameters
- forbidden: write static fields

Constructors are special-cased:

- direct writes to `this` are allowed
- writes through `this` to older reachable objects are still side effects

The implementation is based on:

- Salcianu & Rinard (2005)
- Madhavan et al. (2011)

The code uses:

- SootUp
- Jimple IR
- forward dataflow
- summary-based interprocedural analysis

## Top-Level Execution Modes

There are two distinct execution modes.

### 1. User code mode

- Input is normal `.java` source
- Files are compiled with `JavaCompiler`
- The runner is constructed with a real `classDir`

### 2. JRT / JDK mode

- Input paths match JDK source layout under `jdk/src/...`
- Compilation is skipped
- Classes are loaded from the running JDK module image (`jrt:/`)
- The runner is created with `SideEffectAnalysisRunner.forJrt(...)`
- In this mode, `classDir == null`

This `classDir == null` fact is important because it is the main signal used to decide whether JDK cache updates are allowed.

## How To Run

Use the Gradle wrapper from the repository root.

### Build

```bash
./gradlew build
```

### Analyze user Java files

```bash
./gradlew run --args="MyFile.java"
./gradlew run --args="MyFile.java --method myMethod"
./gradlew run --args="MyFile.java --show-graph --merge"
./gradlew run --args="MyFile.java --debug"
./gradlew run --args="MyFile.java --timing"
```

### Analyze JDK source files

```bash
./gradlew run --args="jdk/src/java.base/share/classes/java/lang/String.java"
./gradlew run --args="jdk/src/java.base/share/classes/java/util/ArrayList.java --method add"
```

### Run tests

```bash
./gradlew test
```

### Focused test

```bash
./gradlew test --tests edu.uw.cse.sideeffect.SideEffectAnalysisTest
```

## Edit Discipline

After every code edit, run:

```bash
./gradlew test
```

Do not consider an implementation change complete until `./gradlew test` passes, unless the user explicitly asks not to run tests or the environment prevents it.

For complicated changes, make a concrete plan before proceeding with implementation.
The plan should identify the affected components, the intended code changes, and how the result will be verified.

## Current Core Terminology

Use these names consistently.

- `rawCallGraph`: direct invocation edges only
- `overrideGraph`: base/declared method -> direct overriding methods
- `dependencyGraph`: merged graph used for analysis order

The dependency graph is:

- raw call graph
- plus override-derived edges

Tarjan SCC runs on the merged `dependencyGraph`, not on the raw call graph alone.

This terminology was explicitly clarified and updated in the code and docs.

## High-Level Pipeline

1. parse CLI options in `Main.java`
2. detect JDK-vs-user input
3. compile user code or load JRT classes
4. create `SideEffectAnalysisRunner`
5. load disk-backed JDK summaries into memory
6. build `rawCallGraph`, `overrideGraph`, and merged `dependencyGraph`
7. run Tarjan SCC on the `dependencyGraph`
8. analyze methods bottom-up by SCC batch
9. store summaries in `SummaryCache`
10. propagate summaries upward through overrides
11. compute final side-effect verdicts from exit graphs
12. optionally emit debug HTML, DOT graphs, and timing data

## Main Classes and Responsibilities

### `Main.java`

- CLI entrypoint
- decides user-code mode vs JRT mode
- calls `detectJdkClasses(...)`
- creates the runner

### `SideEffectAnalysisRunner.java`

- central orchestrator
- creates the SootUp view
- loads the on-disk library cache index and lazily fetches reachable summaries
- runs call-graph construction
- drives bottom-up analysis
- stores summaries
- controls persistence to `jdk-cache/`

### `CallGraphBuilder.java`

- performs bounded method-based BFS discovery into reachable uncached library/JDK methods
- builds the raw call graph
- builds the override graph
- merges them into the dependency graph
- runs Tarjan SCC on the dependency graph

### `PointsToGraph.java`

Represents the abstract graph state:

- `L`: local variable -> abstract node mapping
- `I`: inside edges
- `O`: outside edges
- `E`: globally escaped nodes
- `W`: mutated `(node, field)` pairs
- return targets

Also stores deterministic counters for inside/load node creation.

### `SideEffectFlowAnalysis.java`

- integrates with SootUp `ForwardFlowAnalysis`
- uses `PointsToGraph` as the dataflow fact
- merges incoming states by union
- computes the exit graph from tail nodes

### `TransferFunctions.java`

- statement-level semantics over Jimple
- handles parameter binding, allocation, loads, stores, arrays, calls, returns
- uses summary lookup for interprocedural calls
- falls back conservatively for unknown methods

### `GraphInstantiator.java`

- applies a callee summary at a call site
- remaps callee nodes into caller space
- computes `mu` and `mu'`
- projects callee edges / escapes / return targets / mutations into caller state

### `SideEffectChecker.java`

- computes the final method verdict from the exit graph
- checks graph invariants first
- computes prestate set `A`
- computes globally escaped closure `B`
- reads mutation set `W`

### `MethodSummary.java`

Contains:

- method signature
- exit graph
- side-effect result
- reasons
- return targets

Also supports:

- namespacing contributor nodes
- unioning summaries

### `SummaryCache.java`

- exact full-signature lookup only
- stores summaries used during interprocedural analysis

### `LibrarySummaryCache.java`

- disk-backed cache under `jdk-cache/`
- indexes cached summaries at startup and loads summary bodies lazily
- written only when persistence is explicitly allowed

## Current Dispatch / Summary Design

Important: the current system does exact summary lookup at call sites.

It does **not** union overrides at the call site anymore.

Instead:

1. concrete implementations are analyzed
2. their summaries are namespaced
3. their summaries are merged into cache entries
4. those merged summaries are propagated upward to base / declared methods

So by the time a call site asks for something like a declared interface method, that declared signature is expected to already hold the union of its implementations.

This is the current dynamic-dispatch design.

See also:

- `report-related/dymanic-dispatch.md`

## Important Graph Semantics

### Node kinds

- `InsideNode`: fresh object allocated in the analyzed execution
- `ParameterNode`: prestate object from `this` or a parameter
- `LoadNode`: object loaded from pre-existing heap state
- `GlobalNode`: static/global namespace

### Edge kinds

- inside edges: created by writes
- outside edges: represent heap structure learned from reading pre-existing state

### Side-effect intuition

If the method mutates something that existed before the call, or causes prestate data to escape globally, it is side-effecting.

## Key Transfer-Function Behaviors

### Identity

- `@this` -> `ParameterNode(0)` for instance methods
- parameters become `ParameterNode`s

### Allocation

- `new` creates a fresh `InsideNode`
- new arrays also become fresh `InsideNode`s

### Field load

If a field is read from a prestate-reachable node, the analysis may create a fresh `LoadNode` and an outside edge.

Prestate-reachable means:

- `ParameterNode`
- `LoadNode`
- `GlobalNode`

### Field store

- weak update
- add inside edges
- record mutation in `W`

### Static field store

- mark RHS nodes globally escaped
- record mutation on `GlobalNode`

### Unknown method call fallback

If no safe-method rule and no cached summary exists:

- mark reference arguments as escaped
- mark receiver as escaped for instance calls
- set reference return value to `GlobalNode`

This is intentionally conservative.

## SideEffectChecker Logic

The checker does:

1. validate graph invariants
2. compute prestate set `A`
3. compute globally escaped closure `B`
4. inspect mutation set `W`

The method is side-effecting if:

- `W` contains a mutation on `GlobalNode`
- a prestate node is in `B`
- a prestate node appears in `W`

Constructor exception:

- direct mutation of `P0` (`this`) is allowed

## JDK Cache Update Policy

This was explicitly clarified and should not be accidentally broken.

### Allowed

Updating `jdk-cache/` is allowed only when:

1. the run is in JRT / JDK mode
2. `classDir == null`
3. the signature belongs to a library class such as:
   - `java.*`
   - `javax.*`
   - `sun.*`
   - `com.sun.*`
   - `jdk.*`

This is enforced in `SideEffectAnalysisRunner.shouldPersistLibrarySummary(...)`.

### Not allowed

When analyzing ordinary user code:

- the existing JDK cache index is loaded and reused
- in-memory cache entries may be refined for the current run
- but those refinements are **not** written back to disk

This prevents user-specific information from contaminating the shared JDK cache.

## Recent Clarified Implementation Details

### Custom `InsideNode` / `LoadNode` string-ID constructors

`InsideNode(String id, String label)` and the analogous `LoadNode` constructor exist so nodes can have stable custom IDs instead of only numeric IDs like `I0` / `L0`.

They are used for:

- namespaced summaries in `MethodSummary.namespacedTo(...)`
- deserialization of summaries with nonstandard IDs

Example namespaced IDs look like:

- `I[<contributorSig>#I0]`
- `L[<contributorSig>#L1]`

This avoids collisions when multiple summaries are merged.

## Tests and Verification

Targeted test command that passed during this session:

```bash
./gradlew test --tests edu.uw.cse.sideeffect.SideEffectAnalysisTest
```

## Useful Documentation Files

- `report-related/implementation-pipeline.md`
- `report-related/pseudocode.md`
- `report-related/merged-graph-generation.md`
- `report-related/dymanic-dispatch.md`
- `report-related/interfile_analysis.md`
- `report-related/sootUp-related.md`
- `report-related/sideEffectVerdict.tex`

## Things To Be Careful About

- Do not confuse `rawCallGraph` with `dependencyGraph`
- Tarjan must run on `dependencyGraph`
- `SummaryCache` is exact full-signature lookup now
- dynamic dispatch is handled by summary propagation, not call-site override union
- user-code runs must not persist into `jdk-cache/`
- constructor behavior is intentionally special-cased
- array modeling is simplified
- unknown calls are conservative by design

## Suggested First Files To Read

If starting fresh, read in this order:

1. `src/main/java/edu/uw/cse/sideeffect/Main.java`
2. `src/main/java/edu/uw/cse/sideeffect/SideEffectAnalysisRunner.java`
3. `src/main/java/edu/uw/cse/sideeffect/analysis/CallGraphBuilder.java`
4. `src/main/java/edu/uw/cse/sideeffect/graph/PointsToGraph.java`
5. `src/main/java/edu/uw/cse/sideeffect/analysis/TransferFunctions.java`
6. `src/main/java/edu/uw/cse/sideeffect/analysis/GraphInstantiator.java`
7. `src/main/java/edu/uw/cse/sideeffect/analysis/SideEffectChecker.java`
8. `report-related/implementation-pipeline.md`
