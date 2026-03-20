## Call Graph and Override Graph Generation

The tool constructs two key graphs—the Call Graph and the Override Graph—to drive the bottom-up analysis and handle virtual dispatch correctly. This process occurs in four phases within `CallGraphBuilder.java`.

### Class Discovery (BFS Phase)
Before building the graphs, the tool performs a Breadth-First Search (BFS) to identify relevant dependencies beyond the input set. 

- It starts with the user-provided input classes.
- It scans the method bodies of these classes for method invocations (Jimple `InvokeExpr`).
- If a called class belongs to the JDK (verified via the JRT filesystem) and has not been seen yet, it is added to the set of classes to analyze.
- This discovery process is capped (currently at 200 classes) to prevent the analysis from pulling in the entire Java standard library.

### Override Graph Generation
The Override Graph maps a base method to its overriding implementations (e.g., `Base.m()` → `Derived.m()`). This graph is particularly critical for the side-effect analysis: if a user overrides a library method with side-effecting behavior, the base library method must also be flagged as side-effecting to preserve soundness at virtual call sites.

- **Scope:** This phase iterates only over the **input classes** (user code) using SootUp's `JavaView` to resolve class hierarchies.
- **SootUp Usage:**
    - It uses `JavaSootClass.getSuperclass()` to traverse the inheritance hierarchy.
    - It compares methods using `MethodSignature.getSubSignature()` to detect overrides (same name and parameter types).
- **Logic:**
    1. For each class $C$ (represented as a `JavaSootClass`), the tool identifies its superclass $S$.
    2. If $S$ is also a valid input class in the view, the tool iterates through all concrete methods in $C$.
    3. For each method $m$ in $C$, if $S$ contains a concrete method with the exact same sub-signature, an edge is recorded: $S.m \to C.m$.

### Call Graph Generation
The Call Graph consists of edges mapping a caller to its potential callees (e.g., `Caller.m()` → `Callee.m()`). This graph determines the dependency structure for the analysis.

- **Scope:** This phase iterates over **all discovered classes** (both User input and discovered JDK classes).
- **SootUp Usage:**
    - It retrieves the Jimple `Body` for every concrete method via `JavaSootMethod.getBody()`.
    - It iterates through the `StmtGraph` of the body to find statements that contain method invocations.
    - It handles both `JInvokeStmt` (void calls) and `JAssignStmt` (calls with return values) by extracting the `AbstractInvokeExpr`.
    - It uses `MethodSignature` from the invocation expression to identify the target.
- **Logic:** 
    1. The tool retrieves the method body.
    2. It iterates through all instructions looking for invocation expressions.
    3. For each invocation, it extracts the declared target signature.
    4. **Static Resolution:** The tool checks if the exact target method exists within the `JavaView` of analyzed classes. If it does, a direct edge $Caller \to Callee$ is added.
- **Note:** This construction creates a static call graph based on declared types. Virtual method dispatch is not expanded here; instead, it is handled during the dataflow analysis phase using the `SummaryCache` and the `OverrideGraph`.

### Analysis Order (Tarjan's SCC)
Finally, the constructed Call Graph is fed into Tarjan's Algorithm to identify Strongly Connected Components (SCCs).

- The algorithm produces a list of "batches" sorted in **topological bottom-up order**.
- Leaf methods (those that make no outgoing calls or only call external/native methods) appear first.
- Callers appear after their callees are processed.
- Mutually recursive methods are grouped into a single batch, allowing them to be analyzed together until a fixed-point is reached.

### Note on Custom Call Graph Implementation
It is important to note that this project does **not** utilize SootUp's built-in call graph algorithms (such as CHA or Spark). Instead, a custom call graph builder (`CallGraphBuilder.java`) was implemented from scratch, though it **relies on SootUp components** (specifically `JavaView`, `JavaSootClass`, and `MethodBody`) to read bytecode and resolve class dependencies. This custom design choice was made for several reasons:

1. **JRT Complexity:** Analyzing JDK source code requires resolving classes from the `jrt:/` filesystem (Java 9+ modules). Standard whole-program call graph generators often struggle with the complexity and scale of including the entire JDK.
2. **Controlled Scope:** The analysis requires a specific "partial" world view: starting strictly from user input files and expanding lazily into the JDK (capped at 200 classes) to find relevant dependencies without loading the entire `java.base` module.
3. **Specific Analysis Order:** To support the summary-based inter-procedural logic efficiently, a precise bottom-up analysis order (computed via Tarjan's SCC) was required, which was more straightforward to implement directly on top of a custom lightweight graph structure.

While SootUp provides the essential infrastructure for parsing bytecode, loading classes (`JavaView`), and generating the Jimple IR, the call graph analysis and traversal logic are entirely custom implementations tailored to the specific needs of this side-effect analysis tool.
