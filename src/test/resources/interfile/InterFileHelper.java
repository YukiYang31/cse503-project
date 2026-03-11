package interfile;

// Helper classes for inter-file analysis tests.
// When InterFileMain.java is analyzed alone, methods in this file
// are resolved on demand via Tier 3 (cross-file analysis).

class Container {
    Object data;
    int size;

    Container(Object data) {            // SEF (constructor exception)
        this.data = data;
        this.size = 0;
    }

    // --- Side-effect-free methods ---

    Object getData() {                  // SEF: reads this.data, no mutation
        return this.data;
    }

    Container copy() {                  // SEF: allocates new Container, copies data
        Container c = new Container(null);
        c.data = this.data;
        return c;
    }

    boolean isEmpty() {                 // SEF: reads primitive field
        return this.size == 0;
    }

    // --- Side-effecting methods ---

    void setData(Object d) {            // SE: mutates this.data
        this.data = d;
    }

    void increment() {                  // SE: mutates this.size
        this.size = this.size + 1;
    }
}

class Pair {
    Object first;
    Object second;

    Pair(Object a, Object b) {          // SEF (constructor exception)
        this.first = a;
        this.second = b;
    }

    Pair swap() {                       // SEF: creates new Pair with swapped fields
        return new Pair(this.second, this.first);
    }

    void setFirst(Object a) {          // SE: mutates this.first
        this.first = a;
    }
}
