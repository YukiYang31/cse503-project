# Pseudocode for Side-Effect Analysis

The analysis determines whether a Java method is **side-effect-free** — i.e., it does not mutate any object that existed before the method was called. It builds on the combined pointer and escape analysis of Salcianu & Rinard (VMCAI 2005), with optional node merging from Madhavan et al. (SAS 2011).

---

## 1. Overall Analysis Pipeline

The top-level algorithm compiles source to Jimple IR, computes a bottom-up method ordering, analyzes each method intraprocedurally while composing callee summaries interprocedurally, and issues a verdict per method. Cross-file callees are resolved on demand via the `view` (a handle to the full JDK runtime or compiled classpath).

```
Algorithm: SideEffectAnalysis(sourceFiles)

Input:  Set of Java source files
Output: For each method m, a verdict SIDE_EFFECT_FREE or SIDE_EFFECTING(reason)

1.  Compile sourceFiles to bytecode; load into Jimple IR via SootUp
2.  view    <- JavaView over the class directory (or JDK runtime image)
3.  methods <- all concrete methods from loaded classes
4.  batches <- ComputeBottomUpOrder(methods)          // Algorithm 2
5.  cache   <- empty summary cache
6.  analyzing <- empty set (shared recursion guard for on-demand analysis)
7.  budget    <- [MAX_ON_DEMAND_TOTAL]  // mutable counter, reset per top-level method

8.  for each batch in batches do                      // leaves-first order
9.      if |batch| = 1 then
10.         m <- the single method in batch
11.         budget[0] <- MAX_ON_DEMAND_TOTAL           // reset budget per method
12.         summary <- AnalyzeMethod(m, cache, view, analyzing, budget)  // Algorithm 3
13.         cache.put(m, summary)
14.     else                                           // SCC: mutually recursive
15.         repeat up to K times                       // K = 5
16.             changed <- false
17.             for each method m in batch do
18.                 budget[0] <- MAX_ON_DEMAND_TOTAL
19.                 summary <- AnalyzeMethod(m, cache, view, analyzing, budget)
20.                 if summary differs from cache.get(m) then
21.                     cache.put(m, summary)
22.                     changed <- true
23.             until not changed
24.
25.     for each method m in batch do
26.         verdict <- CheckSideEffects(cache.get(m))  // Algorithm 8
27.         output verdict for m
```

**Constants:**
- `MAX_ON_DEMAND_DEPTH = 5` — maximum depth of recursive on-demand analysis (size of `analyzing` set)
- `MAX_ON_DEMAND_TOTAL = 10` — maximum number of on-demand analyses per top-level method
- `MAX_ON_DEMAND_GRAPH_SIZE = 20` — if an on-demand callee's exit graph exceeds this many nodes, discard the result (prevents instantiation explosion)

---

## 2. Bottom-Up Method Ordering via Tarjan's SCC

Methods are ordered so that callees are analyzed before callers. Mutually recursive methods form a strongly connected component (SCC) and are analyzed together.

```
Algorithm: ComputeBottomUpOrder(methods)

Input:  Set of all concrete methods
Output: List of batches (each batch is a list of methods),
        ordered so callees appear before callers

1.  // Build call graph
2.  for each method m in methods do
3.      for each invoke statement s in body(m) do
4.          target <- resolve(s)          // exact signature, then sub-signature
5.          if target in methods then
6.              addEdge(m -> target)

7.  // Tarjan's SCC algorithm produces SCCs in reverse topological order
8.  batches <- TarjanSCC(methods, edges)
9.  return batches                        // first batch = leaves (no outgoing calls)
```

---

## 3. Intraprocedural Analysis (Forward Dataflow)

Each method is analyzed by a forward dataflow analysis over its control-flow graph. The lattice element is a points-to graph **G = (I, O, L, E, W)**, and the join operator is set union over all components.

```
Algorithm: AnalyzeMethod(m, cache, view, analyzing, budget)

Input:  Method m with Jimple CFG, summary cache,
        JavaView view (for on-demand cross-file resolution),
        analyzing set (recursion guard), budget counter
Output: MethodSummary(m) containing the exit graph and verdict

1.  G_init <- empty PointsToGraph         // I = O = L = E = W = empty
2.  transfer <- TransferFunctions(cache, view, analyzing, budget)

3.  // Standard forward dataflow fixed-point iteration
4.  for each statement s in CFG(m) in forward order do
5.      G_in <- join of G_out for all predecessors of s
6.      G_out <- copy(G_in)
7.      Apply(s, G_out, transfer)          // Algorithm 4

8.  // repeat steps 4-7 until no G_out changes (fixed point)

9.  G_exit <- join of G_out for all tail statements of CFG(m)
10. verdict <- CheckSideEffects(m, G_exit) // Algorithm 8
11. return MethodSummary(m, G_exit, verdict)
```

The **join** operator merges two graphs by taking the union of each component:

```
Join(G1, G2):
    I <- I_1 U I_2
    O <- O_1 U O_2
    L <- for each variable v: L(v) <- L_1(v) U L_2(v)
    E <- E_1 U E_2
    W <- W_1 U W_2
```

---

## 4. Transfer Functions

Each Jimple statement updates the points-to graph according to the following rules. We write `pt(v)` for the set of nodes that variable `v` may point to in the current graph, and use `strongUpdate(v, S)` to mean replacing `pt(v)` with set `S`.

### 4a. Identity (Parameter Binding)

```
Apply(v := @this, G):
    strongUpdate(v, { ParameterNode(0) })

Apply(v := @parameter_k, G):
    strongUpdate(v, { ParameterNode(k + offset) })
    // offset = 1 for instance methods (P0 is 'this'), 0 for static methods
```

### 4b. Object Allocation

```
Apply(v = new T, G):
    n <- fresh InsideNode
    strongUpdate(v, { n })
```

### 4c. Copy and Cast

```
Apply(v = w, G):             // where w is a local variable of reference type
    strongUpdate(v, pt(w))

Apply(v = (T) w, G):
    strongUpdate(v, pt(w))
```

### 4d. Field Load

A field load may introduce **outside edges** when reading from a pre-existing (prestate) object, since the analysis has no information about the object's fields at method entry.

```
Apply(v = w.f, G):
    targets <- {}
    for each node n in pt(w) do
        // Collect existing inside-edge targets
        targets <- targets U InsideEdgeTargets(n, f)
        // If n is prestate-reachable, add an outside edge to a fresh LoadNode
        if IsPrestateReachable(n) then
            nL <- fresh LoadNode
            addOutsideEdge(n, f, nL)
            targets <- targets U { nL }
    strongUpdate(v, targets)

IsPrestateReachable(n):
    return n is ParameterNode or LoadNode or GlobalNode
```

### 4e. Field Store

Field stores are **weak updates** (the new edge is added; existing edges are kept). This is sound because multiple allocation sites may alias the same variable.

```
Apply(w.f = v, G):
    for each node n_base in pt(w) do
        for each node n_rhs in pt(v) do
            addInsideEdge(n_base, f, n_rhs)
        recordMutation(n_base, f)           // add (n_base, f) to W
```

### 4f. Static Field Access

Static fields are modeled through a distinguished **GlobalNode**.

```
Apply(v = T.f, G):               // static field load
    nL <- fresh LoadNode
    addOutsideEdge(GlobalNode, f, nL)
    strongUpdate(v, { nL })

Apply(T.f = v, G):               // static field store
    for each node n in pt(v) do
        markGlobalEscaped(n)      // add n to set E
    recordMutation(GlobalNode, f) // add (GlobalNode, f) to W
```

### 4g. Array Access

Arrays are modeled with a single abstract field (no index sensitivity).

```
Apply(v = w[i], G):
    // treated as field load with field = [] (abstract array element)
    targets <- {}
    for each node n in pt(w) do
        targets <- targets U InsideEdgeTargets(n, [])
        if IsPrestateReachable(n) then
            nL <- fresh LoadNode
            addOutsideEdge(n, [], nL)
            targets <- targets U { nL }
    strongUpdate(v, targets)

Apply(w[i] = v, G):
    for each node n in pt(w) do
        recordMutation(n, [])
```

### 4h. Return Statement

```
Apply(return v, G):
    addReturnTargets(pt(v))       // record what the return value may point to
```

### 4i. Method Invocation

Method invocations are handled in four tiers. Each tier is tried in order; the first one that succeeds handles the call.

```
Apply(v = w.foo(a1, ..., ak), G):

    sig <- resolve method signature of foo

    // Tier 1: Safe method whitelist
    if IsSafeMethod(sig) then
        // Known side-effect-free library method (e.g., Object.hashCode, String.length)
        if v has reference type then
            n <- fresh InsideNode
            strongUpdate(v, { n })
        return

    // Tier 2: Inter-procedural summary cache (same-file callees)
    calleeSummary <- cache.lookup(sig)
    if calleeSummary != null then
        InstantiateSummary(G, calleeSummary, [w, a1, ..., ak], v)  // Algorithm 5
        return

    // Tier 3: On-demand cross-file analysis
    if view != null then
        calleeSummary <- AnalyzeExternalMethod(sig, view, cache, analyzing, budget)  // Algorithm 7
        if calleeSummary != null then
            InstantiateSummary(G, calleeSummary, [w, a1, ..., ak], v)
            return

    // Tier 4: Conservative fallback — assume the worst
    for each reference-type argument a in [w, a1, ..., ak] do
        for each node n in pt(a) do
            markGlobalEscaped(n)
    if v has reference type then
        strongUpdate(v, { GlobalNode })
```

---

## 5. Interprocedural Summary Instantiation

When a callee's summary is available, it is composed into the caller's graph. This is the core of the interprocedural analysis, implementing Section 5.3 of Salcianu & Rinard.

```
Algorithm: InstantiateSummary(G_caller, summary_callee, actualArgs, returnVar)

Input:  Caller's current graph G_caller,
        callee's exit-graph summary G_callee = (I_c, O_c, L_c, E_c, W_c),
        actual arguments [a0, ..., ak],
        variable to receive return value (may be null)
Output: G_caller updated in place

// --- Step 0: Remap callee node IDs to fresh caller-namespace IDs ---
//     (InsideNodes and LoadNodes get fresh IDs; ParameterNodes are not remapped;
//      GlobalNode maps to itself)

for each InsideNode n in G_callee do
    remap(n) <- fresh InsideNode in G_caller
for each LoadNode n in G_callee do
    remap(n) <- fresh LoadNode in G_caller
Apply remap to all edges, return targets, E_c, and W_c in G_callee

// --- Step 1: Compute node mapping mu (least fixed point) ---

Initialize mu:
    for i = 0, ..., k do
        mu(P_i) <- pt_caller(actualArgs[i])     // Constraint 1
    for all other callee nodes n do
        mu(n) <- {}

repeat until mu stabilizes:
    // Constraint 2: callee outside edge meets caller inside edge
    for each outside edge (n1 --f--> n2) in O_c do
        for each node n3 in mu(n1) do
            for each node n4 in InsideEdgeTargets_caller(n3, f) do
                mu(n2) <- mu(n2) U { n4 }

    // Constraint 3: callee outside edge meets callee inside edge (aliasing)
    for each outside edge (n1 --f--> n2) in O_c do
        for each inside edge (n3 --f--> n4) in I_c do
            S1 <- mu(n1) U ({n1} \ ParameterNode)
            S3 <- mu(n3) U ({n3} \ ParameterNode)
            if S1 intersects S3 and (n1 != n3 or n1 is LoadNode) then
                mu(n2) <- mu(n2) U mu(n4) U ({n4} \ ParameterNode)

// Extended mapping: identity for non-parameter nodes
mu'(n) = mu(n) U ({n} \ ParameterNode)

// --- Step 2: Combine graphs ---

// Inside edges
for each inside edge (n1 --f--> n2) in I_c do
    for each n1' in mu'(n1), n2' in mu'(n2) do
        addInsideEdge_caller(n1', f, n2')

// Outside edges (skip if source maps to InsideNode)
for each outside edge (n --f--> nL) in O_c do
    for each n' in mu'(n) do
        if n' is not InsideNode then
            addOutsideEdge_caller(n', f, nL)

// Return value
if returnVar != null then
    retNodes <- Union of mu'(n) for each n in returnTargets(G_callee)
    strongUpdate(returnVar, retNodes)

// Escaped nodes
E_caller <- E_caller U { n' : n in E_c, n' in mu'(n) }

// --- Step 3: Remove captured load nodes ---
//     A LoadNode is "captured" if it is unreachable from any root
//     (parameters, global escaped nodes, variable targets).
liveNodes <- BFS from roots of G_caller along all edges
for each LoadNode nL in G_caller do
    if nL not in liveNodes then
        removeNode(nL)

// --- Step 4: Propagate callee mutations ---
for each (n, f) in W_c do
    for each n' in mu'(n) do
        if n' is not InsideNode and n' exists in G_caller then
            recordMutation_caller(n', f)
```

---

## 6. Node Merging Optimization

An optional optimization from Madhavan et al. (SAS 2011) enforces that each `(source, field, edgeType)` triple has at most one target, bounding graph size. Applied after field loads, at CFG join points, and at method exit.

```
Algorithm: EnforceUniqueness(G)

repeat until no violation exists:
    find (source, field, type) with targets {t1, t2, ...} where |targets| > 1
    pick representative r from {t1, t2} by priority:
        ParameterNode > GlobalNode > InsideNode > LoadNode
        break ties by lexicographic ID
    let other = the non-representative node
    replaceNode(other, r)        // substitute r for other everywhere in G
```

---

## 7. On-Demand Cross-File Analysis

When a method invocation cannot be resolved by the safe method whitelist (Tier 1) or the summary cache (Tier 2), and a `view` is available, the analysis attempts to resolve and analyze the callee on the fly. This enables inter-file analysis (e.g., `HashSet.size()` calling `HashMap.size()` in a different file) without pre-analyzing the entire classpath.

Three guards prevent unbounded recursion and performance explosion:
- **Depth limit**: The `analyzing` set tracks methods currently being analyzed in the on-demand call chain. If `|analyzing| >= MAX_ON_DEMAND_DEPTH`, the call falls back to conservative.
- **Budget**: A mutable counter `budget[0]` is decremented for each on-demand analysis and reset per top-level method. If `budget[0] <= 0`, the call falls back to conservative.
- **Graph size guard**: If the callee's exit graph exceeds `MAX_ON_DEMAND_GRAPH_SIZE` nodes, the result is discarded (not cached) to prevent `InstantiateSummary` cross-product explosion in the caller.

```
Algorithm: AnalyzeExternalMethod(sig, view, cache, analyzing, budget)

Input:  Method signature sig,
        JavaView view (handle to classpath/JDK runtime),
        summary cache, analyzing set (recursion guard), budget counter
Output: MethodSummary or null (null = fall back to conservative)

1.  // Guard: recursion
2.  if sig in analyzing then return null

3.  // Guard: depth limit
4.  if |analyzing| >= MAX_ON_DEMAND_DEPTH then return null

5.  // Guard: budget exhausted
6.  if budget[0] <= 0 then return null

7.  // Resolve method from the view
8.  class <- view.getClass(sig.declaringClass)
9.  if class not found then return null
10. method <- class.getMethod(sig.subSignature)
11. if method not found or not concrete then return null

12. // Begin on-demand analysis
13. analyzing.add(sig)
14. budget[0] <- budget[0] - 1
15. try:
16.     G_exit <- AnalyzeMethod(method, cache, view, analyzing, budget)  // recursive!

17.     // Guard: graph size — discard overly large summaries
18.     if |nodes in G_exit| > MAX_ON_DEMAND_GRAPH_SIZE then return null

19.     summary <- CheckSideEffects(method, G_exit)
20.     cache.put(sig, summary)            // cache for future lookups
21.     return summary
22. catch any exception:
23.     return null                         // graceful fallback
24. finally:
25.     analyzing.remove(sig)              // always clean up, even on exception
```

**Key properties:**
- The `analyzing` set is shared across the recursive call chain (passed by reference), so mutual recursion between on-demand callees is detected automatically.
- The `budget` counter is shared (mutable array `int[1]`) and reset per top-level method in Algorithm 1, ensuring that one expensive method (e.g., `writeObject`) does not starve later methods (e.g., `isEmpty`).
- Successfully analyzed summaries are cached in the summary cache, so the same cross-file method is analyzed at most once per `run()` invocation.

---

## 8. Side-Effect Checking

Given the exit graph of a method, the checker determines the final verdict.

```
Algorithm: CheckSideEffects(m, G_exit)

Input:  Method m, exit graph G_exit = (I, O, L, E, W)
Output: SIDE_EFFECT_FREE or SIDE_EFFECTING(reason)

// Step 1: Compute prestate nodes A
//   (objects that existed before the method was called)
A <- { n in G_exit : n is ParameterNode }
Expand A by BFS along outside edges only:
    worklist <- A
    while worklist is not empty do
        n <- worklist.pop()
        for each outside edge (n --f--> n') in O do
            if n' not in A then
                A <- A U { n' }
                worklist.push(n')

// Step 2: Compute globally escaped nodes B
//   (objects reachable from global state)
B <- E U { GlobalNode }
Expand B by BFS along all edges (inside and outside):
    worklist <- B
    while worklist is not empty do
        n <- worklist.pop()
        for each edge (n --f--> n') in I U O do
            if n' not in B then
                B <- B U { n' }
                worklist.push(n')

// Step 3: Check for static field mutations
for each (GlobalNode, f) in W do
    return SIDE_EFFECTING("writes to static field f")

// Step 4: Check each prestate node
for each node n in A do
    // Skip 'this' mutations in constructors (allowed to initialize own fields)
    if m is a constructor and n = P0 then
        continue

    if n in B then
        return SIDE_EFFECTING("prestate node n escapes to global scope")

    for each (n, f) in W do
        return SIDE_EFFECTING("mutates prestate node n via field f")

// Step 5: All checks passed
return SIDE_EFFECT_FREE
```

---

## Graph Notation Summary

| Symbol | Meaning |
|--------|---------|
| **G = (I, O, L, E, W)** | Points-to graph: Inside edges, Outside edges, Local variable map, Escaped set, Mutated fields |
| **InsideNode** | Freshly allocated object (safe to mutate) |
| **ParameterNode** | Pre-existing object passed as parameter (prestate) |
| **LoadNode** | Unknown object read from pre-existing heap (prestate) |
| **GlobalNode** | Represents static field namespace |
| **Inside edge** (n1 --f--> n2) | Field write: n1.f was assigned to point to n2 |
| **Outside edge** (n1 --f--> n2) | Field read from prestate: n2 was loaded via n1.f |
| **W = { (n, f) }** | Set of (node, field) pairs that were written to |
| **E** | Set of nodes that escaped to global scope (stored in a static field) |
| **pt(v)** | Set of nodes that variable v may point to |
| **mu** | Node mapping from callee namespace to caller namespace |
| **Set A** | Prestate nodes: objects existing before method entry |
| **Set B** | Globally escaped closure: objects reachable from global state |
| **view** | JavaView handle to the classpath or JDK runtime for resolving cross-file classes |
| **analyzing** | Set of method signatures currently being analyzed on demand (recursion guard) |
| **budget** | Mutable counter `int[1]` limiting total on-demand analyses per top-level method |
