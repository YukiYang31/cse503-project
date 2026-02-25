package testcases;


public class MutualRecursion {
    public static void foo(int n) {
        if (n > 0) bar(n - 1);
    }
    public static void bar(int n) {
        if (n > 0) foo(n - 1);
    }
}

