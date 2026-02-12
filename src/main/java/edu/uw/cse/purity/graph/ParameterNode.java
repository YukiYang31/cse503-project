package edu.uw.cse.purity.graph;

/**
 * Represents an object passed as a parameter to the analyzed method.
 * For instance methods, index 0 is 'this'.
 * These are prestate nodes — mutations to them indicate impurity.
 */
public class ParameterNode extends Node {
    private final int paramIndex;
    private final String label;

    public ParameterNode(int paramIndex, String label) {
        super("P" + paramIndex);
        this.paramIndex = paramIndex;
        this.label = label;
    }

    public int getParamIndex() {
        return paramIndex;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public NodeKind getKind() {
        return NodeKind.PARAMETER;
    }
}
