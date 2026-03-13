// Derived class for override analysis test.
// Derived.process() is side-effecting: it mutates this.value.
class OverrideDerived extends OverrideBase {

    OverrideDerived(Object v) {
        super(v);
    }

    // SE: mutates this.value — so any virtual call to process() must be SIDE_EFFECTING
    @Override
    Object process(Object input) {
        this.value = input;   // mutation of pre-existing field
        return this.value;
    }
}
