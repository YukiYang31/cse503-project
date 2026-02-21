class Vec2 {
    double x;
    double y;

    // Side-effect-free: constructor — writing this.x and this.y is allowed (constructor exception)
    Vec2(double x, double y) {
        this.x = x;
        this.y = y;
    }

    // Side-effecting: same writes as constructor, but this is a regular method — mutates this.x, this.y
    void set(double x, double y) {
        this.x = x;
        this.y = y;
    }

    // Side-effect-free: reads this.x, no mutation
    double getX() {
        return this.x;
    }

    // Side-effecting: writes this.x and this.y (mutates receiver in non-constructor)
    void negate() {
        this.x = -this.x;
        this.y = -this.y;
    }
}

class Container {
    Object data;

    // Side-effect-free: constructor — writing this.data is allowed (constructor exception)
    Container(Object obj) {
        this.data = obj;
    }

    // Side-effecting: same write as constructor, but this is a regular method — mutates this.data
    void setData(Object obj) {
        this.data = obj;
    }

    // Side-effect-free: reads this.data, no mutation
    Object getData() {
        return this.data;
    }
}

class LinkedNode {
    int value;
    LinkedNode next;

    // Side-effect-free: constructor — writing this.value and this.next is allowed (constructor exception)
    LinkedNode(int value, LinkedNode next) {
        this.value = value;
        this.next = next;
    }

    // Side-effecting: mutates this.value in a non-constructor method
    void setValue(int value) {
        this.value = value;
    }

    // Side-effect-free: reads this.value, no mutation
    int getValue() {
        return this.value;
    }
}
