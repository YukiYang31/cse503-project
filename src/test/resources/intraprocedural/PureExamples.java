import java.util.ArrayList;

class Pair {
    int x;
    int y;

    Pair(int x, int y) {
        this.x = x;
        this.y = y;
    }
}

public class PureExamples {

    // Pure: arithmetic on primitives, no heap access
    static int sum(int a, int b) {
        return a + b;
    }

    // Pure: Math.max is whitelisted (java.lang.Math class is safe)
    static int maxOfThree(int a, int b, int c) {
        return Math.max(a, Math.max(b, c));
    }

    // Pure: Math.abs is whitelisted (java.lang.Math class is safe)
    static double absoluteDiff(double a, double b) {
        return Math.abs(a - b);
    }

    // Pure: String.concat is pure (String class is immutable, all methods whitelisted)
    static String greet(String name) {
        return "Hello, ".concat(name);
    }

    // Pure: reads param.x field, no mutation
    static int getX(Pair p) {
        return p.x;
    }

    // Pure: reads fields from two parameters, returns computed primitive value
    static int distanceSquared(Pair a, Pair b) {
        int dx = a.x - b.x;
        int dy = a.y - b.y;
        return dx * dx + dy * dy;
    }

    // Pure: allocates new Pair, mutates only the new object, returns it
    static Pair createPair(int x, int y) {
        Pair p = new Pair(x, y);
        return p;
    }

    // Pure: String.valueOf and String.concat are whitelisted (String is immutable)
    static String buildMessage(String prefix, int count) {
        String countStr = String.valueOf(count);
        return prefix.concat(": ").concat(countStr);
    }

    // Pure: creates local ArrayList (whitelisted constructor),
    // add calls operate on a locally-created object
    static ArrayList<Integer> createAndPopulateList(int n) {
        ArrayList<Integer> list = new ArrayList<>();
        list.add(n);
        list.add(n + 1);
        list.add(n + 2);
        return list;
    }

    // Pure: allocates two local int[] arrays, swaps their contents — only local mutations
    static void localSwap() {
        int[] a = new int[]{1};
        int[] b = new int[]{2};
        int tmp = a[0];
        a[0] = b[0];
        b[0] = tmp;
    }

    // Pure: reads arr.length (array length access), no mutation
    static int readArrayLength(int[] arr) {
        return arr.length;
    }

    // Pure: creates and mutates different local objects on each branch — only local mutations
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
