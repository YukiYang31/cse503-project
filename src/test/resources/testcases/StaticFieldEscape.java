public class StaticFieldEscape {
    static Object shared;

    // Impure: stores into a static field
    static void setShared(Object obj) {
        shared = obj;
    }

    // Impure: creates a new object but escapes it to a static field
    static void createAndEscape() {
        Object o = new Object();
        shared = o;
    }

    // Pure: only reads from a static field (no write)
    static Object getShared() {
        return shared;
    }
}
