package testcases;

public class ConditionalSideEffects {

    // May-alias: if p, y aliases the parameter x; otherwise y is a fresh object.
    // The returned value MAY alias x, so callers can observe a side effect
    // depending on the branch taken.
    static Object mayAliasParam(boolean p, Object x) {
        Object y;
        if (p) {
            y = x;           // y aliases the parameter — potential escape
        } else {
            y = new Object(); // y is a fresh allocation — safe
        }
        return y;
    }

    // Conditionally mutates parameter: side-effecting only on the true branch.
    static void conditionalMutate(boolean p, int[] arr) {
        if (p) {
            arr[0] = 99;     // writes to a parameter-reachable location
        }
        // else: no mutation — pure path
    }

    // Both branches create fresh objects, so the result never aliases a parameter.
    // Side-effect-free regardless of which branch executes.
    static Object alwaysFresh(boolean p) {
        Object y;
        if (p) {
            y = new Object();
        } else {
            y = new Object();
        }
        return y;
    }

    // One branch escapes to a static field — side-effecting on the true branch.
    static Object stored = null;

    static void mayEscapeToStatic(boolean p, Object x) {
        if (p) {
            stored = x;      // x escapes through the static field
        }
        // else: nothing stored — pure path
    }
}
