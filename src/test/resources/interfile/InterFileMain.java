package interfile;

// Inter-file analysis test cases.
// Methods here call into Container and Pair (defined in InterFileHelper.java).
// When this file is analyzed alone, those callees are resolved on demand
// via Tier 3 (cross-file analysis from the JavaView).

// --- Caller that invokes SEF callee: should remain SEF ---

class Reader {
    Object readFromContainer(Container c) {     // SEF: calls Container.getData() [SEF]
        return c.getData();
    }

    Container copyContainer(Container c) {      // SEF: calls Container.copy() [SEF]
        return c.copy();
    }

    boolean checkEmpty(Container c) {           // SEF: calls Container.isEmpty() [SEF]
        return c.isEmpty();
    }

    Pair swapPair(Pair p) {                     // SEF: calls Pair.swap() [SEF]
        return p.swap();
    }
}

// --- Caller that invokes SE callee: mutation propagates ---

class Mutator {
    void updateContainer(Container c, Object d) {  // SE: calls Container.setData() [SE]
        c.setData(d);                               // mutation on c propagates
    }

    void growContainer(Container c) {               // SE: calls Container.increment() [SE]
        c.increment();                              // mutation on c propagates
    }

    void replaceFirst(Pair p, Object a) {          // SE: calls Pair.setFirst() [SE]
        p.setFirst(a);                              // mutation on p propagates
    }
}

// --- Mixed: SEF callee + own mutation ---

class Holder {
    Container container;

    void swapContainer(Object data) {               // SE: mutates this.container
        Container temp = new Container(data);       // calls Container.<init> [SEF]
        this.container = temp;                      // field store on this (prestate)
    }

    Container snapshotAndReplace(Object newData) {  // SE: mutates this.container
        Container old = this.container.copy();      // calls Container.copy() [SEF]
        this.container = new Container(newData);    // calls Container.<init> [SEF]
        return old;                                 // old is fresh InsideNode
    }

    Object readThroughHolder() {                    // SEF: reads this.container.data
        return this.container.getData();            // calls Container.getData() [SEF]
    }
}

// --- Constructor calling cross-file constructor ---

class WrappedPair {
    Pair pair;

    WrappedPair(Object a, Object b) {               // SEF (constructor exception)
        this.pair = new Pair(a, b);                 // calls Pair.<init> [SEF]
    }

    Pair getPair() {                                // SEF: reads this.pair
        return this.pair;
    }

    Pair getSwapped() {                             // SEF: calls Pair.swap() [SEF]
        return this.pair.swap();
    }
}
