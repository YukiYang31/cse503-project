// Inter-procedural side-effect analysis test cases
// Exercises Section 5.3 of Salcianu & Rinard (2005)

// --- Side-effect-free reader calling another side-effect-free reader (constraint 1 only) ---

class IPWrapper {
    int value;
    IPWrapper(int v) { this.value = v; }
}

class IPReader {
    int getValue(IPWrapper w) { return w.value; }  // SIDE_EFFECT_FREE
    static int readViaHelper(IPWrapper w, IPReader r) {
        return r.getValue(w);                       // SIDE_EFFECT_FREE (inter-proc)
    }
}

// --- Factory returning fresh InsideNode; caller reads it (constraint 2) ---

class IPFactory {
    static IPWrapper create(int v) {
        IPWrapper w = new IPWrapper(v);  // returns InsideNode
        return w;                         // SIDE_EFFECT_FREE
    }
}

class IPConsumer {
    static int makeAndRead() {
        IPWrapper w = IPFactory.create(5); // w is InsideNode
        return w.value;                     // SIDE_EFFECT_FREE (reads from InsideNode)
    }
}

// --- Side-effecting callee propagates mutation (Step 4: W update) ---

class IPMutator {
    static void modify(IPWrapper w) { w.value = 99; }  // SIDE_EFFECTING
}

class IPSideEffectingCaller {
    static void doModify(IPWrapper w) {
        IPMutator.modify(w);  // SIDE_EFFECTING: callee mutation on param propagates
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
    static int sum(IPLinkedList list) {  // SIDE_EFFECT_FREE with inter-proc
        IPIter it = list.iterator();
        int s = 0;
        while (it.hasNext()) { s += it.next(); }
        return s;
    }
}
