public class ImpureMethods {
    static int counter = 0;

    // Impure: mutates a parameter's field
    static void setX(int[] arr, int val) {
        arr[0] = val;
    }

    // Impure: writes to a static field
    static void increment() {
        counter = counter + 1;
    }

    // Impure: reads a static field and writes to it
    static int getAndIncrement() {
        int old = counter;
        counter = old + 1;
        return old;
    }
}
