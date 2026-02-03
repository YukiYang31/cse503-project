package edu.uw.cse.purity.util;

import edu.uw.cse.purity.graph.*;
import edu.uw.cse.purity.graph.PointsToGraph.EdgeTarget;
import sootup.core.signatures.FieldSignature;

import java.util.*;

/**
 * Implements the node merging optimization from Madhavan et al. (2011).
 *
 * Rule: For any (node, field, edgeType) triple, there should be at most one
 * target node. If adding N -[f,type]-> B when N -[f,type]-> A already exists,
 * merge A and B into a single representative node.
 *
 * This bounds graph size and ensures termination while preserving purity results.
 */
public class NodeMerger {

    /**
     * Enforce the uniqueness invariant on the graph.
     * For each (source, field, edgeType) triple, if there are multiple targets,
     * merge them into one representative.
     */
    public static void enforceUniqueness(PointsToGraph graph) {
        boolean changed = true;
        while (changed) {
            changed = false;
            // Find a violation: a (source, field, type) with multiple targets
            MergePair pair = findMergePair(graph);
            if (pair != null) {
                graph.replaceNode(pair.remove, pair.keep);
                changed = true;
            }
        }
    }

    /**
     * Find two nodes that should be merged: same (source, field, type) with
     * different targets.
     */
    private static MergePair findMergePair(PointsToGraph graph) {
        for (Map.Entry<Node, Map<FieldSignature, Set<EdgeTarget>>> sourceEntry
                : graph.getEdges().entrySet()) {
            for (Map.Entry<FieldSignature, Set<EdgeTarget>> fieldEntry
                    : sourceEntry.getValue().entrySet()) {

                // Group targets by edge type
                Map<EdgeType, List<Node>> targetsByType = new HashMap<>();
                for (EdgeTarget et : fieldEntry.getValue()) {
                    targetsByType.computeIfAbsent(et.type(), k -> new ArrayList<>())
                                 .add(et.target());
                }

                // Check if any type has multiple targets
                for (List<Node> targets : targetsByType.values()) {
                    if (targets.size() > 1) {
                        // Pick first as representative, merge second into first
                        Node keep = pickRepresentative(targets.get(0), targets.get(1));
                        Node remove = (keep == targets.get(0)) ? targets.get(1) : targets.get(0);
                        return new MergePair(keep, remove);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Pick which node to keep when merging. Prefer keeping:
     * 1. ParameterNodes (they have semantic meaning)
     * 2. InsideNodes (they track allocation sites)
     * 3. LoadNodes
     * Between same kinds, keep the one with lower ID (deterministic).
     */
    private static Node pickRepresentative(Node a, Node b) {
        int priorityA = kindPriority(a);
        int priorityB = kindPriority(b);
        if (priorityA != priorityB) {
            return priorityA < priorityB ? a : b;
        }
        // Same kind: keep the one with lexicographically smaller ID
        return a.getId().compareTo(b.getId()) <= 0 ? a : b;
    }

    private static int kindPriority(Node n) {
        return switch (n.getKind()) {
            case PARAMETER -> 0;
            case GLOBAL -> 1;
            case INSIDE -> 2;
            case LOAD -> 3;
        };
    }

    private record MergePair(Node keep, Node remove) {}
}
