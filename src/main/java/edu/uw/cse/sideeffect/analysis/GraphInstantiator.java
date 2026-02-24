package edu.uw.cse.sideeffect.analysis;

import edu.uw.cse.sideeffect.graph.*;
import edu.uw.cse.sideeffect.graph.PointsToGraph.EdgeTarget;
import edu.uw.cse.sideeffect.graph.PointsToGraph.MutatedField;
import edu.uw.cse.sideeffect.output.DebugHtmlWriter;
import sootup.core.jimple.basic.Local;
import sootup.core.signatures.FieldSignature;
import java.util.*;

/**
 * Implements Section 5.3 of Salcianu &amp; Rinard (2005): inter-procedural
 * summary instantiation at call sites.
 *
 * Given a call vR = v0.s(v1, ..., vj), this class maps the callee's exit graph
 * into the caller's graph by:
 *   1. Remapping callee node IDs to avoid collisions
 *   2. Computing the node mapping mu (least fixed point)
 *   3. Combining graphs (inside edges, outside edges, locals, escaped set)
 *   4. Removing captured load nodes
 *   5. Updating mutated fields W
 */
public class GraphInstantiator {

    public record InstantiationResult(int nextInsideCounter, int nextLoadCounter, String muPrimeText) {}

    /**
     * Instantiate a callee summary at a call site in the caller.
     *
     * @param callerGraph       the caller's points-to graph (mutated in place)
     * @param calleeExitGraph   the callee's exit graph (read only — a copy is made internally)
     * @param calleeReturnTargets nodes the callee's return value may point to
     * @param calleeW           the callee's mutated fields
     * @param actualArgs        L(v0), L(v1), ..., L(vj) — v0 is receiver for instance calls
     * @param returnVar         the local variable receiving the return value (null if void)
     * @param isCalleeStatic    whether the callee is a static method
     * @param insideCounter     current inside node counter in the caller
     * @param loadCounter       current load node counter in the caller
     * @param debug             whether to print debug output
     * @param debugWriter       optional HTML debug writer (null to skip HTML debug)
     * @return the updated counters after remapping
     */
    public static InstantiationResult instantiate(
            PointsToGraph callerGraph,
            PointsToGraph calleeExitGraph,
            Set<Node> calleeReturnTargets,
            Set<MutatedField> calleeW,
            List<Set<Node>> actualArgs,
            Local returnVar,
            boolean isCalleeStatic,
            int insideCounter, int loadCounter,
            boolean debug,
            DebugHtmlWriter debugWriter,
            String calleeSig) {

        // --- Step 0: Remap callee nodes to fresh IDs in the caller's namespace ---
        Map<Node, Node> remapTable = new HashMap<>();
        int ic = insideCounter;
        int lc = loadCounter;

        // Collect all nodes from callee exit graph
        Set<Node> calleeNodes = calleeExitGraph.getAllNodes();

        for (Node n : calleeNodes) {
            if (n instanceof ParameterNode) {
                // ParameterNodes are NOT remapped — they get replaced by mu
                continue;
            } else if (n instanceof GlobalNode) {
                // GlobalNode maps to itself
                remapTable.put(n, n);
            } else if (n instanceof InsideNode in) {
                InsideNode fresh = new InsideNode(ic++, "callee: " + in.getLabel());
                remapTable.put(n, fresh);
            } else if (n instanceof LoadNode ln) {
                LoadNode fresh = new LoadNode(lc++, "callee: " + ln.getLabel());
                remapTable.put(n, fresh);
            }
        }

        // Also remap nodes that appear only in W or returnTargets
        for (MutatedField mf : calleeW) {
            Node n = mf.node();
            if (!remapTable.containsKey(n) && !(n instanceof ParameterNode)) {
                if (n instanceof GlobalNode) {
                    remapTable.put(n, n);
                } else if (n instanceof InsideNode in) {
                    remapTable.put(n, new InsideNode(ic++, "callee: " + in.getLabel()));
                } else if (n instanceof LoadNode ln) {
                    remapTable.put(n, new LoadNode(lc++, "callee: " + ln.getLabel()));
                }
            }
        }
        for (Node n : calleeReturnTargets) {
            if (!remapTable.containsKey(n) && !(n instanceof ParameterNode)) {
                if (n instanceof GlobalNode) {
                    remapTable.put(n, n);
                } else if (n instanceof InsideNode in) {
                    remapTable.put(n, new InsideNode(ic++, "callee: " + in.getLabel()));
                } else if (n instanceof LoadNode ln) {
                    remapTable.put(n, new LoadNode(lc++, "callee: " + ln.getLabel()));
                }
            }
        }

        if (debug) {
            System.out.println("Debug== [instantiate] remap table: " + remapTable);
        }

        // Apply remap to callee components
        // Remap inside edges
        List<Edge> remappedInsideEdges = new ArrayList<>();
        Map<Node, Map<FieldSignature, Set<Node>>> calleeInside = calleeExitGraph.getInsideEdges();
        for (var srcEntry : calleeInside.entrySet()) {
            for (var fieldEntry : srcEntry.getValue().entrySet()) {
                for (Node tgt : fieldEntry.getValue()) {
                    Node rSrc = remapNode(srcEntry.getKey(), remapTable);
                    Node rTgt = remapNode(tgt, remapTable);
                    remappedInsideEdges.add(new Edge(rSrc, fieldEntry.getKey(), rTgt));
                }
            }
        }

        // Remap outside edges
        List<Edge> remappedOutsideEdges = new ArrayList<>();
        Map<Node, Map<FieldSignature, Set<Node>>> calleeOutside = calleeExitGraph.getOutsideEdges();
        for (var srcEntry : calleeOutside.entrySet()) {
            for (var fieldEntry : srcEntry.getValue().entrySet()) {
                for (Node tgt : fieldEntry.getValue()) {
                    Node rSrc = remapNode(srcEntry.getKey(), remapTable);
                    Node rTgt = remapNode(tgt, remapTable);
                    remappedOutsideEdges.add(new Edge(rSrc, fieldEntry.getKey(), rTgt));
                }
            }
        }

        // Remap return targets
        Set<Node> remappedReturnTargets = new HashSet<>();
        for (Node n : calleeReturnTargets) {
            remappedReturnTargets.add(remapNode(n, remapTable));
        }

        // Remap escaped set
        Set<Node> remappedEscaped = new HashSet<>();
        for (Node n : calleeExitGraph.getGlobalEscaped()) {
            remappedEscaped.add(remapNode(n, remapTable));
        }

        // Remap W
        Set<MutatedField> remappedW = new HashSet<>();
        for (MutatedField mf : calleeW) {
            remappedW.add(new MutatedField(remapNode(mf.node(), remapTable), mf.field()));
        }

        // --- Debug: record remapped callee graph (after Step 0 renaming) ---
        if (debugWriter != null && calleeSig != null) {
            PointsToGraph remappedCalleeGraph = new PointsToGraph();
            for (Edge e : remappedInsideEdges) {
                remappedCalleeGraph.addInsideEdge(e.src, e.field, e.tgt);
            }
            for (Edge e : remappedOutsideEdges) {
                remappedCalleeGraph.addOutsideEdge(e.src, e.field, e.tgt);
            }
            for (Node n : remappedEscaped) {
                remappedCalleeGraph.markGlobalEscaped(n);
            }
            for (MutatedField mf : remappedW) {
                remappedCalleeGraph.recordMutation(mf.node(), mf.field());
            }
            debugWriter.setNextCalleeGraph(remappedCalleeGraph, calleeSig);
        }

        // --- Step 1: Compute core mapping mu (least fixed point) ---
        Map<Node, Set<Node>> mu = new HashMap<>();

        // Constraint 1: Initialize from actual arguments
        // actualArgs.get(i) maps to callee's ParameterNode(i)
        for (int i = 0; i < actualArgs.size(); i++) {
            Node calleeParam = new ParameterNode(i, "param" + i);
            mu.computeIfAbsent(calleeParam, k -> new HashSet<>()).addAll(actualArgs.get(i));
        }

        if (debug) {
            System.out.println("Debug== [instantiate] mu after constraint 1: " + muToString(mu));
        }

        // Get caller's inside edges for constraint 2
        Map<Node, Map<FieldSignature, Set<Node>>> callerInside = callerGraph.getInsideEdges();

        // Iterate constraints 2 & 3 until fixed point
        boolean changed = true;
        int iterations = 0;
        while (changed) {
            changed = false;
            iterations++;

            // Constraint 2: callee outside edge vs CALLER inside edge
            for (Edge oEdge : remappedOutsideEdges) {
                Node n1 = oEdge.src;
                FieldSignature f = oEdge.field;
                Node n2 = oEdge.tgt;
                Set<Node> muN1 = mu.getOrDefault(n1, Set.of());

                for (var callerSrcEntry : callerInside.entrySet()) {
                    Node n3 = callerSrcEntry.getKey();
                    Map<FieldSignature, Set<Node>> fieldMap = callerSrcEntry.getValue();
                    Set<Node> n4Set = fieldMap.getOrDefault(f, Set.of());
                    if (muN1.contains(n3)) {
                        for (Node n4 : n4Set) {
                            changed |= mu.computeIfAbsent(n2, k -> new HashSet<>()).add(n4);
                        }
                    }
                }
            }

            // Constraint 3: callee outside edge vs CALLEE inside edge (with aliasing)
            for (Edge oEdge : remappedOutsideEdges) {
                Node n1 = oEdge.src;
                FieldSignature f = oEdge.field;
                Node n2 = oEdge.tgt;

                for (Edge iEdge : remappedInsideEdges) {
                    Node n3 = iEdge.src;
                    FieldSignature f2 = iEdge.field;
                    Node n4 = iEdge.tgt;

                    if (!Objects.equals(f, f2)) continue;

                    // Check aliasing: (mu(n1) ∪ {n1}) ∩ (mu(n3) ∪ {n3}) ≠ ∅
                    Set<Node> muN1ext = new HashSet<>(mu.getOrDefault(n1, Set.of()));
                    muN1ext.add(n1);
                    Set<Node> muN3ext = new HashSet<>(mu.getOrDefault(n3, Set.of()));
                    muN3ext.add(n3);

                    if (Collections.disjoint(muN1ext, muN3ext)) continue;

                    // Additional condition: (n1 ≠ n3) ∨ (n1 ∈ LoadNode)
                    if (n1.equals(n3) && !(n1 instanceof LoadNode)) continue;

                    // Add mu(n4) ∪ ({n4} \ PNode) to mu(n2)
                    Set<Node> toAdd = new HashSet<>(mu.getOrDefault(n4, Set.of()));
                    if (!(n4 instanceof ParameterNode)) {
                        toAdd.add(n4);
                    }
                    changed |= mu.computeIfAbsent(n2, k -> new HashSet<>()).addAll(toAdd);
                }
            }

            if (iterations > 100) {
                // Safety valve — should not happen in practice
                if (debug) System.out.println("Debug== [instantiate] mu computation exceeded 100 iterations, stopping");
                break;
            }
        }

        if (debug) {
            System.out.println("Debug== [instantiate] mu converged after " + iterations + " iterations: " + muToString(mu));
        }

        // Compute mu' (extended mapping: adds identity for non-parameter nodes)
        // mu'(n) = mu(n) ∪ ({n} \ PNode)
        // Collect all callee nodes that need mu' computation
        Set<Node> allCalleeRemappedNodes = new HashSet<>();
        for (Edge e : remappedInsideEdges) { allCalleeRemappedNodes.add(e.src); allCalleeRemappedNodes.add(e.tgt); }
        for (Edge e : remappedOutsideEdges) { allCalleeRemappedNodes.add(e.src); allCalleeRemappedNodes.add(e.tgt); }
        allCalleeRemappedNodes.addAll(remappedReturnTargets);
        allCalleeRemappedNodes.addAll(remappedEscaped);
        for (MutatedField mf : remappedW) { allCalleeRemappedNodes.add(mf.node()); }
        // Also include all nodes from mu keys
        allCalleeRemappedNodes.addAll(mu.keySet());

        Map<Node, Set<Node>> muPrime = new HashMap<>();
        for (Node n : allCalleeRemappedNodes) {
            Set<Node> mapped = new HashSet<>(mu.getOrDefault(n, Set.of()));
            if (!(n instanceof ParameterNode)) {
                mapped.add(n);
            }
            if (!mapped.isEmpty()) {
                muPrime.put(n, mapped);
            }
        }

        if (debug) {
            System.out.println("Debug== [instantiate] mu': " + muToString(muPrime));
        }

        // --- Step 2: Combine graphs ---

        // I2 = I ∪ ∪_{(n1,f,n2) ∈ I_callee} mu'(n1) × {f} × mu'(n2)
        for (Edge iEdge : remappedInsideEdges) {
            Set<Node> srcs = muPrime.getOrDefault(iEdge.src, Set.of());
            Set<Node> tgts = muPrime.getOrDefault(iEdge.tgt, Set.of());
            for (Node src : srcs) {
                for (Node tgt : tgts) {
                    callerGraph.addInsideEdge(src, iEdge.field, tgt);
                }
            }
        }

        // O2 = O ∪ ∪_{(n,f,nL) ∈ O_callee} mu'(n) × {f} × {nL}
        // NOTE: only project source, NOT the load node target
        // Skip outside edges where mapped source is an InsideNode (Rule 1: InsideNode
        // cannot be source of OUTSIDE edge — freshly created objects have no prestate)
        for (Edge oEdge : remappedOutsideEdges) {
            Set<Node> srcs = muPrime.getOrDefault(oEdge.src, Set.of());
            for (Node src : srcs) {
                if (src instanceof InsideNode) {
                    // InsideNode has no prestate to read from; the callee's outside edge
                    // was for reading from the parameter, which got resolved via constraint 2.
                    // Convert to inside edge lookup: check if the caller already has an
                    // inside edge from this InsideNode with this field.
                    // If so, the load was already resolved by mu mapping. Skip.
                    continue;
                }
                callerGraph.addOutsideEdge(src, oEdge.field, oEdge.tgt);
            }
        }

        // L2 = L[vR ↦ mu'(L_callee(vret))]
        if (returnVar != null) {
            Set<Node> returnNodes = new HashSet<>();
            for (Node n : remappedReturnTargets) {
                returnNodes.addAll(muPrime.getOrDefault(n, Set.of()));
            }
            callerGraph.strongUpdate(returnVar, returnNodes);
        }

        // E2 = E ∪ mu'(E_callee)
        for (Node n : remappedEscaped) {
            Set<Node> mapped = muPrime.getOrDefault(n, Set.of());
            for (Node m : mapped) {
                callerGraph.markGlobalEscaped(m);
            }
        }

        // --- Debug: Record combined graph before simplification ---
        if (debugWriter != null) {
            debugWriter.setNextPreSimplificationGraph(callerGraph);
        }

        // --- Step 3: Simplify — remove captured load nodes ---
        // A node is "captured" if it is not reachable from any live root in the caller.
        // Live roots include: E2 ∪ {GlobalNode} ∪ {ParameterNodes} ∪ {varPointsTo targets}
        // This ensures prestate load nodes reachable from parameters survive.
        Set<Node> liveRoots = computeLiveNodes(callerGraph);

        // Collect all load nodes in the graph
        Set<Node> allNodes = callerGraph.getAllNodes();
        List<Node> capturedLoadNodes = new ArrayList<>();
        for (Node n : allNodes) {
            if (n instanceof LoadNode && !liveRoots.contains(n)) {
                capturedLoadNodes.add(n);
            }
        }

        // Remove captured load nodes and all their adjacent edges
        for (Node n : capturedLoadNodes) {
            callerGraph.removeNode(n);
        }

        // Remove outside edges from captured non-load nodes
        for (Node n : allNodes) {
            if (!(n instanceof LoadNode) && !liveRoots.contains(n)) {
                callerGraph.removeOutsideEdgesFrom(n);
            }
        }

        if (debug && !capturedLoadNodes.isEmpty()) {
            System.out.println("Debug== [instantiate] removed captured load nodes: " + capturedLoadNodes);
        }

        // --- Step 4: Update W ---
        // W_m' = W_m ∪ ∪_{(n,f) ∈ W_callee} ((mu'(n) \ INode) ∩ N) × {f}
        Set<Node> currentNodes = callerGraph.getAllNodes();
        for (MutatedField mf : remappedW) {
            Set<Node> mapped = muPrime.getOrDefault(mf.node(), Set.of());
            for (Node m : mapped) {
                if (!(m instanceof InsideNode) && currentNodes.contains(m)) {
                    callerGraph.recordMutation(m, mf.field());
                }
            }
        }

        return new InstantiationResult(ic, lc, muToString(muPrime));
    }

    /**
     * Compute the set of "live" nodes: nodes reachable from any root in the caller.
     * Roots include: E ∪ {GlobalNode} ∪ {ParameterNodes} ∪ {varPointsTo targets}.
     * This is used by Step 3 to determine which load nodes are "captured" (unreachable).
     */
    private static Set<Node> computeLiveNodes(PointsToGraph graph) {
        Set<Node> live = new HashSet<>();
        Queue<Node> worklist = new LinkedList<>();

        // Seed from E ∪ {GlobalNode}
        for (Node n : graph.getGlobalEscaped()) {
            if (live.add(n)) worklist.add(n);
        }
        if (live.add(GlobalNode.INSTANCE)) {
            worklist.add(GlobalNode.INSTANCE);
        }

        // Seed from ParameterNodes and all varPointsTo targets
        for (Node n : graph.getAllNodes()) {
            if (n instanceof ParameterNode) {
                if (live.add(n)) worklist.add(n);
            }
        }
        for (Set<Node> targets : graph.getVarPointsTo().values()) {
            for (Node n : targets) {
                if (live.add(n)) worklist.add(n);
            }
        }

        // BFS along all edges
        while (!worklist.isEmpty()) {
            Node current = worklist.poll();
            Map<FieldSignature, Set<PointsToGraph.EdgeTarget>> fieldMap =
                    graph.getEdges().getOrDefault(current, Map.of());
            for (Set<PointsToGraph.EdgeTarget> targets : fieldMap.values()) {
                for (PointsToGraph.EdgeTarget et : targets) {
                    if (live.add(et.target())) {
                        worklist.add(et.target());
                    }
                }
            }
        }

        return live;
    }

    // --- Helper: remap a node using the remap table ---
    private static Node remapNode(Node n, Map<Node, Node> remapTable) {
        if (n instanceof ParameterNode) {
            // ParameterNodes keep their identity (handled by mu)
            return n;
        }
        return remapTable.getOrDefault(n, n);
    }

    // --- Helper: edge record ---
    private record Edge(Node src, FieldSignature field, Node tgt) {}

    // --- Helper: format mu for debug ---
    private static String muToString(Map<Node, Set<Node>> mu) {
        StringBuilder sb = new StringBuilder("{");
        List<String> entries = new ArrayList<>();
        for (var entry : mu.entrySet()) {
            List<String> ids = entry.getValue().stream().map(Node::getId).sorted().toList();
            entries.add(entry.getKey().getId() + " -> {" + String.join(", ", ids) + "}");
        }
        entries.sort(String::compareTo);
        sb.append(String.join(", ", entries));
        sb.append("}");
        return sb.toString();
    }
}
