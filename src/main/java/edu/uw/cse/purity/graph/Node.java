package edu.uw.cse.purity.graph;

import java.util.Objects;

/**
 * Abstract base class for nodes in the points-to graph.
 * Each node represents an abstract heap location.
 */
public abstract class Node {
    private final String id;

    protected Node(String id) {
        this.id = Objects.requireNonNull(id);
    }

    public String getId() {
        return id;
    }

    public abstract NodeKind getKind();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Node node = (Node) o;
        return id.equals(node.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id;
    }

    public enum NodeKind {
        INSIDE,
        PARAMETER,
        LOAD,
        GLOBAL
    }
}
