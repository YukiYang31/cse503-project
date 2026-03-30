# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A static analysis tool that determines whether Java methods are **side-effect-free** (i.e., they do not mutate objects that existed before the method was called). Built on **SootUp** and the **Jimple** IR, implementing Salcianu & Rinard (2005) and Madhavan et al. (2011).

**Side-effect-free definition**: A method may allocate and mutate new objects but must not mutate parameters, static fields, or objects reachable through parameters. Constructors are special-cased: direct `this.f = x` writes are allowed.

## Build & Run

```bash
# Build
./gradlew build

# Run on user Java files
./gradlew run --args="MyFile.java"
./gradlew run --args="MyFile.java --show-graph --merge --debug --timing"

# Run on multiple files (for inter-file override analysis)
./gradlew run --args="src/test/resources/interfile/OverrideBase.java src/test/resources/interfile/OverrideDerived.java"

# Run on JDK sources (uses pre-compiled JRT classes)
./gradlew run --args="jdk/src/java.base/share/classes/java/util/ArrayList.java"
```

**CLI flags**: `--show-graph`, `--merge` (Madhavan node merging), `--method <name>` (filter), `--debug` (HTML traces in `debug/`), `--timing`, `--callgraph-timeout <ms>`, `--method-timeout <ms>`

## Running Tests

Test cases live in `src/test/resources/`. There is no automated test runner; tests are run manually:

```bash
# Intraprocedural tests
./gradlew run --args="src/test/resources/testcases/SideEffectFreeMethods.java"

# Interfile override tests
./gradlew run --args="src/test/resources/interfile/OverrideBase.java src/test/resources/interfile/OverrideDerived.java"
```

## JDK Experiment

```bash
python3 experiment/run_experiment.py                    # Full JDK evaluation
python3 experiment/run_experiment.py Objects.java       # Single file
python3 experiment/run_experiment.py --force            # Force rerun from scratch
python3 experiment/run_experiment.py --skip TreeMap.java
```

## Architecture

```
Java source (.java)
  ↓ javac (or JRT for JDK sources)
Bytecode (.class)
  ↓ SootUp/Jimple IR
CallGraphBuilder → bottom-up method order (Tarjan SCC)
  ↓ per-method (bottom-up):
  ├─ SideEffectFlowAnalysis  (forward dataflow, fixed-point)
  ├─ TransferFunctions       (Jimple stmt → graph operations)
  ├─ GraphInstantiator       (callee summary instantiation at call sites)
  ├─ SideEffectChecker       (verdict: SIDE_EFFECT_FREE / SIDE_EFFECTING)
  └─ SummaryCache            (cache for reuse across call sites)
  ↓
ResultPrinter / GraphPrinter / DebugHtmlWriter
```

### Two Execution Modes
- **User code mode**: Compiles `.java` → `.class` via `javax.tools.JavaCompiler` into a temp directory.
- **JRT mode**: For paths matching `jdk/src/...`, loads pre-compiled classes directly from the running JDK module image.

### Core Data Structures

**PointsToGraph** (`G = ⟨I, O, L, E⟩`):
- `I` — Inside (heap write) edges
- `O` — Outside (heap read from pre-existing state) edges
- `L` — Local variable → node mappings
- `E` — Escaped nodes (globally reachable)

**Node types**: `InsideNode` (newly allocated), `ParameterNode` (pre-existing parameter/`this`), `LoadNode` (loaded from pre-existing heap), `GlobalNode` (static field namespace).

**MethodSummary**: Stores exit `PointsToGraph` + `SideEffectResult` + reason string.

### Three-Tier Method Resolution (in TransferFunctions)
1. `SafeMethods` whitelist — known side-effect-free methods
2. `SummaryCache` — previously computed summaries keyed by exact full signature; reachable cached library summaries are preloaded for the current run, and exact library summaries can also be fetched lazily from disk
3. Conservative fallback — assume side-effecting when analysis is impossible

### Key Design Points
- **Inter-file override analysis**: Detects override relationships across input files; propagates verdicts so overriding methods that add side-effects mark the base method as side-effecting.
- **Node merging** (`--merge`): Madhavan et al. optimization — enforces ≤1 outgoing edge per `(node, field, edgeType)` triple, bounding graph size.
- **Graph invariant validation**: Checked before side-effect determination to catch analysis bugs early.
- **Exact full-signature SummaryCache**: Virtual/interface dispatch is handled by call-graph ordering plus base-summary propagation, not by sub-signature lookup at call sites.

## Key Source Files

| File | Role |
|------|------|
| `Main.java` | CLI entry, argument parsing, mode routing |
| `SideEffectAnalysisRunner.java` | Orchestrator: SootUp view, call graph, bottom-up analysis loop, library cache integration |
| `PointsToGraph.java` | Core points-to graph data structure |
| `TransferFunctions.java` | Jimple statement → graph operation mapping; three-tier resolution |
| `SideEffectFlowAnalysis.java` | SootUp forward dataflow framework integration |
| `GraphInstantiator.java` | Callee summary instantiation (Section 5.3 of Salcianu & Rinard) |
| `SideEffectChecker.java` | Final verdict computation from exit graph |
| `CallGraphBuilder.java` | Call graph with method-based uncached-JDK BFS + Tarjan SCC for bottom-up order; BFS caches per-method body traversal results (`BfsBodyCache`) so each body is parsed once; single JRT view shared between BFS and call graph construction |
| `SafeMethods.java` | Whitelist of known side-effect-free library methods |
| `NodeMerger.java` | Madhavan et al. graph bounding optimization |
| `LibrarySummaryCache.java` | Disk-backed cache for library method summaries (`jdk-cache/`) |
| `MethodSummarySerializer.java` | JSON serialization/deserialization for `MethodSummary` + `PointsToGraph` |

## Output Artifacts

- **stdout**: Side-effect verdict table
- `debug/` — Per-method HTML traces with Jimple IR, analysis trace, graph (rendered via viz.js)
- `dot-graph/` — Graphviz DOT files for exit graphs
- `timing/` — JSON timing data per run
