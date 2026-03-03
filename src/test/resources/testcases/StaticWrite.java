package testcases;

public class StaticWrite {
    static class Node {
        Object f;
    }

    static Node globalNode = new Node();

    // Side-effecting: direct static field write (global.f = x)
    static void storeToGlobal(Object x) {
        globalNode.f = x;
    }
}
