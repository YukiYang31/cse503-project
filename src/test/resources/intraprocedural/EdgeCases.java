class Point {
    int x;
    int y;

    Point(int x, int y) {
        this.x = x;
        this.y = y;
    }
}

public class EdgeCases {
    static int GLOBAL_VALUE = 100;

    // Side-effect-free: creates int[], mutates it, returns it — only local allocation mutated
    static int[] createMutateReturn() {
        int[] arr = new int[3];
        arr[0] = 1;
        arr[1] = 2;
        arr[2] = 3;
        return arr;
    }

    // Side-effect-free: creates two objects, links them, returns root — all local allocations
    static Point createChain() {
        Point inner = new Point(1, 2);
        Point outer = new Point(inner.x, inner.y);
        return outer;
    }

    // Side-effect-free: reads a static field but never writes it
    static int readStaticOnly() {
        return GLOBAL_VALUE;
    }

    // Side-effect-free: returns parameter unchanged — no heap access at all
    static int identityFunction(int x) {
        return x;
    }

    // Side-effecting: mutates p.x in one branch — any-path side effect makes the whole method side-effecting
    static void conditionalMutation(Point p, boolean flag) {
        if (flag) {
            p.x = 0;
        }
        // Even though the mutation only happens on one path, the method is still side-effecting
    }

    // Side-effecting: allocates a local object but then mutates parameter — parameter mutation is side-effecting
    static void unusedAllocation(Point p) {
        int[] local = new int[5];
        local[0] = 99;
        p.x = local[0];
    }

    // Side-effect-free: casts parameter to specific type, returns it — cast is just a type check + copy
    static String castAndReturn(Object obj) {
        String s = (String) obj;
        return s;
    }
}
