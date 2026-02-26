package intraprocedural;

class Counter {
    int count;

    Counter(int count) {
        this.count = count;
    }

    // Side-effecting: mutates this.count in a non-constructor method
    void resetCount() {
        this.count = 0;
    }
}

class Wallet {
    Account account;

    Wallet(Account account) {
        this.account = account;
    }
}

class Account {
    int balance;

    Account(int balance) {
        this.balance = balance;
    }
}

class IntPair {
    int x;
    int y;

    IntPair(int x, int y) {
        this.x = x;
        this.y = y;
    }
}

public class SideEffectingExamples {
    static Object sharedRef;

    // Side-effecting: mutates parameter's field (c.count += 1)
    static void incrementField(Counter c) {
        c.count += 1;
    }

    // Side-effecting: mutates parameter array element (arr[0] = 0)
    static void resetArray(int[] arr) {
        arr[0] = 0;
    }

    // Side-effecting: reads and writes parameter's fields (p.x, p.y swap)
    static void swapFields(IntPair p) {
        int tmp = p.x;
        p.x = p.y;
        p.y = tmp;
    }

    // Side-effecting: stores parameter reference into static field (parameter escapes to global scope)
    static void escapeToStatic(Object obj) {
        sharedRef = obj;
    }

    // Side-effecting: stores parameter into static field (parameter escapes to global scope)
    static void storeCounterGlobally(Counter c) {
        sharedRef = c;
    }

    // Side-effecting: stores parameter's reachable object into static field (transitive escape)
    static void escapeNested(Wallet w) {
        sharedRef = w.account;
    }

    // Side-effecting: transitive mutation through parameter's field (w.account.balance += amount)
    static void mutateNested(Wallet w, int amount) {
        w.account.balance += amount;
    }
}
