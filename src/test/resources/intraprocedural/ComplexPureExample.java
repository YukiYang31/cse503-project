import java.util.ArrayList;

class DataNode {
    Object value;
    DataNode next;

    DataNode(Object value, DataNode next) {
        this.value = value;
        this.next = next;
    }
}

class Registry {
    static Object defaultValue;
    Object[] items;

    Registry(Object[] items) {
        this.items = items;
    }
}

/**
 * A single PURE method that exercises nearly every transfer-function rule
 * in TransferFunctions.apply(), stress-testing the analysis's precision
 * on complex code that remains pure.
 *
 * Transfer-function rules exercised by complexPure():
 *
 *  Rule                          | Jimple pattern        | Line(s)
 *  ----------------------------- | --------------------- | -------
 *  handleIdentity (@parameter)   | r := @parameter[i]    | (implicit for registry, head, raw)
 *  handleNew                     | x = new T             | localA, localB
 *  handleNewArray                | x = new T[n]          | localArr
 *  handleFieldLoad (from param)  | x = y.f  (y=param)   | head.value, head.next, registry.items
 *  handleFieldLoad (from local)  | x = y.f  (y=inside)  | localA.next  (traverses inside edge)
 *  handleFieldStore              | x.f = y               | localA.next = localB
 *  handleStaticFieldLoad         | x = C.f               | Registry.defaultValue
 *  handleCopy                    | x = y                 | alias = localA
 *  handleCast                    | x = (T) y             | (String) raw
 *  handleArrayLoad               | x = y[i]              | items[0]
 *  handleArrayStore              | y[i] = x              | localArr[0..2] = ...
 *  handleInvoke (safe, ref ret)  | x = safe(...)         | str.concat("...")
 *  handleInvoke (safe, ctor)     | new + safe <init>     | new ArrayList<>()
 *  handleReturn                  | return x              | return localArr
 *
 *  NOT exercised (inherently impure):
 *   - handleStaticFieldStore  (writing to a static field escapes objects globally)
 *   - handleInvoke (unknown)  (unknown calls conservatively escape all arguments)
 *
 * Additional rules exercised by instancePure():
 *  handleIdentity (@this)        | r := @this            | (implicit for 'this')
 */
public class ComplexPureExample {
    Object tag;

    ComplexPureExample(Object tag) {
        this.tag = tag;
    }

    static Object[] complexPure(Registry registry, DataNode head, Object raw) {
        // --- handleNew ---
        // Allocate local objects. Their constructors are not whitelisted, so the
        // InsideNodes get globally escaped — but InsideNodes are not prestate objects,
        // so this does NOT cause impurity. Tests the analysis's precision.
        DataNode localA = new DataNode(null, null);
        DataNode localB = new DataNode(null, null);

        // --- handleNewArray ---
        // Array allocation has no constructor call, so the InsideNode is NOT escaped.
        Object[] localArr = new Object[3];

        // --- handleFieldLoad (parameter base → creates LoadNodes + outside edges) ---
        // Reading reference-type fields from parameters. Each creates a LoadNode
        // because the base (ParameterNode) is prestate-reachable.
        Object headValue = head.value;
        DataNode headNext = head.next;
        Object[] items = registry.items;

        // --- handleFieldStore (on local InsideNode) ---
        // Stores InsideNode_B into InsideNode_A.next via inside edge.
        // The mutation is recorded on InsideNode_A, which is not a prestate node → pure.
        localA.next = localB;

        // --- handleFieldLoad (InsideNode base → traverses inside edges, no LoadNode) ---
        // Reading from a locally-created object. The base is InsideNode_A, which is NOT
        // prestate-reachable, so no LoadNode is created. Instead, the analysis follows
        // the inside edge to InsideNode_B. Exercises a different code path than the
        // parameter field load above.
        DataNode readBack = localA.next;

        // --- handleStaticFieldLoad ---
        // Reads a static reference field. Creates an outside edge from GlobalNode and
        // a LoadNode. No write to the static field → pure.
        Object defaultVal = Registry.defaultValue;

        // --- handleCopy ---
        // Simple reference copy. Strong-updates alias to point to the same InsideNode as localA.
        DataNode alias = localA;

        // --- handleCast ---
        // Cast expression. Copies the points-to set of 'raw' (ParameterNode) to 'str'.
        String str = (String) raw;

        // --- handleArrayLoad ---
        // Reads from items[0] where 'items' points to a LoadNode (from registry.items).
        // Since LoadNode is prestate-reachable, a new LoadNode is created for the
        // array element.
        Object firstItem = items[0];

        // --- handleArrayStore (on local InsideNode) ---
        // Writes into the local array. handleArrayStore only records the mutation
        // on the InsideNode (no inside edges added). The InsideNode is not prestate → pure.
        // Stores values of mixed provenance: LoadNode (headValue), LoadNode (defaultVal),
        // LoadNode (firstItem) — but none of this causes impurity because the array
        // InsideNode was never escaped and no edges are added.
        localArr[0] = headValue;
        localArr[1] = defaultVal;
        localArr[2] = firstItem;

        // --- handleInvoke (safe method, reference-type return) ---
        // String.concat is whitelisted (all java.lang.String methods are safe).
        // Returns a fresh InsideNode representing the new String.
        String greeting = str.concat(" world");

        // --- handleInvoke (safe constructor) ---
        // ArrayList constructor is whitelisted. Creates an InsideNode that is NOT
        // globally escaped (unlike the DataNode constructor above).
        ArrayList<Object> tempList = new ArrayList<>();

        // --- handleReturn ---
        // Returns a reference type (Object[]). The InsideNode for localArr is returned.
        return localArr;
    }

    /**
     * Instance method exercising the @this identity rule.
     *
     * In instance methods, Jimple generates `r0 := @this` which maps to
     * ParameterNode(0). Reading this.tag creates a LoadNode via an outside
     * edge from ParameterNode(0). No mutations → PURE.
     */
    Object instancePure(DataNode input) {
        // --- handleIdentity (@this) ---
        // this → ParameterNode(0), implicit in instance methods

        // --- handleIdentity (@parameter) ---
        // input → ParameterNode(1), implicit for reference-type parameters

        // --- handleFieldLoad (from @this) ---
        // Creates an outside edge from ParameterNode(0) and a LoadNode for tag.
        Object myTag = this.tag;

        // --- handleFieldLoad (from parameter) ---
        Object inputValue = input.value;

        // --- handleInvoke (safe, on prestate object) ---
        // Object.toString() is whitelisted. Even though the receiver (myTag) is a
        // LoadNode (prestate), the safe call has no side effects and returns a
        // fresh InsideNode.
        String tagStr = myTag.toString();

        // --- handleNew ---
        DataNode result = new DataNode(null, null);

        // --- handleReturn ---
        return result;
    }
}
