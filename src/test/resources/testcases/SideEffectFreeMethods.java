package testcases;
public class SideEffectFreeMethods {
    // Side-effect-free: no heap writes at all
    static int add(int a, int b) {
        return a + b;
    }

    // Side-effect-free: creates new object, mutates only it
    static int[] createArray(int size) {
        int[] arr = new int[size];
        arr[0] = 42;
        return arr;
    }
}
