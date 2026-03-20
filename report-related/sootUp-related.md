# SootUp Usage in the Codebase

This document outlines where and how the SootUp framework is utilized in the side-effect analysis tool. SootUp is primarily used for **Intermediate Representation (Jimple)**, **Class Loading**, and **Control Flow Graph (CFG)** generation, but notably **not** for the call graph algorithm itself.

## 1. Project View and Class Loading (Used by CallGraphBuilder)
SootUp provides the foundational "bricks" for understanding the codebase. The `CallGraphBuilder` relies heavily on `JavaView` to resolve classes and methods, even though the call graph structure itself is built manually.

*   **`SideEffectAnalysisRunner.java`**
    *   **`JavaView`**: The central entry point for accessing code. It is initialized with either:
        *   `JavaClassPathAnalysisInputLocation`: For compiled user classes.
        *   `JrtFileSystemAnalysisInputLocation`: For loading JDK classes directly from the Java Runtime Environment (Java 9+ modules).
    *   **`JavaSootClass`**: Represents a loaded class.
    *   **Retrieval**: Uses `view.getClasses()` or `view.getClass(type)` to fetch class models.

*   **`CallGraphBuilder.java`**
    *   Uses `JavaView` to inspect potential callee classes during the custom BFS discovery phase.
    *   Specifically instantiates a separate `JavaView` with `JrtFileSystemAnalysisInputLocation` for resolving JDK dependencies on demand.
    *   **Crucial Distinction**: While `CallGraphBuilder` uses SootUp to *find* and *read* classes, it **does not** use SootUp's built-in `CallGraph` class or algorithms (like Spark or CHACallGraph). Instead, it iterates over Jimple instructions manually to construct its own graph edges.

## 2. Intermediate Representation (Jimple)
The tool relies entirely on SootUp's **Jimple** IR for analysis. Jimple is a typed, 3-address code intermediate representation that simplifies Java bytecode.

*   **`TransferFunctions.java`**
    *   This is the core of the analysis, interpreting Jimple statements to update the Points-to Graph.
    *   **`Stmt`**: The base class for all instructions.
    *   **`JAssignStmt`**: Handles assignments (`x = y`, `x.f = y`, `x = new T`).
    *   **`JInvokeStmt`**: Handles method calls (`x.m()`).
    *   **`JIdentityStmt`**: Handles parameter passing (`@this`, `@parameter0`).
    *   **`JReturnStmt`**: Handles method returns.
    *   **Expressions**: Uses `JNewExpr`, `JVirtualInvokeExpr`, `JFieldRef`, `JArrayRef`, etc., to understand the right-hand side of assignments.
    *   **`Local`**: Represents local variables in the method body.
    *   **`FieldSignature`**: uniquely identifies class fields during store/load operations.

## 3. Control Flow Graph (CFG) and Body
SootUp generates the Control Flow Graph used to drive the dataflow analysis.

*   **`SideEffectAnalysisRunner.java`** & **`CallGraphBuilder.java`**
    *   **`JavaSootMethod.getBody()`**: Retrieves the `Body` of a method, which contains locals and instructions.
    *   **`Body.getStmtGraph()`**: Returns the `StmtGraph` (CFG), providing the graph structure (successors/predecessors) for statements.

*   **`SideEffectFlowAnalysis.java`**
    *   **`ForwardFlowAnalysis`**: The analysis class extends SootUp's `ForwardFlowAnalysis` abstract class. This leverages SootUp's built-in fixed-point solver engine to propagate abstract states through the CFG until convergence.

## 4. Why Custom Call Graph?
While SootUp provides the building blocks (Method Signatures, Class Hierarchies, `JavaView`), the project implements a **custom** call graph builder in `CallGraphBuilder.java` rather than using SootUp's built-in CHA or Spark algorithms. This matches the specific requirement to partially explore the JDK (capped at 200 classes) and respect the custom "SafeMethods" list, which provided better control than configuring the standard whole-program analyses. The implementation manually iterates over Jimple instructions provided by SootUp to discover call edges.
