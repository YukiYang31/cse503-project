package testcases;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Test case combining JDK library calls with nested inner classes.
 *
 * Expected verdicts:
 *   Container.<init>()          : SIDE_EFFECT_FREE  (constructor exception)
 *   Container.size()            : SIDE_EFFECT_FREE  (delegates to ArrayList.size via inner Shelf)
 *   Container.addItem(Object)   : SIDE_EFFECTING    (mutates this.shelf which is prestate)
 *   Container.getSnapshot()     : SIDE_EFFECT_FREE  (creates new list, reads only)
 *   Container.clear()           : SIDE_EFFECTING    (mutates prestate ArrayList)
 *   Shelf.<init>(int)           : SIDE_EFFECT_FREE  (constructor exception)
 *   Shelf.count()               : SIDE_EFFECT_FREE  (reads ArrayList.size)
 *   Shelf.put(Object)           : SIDE_EFFECTING    (mutates prestate ArrayList)
 *   Shelf.copyItems()           : SIDE_EFFECT_FREE  (creates new ArrayList, reads only)
 *   Shelf.ItemView.<init>()     : SIDE_EFFECT_FREE  (constructor exception)
 *   Shelf.ItemView.label()      : SIDE_EFFECT_FREE  (string concat via invokedynamic, String.valueOf via JDK)
 *   Shelf.ItemView.setLabel(..) : SIDE_EFFECTING    (mutates prestate prefix field)
 *   summarize(Container)        : SIDE_EFFECT_FREE  (reads only, delegates through inner classes + JDK)
 *   mutateViaInner(Container)   : SIDE_EFFECTING    (calls shelf.put which mutates)
 */
public class InnerClassAndJdk {

    // --- Inner class: Shelf wraps an ArrayList ---
    static class Shelf {
        private final ArrayList<Object> items;
        private final int capacity;

        Shelf(int capacity) {
            this.items = new ArrayList<>();
            this.capacity = capacity;
        }

        // Delegates to JDK ArrayList.size() — should be SIDE_EFFECT_FREE
        int count() {
            return items.size();
        }

        // Mutates the prestate ArrayList — SIDE_EFFECTING
        void put(Object item) {
            items.add(item);
        }

        // Creates a new ArrayList copy — SIDE_EFFECT_FREE
        ArrayList<Object> copyItems() {
            ArrayList<Object> copy = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                copy.add(items.get(i));
            }
            return copy;
        }

        // --- Doubly-nested inner class: ItemView ---
        class ItemView {
            private String prefix;

            ItemView() {
                this.prefix = "item";
            }

            // Pure: String.valueOf + concat via JDK — SIDE_EFFECT_FREE
            String label() {
                return prefix + ": " + String.valueOf(count());
            }

            // Mutates prestate field — SIDE_EFFECTING
            void setLabel(String newPrefix) {
                this.prefix = newPrefix;
            }
        }
    }

    // --- Outer class: Container uses Shelf ---
    static class Container {
        private final Shelf shelf;

        Container() {
            this.shelf = new Shelf(10);
        }

        // Delegates through inner class to JDK — SIDE_EFFECT_FREE
        int size() {
            return shelf.count();
        }

        // Mutates shelf's internal ArrayList — SIDE_EFFECTING
        void addItem(Object item) {
            shelf.put(item);
        }

        // Creates a snapshot via inner class — SIDE_EFFECT_FREE
        ArrayList<Object> getSnapshot() {
            return shelf.copyItems();
        }

        // Mutates via JDK Collections — SIDE_EFFECTING
        void clear() {
            shelf.items.clear();
        }
    }

    // --- Static methods exercising the full chain ---

    // Reads size through Container → Shelf → ArrayList.size() — SIDE_EFFECT_FREE
    static int summarize(Container c) {
        int total = c.size();
        return total;
    }

    // Mutates through Container → Shelf.put → ArrayList.add — SIDE_EFFECTING
    static void mutateViaInner(Container c) {
        c.addItem("hello");
    }

    public static void main(String[] args) {
        Container c = new Container();
        c.addItem("a");
        c.addItem("b");
        int n = summarize(c);
        mutateViaInner(c);
        ArrayList<Object> snap = c.getSnapshot();
    }
}
