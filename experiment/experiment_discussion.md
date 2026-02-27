# Experiment Discussion: `NOT_ANALYZED` Methods

## Overview

In our experiment results, a significant number of methods receive the verdict `NOT_ANALYZED`. This label is **not** a Java-side verdict — the tool's `SideEffectResult` enum only has three possible outcomes:

- `SIDE_EFFECT_FREE`
- `SIDE_EFFECTING`
- `GRAPH_VIOLATION`

`NOT_ANALYZED` is assigned by the Python experiment script (`run_experiment.py`) when a method present in the ground truth (JDK annotations) is **absent from the tool's timing JSON output**.

---

## Causes of `NOT_ANALYZED`

### 1. Abstract / Interface Methods (`!method.isConcrete()`)

**Reason in CSV:** `abstract/interface method`

Both the analysis runner and the call graph builder skip non-concrete methods:

```java
if (!method.isConcrete()) continue;
```

Abstract methods and interface method declarations have no bytecode body, so there is nothing to analyze. This is expected and correct behavior.

**Examples:**
- `AbstractCollection.iterator()` — abstract
- `AbstractCollection.size()` — abstract
- `AbstractList.get()` — abstract
- `AbstractMap.entrySet()` — abstract

---

### 2. Inner Class Methods Not Loaded in JRT Mode ⭐ (Biggest Culprit)

**Reason in CSV:** `not in tool output`

When the tool processes a `.java` file, it resolves classes by constructing the fully-qualified class name (FQCN) from the file path. For example:

```
jdk/src/java.base/share/classes/java/util/HashMap.java → java.util.HashMap
```

However, **inner classes** like `HashMap$Node`, `HashMap$TreeNode`, `HashMap$KeySet`, `HashMap$EntrySet`, `AbstractList$Itr`, `AbstractList$SubList`, `AbstractMap$SimpleEntry`, etc. are compiled into separate `.class` files but share the same `.java` source file. Since the tool only resolves the **top-level class FQCN**, inner class methods are never loaded and are completely invisible to the analysis.

This is the **primary cause** of the `not in tool output` entries in the CSV. For example, in the `HashMap.java` timing output, only `java.util.HashMap` methods appear — none from `HashMap$Node`, `HashMap$KeySet`, `HashMap$Values`, `HashMap$EntrySet`, or any other inner class.

**Examples:**
- `AbstractList$Itr.hasNext()` — inner class not loaded
- `AbstractList$SubList.size()` — inner class not loaded
- `AbstractMap$SimpleEntry.getKey()` — inner class not loaded
- `AbstractMap$SimpleEntry.getValue()` — inner class not loaded
- `AbstractMap$SimpleImmutableEntry.equals()` — inner class not loaded
- `HashMap$Node.*`, `HashMap$TreeNode.*`, `HashMap$KeySet.*` — inner classes not loaded

---

### 3. Exception During Analysis

**Reason in CSV:** `not in tool output`

If `analyzeSCC()` throws an exception (e.g., SootUp fails to retrieve the method body, encounters unsupported bytecode), the method is caught and logged to stderr, but `null` is returned. The method silently disappears from the output with no timing entry.

```java
catch (Exception e) {
    System.err.println("Error analyzing " + method.getName() + ": " + e.getMessage());
    e.printStackTrace();
    return null;
}
```

---

### 4. `--method` Filter Active

**Reason in CSV:** `not in tool output`

When a `methodFilter` is passed via the CLI (`--method <name>`), only the method with that exact name is analyzed. All others are silently skipped. This does **not** apply during the experiment (which runs without `--method`), but could cause confusion in manual runs.

---

### 5. Signature Mismatch Between Ground Truth and Tool Output

**Reason in CSV:** `not in tool output`

The Python script's `parse_sootup_signature()` builds a canonical key to match tool output against ground truth annotations. If the key formats don't align (e.g., due to generic type erasure differences, or the annotation extractor producing a different parameter format than SootUp), a method that **was** actually analyzed may fail to match and incorrectly appear as `NOT_ANALYZED`.

---

## Summary

| Cause | CSV Reason | Frequency | Avoidable? |
|---|---|---|---|
| Abstract/interface method | `abstract/interface method` | Common | No (expected) |
| Inner classes not loaded (JRT mode) | `not in tool output` | **Very common** | Yes — resolve inner classes from `.class` files |
| Exception during analysis | `not in tool output` | Rare | Partially — improve error handling |
| `--method` filter active | `not in tool output` | N/A in experiment | N/A |
| Signature key mismatch | `not in tool output` | Uncommon | Yes — normalize key formats |

### Key Takeaway

The most impactful gap is the **inner class resolution** issue. A large number of JDK-annotated methods live in inner classes (iterators, sub-lists, entry sets, map entries, etc.) that are never loaded by the tool's JRT class resolution logic. Addressing this would significantly reduce the `NOT_ANALYZED` count in the experiment results.

---

# False Positive Case Study: `HashSet.size()`

## Verdict

The tool reports: **`SIDE_EFFECTING (loaded object 'load map from parameter this' escapes to global scope)`**

This is a **false positive** — `HashSet.size()` is genuinely side-effect-free.

## What the Method Actually Does

The JDK source (`HashSet.java`, lines 189–193):

```java
public int size() {
    return map.size();
}
```

It reads `this.map` (a `HashMap` field) and delegates to `HashMap.size()`, which itself just returns the primitive `int` field `this.size`. No mutations anywhere.

## Jimple IR

```
this := @this: java.util.HashSet              // P0 = this
$stack1 = this.<java.util.HashSet: java.util.HashMap map>   // L0 = this.map (outside edge read)
$stack2 = virtualinvoke $stack1.<java.util.HashMap: int size()>()   // call size() on L0
return $stack2
```

## Exit Graph

```
Nodes:  P0 (this: HashSet), L0 (load map from parameter this)
O:      P0 --map--> L0
I:      (none)
E:      {L0}       ← L0 is globally escaped
W:      {}         ← no mutations
```

## Why the Tool Gets It Wrong

The root cause is a **class hierarchy mismatch** in `SafeMethods.isSafe()`:

1. `SafeMethods.java` registers `java.util.Map#size` as a safe method.
2. But the Jimple bytecode call is `virtualinvoke $stack1.<java.util.HashMap: int size()>()` — the **concrete class** `HashMap`, not the interface `Map`.
3. `isSafe()` does **exact class name matching**: it constructs the key `"java.util.HashMap#size"` and looks it up — but only `"java.util.Map#size"` is in the set. **No match.**
4. The summary cache also has no entry for `HashMap.size()` because `HashMap` is in a **different file** than `HashSet` (inter-procedural scope is same-file only).
5. So the **conservative fallback** in `TransferFunctions.handleInvoke()` kicks in: all reference-type arguments (including the receiver `$stack1` → `L0`) are marked as **globally escaped**.
6. In the side-effect check, `L0` is a prestate node (LoadNode reachable from `P0` via outside edge) **and** is in set B (globally escaped) → **SIDE_EFFECTING**.

## The Chain of Events

```
HashSet.size()
  → reads this.map → creates LoadNode L0
  → calls L0.size() → resolves as java.util.HashMap.size()
  → SafeMethods lookup: "java.util.HashMap#size" → NOT FOUND
    (only "java.util.Map#size" is registered)
  → SummaryCache lookup: HashMap.size() → NOT FOUND
    (HashMap is in a different file, no inter-procedural summary)
  → Conservative fallback: mark L0 as globally escaped
  → SideEffectChecker: L0 ∈ prestate ∩ globally escaped → SIDE_EFFECTING
```

## Root Cause

**`SafeMethods.isSafe()` doesn't walk the class/interface hierarchy.** It has `java.util.Map#size` registered, but the bytecode call target is `java.util.HashMap.size()`. Since `HashMap implements Map`, `HashMap#size` should inherit the safety of `Map#size` — but the tool doesn't check supertypes or implemented interfaces.

## Impact

This same issue affects **any** safe method registered under an interface or parent class name when the Jimple bytecode uses the concrete subclass name. This is very common because:
- JDK classes frequently delegate to fields typed as concrete classes (e.g., `HashMap`, `ArrayList`)
- The bytecode reflects the declared type of the receiver at the call site, not the interface

Examples of methods likely affected by the same bug pattern:
- `HashSet.isEmpty()` → calls `HashMap.isEmpty()` (registered as `Map#isEmpty`)
- `HashSet.contains()` → calls `HashMap.containsKey()` (registered as `Map#containsKey`)
- Any class delegating to `Collection#size`, `List#get`, etc. via a concrete type

## Possible Fix

Modify `SafeMethods.isSafe()` to walk the class hierarchy using SootUp's type resolver, or alternatively, register safe methods under all known concrete implementations (e.g., add `java.util.HashMap#size`, `java.util.ArrayList#size`, etc. alongside the interface entries).
