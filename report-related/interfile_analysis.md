# How Inter-File Analysis Works

There are two scenarios depending on whether the callee is in the **same compilation unit** or not.

## Scenario 1: Both files compiled together (Tier 2)

When you run:
```
./gradlew run --args="InterFileHelper.java InterFileMain.java"
```

Both files are compiled into the same class directory. The pipeline is:

1. **All classes loaded** — `view.getClasses()` returns all 6 classes: `Container`, `Pair`, `Reader`, `Mutator`, `Holder`, `WrappedPair`

2. **Call graph built across all classes** — `CallGraphBuilder.computeBottomUpOrder()` sees that `Reader.copyContainer` calls `Container.copy`, so it places `Container.copy` **before** `Reader.copyContainer` in the bottom-up ordering

3. **Callee analyzed first** — `Container.copy()` is analyzed, and its summary (exit graph + return targets) is stored in the `SummaryCache`

4. **Caller hits Tier 2** — When `Reader.copyContainer` encounters the call `c.copy()`, `handleInvoke()` at line 501 does:
   ```java
   MethodSummary calleeSummary = summaryCache.lookup(fullSig, subSig);
   ```
   This **hits** because `Container.copy`'s summary was cached in step 3. The summary is instantiated via `applySummaryToState()` (line 510).

This is **same-file interprocedural** analysis — all classes are in one call graph.

## Scenario 2: Callee in a different file (BFS discovery + complete call graph)

This is what happens during **JDK analysis**. For example, when analyzing `HashSet.java`:

```
./gradlew run --args="jdk/src/java.base/share/classes/java/util/HashSet.java"
```

1. **HashSet's classes loaded** — The runner loads `HashSet` (and its inner classes) from the JRT filesystem as the initial class set.

2. **BFS class discovery** — `CallGraphBuilder.computeBottomUpOrder()` performs a BFS from the initial classes. When it encounters `HashSet.size()` calling `this.map.size()` (which resolves to `HashMap.size()`), it discovers `HashMap` as a reachable class and adds it to the discovered set. The BFS continues transitively from `HashMap`'s methods, discovering further reachable classes, bounded by 200 classes and forbidden package prefixes.

3. **Complete call graph built** — After BFS, the call graph includes both `HashSet` and `HashMap` (and other discovered classes). `HashMap.size()` appears as a callee of `HashSet.size()` in the graph.

4. **Library cache pre-population** — Before analysis begins, cached summaries from `jdk-cache/` are loaded into the `SummaryCache`. If `HashMap.size()` was analyzed in a prior run, its summary is immediately available.

5. **Bottom-up analysis** — Tarjan's SCC produces a bottom-up order where `HashMap.size()` is analyzed before `HashSet.size()`. When `HashSet.size()` is analyzed, `HashMap.size()`'s summary is already in the cache (Tier 2 hit) and is instantiated via `applySummaryToState()`.

6. **Library cache persistence** — After analyzing each library method, its summary is written to `jdk-cache/` for reuse in future runs.

7. **Conservative fallback** — If a method is not discovered by BFS (e.g., in a forbidden package, beyond the 200-class cap, or native), the conservative fallback marks all arguments as globally escaped — the sound safe default.

## How callee bodies are obtained during BFS discovery

During BFS class discovery, the `CallGraphBuilder` resolves callee classes through SootUp's `JavaView`:

1. **Method signature from Jimple IR** — Each invoke statement carries the full `MethodSignature` (class type + name + parameter types + return type) encoded in the bytecode.
2. **Class resolved from JavaView** — `view.getClass(classType)` lazily loads the class. In JRT mode, this loads from the JDK module image (`jrt:/`).
3. **Method found by sub-signature** — `class.getMethod(subSignature)` searches the class's method table. Native or abstract methods (`!isConcrete()`) are skipped.
4. **Body decompiled lazily** — `method.getBody()` triggers SootUp's lazy Jimple body construction from bytecode.

## Which JDK methods can BFS discover?

The BFS uses a separate JRT `JavaView` to resolve JDK classes. Every class in every JDK module (`java.base`, `java.util`, `java.io`, `java.lang`, etc.) is available.

**Limits on BFS scope:**
- **Forbidden packages**: `sun.*`, `com.sun.*`, `jdk.internal.*`, `java.awt.*`, `javax.swing.*`, `java.nio.*`, `java.security.*`, `javax.crypto.*`, `java.lang.invoke.*`, `java.lang.reflect.*`, `java.util.concurrent.*` — implementation internals and frameworks with limited analysis value
- **Class cap**: Maximum 200 discovered classes to prevent explosion
- **SafeMethods/LibrarySummaryCache**: Methods already known to be safe or already cached are not traversed further
- **Native/abstract methods**: No bytecode body, cannot be analyzed

In normal (non-JRT) mode, the `CallGraphBuilder` creates a JRT view lazily for BFS discovery, so JDK methods are still discoverable even when the main view only covers user-compiled classes.

## Scope for user-defined inter-file calls

BFS discovery helps with JDK classes but does **not** help when user-written file1 calls user-written file2 but only file1 was passed as input.

**Why**: in normal mode, the user `JavaView` only covers compiled output of the files explicitly passed as args. If file2 was not passed, it was never compiled and is not in the class directory. The BFS can only discover JDK classes via the JRT view, not uncompiled user classes.

**Consequence**: to get proper inter-procedural analysis across user-defined files, **all files must be passed together as args**. When both files are compiled together, both are loaded by `view.getClasses()`, the call graph covers both, and callees are analyzed bottom-up (Tier 2 hit).
