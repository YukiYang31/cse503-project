package edu.uw.cse.purity.graph;

/**
 * Represents an object allocated via 'new' within the analyzed method.
 * One InsideNode per allocation site (Stmt).
 * Mutations to InsideNodes are allowed (they are newly created objects).
 */
public class InsideNode extends Node {
    private final String label; // human-readable description (e.g., "new Point at line 5")

    public InsideNode(int siteIndex, String label) {
        super("I" + siteIndex);
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public NodeKind getKind() {
        return NodeKind.INSIDE;
    }
}
