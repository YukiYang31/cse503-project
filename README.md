# Java Side-Effect Analysis Tool

A static analysis tool that determines whether Java methods are **side-effect-free** — that is, whether they avoid mutating any object that existed before the method was called. Built on [SootUp](https://soot-oss.github.io/SootUp/) and the Jimple intermediate representation.

## Theoretical Background

This tool implements the side-effect analysis described by:

1. **Salcianu & Rinard (2005)** — *Purity and Side Effect Analysis for Java Programs* — defines purity via a pointer/escape analysis using a points-to graph **G = ⟨I, O, L, E⟩** (inside edges, outside edges, local variable state, escaped nodes) with four node types (Inside, Parameter, Load, Global) and two edge types (Inside, Outside).
2. **Madhavan et al. (2011)** — *Purity Analysis: An Abstract Interpretation Formulation* — provides a lattice-theoretic reformulation with a node merging optimization that bounds graph size.

### What "Side-Effect-Free" Means

A method is **side-effect-free** if it does not mutate any heap location that existed before the method was called. Specifically:

- **Allowed**: Allocating new objects and mutating them (`Point p = new Point(); p.x = 5;`)
- **Forbidden**: Mutating an object passed as a parameter (`param.x = 5;`)
- **Forbidden**: Writing to static fields (`MyClass.counter++`)
- **Forbidden**: Mutating objects reachable through parameters (`param.list.add(x)`)

Constructors have a special rule: direct field writes to `this` (`this.f = x`) are allowed since they initialize the new object, but writes to objects reachable *through* `this` are still forbidden.

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

### Package Structure

| Package | Role |
|---|---|
| `edu.uw.cse.sideeffect` | CLI entry point, configuration, source compilation, analysis orchestration |
| `edu.uw.cse.sideeffect.graph` | Points-to graph data structures: nodes, edges, the graph itself |
| `edu.uw.cse.sideeffect.analysis` | Dataflow analysis engine: flow analysis, transfer functions, side-effect checking, inter-procedural summary instantiation |
| `edu.uw.cse.sideeffect.output` | Result formatting: text verdicts, DOT graph output, and HTML debug traces |
| `edu.uw.cse.sideeffect.util` | Utilities: node merging optimization, safe method whitelist |

### Key Files

- **`Main.java`** — CLI argument parsing, invokes `JavaCompiler` then `SideEffectAnalysisRunner`
- **`JavaCompiler.java`** — Compiles `.java` to `.class` in a temp directory using `javax.tools.JavaCompiler`
- **`SideEffectAnalysisRunner.java`** — Creates a SootUp `JavaView`, builds call graph, analyzes methods bottom-up with inter-procedural summary cache
- **`PointsToGraph.java`** — Core data structure: variable-to-node mappings, heap edges, mutation tracking, global escape tracking
- **`TransferFunctions.java`** — Maps each Jimple statement type to a graph operation; looks up callee summaries from `SummaryCache` for inter-procedural calls
- **`SideEffectFlowAnalysis.java`** — Forward dataflow analysis extending SootUp's `ForwardFlowAnalysis`
- **`SideEffectChecker.java`** — Validates graph invariants, then determines side-effectness from the exit graph: checks prestate mutations and global escape
- **`GraphInstantiator.java`** — Section 5.3 of Salcianu & Rinard: instantiates callee summaries at call sites by computing a node mapping (mu), combining graphs, removing captured load nodes, and propagating mutations
- **`SummaryCache.java`** — Stores method summaries keyed by full signature and sub-signature (for virtual/interface dispatch resolution)
- **`CallGraphBuilder.java`** — Builds an intra-file call graph and computes bottom-up analysis order using Tarjan's SCC algorithm
- **`DebugHtmlWriter.java`** — Generates per-method HTML debug traces with visual graph renderings via viz.js
- **`NodeMerger.java`** — Madhavan et al. (2011) optimization: enforces at most one outgoing edge per (node, field, type) triple
- **`SafeMethods.java`** — Whitelist of known-side-effect-free methods and constructors

## Key Design Choices

### Why SootUp (not old Soot)

SootUp is the modern successor to Soot. Key advantages:
- No global singletons — `JavaView` is the entry point, created per analysis run
- Java 21 support (via ASM 9.5)
- Modular architecture, actively maintained
- Clean Jimple IR with pattern-matchable statement types

### Inter-Procedural Analysis (Section 5.3)

The analysis processes methods bottom-up over an intra-file call graph. When method A calls method B (both in the analyzed files), B's summary is instantiated at A's call site using the algorithm from Section 5.3 of Salcianu & Rinard (2005):

1. **Call graph construction**: `CallGraphBuilder` collects invoke targets from Jimple, resolves virtual/interface calls to concrete implementations within the analyzed classes, and computes a bottom-up order using Tarjan's SCC algorithm.
2. **Bottom-up analysis**: Methods are analyzed in reverse topological order. Leaf methods (no user-defined callees) are analyzed first; their summaries are cached and used when analyzing their callers.
3. **Summary instantiation**: At each call site, `GraphInstantiator` maps callee parameter nodes to caller argument nodes (via a least-fixed-point mu mapping), combines inside/outside edges, removes captured load nodes, and propagates callee mutations to the caller's graph.
4. **SCC handling**: For mutually recursive methods, the analysis iterates within each SCC until summaries stabilize (max 5 iterations).

Calls to methods outside the analyzed files (e.g., JDK methods) still use the conservative fallback unless listed in `SafeMethods`.

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

## How to Build & Run

### Prerequisites
- Java 21+ (JDK)
- Gradle (or use the included wrapper)

### Build
```bash
./gradlew build
```

### Run
```bash
# Analyze all methods in a Java file
./gradlew run --args="MyFile.java"

# Analyze a specific method
./gradlew run --args="MyFile.java --method myMethod"

# Show points-to graphs (text + DOT files)
./gradlew run --args="MyFile.java --show-graph"

# Enable node merging (Madhavan et al. 2011 optimization)
./gradlew run --args="MyFile.java --merge"

# Combine flags
./gradlew run --args="MyFile.java --show-graph --merge"

# Generate HTML debug traces with visual graphs (opens in browser)
./gradlew run --args="MyFile.java --debug"

# Debug a specific method
./gradlew run --args="MyFile.java --debug --method myMethod"

# Print timing summary and save JSON to timing/
./gradlew run --args="MyFile.java --timing"
```

### Render DOT Graphs
```bash
dot -Tpng 'MethodName.dot' -o graph.png
```

## CLI Flags

| Flag | Description |
|---|---|
| `--show-graph` | Print text graph summaries and write DOT files for each method |
| `--merge` | Enable node merging (Madhavan et al. 2011 optimization) |
| `--method <name>` | Only analyze methods with this name |
| `--debug` | Write per-method HTML debug traces to `debug/` directory (implies `--show-graph` and `--timing`) |
| `--timing` | Print timing summary and save structured JSON to `timing/` directory |

### Timing Pipeline

When `--timing` is enabled, timestamps are recorded around each phase of the pipeline.
The flowchart below shows exactly where each measurement is taken:

```
Main.java                              SideEffectAnalysisRunner.java
─────────                              ─────────────────────────────

timer.startTotal()                     ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐
    │                                                                        │
    ▼                                                                        │
┌──────────────────────────┐                                                 │
│  JavaCompiler.compile()  │  ◀── ⏱ Compilation (javac)                     │
│  timer.recordCompilation │                                                 │
└──────────────────────────┘                                                 │
    │                                                                        │
    ▼                                                                        │
  runner.run() ───────────────▶  run()                                       │
                                  │                                          │
                                  ▼                                          │
                            ┌────────────────────────┐                       │
                            │  new JavaView(classDir) │                      │
                            │  view.getClasses()      │  ◀── ⏱ SootUp IR    │  Total
                            │  timer.recordIrLoading  │       loading        │  wall-
                            └────────────────────────┘                       │  clock
                                  │                                          │
                                  ▼                                          │
                            ┌────────────────────────┐                       │
                            │  readSourceFiles()      │  (not timed;         │
                            │  (debug mode only)      │   negligible)        │
                            └────────────────────────┘                       │
                                  │                                          │
                                  ▼                                          │
                            ┌─────────────────────────────┐                  │
                            │  CallGraphBuilder            │                 │
                            │    .computeBottomUpOrder()   │  ◀── ⏱ Call     │
                            │  timer.recordCallGraph       │      graph      │
                            └─────────────────────────────┘                  │
                                  │                                          │
                                  ▼                                          │
                            ┌─ for each method batch ──────────────────┐     │
                            │                                          │     │
                            │  ┌────────────────────────────────────┐  │     │
                            │  │  SideEffectFlowAnalysis(...)      │  │     │
                            │  │  analysis.getExitGraph()           │  │     │
                            │  │  dataflowNs = elapsed              │  │     │
                            │  └────────────────────────────────────┘  │     │
                            │        │              ◀── ⏱ Dataflow     │     │
                            │        ▼                  (per-method)   │     │
                            │  ┌────────────────────────────────────┐  │     │
                            │  │  SideEffectChecker.check(...)     │  │     │
                            │  │  sideEffectNs = elapsed                │  │     │
                            │  └────────────────────────────────────┘  │     │
                            │        │              ◀── ⏱ Side-effect       │     │
                            │        ▼                  (per-method)   │     │
                            │  timer.addMethodTiming(...)              │     │
                            │  (accumulates into dataflow/side-effect       │     │
                            │   totals)                                │     │
                            └──────────────────────────────────────────┘     │
                                  │                                          │
                                  ▼                                          │
                            ┌────────────────────────┐                       │
                            │  GraphPrinter           │  (not timed;         │
                            │  ResultPrinter.print()  │   negligible)        │
                            └────────────────────────┘                       │
                                  │                                          │
    ◀──────────────────────── return                                         │
    │                                                                        │
timer.endTotal()                   ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┘
timer.printReport()
timer.saveJson()
```

The five timed phases (Compilation → IR Loading → Call Graph → Dataflow → Side-effect) account for the vast majority of wall-clock time. Small gaps between phases (source file reading, result printing,
loop overhead) are not individually timed.

## Understanding the Output

### Side-Effect Analysis Verdicts
```
=== Side-Effect Analysis Results ===
SideEffectFreeMethods.add(int,int)     : SIDE_EFFECT_FREE
SideEffectingMethods.setX(Point,int)   : SIDE_EFFECTING  (mutates Point parameter via field x)
SideEffectingMethods.increment()       : SIDE_EFFECTING  (writes to static field counter)
```

If a graph invariant violation is detected, the verdict is printed in red instead:
```
BuggyMethod.foo()  : GRAPH VIOLATION  (Rule 1 violated: InsideNode I0 has outside edge --f--> L0)
```
This indicates a bug in graph construction rather than a side-effect property of the method.

### Graph Text Summary
When `--show-graph` is used, each method's exit graph is presented as **G = ⟨I, O, L, E⟩** per the paper's formal definition:
- **Nodes** — all abstract heap locations with their type and description
- **I (Inside Edges)** — heap references created by the method (writes)
- **O (Outside Edges)** — heap references read from pre-existing objects
- **L (Local Variables)** — which local variables point to which nodes
- **E (Globally Escaped)** — nodes whose address is stored in static fields
- **W (Mutated Fields)** — which (node, field) pairs were written to 

### DOT Graph Color Scheme
- **Green box** (InsideNode): Newly allocated object — mutations are safe
- **Blue ellipse** (ParameterNode): Pre-existing parameter object — mutations mean side-effecting
- **Red diamond** (LoadNode): Object loaded from pre-existing heap — mutations mean side-effecting
- **Orange octagon** (GlobalNode): Static field namespace
- **Solid arrow**: InsideEdge (write/mutation)
- **Dashed arrow**: OutsideEdge (read from pre-existing heap)
- **Red border**: Prestate node that was mutated (side-effect source)

### Debug HTML Traces

When `--debug` is used, the tool writes one self-contained HTML file per method to the `debug/` directory. Open any file in a browser to see:

1. **Java Source Code** — The original source lines for the method
2. **Bytecode** — JVM bytecode for the method, disassembled via ASM's `Textifier` (no `javap` needed)
3. **Jimple Body** — The full Jimple IR for the method, with line numbers
4. **Analysis Trace (Key Milestones)** — A visual points-to graph after each significant statement:
   - Identity statements (parameter/this setup)
   - Field loads and stores (`x = y.f`, `x.f = y`)
   - Static field reads and writes
   - Array loads and stores
   - Object allocations (`new T()`)
   - Method invocations
   - Skips trivial statements (local copies, casts, gotos, ifs)
5. **Exit Graph** — The final points-to graph at method exit, rendered visually
6. **Graph Components G = ⟨I, O, L, E⟩** — textual breakdown of the exit graph into its four formal components
7. **Prestate Nodes** — The set of nodes representing pre-existing objects
8. **Set W (Mutated Fields)** — All (node, field) pairs that were written to
9. **Side-Effect Analysis Result** — The final verdict (SIDE_EFFECT_FREE/SIDE_EFFECTING) with reason

This gives a complete view of the compilation pipeline: **Java source → JVM bytecode → Jimple IR → analysis**.

Graphs are rendered in the browser using [viz.js](https://github.com/nicknisi/viz.js) (Graphviz compiled to JavaScript) loaded from a CDN — no Graphviz installation required. The same DOT color scheme described above applies: green boxes for inside nodes, blue ellipses for parameter nodes, red diamonds for load nodes, and red borders for side-effecting sources.

`--debug` automatically implies `--show-graph`, so DOT files and text summaries are also generated alongside the HTML traces.

## How to Extend

### Adding Safe Methods
Edit `SafeMethods.java`. Three categories:
- `SAFE_CONSTRUCTOR_CLASSES`: Classes whose constructors are known side-effect-free
- `SAFE_CLASS_PREFIXES`: Classes whose instance methods are known side-effect-free (e.g., `java.lang.String`)
- `SAFE_METHOD_SIGNATURES`: Specific method signatures known to be side-effect-free

### Adding Transfer Functions
Edit `TransferFunctions.java`. The `apply()` method dispatches on `Stmt` type. Add new cases by pattern matching on Jimple statement types.

### Inter-Procedural Analysis (Section 5.3)
The inter-procedural analysis follows Section 5.3 of the paper. Key components:

1. **`CallGraphBuilder`** builds an intra-file call graph from Jimple invoke statements and computes a bottom-up analysis order using Tarjan's SCC algorithm.
2. **`SummaryCache`** stores method summaries keyed by both full signature and sub-signature (for virtual/interface dispatch resolution).
3. **`GraphInstantiator`** implements the 4-step summary instantiation algorithm: compute node mapping μ (least fixed point of 3 constraints), combine caller/callee graphs, simplify by removing captured load nodes, and propagate mutated fields.
4. **`TransferFunctions.handleInvoke()`** checks the cache between the `SafeMethods` whitelist and conservative fallback, applying callee summaries when available.

To extend this further (e.g., whole-program analysis), the main change would be expanding `CallGraphBuilder` to include methods from library JARs or use a pre-computed call graph.

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

All results now match the paper's inter-procedural analysis. The key cases are `List.iterator()` and `Main.sumX()`, which require composing method summaries across multiple call sites to determine that the iterator is a fresh InsideNode and all mutations target only that InsideNode (not any prestate object).

## Known Limitations

- **Inter-procedural scope is same-file only**: The bottom-up analysis covers methods defined in the analyzed source files. Calls to external methods (libraries, JDK) not in `SafeMethods` are still treated conservatively.
- **Recursive call chains use bounded iteration**: Mutually recursive methods (SCCs in the call graph) are analyzed by iterating up to 5 times. If summaries do not stabilize, the last computed summary is used. The paper suggests iterating to a true fixed point; we cap at 5 for practical reasons.
- **No exception-path precision**: Exception control flow is handled by SootUp's CFG but not modeled with special precision.
- **Array modeling is simplified**: Array elements are tracked via mutation records but not with per-index precision.

## References

1. A. Salcianu and M. Rinard. *Purity and Side Effect Analysis for Java Programs*. VMCAI 2005.
2. R. Madhavan, G. Ramalingam, and K. Vaswani. *Purity Analysis: An Abstract Interpretation Formulation*. SAS 2011.

