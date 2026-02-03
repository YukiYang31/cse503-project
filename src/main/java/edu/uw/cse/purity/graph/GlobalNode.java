package edu.uw.cse.purity.graph;

/**
 * Singleton node representing the global/static field namespace.
 * Any object stored into a static field "escapes" to this node.
 */
public class GlobalNode extends Node {
    public static final GlobalNode INSTANCE = new GlobalNode();

    private GlobalNode() {
        super("GBL");
    }

    @Override
    public NodeKind getKind() {
        return NodeKind.GLOBAL;
    }
}
