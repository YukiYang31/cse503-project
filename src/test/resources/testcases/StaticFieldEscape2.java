public class StaticFieldEscape2 {
    // This is a "Global Node" (reachable from n_GBL -> Set B)
    public static int counter = 0;

    // IMPURE METHOD
    // It does NOT take 'counter' as a parameter.
    public void incrementCounter() {
        // Mutation happens here directly on the static field
        StaticFieldEscape2.counter = StaticFieldEscape2.counter + 1;
    }
}