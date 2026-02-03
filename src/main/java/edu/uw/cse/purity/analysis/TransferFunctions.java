package edu.uw.cse.purity.analysis;

import edu.uw.cse.purity.AnalysisConfig;
import edu.uw.cse.purity.graph.*;
import edu.uw.cse.purity.util.NodeMerger;
import edu.uw.cse.purity.util.SafeMethods;
import sootup.core.jimple.basic.Local;
import sootup.core.jimple.basic.Value;
import sootup.core.jimple.common.expr.*;
import sootup.core.jimple.common.ref.*;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JIdentityStmt;
import sootup.core.jimple.common.stmt.JInvokeStmt;
import sootup.core.jimple.common.stmt.JReturnStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.signatures.FieldSignature;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.ReferenceType;

import java.util.HashSet;
import java.util.Set;

/**
 * Maps each Jimple Stmt to the corresponding graph operation.
 * This is the core of the analysis — it implements the abstract semantics
 * from Sălcianu & Rinard (2005).
 */
public class TransferFunctions {

    private final AnalysisConfig config;
    private final boolean isStaticMethod;

    // Counters for generating unique node IDs
    private int insideNodeCounter = 0;
    private int loadNodeCounter = 0;

    // Special local variable representing the return value
    public static final String RETURN_VAR_NAME = "$RETURN";

    public TransferFunctions(AnalysisConfig config, boolean isStaticMethod) {
        this.config = config;
        this.isStaticMethod = isStaticMethod;
    }

    /**
     * Apply the transfer function for the given statement, mutating the graph.
     */
    public void apply(Stmt stmt, PointsToGraph graph) {
        try {
            if (stmt instanceof JIdentityStmt identityStmt) {
                handleIdentity(identityStmt, graph);
            } else if (stmt instanceof JAssignStmt assignStmt) {
                handleAssign(assignStmt, graph);
            } else if (stmt instanceof JInvokeStmt invokeStmt) {
                handleInvokeStmt(invokeStmt, graph);
            } else if (stmt instanceof JReturnStmt returnStmt) {
                handleReturn(returnStmt, graph);
            }
            // JIfStmt, JGotoStmt, JReturnVoidStmt, JNopStmt, etc. — no graph effect
        } catch (Exception e) {
            // Fix #5: Graceful degradation on unresolvable types
            System.err.println("  Warning: error processing statement: " + stmt + " — " + e.getMessage());
        }
    }

    // --- Fix #7: JIdentityStmt handles @this and @parameter ---

    private void handleIdentity(JIdentityStmt stmt, PointsToGraph graph) {
        Value lhs = stmt.getLeftOp();
        Value rhs = stmt.getRightOp();

        if (!(lhs instanceof Local local)) return;
        if (!(lhs.getType() instanceof ReferenceType)) return;

        if (rhs instanceof JThisRef) {
            // r0 := @this → map to ParameterNode(0)
            graph.strongUpdate(local, Set.of(new ParameterNode(0)));
        } else if (rhs instanceof JParameterRef paramRef) {
            int paramIndex = paramRef.getIndex();
            // For instance methods, offset by 1 (index 0 is 'this')
            int nodeIndex = isStaticMethod ? paramIndex : paramIndex + 1;
            graph.strongUpdate(local, Set.of(new ParameterNode(nodeIndex)));
        }
        // Skip JCaughtExceptionRef — not relevant for purity
    }

    // --- JAssignStmt dispatching ---

    private void handleAssign(JAssignStmt stmt, PointsToGraph graph) {
        Value lhs = stmt.getLeftOp();
        Value rhs = stmt.getRightOp();

        // Case: x.f = y (field store — weak update)
        if (lhs instanceof JInstanceFieldRef fieldRef) {
            handleFieldStore(fieldRef, rhs, graph);
            return;
        }

        // Case: staticField = y
        if (lhs instanceof JStaticFieldRef staticRef) {
            handleStaticFieldStore(staticRef, rhs, graph);
            return;
        }

        // Case: x[i] = y (array store — treat as field store)
        if (lhs instanceof JArrayRef arrayRef) {
            handleArrayStore(arrayRef, rhs, graph);
            return;
        }

        // LHS must be a Local for remaining cases
        if (!(lhs instanceof Local lhsLocal)) return;

        // Case: x = new T (allocation)
        if (rhs instanceof JNewExpr newExpr) {
            handleNew(lhsLocal, newExpr, graph);
            return;
        }

        // Case: x = new T[size] (array allocation)
        if (rhs instanceof JNewArrayExpr newArrayExpr) {
            handleNewArray(lhsLocal, graph);
            return;
        }

        // Case: x = y (local copy)
        if (rhs instanceof Local rhsLocal) {
            handleCopy(lhsLocal, rhsLocal, graph);
            return;
        }

        // Case: x = y.f (field load)
        if (rhs instanceof JInstanceFieldRef fieldRef) {
            handleFieldLoad(lhsLocal, fieldRef, graph);
            return;
        }

        // Case: x = staticField
        if (rhs instanceof JStaticFieldRef staticRef) {
            handleStaticFieldLoad(lhsLocal, staticRef, graph);
            return;
        }

        // Case: x = (T) y (cast)
        if (rhs instanceof JCastExpr castExpr) {
            handleCast(lhsLocal, castExpr, graph);
            return;
        }

        // Case: x = y[i] (array load)
        if (rhs instanceof JArrayRef arrayRef) {
            handleArrayLoad(lhsLocal, arrayRef, graph);
            return;
        }

        // Case: x = foo(args) (invoke with return value)
        if (rhs instanceof AbstractInvokeExpr invokeExpr) {
            handleInvokeWithReturn(lhsLocal, invokeExpr, graph);
            return;
        }

        // Primitive operations, constants, etc. — no graph effect for reference analysis
        // But we should still clear the target if it's a reference type being assigned a non-reference
        if (lhsLocal.getType() instanceof ReferenceType) {
            graph.strongUpdate(lhsLocal, new HashSet<>());
        }
    }

    // --- Individual transfer functions ---

    /** x = new T → create InsideNode, strong update */
    private void handleNew(Local lhs, JNewExpr newExpr, PointsToGraph graph) {
        InsideNode node = new InsideNode(insideNodeCounter++,
            "new " + newExpr.getType().getClassName());
        graph.strongUpdate(lhs, Set.of(node));
    }

    /** x = new T[size] → create InsideNode for the array */
    private void handleNewArray(Local lhs, PointsToGraph graph) {
        InsideNode node = new InsideNode(insideNodeCounter++, "new array");
        graph.strongUpdate(lhs, Set.of(node));
    }

    /** x = y → strong update: copy y's targets to x */
    private void handleCopy(Local lhs, Local rhs, PointsToGraph graph) {
        if (!(lhs.getType() instanceof ReferenceType)) return;
        Set<Node> targets = graph.pointsTo(rhs);
        graph.strongUpdate(lhs, new HashSet<>(targets));
    }

    /**
     * x = y.f → field load.
     * For each node n that y points to:
     *   - Collect inside-edge targets (known writes)
     *   - If n is an escaped node, add outside edge + load node
     */
    private void handleFieldLoad(Local lhs, JInstanceFieldRef fieldRef, PointsToGraph graph) {
        if (!(lhs.getType() instanceof ReferenceType)) return;

        Local base = (Local) fieldRef.getBase();
        FieldSignature field = fieldRef.getFieldSignature();
        Set<Node> baseNodes = graph.pointsTo(base);
        Set<Node> result = new HashSet<>();

        for (Node n : baseNodes) {
            // Collect existing inside-edge targets
            result.addAll(graph.getTargets(n, field, EdgeType.INSIDE));

            // Collect existing outside-edge targets
            result.addAll(graph.getTargets(n, field, EdgeType.OUTSIDE));

            // If n could be a prestate node, add an outside edge + load node
            if (isPrestateReachable(n)) {
                // Check if we already have an outside edge for this (n, field)
                Set<Node> existingOutside = graph.getTargets(n, field, EdgeType.OUTSIDE);
                if (existingOutside.isEmpty()) {
                    LoadNode loadNode = new LoadNode(loadNodeCounter++,
                        "load " + field.getName() + " from " + n.getId());
                    graph.addOutsideEdge(n, field, loadNode);
                    result.add(loadNode);
                }
                // Otherwise the existing outside targets are already in result
            }
        }

        graph.strongUpdate(lhs, result);

        // Apply node merging after field load (if enabled)
        if (!config.noMerge) {
            NodeMerger.enforceUniqueness(graph);
        }
    }

    /** x.f = y → weak update: add inside edges, record mutation */
    private void handleFieldStore(JInstanceFieldRef fieldRef, Value rhs, PointsToGraph graph) {
        Local base = (Local) fieldRef.getBase();
        FieldSignature field = fieldRef.getFieldSignature();
        Set<Node> baseNodes = graph.pointsTo(base);
        Set<Node> rhsNodes = (rhs instanceof Local rhsLocal)
            ? graph.pointsTo(rhsLocal) : new HashSet<>();

        for (Node baseNode : baseNodes) {
            for (Node rhsNode : rhsNodes) {
                graph.addInsideEdge(baseNode, field, rhsNode);
            }
            graph.recordMutation(baseNode, field);
        }
    }

    /** staticField = y → inside edge from GlobalNode, record mutation, set hasGlobalSideEffect */
    private void handleStaticFieldStore(JStaticFieldRef staticRef, Value rhs, PointsToGraph graph) {
        FieldSignature field = staticRef.getFieldSignature();
        Set<Node> rhsNodes = (rhs instanceof Local rhsLocal)
            ? graph.pointsTo(rhsLocal) : new HashSet<>();

        for (Node rhsNode : rhsNodes) {
            graph.addInsideEdge(GlobalNode.INSTANCE, field, rhsNode);
            graph.markGlobalEscaped(rhsNode);
        }
        graph.recordMutation(GlobalNode.INSTANCE, field);
        graph.setHasGlobalSideEffect(); // Fix #4: immediate impurity
    }

    /** x = staticField → outside edge from GlobalNode + load node */
    private void handleStaticFieldLoad(Local lhs, JStaticFieldRef staticRef, PointsToGraph graph) {
        if (!(lhs.getType() instanceof ReferenceType)) return;

        FieldSignature field = staticRef.getFieldSignature();
        Set<Node> result = new HashSet<>();

        // Collect existing targets
        result.addAll(graph.getTargets(GlobalNode.INSTANCE, field, EdgeType.INSIDE));
        result.addAll(graph.getTargets(GlobalNode.INSTANCE, field, EdgeType.OUTSIDE));

        // Add outside edge if none exists
        Set<Node> existingOutside = graph.getTargets(GlobalNode.INSTANCE, field, EdgeType.OUTSIDE);
        if (existingOutside.isEmpty()) {
            LoadNode loadNode = new LoadNode(loadNodeCounter++,
                "load static " + field.getName());
            graph.addOutsideEdge(GlobalNode.INSTANCE, field, loadNode);
            result.add(loadNode);
        }

        graph.strongUpdate(lhs, result);

        if (!config.noMerge) {
            NodeMerger.enforceUniqueness(graph);
        }
    }

    /** x = (T) y → same as copy */
    private void handleCast(Local lhs, JCastExpr castExpr, PointsToGraph graph) {
        if (!(lhs.getType() instanceof ReferenceType)) return;
        Value op = castExpr.getOp();
        if (op instanceof Local rhsLocal) {
            Set<Node> targets = graph.pointsTo(rhsLocal);
            graph.strongUpdate(lhs, new HashSet<>(targets));
        }
    }

    /** x = y[i] → treat like field load with synthetic field */
    private void handleArrayLoad(Local lhs, JArrayRef arrayRef, PointsToGraph graph) {
        if (!(lhs.getType() instanceof ReferenceType)) return;
        // We treat array element access as a field access with a null field signature
        // This is a simplification — we use the base's existing edges
        Local base = (Local) arrayRef.getBase();
        Set<Node> baseNodes = graph.pointsTo(base);
        Set<Node> result = new HashSet<>();

        for (Node n : baseNodes) {
            // For arrays, we model element access: the array node itself "contains" its elements
            // A simple model: if the array node is prestate, create a load node
            if (isPrestateReachable(n)) {
                LoadNode loadNode = new LoadNode(loadNodeCounter++,
                    "array element from " + n.getId());
                result.add(loadNode);
            }
            // Also include any InsideNodes that were stored into this array
            for (var fieldEntry : graph.getEdges().getOrDefault(n, java.util.Map.of()).entrySet()) {
                for (var et : fieldEntry.getValue()) {
                    result.add(et.target());
                }
            }
        }

        graph.strongUpdate(lhs, result);
    }

    /** y[i] = x → treat like field store */
    private void handleArrayStore(JArrayRef arrayRef, Value rhs, PointsToGraph graph) {
        Local base = (Local) arrayRef.getBase();
        Set<Node> baseNodes = graph.pointsTo(base);
        Set<Node> rhsNodes = (rhs instanceof Local rhsLocal)
            ? graph.pointsTo(rhsLocal) : new HashSet<>();

        for (Node baseNode : baseNodes) {
            // Record as mutation of the array object
            // We use null field to represent array element writes
            graph.recordMutation(baseNode, null);
            // We don't add specific field edges for arrays (simplification)
            // The mutation record is sufficient for purity checking
        }
    }

    /** foo(args) — invoke without return value */
    private void handleInvokeStmt(JInvokeStmt stmt, PointsToGraph graph) {
        AbstractInvokeExpr invokeExpr = stmt.getInvokeExpr();
        handleInvoke(invokeExpr, null, graph);
    }

    /** x = foo(args) — invoke with return value */
    private void handleInvokeWithReturn(Local lhs, AbstractInvokeExpr invokeExpr, PointsToGraph graph) {
        handleInvoke(invokeExpr, lhs, graph);
    }

    /**
     * Handle a method invocation.
     * If the method is in SafeMethods → no side effects, return treated as fresh InsideNode.
     * Otherwise → conservatively mark as impure.
     */
    private void handleInvoke(AbstractInvokeExpr invokeExpr, Local returnVar, PointsToGraph graph) {
        MethodSignature methodSig = invokeExpr.getMethodSignature();

        if (SafeMethods.isSafe(methodSig)) {
            // Safe method: no side effects on the graph
            // If there's a return value of reference type, treat as fresh node
            if (returnVar != null && returnVar.getType() instanceof ReferenceType) {
                InsideNode freshReturn = new InsideNode(insideNodeCounter++,
                    "return from " + methodSig.getName());
                graph.strongUpdate(returnVar, Set.of(freshReturn));
            }
            return;
        }

        // Unknown method: conservatively mark all reference-type arguments as globally escaped
        // and flag the method as having unknown side effects
        for (Value arg : invokeExpr.getArgs()) {
            if (arg instanceof Local argLocal && argLocal.getType() instanceof ReferenceType) {
                Set<Node> argNodes = graph.pointsTo(argLocal);
                for (Node n : argNodes) {
                    graph.markGlobalEscaped(n);
                }
            }
        }

        // If it's a virtual/interface call, the receiver is also an argument
        if (invokeExpr instanceof AbstractInstanceInvokeExpr instanceInvoke) {
            Local base = instanceInvoke.getBase();
            Set<Node> baseNodes = graph.pointsTo(base);
            for (Node n : baseNodes) {
                graph.markGlobalEscaped(n);
            }
        }

        // Conservative: mark as having global side effect (unknown call could do anything)
        graph.setHasGlobalSideEffect();

        // Return value: point to GlobalNode (could be anything)
        if (returnVar != null && returnVar.getType() instanceof ReferenceType) {
            graph.strongUpdate(returnVar, Set.of(GlobalNode.INSTANCE));
        }
    }

    /** return x → track return value */
    private void handleReturn(JReturnStmt stmt, PointsToGraph graph) {
        Value retVal = stmt.getOp();
        if (retVal instanceof Local retLocal && retLocal.getType() instanceof ReferenceType) {
            // Store in a conceptual RETURN variable — we don't actually need this
            // for purity checking, but it's useful for inter-procedural extension
        }
    }

    // --- Helper: check if a node could be a prestate node ---

    /**
     * A node is "prestate reachable" if it is:
     * - A ParameterNode
     * - A LoadNode (created from reading an escaped object's field)
     * - GlobalNode
     */
    private boolean isPrestateReachable(Node n) {
        return n instanceof ParameterNode
            || n instanceof LoadNode
            || n instanceof GlobalNode;
    }
}
