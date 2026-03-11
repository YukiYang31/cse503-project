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

## How the callee's body is obtained (the 4-step chain)

When `analyzeExternalMethod` resolves a callee, the body arrives through four sequential steps:

**Step 1: `methodSig` comes directly from the Jimple IR (line 483)**
```java
MethodSignature methodSig = invokeExpr.getMethodSignature();
```
Jimple's `AbstractInvokeExpr` (e.g., a `JVirtualInvokeExpr` for `this.map.size()`) already carries the full `MethodSignature` — class type + name + parameter types + return type — encoded in the bytecode. No resolution is needed at this point; it is just reading what the compiled `.class` file says.

**Step 2: Class resolved from `JavaView` (lines 620–621)**
```java
ClassType classType = methodSig.getDeclClassType();
Optional<JavaSootClass> classOpt = view.getClass(classType);
```
`JavaView` is SootUp's lazy class loader. In JRT mode it is backed by the JDK module image (`jrt:/`). `view.getClass(classType)` does a lookup by class name — if `HashMap` has not been loaded yet, it reads and parses it from the JRT right here on demand. If the class is not found (e.g., the view is backed by a user class directory and the class was never compiled), it returns empty and the whole method returns `null`.

**Step 3: Method found by sub-signature (line 624)**
```java
Optional<? extends JavaSootMethod> methodOpt = classOpt.get().getMethod(methodSig.getSubSignature());
if (methodOpt.isEmpty() || !methodOpt.get().isConcrete()) return null;
```
Sub-signature is name + parameter types with no class prefix (e.g., `size()`). `getMethod` searches the class's method table for a match. If the method is abstract or native (`isConcrete()` is false), it returns `null` and falls back to Tier 4. Native methods like `Object.hashCode()` or `System.arraycopy()` are implemented in C/C++ with no bytecode, so they always fail this check.

**Step 4: Body decompiled/parsed lazily (line 631)**
```java
Body body = method.getBody();
StmtGraph<?> cfg = body.getStmtGraph();
```
`getBody()` triggers SootUp's lazy Jimple body construction — it decompiles the bytecode for that method into Jimple IR on the fly. The `StmtGraph` (CFG) is then extracted and handed directly to a new `SideEffectFlowAnalysis` instance.

The full chain: **Jimple IR gives the signature for free → `JavaView` loads the class from JRT → sub-signature lookup finds the method → `getBody()` decompiles it**. The `JavaView` is the key piece — it acts as the cross-file class loader that makes all of this possible without pre-compiling everything upfront.

## Which JDK methods can Tier 3 reach?

In JRT mode, `JrtFileSystemAnalysisInputLocation` points the `JavaView` at the full running JDK module image. This is the same image the JVM itself boots from — every class in every JDK module (`java.base`, `java.util`, `java.io`, `java.lang`, etc.) is available. So `view.getClass(classType)` can resolve essentially any JDK class name.

The only hard limit is **native methods**. Any method where `isConcrete()` is false (abstract or native) returns `null` at line 625 and falls through to Tier 4. Methods like `Object.hashCode()`, `System.arraycopy()`, `Thread.currentThread()` have no bytecode and can never have a body retrieved.

In normal (non-JRT) mode the view only covers user-compiled classes, so Tier 3 cannot reach any JDK method at all (see next section).

## The three guards preventing blowup

Without guards, on-demand analysis could spiral out of control (method A calls B calls C calls D...). Three mechanisms prevent this:

| Guard | Constant | Purpose |
|---|---|---|
| **Depth** | `analyzing` set, max 5 | Prevents deep recursive call chains |
| **Budget** | `onDemandBudget[0]`, max 10 per top-level method | Limits total on-demand analyses so one expensive method doesn't starve others |
| **Graph size** | 20 nodes | Discards overly complex summaries that would cause O(n²) instantiation |

All three are shared across the recursive call chain (passed by reference), and the budget is reset per top-level method in the outer analysis loop.

## Tier 3 scope: JRT only, not user-defined inter-file calls

Tier 3 on-demand analysis only works for JDK/JRT classes. It does **not** help when user-written file1 calls user-written file2 but only file1 was passed as input.

**Why**: in normal (non-JRT) mode, the `JavaView` is backed by `JavaClassPathAnalysisInputLocation` pointing at a temporary directory containing only the compiled output of the files explicitly passed as args (Main.java line 94: `JavaCompiler.compile(sourceFiles)`). If file2 was not passed, it was never compiled and is not in that directory. So `view.getClass("File2Class")` returns empty → `analyzeExternalMethod` returns `null` → Tier 4 conservative escape.

**In JRT mode**: `JrtFileSystemAnalysisInputLocation` points the view at the full running JDK module image (`jrt:/`). Every concrete (non-native) JDK class across all modules is resolvable on demand, which is why Tier 3 is effective for JDK analysis.

**Consequence for user code**: to get proper inter-procedural analysis across user-defined files, **all files must be passed together as args**. When both files are compiled together they are both in the class directory, both loaded by `view.getClasses()`, the call graph covers both, and the callee is analyzed first (Tier 2) — no on-demand lookup needed. Passing only file1 means any call into file2 silently falls back to conservative (all arguments globally escaped).

## How the depth limit controls recursive Tier 3 calls

The depth limit is what directly caps the recursive on-demand chain described in step 5 above. The `analyzing` field is a `Set<String>` of method signatures **currently being on-demand analyzed**, not a simple integer counter. Its `.size()` is the current depth.

The check at line 617:
```java
if (analyzing.size() >= MAX_ON_DEMAND_DEPTH) return null;  // depth limit
```

Walk through an example — `HashSet.size()` → `HashMap.size()` → some further external call:

1. `analyzeExternalMethod("HashMap.size()")` is called. `analyzing.size() == 0`, so the check passes.
2. `analyzing.add("HashMap.size()")` — set size becomes 1 (line 628).
3. A new `SideEffectFlowAnalysis` is created for `HashMap.size()`, and **the same `analyzing` set and `onDemandBudget` array are passed into it** (line 643) — they are shared by reference across the entire recursive stack.
4. If `HashMap.size()` calls another external method, `analyzeExternalMethod` is invoked again. It checks `analyzing.size() == 1` — still below 5, so it proceeds.
5. This continues until the set holds 5 signatures. At that point the check fires, returns `null`, and the caller falls back to Tier 4 (conservative escape).
6. As each level finishes (or throws), `analyzing.remove(sig)` (line 662) shrinks the set back, keeping the guard accurate.

The set also serves a second purpose at line 616:
```java
if (analyzing.contains(sig)) return null;
```
This catches **cycles** — if a method eventually calls itself back through the on-demand chain, it is already in the set and is immediately rejected, preventing infinite recursion independently of the depth counter.

In short: the depth limit (`analyzing.size() >= 5`) limits how many Tier 3 hops deep the recursive on-demand analysis can go, while the membership check (`analyzing.contains(sig)`) handles cycles. Both operate on the same shared set.
