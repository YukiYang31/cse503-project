// Inter-procedural purity analysis test cases
// Exercises Section 5.3 of Salcianu & Rinard (2005)

// --- Pure reader calling another pure reader (constraint 1 only) ---

class IPWrapper {
    int value;
    IPWrapper(int v) { this.value = v; }
}

class IPReader {
    int getValue(IPWrapper w) { return w.value; }  // PURE
    static int readViaHelper(IPWrapper w, IPReader r) {
        return r.getValue(w);                       // PURE (inter-proc)
    }
}

// --- Factory returning fresh InsideNode; caller reads it (constraint 2) ---

class IPFactory {
    static IPWrapper create(int v) {
        IPWrapper w = new IPWrapper(v);  // returns InsideNode
        return w;                         // PURE
    }
}

class IPConsumer {
    static int makeAndRead() {
        IPWrapper w = IPFactory.create(5); // w is InsideNode
        return w.value;                     // PURE (reads from InsideNode)
    }
}

// --- Impure callee propagates mutation (Step 4: W update) ---

class IPMutator {
    static void modify(IPWrapper w) { w.value = 99; }  // IMPURE
}

class IPImpureCaller {
    static void doModify(IPWrapper w) {
        IPMutator.modify(w);  // IMPURE: callee mutation on param propagates
    }
}

// --- Iterator pattern (exercises constraints 2 & 3 together) ---

class IPNode {
    int data;
    IPNode next;
    IPNode(int d, IPNode n) { data = d; next = n; }
}

class IPIter {
    IPNode current;
    IPIter(IPNode start) { current = start; }
    boolean hasNext() { return current != null; }
    int next() { int d = current.data; current = current.next; return d; }
}

class IPLinkedList {
    IPNode head;
    IPIter iterator() { return new IPIter(head); }
}

class IPSum {
    static int sum(IPLinkedList list) {  // PURE with inter-proc
        IPIter it = list.iterator();
        int s = 0;
        while (it.hasNext()) { s += it.next(); }
        return s;
    }
}
