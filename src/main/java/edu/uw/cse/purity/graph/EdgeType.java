package edu.uw.cse.purity.graph;

/**
 * The type of a heap edge in the points-to graph.
 *
 * INSIDE (solid): Represents a definite write/mutation (x.f = y).
 * OUTSIDE (dashed): Represents a read from pre-existing heap (x = y.f where y is escaped).
 */
public enum EdgeType {
    INSIDE,
    OUTSIDE
}
