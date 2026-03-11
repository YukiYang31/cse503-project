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

## Scenario 2: Callee in a different file (Tier 3 — the actual inter-file path)

This is what happens during **JDK analysis**. For example, when analyzing `HashSet.java`:

```
./gradlew run --args="jdk/src/java.base/share/classes/java/util/HashSet.java"
```

1. **Only HashSet's classes loaded** — The runner loads only `HashSet` (and its inner classes) from the JRT filesystem. `HashMap` is NOT in the loaded set.

2. **Call graph built for HashSet only** — `HashMap.size()` is not in the call graph because `HashMap` was never loaded as a target class.

3. **Tier 2 misses** — When `HashSet.size()` calls `this.map.size()` (which is `HashMap.size()`), the summary cache lookup at line 501 returns `null` because `HashMap.size()` was never analyzed.

4. **Tier 3 kicks in** — Lines 503–506:
   ```java
   if (calleeSummary == null && view != null) {
       calleeSummary = analyzeExternalMethod(methodSig);
   }
   ```

5. **`analyzeExternalMethod`** (line 614) does the following:
   - **Guard checks**: recursion (`analyzing` set), depth limit (5), budget (10 per top-level method)
   - **Resolve from JavaView**: `view.getClass(classType)` finds `HashMap` from the JRT filesystem (line 621)
   - **Find the method**: `classOpt.get().getMethod(subSig)` finds `HashMap.size()` (line 624)
   - **Analyze on the fly**: Creates a lightweight `SideEffectFlowAnalysis` (no debug, no timing) on the callee's Jimple body (lines 641–643). This analysis itself can trigger further Tier 3 calls recursively (e.g., if `HashMap.size()` calls another external method).
   - **Graph size guard**: If the exit graph exceeds 20 nodes, discard it (line 650) — prevents instantiation explosion
   - **Cache the result**: `summaryCache.put(sig, ...)` (line 657) — so the next call to `HashMap.size()` from anywhere hits Tier 2 instead

6. **Summary instantiated** — Back in `handleInvoke`, the on-demand summary is applied via `applySummaryToState()` (line 510), exactly the same as Tier 2.

7. **Tier 4 fallback** — If `analyzeExternalMethod` returns `null` (method not found, native, depth/budget exhausted, graph too large), lines 514–539 mark all arguments as globally escaped — the conservative safe default.

## The three guards preventing blowup

Without guards, on-demand analysis could spiral out of control (method A calls B calls C calls D...). Three mechanisms prevent this:

| Guard | Constant | Purpose |
|---|---|---|
| **Depth** | `analyzing` set, max 5 | Prevents deep recursive call chains |
| **Budget** | `onDemandBudget[0]`, max 10 per top-level method | Limits total on-demand analyses so one expensive method doesn't starve others |
| **Graph size** | 20 nodes | Discards overly complex summaries that would cause O(n²) instantiation |

All three are shared across the recursive call chain (passed by reference), and the budget is reset per top-level method in the outer analysis loop.
