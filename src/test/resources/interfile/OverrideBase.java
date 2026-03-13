// Base class for override analysis test.
// Base.process() is side-effect-free: it only reads from its parameter.
class OverrideBase {
    Object value;

    OverrideBase(Object v) {
        this.value = v;
    }

    // SEF: only reads from this
    Object process(Object input) {
        return this.value;
    }

    // Caller that dispatches virtually — effective SEF depends on all overrides
    Object caller(OverrideBase b, Object input) {
        return b.process(input);
    }
}
