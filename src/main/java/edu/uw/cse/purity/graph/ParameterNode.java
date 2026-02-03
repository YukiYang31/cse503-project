package edu.uw.cse.purity.graph;

/**
 * Represents an object passed as a parameter to the analyzed method.
 * For instance methods, index 0 is 'this'.
 * These are prestate nodes — mutations to them indicate impurity.
 */
public class ParameterNode extends Node {
    private final int paramIndex;

    public ParameterNode(int paramIndex) {
        super("P" + paramIndex);
        this.paramIndex = paramIndex;
    }

    public int getParamIndex() {
        return paramIndex;
    }

    @Override
    public NodeKind getKind() {
        return NodeKind.PARAMETER;
    }
}
