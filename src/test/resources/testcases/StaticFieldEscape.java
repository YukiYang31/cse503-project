public class StaticFieldEscape {
    static Object shared;

    // Side-effecting: stores into a static field
    static void setShared(Object obj) {
        shared = obj;
    }

    // Side-effecting: creates a new object but escapes it to a static field
    static void createAndEscape() {
        Object o = new Object();
        shared = o;
    }

    // Side-effect-free: only reads from a static field (no write)
    static Object getShared() {
        return shared;
    }
}
