class Elem {
    int value;
}

class ArrayExamples {
    // SIDE_EFFECTING: loads element from parameter array then mutates the element object
    void mutateLoadedElement(Elem[] arr) {
        Elem e = arr[0];
        e.value = 42;
    }

    // SIDE_EFFECTING: stores into a parameter array (mutates the array itself)
    void storeIntoParamArray(Elem[] arr) {
        arr[0] = new Elem();
    }

    // SIDE_EFFECT_FREE: creates a fresh array and stores into it — no prestate mutation
    Elem[] createAndFillFreshArray() {
        Elem[] arr = new Elem[2];
        arr[0] = new Elem();
        arr[1] = new Elem();
        return arr;
    }

    // SIDE_EFFECT_FREE: reads array element but never mutates anything
    int readArrayElement(Elem[] arr) {
        Elem e = arr[0];
        return e.value;
    }
}
