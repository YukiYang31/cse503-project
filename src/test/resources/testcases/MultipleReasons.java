package testcases;

/**
 * Test cases for methods that have multiple simultaneous side-effect violations.
 *
 * Before the fix, analysis would stop and return on the FIRST violation found.
 * After the fix, ALL violations are accumulated and returned together.
 *
 * Each method below documents the expected set of reasons.
 */
public class MultipleReasons {

    static Object cache1 = null;
    static Object cache2 = null;

    static class Box {
        Object value;
        Object extra;
    }

    /**
     * SIDE_EFFECTING — two reasons:
     *   1. writes to static field 'cache1'
     *   2. writes to static field 'cache2'
     */
    static void writeTwoStaticFields(Object x) {
        cache1 = x;
        cache2 = x;
    }

    /**
     * SIDE_EFFECTING — two reasons:
     *   1. mutates parameter 'a' via array element
     *   2. mutates parameter 'b' via array element
     */
    static void mutateTwoParams(int[] a, int[] b) {
        a[0] = 1;
        b[0] = 2;
    }

    /**
     * SIDE_EFFECTING — two reasons:
     *   1. writes to static field 'cache1'
     *   2. mutates parameter 'box' via field 'value'
     */
    static void staticWriteAndParamMutation(Box box, Object x) {
        cache1 = x;
        box.value = x;
    }

    /**
     * SIDE_EFFECTING — three reasons:
     *   1. writes to static field 'cache1'
     *   2. writes to static field 'cache2'
     *   3. mutates parameter 'box' via field 'value'
     */
    static void allThreeViolations(Box box, Object x) {
        cache1 = x;
        cache2 = x;
        box.value = x;
    }

    /**
     * SIDE_EFFECT_FREE — new Box is freshly allocated; no prestate mutation.
     */
    static Box createBox(Object x) {
        Box b = new Box();
        b.value = x;
        return b;
    }
}
