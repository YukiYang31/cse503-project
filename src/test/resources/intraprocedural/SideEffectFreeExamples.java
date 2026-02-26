package intraprocedural;

import java.util.ArrayList;

class Pair {
    int x;
    int y;

    Pair(int x, int y) {
        this.x = x;
        this.y = y;
    }
}

public class SideEffectFreeExamples {

    // Side-effect-free: arithmetic on primitives, no heap access
    static int sum(int a, int b) {
        return a + b;
    }

    // Side-effect-free: Math.max is whitelisted (java.lang.Math class is safe)
    static int maxOfThree(int a, int b, int c) {
        return Math.max(a, Math.max(b, c));
    }

    // Side-effect-free: Math.abs is whitelisted (java.lang.Math class is safe)
    static double absoluteDiff(double a, double b) {
        return Math.abs(a - b);
    }

    // Side-effect-free: String.concat is side-effect-free (String class is immutable, all methods whitelisted)
    static String greet(String name) {
        return "Hello, ".concat(name);
    }

    // Side-effect-free: reads param.x field, no mutation
    static int getX(Pair p) {
        return p.x;
    }

    // Side-effect-free: reads fields from two parameters, returns computed primitive value
    static int distanceSquared(Pair a, Pair b) {
        int dx = a.x - b.x;
        int dy = a.y - b.y;
        return dx * dx + dy * dy;
    }

    // Side-effect-free: allocates new Pair, mutates only the new object, returns it
    static Pair createPair(int x, int y) {
        Pair p = new Pair(x, y);
        return p;
    }

    // Side-effect-free: String.valueOf and String.concat are whitelisted (String is immutable)
    static String buildMessage(String prefix, int count) {
        String countStr = String.valueOf(count);
        return prefix.concat(": ").concat(countStr);
    }

    // Side-effect-free: creates local ArrayList (whitelisted constructor),
    // add calls operate on a locally-created object
    static ArrayList<Integer> createAndPopulateList(int n) {
        ArrayList<Integer> list = new ArrayList<>();
        list.add(n);
        list.add(n + 1);
        list.add(n + 2);
        return list;
    }

    // Side-effect-free: allocates two local int[] arrays, swaps their contents — only local mutations
    static void localSwap() {
        int[] a = new int[]{1};
        int[] b = new int[]{2};
        int tmp = a[0];
        a[0] = b[0];
        b[0] = tmp;
    }

    // Side-effect-free: reads arr.length (array length access), no mutation
    static int readArrayLength(int[] arr) {
        return arr.length;
    }

    // Side-effect-free: creates and mutates different local objects on each branch — only local mutations
    static Object conditionalCreate(boolean flag) {
        if (flag) {
            int[] arr = new int[3];
            arr[0] = 42;
            return arr;
        } else {
            Pair p = new Pair(0, 0);
            return p;
        }
    }
}
