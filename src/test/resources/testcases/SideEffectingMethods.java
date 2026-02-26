package testcases;
public class SideEffectingMethods {
    static int counter = 0;

    // Side-effecting: mutates a parameter's field
    static void setX(int[] arr, int val) {
        arr[0] = val;
    }

    // Side-effecting: writes to a static field
    static void increment() {
        counter = counter + 1;
    }

    // Side-effecting: reads a static field and writes to it
    static int getAndIncrement() {
        int old = counter;
        counter = old + 1;
        return old;
    }
}
