import java.util.ArrayList;

public class NewObjectMutation {
    // Pure: creates a new ArrayList, mutates only it, returns it
    static ArrayList<String> createList() {
        ArrayList<String> list = new ArrayList<>();
        list.add("hello");
        return list;
    }

    // Impure: mutates the parameter list
    static void addToList(ArrayList<String> list, String item) {
        list.add(item);
    }

    // Pure: creates a new object and returns it
    static int[] copyFirst(int[] src) {
        int[] dst = new int[1];
        return dst;
    }
}
