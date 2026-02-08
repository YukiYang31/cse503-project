# Java Purity Analysis Tool

A static analysis tool that determines whether Java methods are **pure** — that is, whether they avoid mutating any object that existed before the method was called. Built on [SootUp](https://soot-oss.github.io/SootUp/) and the Jimple intermediate representation.

## Theoretical Background

This tool implements the intra-procedural purity analysis described by:

1. **Salcianu & Rinard (2005)** — *Purity and Side Effect Analysis for Java Programs* — defines purity via a pointer/escape analysis using a points-to graph with four node types (Inside, Parameter, Load, Global) and two edge types (Inside, Outside).
2. **Madhavan et al. (2011)** — *Purity Analysis: An Abstract Interpretation Formulation* — provides a lattice-theoretic reformulation with a node merging optimization that bounds graph size.

### What "Pure" Means

A method is **pure** if it does not mutate any heap location that existed before the method was called. Specifically:

- **Allowed**: Allocating new objects and mutating them (`Point p = new Point(); p.x = 5;`)
- **Forbidden**: Mutating an object passed as a parameter (`param.x = 5;`)
- **Forbidden**: Writing to static fields (`MyClass.counter++`)
- **Forbidden**: Mutating objects reachable through parameters (`param.list.add(x)`)

Constructors have a special rule: direct field writes to `this` (`this.f = x`) are allowed since they initialize the new object, but writes to objects reachable *through* `this` are still forbidden.

## Architecture

```
.java source --> javac --> .class bytecode --> SootUp/Jimple --> PointsToGraph --> PurityChecker --> verdict
```

### Package Structure

| Package | Role |
|---|---|
| `edu.uw.cse.purity` | CLI entry point, configuration, source compilation, analysis orchestration |
| `edu.uw.cse.purity.graph` | Points-to graph data structures: nodes, edges, the graph itself |
| `edu.uw.cse.purity.analysis` | Dataflow analysis engine: flow analysis, transfer functions, purity checking |
| `edu.uw.cse.purity.output` | Result formatting: text verdicts, DOT graph output, and HTML debug traces |
| `edu.uw.cse.purity.util` | Utilities: node merging optimization, safe method whitelist |

### Key Files

- **`Main.java`** — CLI argument parsing, invokes `JavaCompiler` then `PurityAnalysisRunner`
- **`JavaCompiler.java`** — Compiles `.java` to `.class` in a temp directory using `javax.tools.JavaCompiler`
- **`PurityAnalysisRunner.java`** — Creates a SootUp `JavaView`, iterates methods, runs analysis on each
- **`PointsToGraph.java`** — Core data structure: variable-to-node mappings, heap edges, mutation tracking, global escape tracking
- **`TransferFunctions.java`** — Maps each Jimple statement type to a graph operation (the abstract semantics)
- **`PurityFlowAnalysis.java`** — Forward dataflow analysis extending SootUp's `ForwardFlowAnalysis`
- **`PurityChecker.java`** — Determines purity from the exit graph: checks prestate mutations and global escape
- **`DebugHtmlWriter.java`** — Generates per-method HTML debug traces with visual graph renderings via viz.js
- **`NodeMerger.java`** — Madhavan et al. (2011) optimization: enforces at most one outgoing edge per (node, field, type) triple
- **`SafeMethods.java`** — Whitelist of known-pure methods and constructors

## Key Design Choices

### Why SootUp (not old Soot)

SootUp is the modern successor to Soot. Key advantages:
- No global singletons — `JavaView` is the entry point, created per analysis run
- Java 21 support (via ASM 9.5)
- Modular architecture, actively maintained
- Clean Jimple IR with pattern-matchable statement types

### Intra-Procedural Scope

This implementation analyzes one method at a time. Unknown method calls are handled conservatively (flagged as potentially impure). This is sound but may produce false positives for methods that call other pure methods. See the **Extensibility** section for how inter-procedural support could be added.

### Constructor Whitelist (the `<init>` Trap)

In Jimple, `new T()` compiles to two statements: `x = new T` followed by `specialinvoke x.<init>()`. Since unknown calls are conservatively impure, every allocation would be flagged impure without whitelisting standard library constructors. `SafeMethods.java` includes constructors for `Object`, `String`, `ArrayList`, `HashMap`, `StringBuilder`, wrapper types, and other common JDK classes.

### Constructor `this` Mutation

The constructor exception is precise: only direct `this.f = x` writes (where `this` is `ParameterNode(0)`) are exempt. Writes that traverse through `this` to reach other prestate objects (e.g., `this.list.add(x)` where `this.list` is a prestate object) are still flagged as impure.

### Static Field Writes = Immediate Impurity

A `hasGlobalSideEffect` boolean in the graph is set whenever a static field is written. This is checked first in `PurityChecker` — no graph traversal needed.

### Strong vs Weak Updates

- **Strong update**: For local variable assignments (`x = ...`), the variable's targets are wiped and replaced. Locals point to exactly one set of nodes.
- **Weak update**: For field stores (`x.f = y`), edges are added without removing existing ones. Since `x` may alias multiple nodes, we conservatively keep all possibilities.

### Node Merging (Optional)

The Madhavan et al. (2011) optimization enforces that for any `(node, field, edgeType)` triple, at most one target node exists. When a second target would be added, the two targets are merged (one becomes representative, edges are redirected). This bounds graph size without losing precision for purity analysis.

Node merging is applied in three places:
1. After field-load transfer functions
2. At CFG join points (inside `merge()`)
3. At method exit before purity checking

Node merging is disabled by default (showing pure 2005-style graphs). Enable with `--merge`.

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
| `--debug` | Write per-method HTML debug traces to `debug/` directory (implies `--show-graph`) |

## Understanding the Output

### Purity Verdicts
```
=== Purity Analysis Results ===
PureMethods.add(int,int)        : PURE
ImpureMethods.setX(Point,int)   : IMPURE  (mutates prestate node P1 via field x)
ImpureMethods.increment()       : IMPURE  (writes to static field)
```

### Graph Text Summary
When `--show-graph` is used, each method's exit graph is printed showing:
- **Nodes**: All abstract heap locations with their type and description
- **Edges**: Heap edges labeled with field names and edge type (INSIDE=solid, OUTSIDE=dashed)
- **Variable Mapping**: Which local variables point to which nodes
- **Prestate Nodes**: Nodes representing pre-existing objects (mutations here = impure)
- **Mutated Fields**: Which (node, field) pairs were written to

### DOT Graph Color Scheme
- **Green box** (InsideNode): Newly allocated object — mutations are safe
- **Blue ellipse** (ParameterNode): Pre-existing parameter object — mutations mean impure
- **Red diamond** (LoadNode): Object loaded from pre-existing heap — mutations mean impure
- **Orange octagon** (GlobalNode): Static field namespace
- **Solid arrow**: InsideEdge (write/mutation)
- **Dashed arrow**: OutsideEdge (read from pre-existing heap)
- **Red border**: Prestate node that was mutated (impurity source)

### Debug HTML Traces

When `--debug` is used, the tool writes one self-contained HTML file per method to the `debug/` directory. Open any file in a browser to see:

1. **Jimple Body** — The full Jimple IR for the method, with line numbers
2. **Analysis Trace (Key Milestones)** — A visual points-to graph after each significant statement:
   - Identity statements (parameter/this setup)
   - Field loads and stores (`x = y.f`, `x.f = y`)
   - Static field reads and writes
   - Array loads and stores
   - Object allocations (`new T()`)
   - Method invocations
   - Skips trivial statements (local copies, casts, gotos, ifs)
3. **Exit Graph** — The final points-to graph at method exit, rendered visually
4. **Prestate Nodes** — The set of nodes representing pre-existing objects
5. **Set W (Mutated Fields)** — All (node, field) pairs that were written to
6. **Purity Result** — The final verdict (PURE/IMPURE) with reason

Graphs are rendered in the browser using [viz.js](https://github.com/nicknisi/viz.js) (Graphviz compiled to JavaScript) loaded from a CDN — no Graphviz installation required. The same DOT color scheme described above applies: green boxes for inside nodes, blue ellipses for parameter nodes, red diamonds for load nodes, and red borders for impurity sources.

`--debug` automatically implies `--show-graph`, so DOT files and text summaries are also generated alongside the HTML traces.

## How to Extend

### Adding Safe Methods
Edit `SafeMethods.java`. Three categories:
- `SAFE_CONSTRUCTOR_CLASSES`: Classes whose constructors are known pure
- `SAFE_CLASS_PREFIXES`: Classes whose instance methods are known pure (e.g., `java.lang.String`)
- `SAFE_METHOD_SIGNATURES`: Specific method signatures known to be pure

### Adding Transfer Functions
Edit `TransferFunctions.java`. The `apply()` method dispatches on `Stmt` type. Add new cases by pattern matching on Jimple statement types.

### Inter-Procedural Extension
The architecture supports inter-procedural analysis without rewriting:

1. **`MethodSummary`** already stores the exit `PointsToGraph` per method — the exact artifact needed for composition.
2. **`PointsToGraph` uses `ParameterNode`s** as placeholders for caller-provided objects. At a call site, these can be substituted with caller's actual nodes.
3. **`TransferFunctions.handleInvoke()`** is the dispatch point — replace the conservative fallback with callee summary lookup and graph instantiation.

To add inter-procedural support, you would need:
- A `SummaryCache` (`Map<MethodSignature, MethodSummary>`) populated during bottom-up call graph traversal
- A `GraphInstantiator` to map callee `ParameterNode`s to caller argument nodes and merge graphs
- Cycle handling for recursive methods (iterate until summaries stabilize)

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

### Purity Verdict Comparison

| Method | Paper (inter-procedural) | Our Tool (intra-procedural) | Notes |
|---|---|---|---|
| `Point.<init>` | PURE | PURE | Constructor exception for `this.f` writes |
| `Cell.<init>` | PURE | PURE | Constructor exception |
| `ListItr.<init>` | PURE | PURE | Constructor exception |
| `List.<init>` | PURE | PURE | Constructor exception |
| `ListItr.hasNext()` | PURE | PURE | Only reads, no mutations |
| `Point.flip()` | IMPURE | IMPURE | Mutates prestate `this.x`, `this.y` |
| `ListItr.next()` | IMPURE | IMPURE | Mutates prestate `this.cell` |
| `List.add()` | IMPURE | IMPURE | Mutates `this.head`; also conservative on `Cell.<init>` call |
| `List.iterator()` | PURE | **IMPURE** | Conservative: unknown `ListItr.<init>` call |
| `Main.sumX()` | **PURE** | **IMPURE** | Conservative: unknown calls to `iterator()`, `hasNext()`, `next()` |
| `Main.flipAll()` | IMPURE | IMPURE | Both agree; paper provides finer detail via write effects |

The two divergences (`List.iterator` and `Main.sumX`) are expected consequences of intra-procedural analysis. The paper's inter-procedural analysis can determine that `sumX` is pure because it composes method summaries: the iterator returned by `list.iterator()` is an InsideNode, and all mutations by `it.next()` target only that InsideNode (not any prestate node). Our tool lacks this cross-method reasoning and conservatively flags any unknown call as potentially impure.

Adding inter-procedural support (see **How to Extend** above) would resolve both cases by instantiating callee `MethodSummary` graphs at call sites, exactly as described in Section 5.3 of the paper.

## Known Limitations

- **Conservative on unknown calls**: Any call to a method not in `SafeMethods` is treated as potentially impure. This is sound but may produce false positives.
- **No inter-procedural analysis**: User-defined methods called from the analyzed method are treated conservatively. This is the primary source of false positives — methods like `sumX` that are pure (per the paper's inter-procedural analysis) are flagged impure because their callees cannot be analyzed.
- **No exception-path precision**: Exception control flow is handled by SootUp's CFG but not modeled with special precision.
- **Array modeling is simplified**: Array elements are tracked via mutation records but not with per-index precision.

## References

1. A. Salcianu and M. Rinard. *Purity and Side Effect Analysis for Java Programs*. VMCAI 2005.
2. R. Madhavan, G. Ramalingam, and K. Vaswani. *Purity Analysis: An Abstract Interpretation Formulation*. SAS 2011.

