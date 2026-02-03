package edu.uw.cse.purity.graph;

/**
 * Represents an unknown object read from the heap via a field load
 * on an escaped object (e.g., x = param.f).
 * LoadNodes model "outside" memory — they are prestate nodes.
 */
public class LoadNode extends Node {
    private final String label;

    public LoadNode(int siteIndex, String label) {
        super("L" + siteIndex);
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public NodeKind getKind() {
        return NodeKind.LOAD;
    }
}
