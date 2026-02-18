package edu.uw.cse.purity.graph;

import sootup.core.jimple.basic.Local;
import sootup.core.signatures.FieldSignature;

import java.util.*;

/**
 * The core data structure for the purity analysis.
 * Represents a points-to graph G = ⟨I, O, L, E⟩ per Salcianu &amp; Rinard (2005):
 * <ul>
 *   <li><b>I</b> (inside edges) — heap references created by writes ({@link EdgeType#INSIDE} entries in {@link #edges})</li>
 *   <li><b>O</b> (outside edges) — heap references read from pre-existing objects ({@link EdgeType#OUTSIDE} entries in {@link #edges})</li>
 *   <li><b>L</b> (local variables) — which locals point to which nodes ({@link #varPointsTo})</li>
 *   <li><b>E</b> (globally escaped) — nodes whose address is stored in static fields ({@link #globalEscaped})</li>
 * </ul>
 *
 * Additional field not in the formal G tuple but needed for purity analysis:
 * <ul>
 *   <li><b>W</b> (mutated fields) — tracked in {@link #mutatedFields}</li>
 * </ul>
 */
public class PointsToGraph {

    /** Variable → set of abstract nodes it may point to */
    private final Map<Local, Set<Node>> varPointsTo;

    /**
     * Heap edges: source node → field → set of (target node, edge type).
     * InsideEdges represent writes; OutsideEdges represent reads from escaped heap.
     */
    private final Map<Node, Map<FieldSignature, Set<EdgeTarget>>> edges;

    /** Tracks which (node, field) pairs have been written to */
    private final Set<MutatedField> mutatedFields;

    /** Nodes that have escaped to static fields */
    private final Set<Node> globalEscaped;

    /** Nodes that the method's return value may point to (for inter-procedural analysis) */
    private final Set<Node> returnTargets;

    /**
     * Node counters embedded in the graph state so that the forward flow analysis
     * fixed-point iteration produces deterministic node IDs.
     * When a graph is copied (for flowThrough), the counters come along;
     * when graphs are merged (at join points), counters are max'd.
     */
    private int insideNodeCounter = 0;
    private int loadNodeCounter = 0;

    public PointsToGraph() {
        this.varPointsTo = new HashMap<>();
        this.edges = new HashMap<>();
        this.mutatedFields = new HashSet<>();
        this.globalEscaped = new HashSet<>();
        this.returnTargets = new HashSet<>();
    }

    // --- Node counter accessors (for TransferFunctions) ---

    public int getInsideNodeCounter() { return insideNodeCounter; }
    public void setInsideNodeCounter(int val) { insideNodeCounter = val; }
    public int getLoadNodeCounter() { return loadNodeCounter; }
    public void setLoadNodeCounter(int val) { loadNodeCounter = val; }

    // --- Record types ---

    public record EdgeTarget(Node target, EdgeType type) {}

    public record MutatedField(Node node, FieldSignature field) {}

    // --- Variable operations ---

    /** Get all nodes a variable may point to (empty set if unknown) */
    public Set<Node> pointsTo(Local v) {
        return varPointsTo.getOrDefault(v, Collections.emptySet());
    }

    /** Strong update: v now points to exactly these targets */
    public void strongUpdate(Local v, Set<Node> targets) {
        varPointsTo.put(v, new HashSet<>(targets));
    }

    // --- Edge operations ---

    /** Add an inside (mutation) edge: source --field--> target */
    public void addInsideEdge(Node source, FieldSignature field, Node target) {
        edges.computeIfAbsent(source, k -> new HashMap<>())
             .computeIfAbsent(field, k -> new HashSet<>())
             .add(new EdgeTarget(target, EdgeType.INSIDE));
    }

    /** Add an outside (read) edge: source --field--> target */
    public void addOutsideEdge(Node source, FieldSignature field, Node target) {
        edges.computeIfAbsent(source, k -> new HashMap<>())
             .computeIfAbsent(field, k -> new HashSet<>())
             .add(new EdgeTarget(target, EdgeType.OUTSIDE));
    }

    /** Get all targets reachable from source via field, filtered by edge type */
    public Set<Node> getTargets(Node source, FieldSignature field, EdgeType type) {
        Map<FieldSignature, Set<EdgeTarget>> fieldMap = edges.get(source);
        if (fieldMap == null) return Collections.emptySet();
        Set<EdgeTarget> edgeTargets = fieldMap.get(field);
        if (edgeTargets == null) return Collections.emptySet();
        Set<Node> result = new HashSet<>();
        for (EdgeTarget et : edgeTargets) {
            if (et.type() == type) {
                result.add(et.target());
            }
        }
        return result;
    }

    /** Get ALL targets reachable from source via field (any edge type) */
    public Set<Node> getAllTargets(Node source, FieldSignature field) {
        Map<FieldSignature, Set<EdgeTarget>> fieldMap = edges.get(source);
        if (fieldMap == null) return Collections.emptySet();
        Set<EdgeTarget> edgeTargets = fieldMap.get(field);
        if (edgeTargets == null) return Collections.emptySet();
        Set<Node> result = new HashSet<>();
        for (EdgeTarget et : edgeTargets) {
            result.add(et.target());
        }
        return result;
    }

    /** Get all edge targets (with type info) from source via field */
    public Set<EdgeTarget> getEdgeTargets(Node source, FieldSignature field) {
        Map<FieldSignature, Set<EdgeTarget>> fieldMap = edges.get(source);
        if (fieldMap == null) return Collections.emptySet();
        return fieldMap.getOrDefault(field, Collections.emptySet());
    }

    // --- Mutation tracking ---

    public void recordMutation(Node node, FieldSignature field) {
        mutatedFields.add(new MutatedField(node, field));
    }

    public Set<MutatedField> getMutatedFields() {
        return Collections.unmodifiableSet(mutatedFields);
    }

    // --- Global escape ---

    public void markGlobalEscaped(Node node) {
        globalEscaped.add(node);
    }

    public boolean isGloballyEscaped(Node node) {
        return globalEscaped.contains(node);
    }

    public Set<Node> getGlobalEscaped() {
        return Collections.unmodifiableSet(globalEscaped);
    }

    // --- Return targets (for inter-procedural analysis) ---

    public void addReturnTargets(Set<Node> targets) {
        returnTargets.addAll(targets);
    }

    public Set<Node> getReturnTargets() {
        return Collections.unmodifiableSet(returnTargets);
    }

    // --- Graph accessors ---

    public Map<Local, Set<Node>> getVarPointsTo() {
        return Collections.unmodifiableMap(varPointsTo);
    }

    public Map<Node, Map<FieldSignature, Set<EdgeTarget>>> getEdges() {
        return Collections.unmodifiableMap(edges);
    }

    /** Get inside edges only (set I in the paper). */
    public Map<Node, Map<FieldSignature, Set<Node>>> getInsideEdges() {
        return filterEdgesByType(EdgeType.INSIDE);
    }

    /** Get outside edges only (set O in the paper). */
    public Map<Node, Map<FieldSignature, Set<Node>>> getOutsideEdges() {
        return filterEdgesByType(EdgeType.OUTSIDE);
    }

    private Map<Node, Map<FieldSignature, Set<Node>>> filterEdgesByType(EdgeType type) {
        Map<Node, Map<FieldSignature, Set<Node>>> result = new HashMap<>();
        for (Map.Entry<Node, Map<FieldSignature, Set<EdgeTarget>>> entry : edges.entrySet()) {
            for (Map.Entry<FieldSignature, Set<EdgeTarget>> fe : entry.getValue().entrySet()) {
                for (EdgeTarget et : fe.getValue()) {
                    if (et.type() == type) {
                        result.computeIfAbsent(entry.getKey(), k -> new HashMap<>())
                              .computeIfAbsent(fe.getKey(), k -> new HashSet<>())
                              .add(et.target());
                    }
                }
            }
        }
        return result;
    }

    /** Get all nodes present in the graph */
    public Set<Node> getAllNodes() {
        Set<Node> nodes = new HashSet<>();
        for (Set<Node> targets : varPointsTo.values()) {
            nodes.addAll(targets);
        }
        for (Map.Entry<Node, Map<FieldSignature, Set<EdgeTarget>>> entry : edges.entrySet()) {
            nodes.add(entry.getKey());
            for (Set<EdgeTarget> ets : entry.getValue().values()) {
                for (EdgeTarget et : ets) {
                    nodes.add(et.target());
                }
            }
        }
        for (MutatedField mf : mutatedFields) {
            nodes.add(mf.node());
        }
        nodes.addAll(globalEscaped);
        return nodes;
    }

    // --- Deep copy ---

    public PointsToGraph copy() {
        PointsToGraph clone = new PointsToGraph();
        for (Map.Entry<Local, Set<Node>> entry : varPointsTo.entrySet()) {
            clone.varPointsTo.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        for (Map.Entry<Node, Map<FieldSignature, Set<EdgeTarget>>> entry : edges.entrySet()) {
            Map<FieldSignature, Set<EdgeTarget>> fieldMapCopy = new HashMap<>();
            for (Map.Entry<FieldSignature, Set<EdgeTarget>> fe : entry.getValue().entrySet()) {
                fieldMapCopy.put(fe.getKey(), new HashSet<>(fe.getValue()));
            }
            clone.edges.put(entry.getKey(), fieldMapCopy);
        }
        clone.mutatedFields.addAll(this.mutatedFields);
        clone.globalEscaped.addAll(this.globalEscaped);
        clone.returnTargets.addAll(this.returnTargets);
        clone.insideNodeCounter = this.insideNodeCounter;
        clone.loadNodeCounter = this.loadNodeCounter;
        return clone;
    }

    /** Copy this graph's contents into dest (mutates dest) */
    public void copyInto(PointsToGraph dest) {
        dest.varPointsTo.clear();
        dest.edges.clear();
        dest.mutatedFields.clear();
        dest.globalEscaped.clear();
        dest.returnTargets.clear();
        for (Map.Entry<Local, Set<Node>> entry : varPointsTo.entrySet()) {
            dest.varPointsTo.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        for (Map.Entry<Node, Map<FieldSignature, Set<EdgeTarget>>> entry : edges.entrySet()) {
            Map<FieldSignature, Set<EdgeTarget>> fieldMapCopy = new HashMap<>();
            for (Map.Entry<FieldSignature, Set<EdgeTarget>> fe : entry.getValue().entrySet()) {
                fieldMapCopy.put(fe.getKey(), new HashSet<>(fe.getValue()));
            }
            dest.edges.put(entry.getKey(), fieldMapCopy);
        }
        dest.mutatedFields.addAll(this.mutatedFields);
        dest.globalEscaped.addAll(this.globalEscaped);
        dest.returnTargets.addAll(this.returnTargets);
        dest.insideNodeCounter = this.insideNodeCounter;
        dest.loadNodeCounter = this.loadNodeCounter;
    }

    // --- Merge (union for join points) ---

    /** Merge another graph into this one (union semantics) */
    public void mergeWith(PointsToGraph other) {
        // Union variable mappings
        for (Map.Entry<Local, Set<Node>> entry : other.varPointsTo.entrySet()) {
            varPointsTo.computeIfAbsent(entry.getKey(), k -> new HashSet<>())
                       .addAll(entry.getValue());
        }
        // Union edges
        for (Map.Entry<Node, Map<FieldSignature, Set<EdgeTarget>>> entry : other.edges.entrySet()) {
            Map<FieldSignature, Set<EdgeTarget>> thisFieldMap =
                edges.computeIfAbsent(entry.getKey(), k -> new HashMap<>());
            for (Map.Entry<FieldSignature, Set<EdgeTarget>> fe : entry.getValue().entrySet()) {
                thisFieldMap.computeIfAbsent(fe.getKey(), k -> new HashSet<>())
                            .addAll(fe.getValue());
            }
        }
        // Union mutations
        mutatedFields.addAll(other.mutatedFields);
        // Union global escaped
        globalEscaped.addAll(other.globalEscaped);
        // Union return targets
        returnTargets.addAll(other.returnTargets);
        // Max node counters (ensure deterministic IDs after merge)
        insideNodeCounter = Math.max(insideNodeCounter, other.insideNodeCounter);
        loadNodeCounter = Math.max(loadNodeCounter, other.loadNodeCounter);
    }

    // --- Node replacement (used by NodeMerger) ---

    /** Replace all occurrences of oldNode with newNode throughout the graph */
    public void replaceNode(Node oldNode, Node newNode) {
        // Replace in variable mappings
        for (Set<Node> targets : varPointsTo.values()) {
            if (targets.remove(oldNode)) {
                targets.add(newNode);
            }
        }

        // Replace in edges (both as source and as target)
        // First, handle oldNode as source
        Map<FieldSignature, Set<EdgeTarget>> oldSourceEdges = edges.remove(oldNode);
        if (oldSourceEdges != null) {
            Map<FieldSignature, Set<EdgeTarget>> newSourceEdges =
                edges.computeIfAbsent(newNode, k -> new HashMap<>());
            for (Map.Entry<FieldSignature, Set<EdgeTarget>> fe : oldSourceEdges.entrySet()) {
                newSourceEdges.computeIfAbsent(fe.getKey(), k -> new HashSet<>())
                              .addAll(fe.getValue());
            }
        }

        // Then, handle oldNode as target in all edges
        for (Map<FieldSignature, Set<EdgeTarget>> fieldMap : edges.values()) {
            for (Map.Entry<FieldSignature, Set<EdgeTarget>> fe : fieldMap.entrySet()) {
                Set<EdgeTarget> ets = fe.getValue();
                Set<EdgeTarget> toAdd = new HashSet<>();
                Set<EdgeTarget> toRemove = new HashSet<>();
                for (EdgeTarget et : ets) {
                    if (et.target().equals(oldNode)) {
                        toRemove.add(et);
                        toAdd.add(new EdgeTarget(newNode, et.type()));
                    }
                }
                ets.removeAll(toRemove);
                ets.addAll(toAdd);
            }
        }

        // Replace in mutatedFields
        Set<MutatedField> toAdd = new HashSet<>();
        Set<MutatedField> toRemove = new HashSet<>();
        for (MutatedField mf : mutatedFields) {
            if (mf.node().equals(oldNode)) {
                toRemove.add(mf);
                toAdd.add(new MutatedField(newNode, mf.field()));
            }
        }
        mutatedFields.removeAll(toRemove);
        mutatedFields.addAll(toAdd);

        // Replace in globalEscaped
        if (globalEscaped.remove(oldNode)) {
            globalEscaped.add(newNode);
        }
    }

    // --- Node removal (for inter-procedural simplification) ---

    /** Remove a node from all edges, varPointsTo, mutatedFields, and globalEscaped */
    public void removeNode(Node n) {
        // Remove from varPointsTo
        for (Set<Node> targets : varPointsTo.values()) {
            targets.remove(n);
        }
        // Remove as edge source
        edges.remove(n);
        // Remove as edge target
        for (Map<FieldSignature, Set<EdgeTarget>> fieldMap : edges.values()) {
            for (Set<EdgeTarget> ets : fieldMap.values()) {
                ets.removeIf(et -> et.target().equals(n));
            }
        }
        // Remove from mutatedFields
        mutatedFields.removeIf(mf -> mf.node().equals(n));
        // Remove from globalEscaped
        globalEscaped.remove(n);
        // Remove from returnTargets
        returnTargets.remove(n);
    }

    /** Remove all outside edges where n is the source */
    public void removeOutsideEdgesFrom(Node n) {
        Map<FieldSignature, Set<EdgeTarget>> fieldMap = edges.get(n);
        if (fieldMap == null) return;
        for (Set<EdgeTarget> ets : fieldMap.values()) {
            ets.removeIf(et -> et.type() == EdgeType.OUTSIDE);
        }
    }

    // --- Invariant validation ---

    /**
     * Validate graph invariants per Salcianu & Rinard (2005):
     * Rule 1: InsideNode cannot be the source of an OUTSIDE edge.
     * Rule 2: An OUTSIDE edge cannot lead to an InsideNode.
     *
     * @return list of violation descriptions (empty if no violations)
     */
    public List<String> validateInvariants() {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<Node, Map<FieldSignature, Set<EdgeTarget>>> entry : edges.entrySet()) {
            Node source = entry.getKey();
            for (Map.Entry<FieldSignature, Set<EdgeTarget>> fe : entry.getValue().entrySet()) {
                String fieldName = fe.getKey() != null ? fe.getKey().getName() : "[]";
                for (EdgeTarget et : fe.getValue()) {
                    if (et.type() == EdgeType.OUTSIDE) {
                        // Rule 1: InsideNode cannot be source of an outside edge
                        if (source instanceof InsideNode) {
                            violations.add("Rule 1 violated: InsideNode " + source.getId()
                                + " has outside edge --" + fieldName + "--> " + et.target().getId());
                        }
                        // Rule 2: Outside edge cannot lead to InsideNode
                        if (et.target() instanceof InsideNode) {
                            violations.add("Rule 2 violated: Outside edge from " + source.getId()
                                + " --" + fieldName + "--> leads to InsideNode " + et.target().getId());
                        }
                    }
                }
            }
        }
        return violations;
    }

    // --- Equality (for fixed-point detection) ---

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PointsToGraph other)) return false;
        return varPointsTo.equals(other.varPointsTo)
            && edges.equals(other.edges)
            && mutatedFields.equals(other.mutatedFields)
            && globalEscaped.equals(other.globalEscaped)
            && returnTargets.equals(other.returnTargets);
    }

    @Override
    public int hashCode() {
        return Objects.hash(varPointsTo, edges, mutatedFields, globalEscaped, returnTargets);
    }
}
