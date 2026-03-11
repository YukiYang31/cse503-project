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
./gradlew run --args="jdk/src/java.base/share/classes/java/io/File.java"

# Analyze a specific method
./gradlew run --args="MyFile.java --method myMethod"
./gradlew run --args="jdk/src/java.base/share/classes/java/lang/String.java --method indexOf"

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
./gradlew run --args="jdk/src/java.base/share/classes/java/lang/Long.java --timing"

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

> **JDK source files**: When analyzing JDK sources (paths matching `jdk/src/<module>/share/classes/...`), the Compilation phase is skipped entirely. Instead, the IR Loading phase uses `JrtFileSystemAnalysisInputLocation` to load pre-compiled classes directly from the running JDK's module image (`jrt:/` filesystem). This avoids the impossible task of compiling JDK internals standalone.

## JDK Experiment

The `experiment/` directory contains scripts to evaluate the tool against the Checker Framework's manual `@Pure` and `@SideEffectFree` annotations in the JDK's `java.util` package (~870 annotated methods across 67 files). The JDK with annotations can be cloned from https://github.com/typetools/jdk. Users need to clone the JDK into the top directory for experiments to run. 

### Running the Experiment

```bash
# Full run: analyze all java/util files and produce results CSV
# Automatically resumes from where it left off if interrupted
python3 experiment/run_experiment.py

# Re-run a single file (always re-runs, ignores cache; merges into full CSV)
python3 experiment/run_experiment.py Objects.java

# Re-generate CSV from previously saved results (no tool re-run)
python3 experiment/run_experiment.py --skip-run

# Force re-run all files from scratch (ignore cached results)
# Saves to a timestamped CSV (e.g. results_20260226_143022.csv) to preserve the existing results.csv
python3 experiment/run_experiment.py --force

# Skip specific files that are too slow or hang
python3 experiment/run_experiment.py --skip TreeMap.java
python3 experiment/run_experiment.py --skip TreeMap.java,HashMap.java
```

The script saves per-file results to `experiment/tool_results/` as it goes. On re-run, it detects which files already have cached results and skips them, so you can safely `Ctrl+C` and resume later without losing progress.

### What It Does

1. **Extract ground truth** (`extract_annotations.py`): Parses `@Pure`/`@SideEffectFree` annotations from JDK source files, handling inner classes, receiver parameters, generic erasure, and abstract methods. Outputs `experiment/ground_truth.json`.

2. **Run the tool**: Executes the side-effect analysis on each `.java` file one at a time via `./gradlew run --args="<file> --timing"`. Tool results (timing JSON with verdicts) are saved to `experiment/tool_results/` for reproducibility.

3. **Produce CSV**: Each row is one method with columns for JDK annotation, tool verdict, match category, per-method timing, and per-file pipeline timing. Written to `experiment/results.csv` by default; when `--force` is used, written to a timestamped file (e.g. `experiment/results_20260226_143022.csv`) so the existing `results.csv` is not overwritten.

### Match Categories

| Category | Meaning |
|---|---|
| Match | JDK annotated + tool says SIDE_EFFECT_FREE |
| Tool False Positive | JDK annotated + tool says SIDE_EFFECTING/GRAPH_VIOLATION |
| Annotation Deficit | Not annotated + tool says SIDE_EFFECT_FREE (in a file that has other annotations) |
| File Not Annotated | Tool says SIDE_EFFECT_FREE but the entire file has no annotations |
| Both Side-Effecting | Not annotated + tool says SIDE_EFFECTING |
| Not Analyzed | Annotated but tool didn't analyze (abstract method, error) |

### Output Files

| File | Description |
|---|---|
| `experiment/ground_truth.json` | Parsed annotations from JDK source |
| `experiment/tool_results/*.json` | Per-file timing JSON with verdicts |
| `experiment/results.csv` | Combined results CSV (default output) |
| `experiment/results_<timestamp>.csv` | Timestamped results CSV produced when `--force` is used |

## Known Limitations

- **JDK analysis uses runtime bytecode**: When analyzing JDK source files, classes are loaded from the JDK runtime image rather than compiling from source. The analysis results reflect the compiled bytecode, which may differ slightly from source-level expectations (e.g., compiler-generated bridge methods, synthetic fields).
- **On-demand cross-file analysis has bounded scope**: Cross-file callees are analyzed on demand, but depth (default 5), per-method budget (default 10), and graph size (default 20 nodes) limits mean deeply nested or complex JDK call chains may still fall back to conservative. Methods whose exit graph exceeds the size limit are not cached, so their callers also fall back to conservative.
- **On-demand inter-file analysis only covers JDK classes, not user-defined files**: When analyzing user code, the `JavaView` is backed only by the compiled output of the files explicitly passed as arguments. If file1 calls file2 but only file1 is given as input, file2 is never compiled or loaded — `view.getClass()` returns empty and the call falls back to conservative (all arguments globally escaped). To get proper inter-procedural analysis across user-defined files, all files must be passed together as arguments.
- **Recursive call chains use bounded iteration**: Mutually recursive methods (SCCs in the call graph) are analyzed by iterating up to 5 times. If summaries do not stabilize, the last computed summary is used. The paper suggests iterating to a true fixed point; we cap at 5 for practical reasons.
- **No exception-path precision**: Exception control flow is handled by SootUp's CFG but not modeled with special precision.
- **Array modeling is simplified**: Array elements are tracked via mutation records but not with per-index precision.

## References

1. A. Salcianu and M. Rinard. *Purity and Side Effect Analysis for Java Programs*. VMCAI 2005.
2. R. Madhavan, G. Ramalingam, and K. Vaswani. *Purity Analysis: An Abstract Interpretation Formulation*. SAS 2011.

## Further Reading

See [CODE_OUTLINE.md](CODE_OUTLINE.md) for detailed code structure, design decisions, and how to extend the tool.
