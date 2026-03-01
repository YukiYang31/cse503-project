# Code Outline

This document describes the code structure, key design decisions, and how to extend the Java Side-Effect Analysis Tool. For usage instructions, see [README.md](README.md).

## Architecture

```
.java source --> javac --> .class bytecode --> SootUp/Jimple
                                                    |
                                              CallGraphBuilder (bottom-up order)
                                                    |
                                              for each method (bottom-up):
                                                    |
                                              PointsToGraph (with inter-procedural summary instantiation)
                                                    |
                                              SideEffectChecker --> verdict + MethodSummary (cached)
```

**Two modes of operation:**
- **User code mode**: `.java` files are compiled to `.class` via `JavaCompiler`, then loaded into a SootUp `JavaView` using `JavaClassPathAnalysisInputLocation`.
- **JRT mode**: JDK source file paths (e.g., `jdk/src/java.base/share/classes/java/util/HashSet.java`) are detected by `Main.java`. Instead of compiling, classes are loaded directly from the running JDK's module image via `JrtFileSystemAnalysisInputLocation`. The source path is used only to determine which classes to analyze.

## Package Structure

| Package | Role |
|---|---|
| `edu.uw.cse.sideeffect` | CLI entry point, configuration, source compilation, analysis orchestration |
| `edu.uw.cse.sideeffect.graph` | Points-to graph data structures: nodes, edges, the graph itself |
| `edu.uw.cse.sideeffect.analysis` | Dataflow analysis engine: flow analysis, transfer functions, side-effect checking, inter-procedural summary instantiation |
| `edu.uw.cse.sideeffect.output` | Result formatting: text verdicts, DOT graph output, and HTML debug traces |
| `edu.uw.cse.sideeffect.util` | Utilities: node merging optimization, safe method whitelist |

## Key Files

### Entry Point & Orchestration (`edu.uw.cse.sideeffect`)

- **`Main.java`** — CLI argument parsing. Detects JDK source files and routes to either compilation or JRT mode, then invokes `SideEffectAnalysisRunner`.
- **`AnalysisConfig.java`** — Holds CLI flags (`showGraph`, `merge`, `methodFilter`, `debug`, `timing`). Passed to all analysis components. `--debug` implies `--show-graph` and `--timing`.
- **`JavaCompiler.java`** — Compiles `.java` to `.class` in a temp directory using `javax.tools.JavaCompiler`.
- **`SideEffectAnalysisRunner.java`** — Creates a SootUp `JavaView`, builds call graph, analyzes methods bottom-up with inter-procedural summary cache. Creates and manages the shared state for on-demand cross-file analysis (`JavaView`, recursion guard `Set<String>`, budget `int[]`).

### Graph Data Structures (`edu.uw.cse.sideeffect.graph`)

- **`PointsToGraph.java`** — Core data structure: variable-to-node mappings (`L`), heap edges (`I` and `O`), mutation tracking (`W`), global escape tracking (`E`), return targets. Supports `copyInto()`, `mergeWith()` (union semantics at join points), `strongUpdate()`, `addInsideEdge()`, `addOutsideEdge()`, `markGlobalEscaped()`, `recordMutation()`.
- **`Node.java`** — Abstract base for all graph nodes. Each node has a unique string ID.
- **`InsideNode.java`** — Represents objects allocated within the method (`new T()`). Numbered per-method (I0, I1, ...).
- **`ParameterNode.java`** — Represents pre-existing objects passed as parameters. Index 0 is `this` for instance methods.
- **`LoadNode.java`** — Represents objects loaded from the pre-existing heap via field reads on escaped nodes. Numbered per-method (L0, L1, ...).
- **`GlobalNode.java`** — Singleton representing the static field namespace. Source of outside edges for static field reads; mutation target for static field writes.
- **`EdgeType.java`** — Enum: `INSIDE` (heap write) or `OUTSIDE` (heap read from pre-existing state).

### Analysis Engine (`edu.uw.cse.sideeffect.analysis`)

- **`SideEffectFlowAnalysis.java`** — Forward dataflow analysis extending SootUp's `ForwardFlowAnalysis<PointsToGraph>`. Handles fixed-point iteration, join-point merging (union semantics), and exit graph computation. Threads `JavaView`, `Set<String> analyzing`, and `int[] onDemandBudget` through to `TransferFunctions`.
- **`TransferFunctions.java`** — Maps each Jimple statement type to a graph operation. This is the core of the analysis. Handles: `JIdentityStmt` (parameter/this setup), `JAssignStmt` (allocations, copies, field loads/stores, casts, array ops, invocations), `JInvokeStmt` (void calls), `JReturnStmt` (return value tracking). Implements the four-tier method resolution strategy for invocations (see [Method Resolution](#method-resolution-four-tier-strategy) below).
- **`SideEffectChecker.java`** — Determines method side-effects from the exit graph. Algorithm: (1) compute prestate nodes A (BFS from ParameterNodes via outside edges), (2) compute globally escaped nodes B (BFS from E union GlobalNode via all edges), (3) check for static field mutations in W, (4) for each node in A check escape to B and mutations in W. Constructor exception: allows direct `this.f` writes for `<init>` methods.
- **`GraphInstantiator.java`** — Implements Section 5.3 of Salcianu & Rinard: instantiates callee summaries at call sites. Steps: (0) remap callee node IDs to fresh caller IDs, (1) compute node mapping mu via least fixed point of 3 constraints, (2) combine graphs (inside/outside edges, locals, escaped set), (3) remove captured load nodes, (4) propagate mutated fields W.
- **`MethodSummary.java`** — Stores the analysis result for a single method: exit `PointsToGraph`, `SideEffectResult` enum (`SIDE_EFFECT_FREE`, `SIDE_EFFECTING`, `GRAPH_VIOLATION`), reason string, and return targets for inter-procedural instantiation.
- **`SummaryCache.java`** — Dual-key cache: stores summaries by both full signature (e.g., `<java.util.HashMap: int size()>`) and sub-signature (e.g., `int size()`). The sub-signature fallback handles virtual/interface dispatch where the call site type differs from the implementation type.
- **`CallGraphBuilder.java`** — Builds an intra-file call graph from Jimple invoke statements. Resolves virtual/interface calls to concrete implementations within the analyzed classes. Computes bottom-up analysis order using Tarjan's SCC algorithm. Returns batches (single methods or SCCs).

### Output (`edu.uw.cse.sideeffect.output`)

- **`ResultPrinter.java`** — Prints the final side-effect verdicts table to stdout.
- **`GraphPrinter.java`** — Prints text graph summaries (`G = ⟨I, O, L, E⟩`) and writes DOT files for Graphviz visualization.
- **`DebugHtmlWriter.java`** — Generates per-method self-contained HTML debug traces with visual graph renderings via viz.js (loaded from CDN). Shows source code, bytecode, Jimple IR, step-by-step analysis trace, and exit graph.

### Utilities (`edu.uw.cse.sideeffect.util`)

- **`SafeMethods.java`** — Whitelist of known-side-effect-free methods and constructors. Three categories: `SAFE_CONSTRUCTOR_CLASSES` (classes whose `<init>` is safe), `SAFE_CLASS_PREFIXES` (classes whose all instance methods are safe, e.g., `java.lang.String`), `SAFE_METHOD_SIGNATURES` (individual method signatures). This is Tier 1 in the method resolution strategy.
- **`NodeMerger.java`** — Madhavan et al. (2011) optimization: enforces at most one outgoing edge per `(node, field, edgeType)` triple. When a second target would be added, the two targets are merged into one representative node and all edges are redirected.
- **`TimingRecorder.java`** — Records per-phase and per-method timing data. Prints a summary table and saves structured JSON to `timing/`.

## Key Design Decisions

### Why SootUp (not old Soot)

SootUp is the modern successor to Soot. Key advantages:
- No global singletons — `JavaView` is the entry point, created per analysis run
- Java 21 support (via ASM 9.5)
- Modular architecture, actively maintained
- Clean Jimple IR with pattern-matchable statement types

### Method Resolution: Four-Tier Strategy

When `TransferFunctions.handleInvoke()` encounters a method call, it resolves the callee through four tiers:

```
Tier 1: SafeMethods.isSafe()?          → YES → treat as safe, return
Tier 2: SummaryCache.lookup()?         → YES → instantiate summary, return
Tier 3: On-demand analysis?            → resolve from JavaView, analyze, cache, instantiate
Tier 4: Conservative fallback          → mark all args escaped (only if tiers 1-3 fail)
```

**Tier 1 (SafeMethods)** is a hardcoded whitelist — it *assumes* methods are safe without proof. This is necessary for common JDK methods like constructors (`Object.<init>()`, `ArrayList.<init>()`) that would otherwise cause every `new` expression to be flagged as side-effecting (see [The `<init>` Trap](#constructor-whitelist-the-init-trap)).

**Tier 2 (SummaryCache)** looks up summaries from methods already analyzed in the current run — either same-file methods analyzed bottom-up, or cross-file methods previously analyzed on-demand.

**Tier 3 (On-demand analysis)** resolves the callee from the `JavaView` and analyzes it on the fly. This is the key mechanism for cross-file inter-procedural analysis. See [On-Demand Cross-File Analysis](#on-demand-cross-file-analysis) for details.

**Tier 4 (Conservative fallback)** marks all reference-type arguments and the receiver as globally escaped, and points the return value to `GlobalNode`. This is sound but pessimistic — it may produce false positives.

### Inter-Procedural Analysis (Section 5.3)

The analysis processes methods bottom-up over an intra-file call graph:

1. **Call graph construction**: `CallGraphBuilder` collects invoke targets from Jimple, resolves virtual/interface calls to concrete implementations within the analyzed classes, and computes a bottom-up order using Tarjan's SCC algorithm.
2. **Bottom-up analysis**: Methods are analyzed in reverse topological order. Leaf methods (no user-defined callees) are analyzed first; their summaries are cached and used when analyzing their callers.
3. **Summary instantiation**: At each call site, `GraphInstantiator` maps callee parameter nodes to caller argument nodes (via a least-fixed-point mu mapping), combines inside/outside edges, removes captured load nodes, and propagates callee mutations to the caller's graph.
4. **SCC handling**: For mutually recursive methods, the analysis iterates within each SCC until summaries stabilize (max 5 iterations).

### On-Demand Cross-File Analysis

When a method call cannot be resolved within the analyzed file (SafeMethods miss + SummaryCache miss), the tool resolves the callee from the `JavaView` and analyzes it on the fly, caching the result for future lookups. This enables cross-file inter-procedural analysis without requiring whole-program analysis upfront.

For example, `HashSet.size()` calls `this.map.size()`, which resolves to `HashMap.size()` in bytecode. Without on-demand analysis, the tool would conservatively mark all arguments as globally escaped (false positive). With on-demand analysis, `HashMap.size()` is analyzed, found to be side-effect-free, and its summary is instantiated at the call site — correctly classifying `HashSet.size()` as `SIDE_EFFECT_FREE`.

**Implementation** (`TransferFunctions.analyzeExternalMethod()`):
1. Check recursion guard — if the method's signature is already in the `analyzing` set, return null (avoids infinite loops for mutual recursion)
2. Check depth limit — if `analyzing.size() >= MAX_ON_DEMAND_DEPTH` (default 5), return null
3. Check budget — if `onDemandBudget[0] <= 0`, return null
4. Resolve the class from `JavaView` using `methodSig.getDeclClassType()`
5. Find the concrete method; if abstract/native/missing, return null
6. Add signature to `analyzing`, decrement budget
7. Create a lightweight `SideEffectFlowAnalysis` (no debug, no timing) with the same `view`, `analyzing` set, and `onDemandBudget` (enabling recursive on-demand for the callee's callees)
8. Check graph size — if exit graph exceeds 20 nodes, return null (avoids instantiation explosion)
9. Run `SideEffectChecker.check()`, create `MethodSummary`, cache in `SummaryCache`
10. Remove signature from `analyzing` (via `try-finally`)

**Performance guards** (constants in `TransferFunctions.java`):

| Guard | Default | Purpose |
|---|---|---|
| `MAX_ON_DEMAND_DEPTH` | 5 | Max depth of recursive on-demand calls. Only counts cross-file calls — same-file cache hits cost zero depth. |
| `MAX_ON_DEMAND_TOTAL` | 10 | Max total on-demand analyses per top-level method. Reset before each top-level method so one greedy method doesn't starve others. |
| Graph size guard | 20 nodes | Max exit graph size for caching. Prevents `GraphInstantiator` from producing quadratic edge cross-products. |

**Shared state** (created in `SideEffectAnalysisRunner.run()`, threaded through `SideEffectFlowAnalysis` → `TransferFunctions`):

| State | Type | Lifetime | Purpose |
|---|---|---|---|
| `view` | `JavaView` | Entire `run()` | Resolves classes for on-demand analysis |
| `analyzing` | `Set<String>` | Entire `run()` | Recursion guard — tracks signatures currently being analyzed in the call chain |
| `onDemandBudget` | `int[1]` | Reset per top-level method | Mutable counter shared across recursive on-demand calls. Uses `int[]` instead of `int` because Java passes primitives by value. |
| `summaryCache` | `SummaryCache` | Entire `run()` | Caches all summaries (same-file + on-demand). Never reset during a run. |

### Constructor Whitelist (the `<init>` Trap)

In Jimple, `new T()` compiles to two statements: `x = new T` followed by `specialinvoke x.<init>()`. Since unknown calls are conservatively side-effecting, every allocation would be flagged side-effecting without whitelisting standard library constructors. `SafeMethods.java` includes constructors for `Object`, `String`, `ArrayList`, `HashMap`, `StringBuilder`, wrapper types, and other common JDK classes.

### Constructor `this` Mutation

The constructor exception is precise: only direct `this.f = x` writes (where `this` is `ParameterNode(0)`) are exempt. Writes that traverse through `this` to reach other prestate objects (e.g., `this.list.add(x)` where `this.list` is a prestate object) are still flagged as side-effecting.

### Graph Invariant Validation

Before checking side-effects, the exit graph is validated against two structural invariants from the Salcianu & Rinard (2005) model:

1. **Rule 1**: An `InsideNode` cannot be the source of an `OUTSIDE` edge. Inside nodes represent objects allocated within the method — they have no pre-existing heap to read from.
2. **Rule 2**: An `OUTSIDE` edge cannot lead to an `InsideNode`. Outside edges represent reads from the pre-existing heap, which cannot contain freshly allocated objects.

If either rule is violated, the tool prints the violations in **red** (ANSI escape codes) and skips the SIDE_EFFECT_FREE/SIDE_EFFECTING verdict entirely. This catches bugs in graph construction or node merging that would otherwise produce unsound results.

### Static Field Writes = Side-Effecting

When a static field is written, the analysis records a mutation on `GlobalNode` with the written field. Since `GlobalNode` is always in the globally escaped set B, any prestate node stored into a static field will appear in B, and the mutation record `⟨GlobalNode, field⟩` in set W triggers a side-effecting verdict during the standard graph-based side-effect check.

### Strong vs Weak Updates

- **Strong update**: For local variable assignments (`x = ...`), the variable's targets are wiped and replaced. Locals point to exactly one set of nodes.
- **Weak update**: For field stores (`x.f = y`), edges are added without removing existing ones. Since `x` may alias multiple nodes, we conservatively keep all possibilities.

### Node Merging (Optional)

The Madhavan et al. (2011) optimization enforces that for any `(node, field, edgeType)` triple, at most one target node exists. When a second target would be added, the two targets are merged (one becomes representative, edges are redirected). This bounds graph size without losing precision for side-effect analysis.

Node merging is applied in three places:
1. After field-load transfer functions
2. At CFG join points (inside `merge()`)
3. At method exit before side-effect checking

Node merging is disabled by default (showing the 2005-style graphs). Enable with `--merge`.

## How to Extend

### Adding Safe Methods
Edit `SafeMethods.java`. Three categories:
- `SAFE_CONSTRUCTOR_CLASSES`: Classes whose constructors are known side-effect-free
- `SAFE_CLASS_PREFIXES`: Classes whose instance methods are known side-effect-free (e.g., `java.lang.String`)
- `SAFE_METHOD_SIGNATURES`: Specific method signatures known to be side-effect-free

Note: with on-demand cross-file analysis (Tier 3), many methods that previously required whitelisting are now analyzed automatically. You only need to add methods here if they are native, too complex for on-demand analysis (exceed budget/depth/graph-size limits), or if you want to force a specific result.

### Adding Transfer Functions
Edit `TransferFunctions.java`. The `apply()` method dispatches on `Stmt` type. Add new cases by pattern matching on Jimple statement types. The main dispatch is:
- `JIdentityStmt` → `handleIdentity()` (parameter/this setup)
- `JAssignStmt` → `handleAssign()` (further dispatches on LHS/RHS type)
- `JInvokeStmt` → `handleInvokeStmt()` (void calls)
- `JReturnStmt` → `handleReturn()` (return value tracking)

### Tuning On-Demand Analysis Performance
Edit constants at the top of `TransferFunctions.java`:
- `MAX_ON_DEMAND_DEPTH` (default 5): Increase to follow deeper cross-file call chains at the cost of slower analysis.
- `MAX_ON_DEMAND_TOTAL` (default 10): Increase to analyze more unique callees per top-level method. The budget is also reset in `SideEffectAnalysisRunner.analyzeMethod()`.
- Graph size guard (default 20 nodes): The threshold in `analyzeExternalMethod()`. Increase to allow larger callee summaries to be instantiated, at the risk of quadratic edge explosion in `GraphInstantiator`.

### Extending Inter-Procedural Analysis
Key components:

1. **`CallGraphBuilder`** builds an intra-file call graph from Jimple invoke statements and computes a bottom-up analysis order using Tarjan's SCC algorithm.
2. **`SummaryCache`** stores method summaries keyed by both full signature and sub-signature (for virtual/interface dispatch resolution).
3. **`GraphInstantiator`** implements the 4-step summary instantiation algorithm: compute node mapping mu (least fixed point of 3 constraints), combine caller/callee graphs, simplify by removing captured load nodes, and propagate mutated fields.
4. **`TransferFunctions.handleInvoke()`** implements the four-tier resolution strategy: SafeMethods → SummaryCache → on-demand cross-file analysis → conservative fallback.

The on-demand analysis (Tier 3) automatically resolves cross-file callees from the `JavaView`, so most JDK delegation patterns (e.g., `HashSet.size()` → `HashMap.size()`) are handled without manual whitelisting.

## Validation Against the Paper

The test suite includes the complete example from Section 2 of the Salcianu & Rinard paper (Figure 1): a singly linked `List` with `Cell` nodes, a `ListItr` iterator, a `Point` class, and two static methods `sumX` and `flipAll`.

### Points-to Graph Comparison

For methods that do not involve inter-procedural calls, our tool's exit graphs match the paper's Figures 2 and 3 exactly (modulo node renumbering, since the paper uses globally unique IDs while our tool numbers per-method):

| Method | Paper | Our Tool | Match? |
|---|---|---|---|
| `Point.<init>` | Fig 2.a: `W = {(P1,x), (P1,y)}` | `W = {(P0,x), (P0,y)}` | Yes |
| `Cell.<init>` | Fig 2.b: edges `P2→P3 (data)`, `P2→P4 (next)` | `P0→P1 (data)`, `P0→P2 (next)` | Yes |
| `ListItr.<init>` | Fig 3.d: edge `P7→P8 (cell)` | `P0→P1 (cell)` | Yes |
| `ListItr.hasNext` | Fig 3.e: outside `P9→L2 (cell)` | outside `P0→L0 (cell)` | Yes |
| `ListItr.next` | Fig 3.f: outside `P10→L3→L4,L5`; inside `P10→L5` | outside `P0→L0→L1,L2`; inside `P0→L2` | Yes |
| `Point.flip` | `W = {(P1,x), (P1,y)}` | `W = {(P0,x), (P0,y)}` | Yes |

### Side-effect Verdict Comparison

| Method | Paper (inter-procedural) | Our Tool | Notes |
|---|---|---|---|
| `Point.<init>` | SIDE_EFFECT_FREE | SIDE_EFFECT_FREE | Constructor exception for `this.f` writes |
| `Cell.<init>` | SIDE_EFFECT_FREE | SIDE_EFFECT_FREE | Constructor exception |
| `ListItr.<init>` | SIDE_EFFECT_FREE | SIDE_EFFECT_FREE | Constructor exception |
| `List.<init>` | SIDE_EFFECT_FREE | SIDE_EFFECT_FREE | Constructor exception |
| `ListItr.hasNext()` | SIDE_EFFECT_FREE | SIDE_EFFECT_FREE | Only reads, no mutations |
| `Point.flip()` | SIDE_EFFECTING | SIDE_EFFECTING | Mutates prestate `this.x`, `this.y` |
| `ListItr.next()` | SIDE_EFFECTING | SIDE_EFFECTING | Mutates prestate `this.cell` |
| `List.add()` | SIDE_EFFECTING | SIDE_EFFECTING | Mutates `this.head` |
| `List.iterator()` | SIDE_EFFECT_FREE | SIDE_EFFECT_FREE | Inter-procedural: `ListItr.<init>` summary shows only InsideNode mutation |
| `Main.sumX()` | SIDE_EFFECT_FREE | SIDE_EFFECT_FREE | Inter-procedural: `iterator()`, `hasNext()`, `next()` summaries compose correctly |
| `Main.flipAll()` | SIDE_EFFECTING | SIDE_EFFECTING | Inter-procedural: `flip()` mutation on prestate objects propagates through |

All results match the paper's inter-procedural analysis. The key cases are `List.iterator()` and `Main.sumX()`, which require composing method summaries across multiple call sites to determine that the iterator is a fresh InsideNode and all mutations target only that InsideNode (not any prestate object).

## References

1. A. Salcianu and M. Rinard. *Purity and Side Effect Analysis for Java Programs*. VMCAI 2005.
2. R. Madhavan, G. Ramalingam, and K. Vaswani. *Purity Analysis: An Abstract Interpretation Formulation*. SAS 2011.
