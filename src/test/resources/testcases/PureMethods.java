public class PureMethods {
    // Pure: no heap writes at all
    static int add(int a, int b) {
        return a + b;
    }

    // Pure: creates new object, mutates only it
    static int[] createArray(int size) {
        int[] arr = new int[size];
        arr[0] = 42;
        return arr;
    }
}
