package edu.uw.cse.purity.util;

import sootup.core.signatures.MethodSignature;

import java.util.HashSet;
import java.util.Set;

/**
 * Whitelist of methods known to be pure (no side effects on prestate objects).
 * Includes constructors of standard library classes (Critical Fix #1).
 *
 * For intra-procedural analysis, any call not in this list is conservatively
 * treated as impure.
 */
public class SafeMethods {

    private static final Set<String> SAFE_CLASS_PREFIXES = new HashSet<>();
    private static final Set<String> SAFE_METHOD_SIGNATURES = new HashSet<>();
    private static final Set<String> SAFE_CONSTRUCTOR_CLASSES = new HashSet<>();

    static {
        // --- Constructors (Critical Fix #1: the <init> trap) ---
        // Without these, every 'new' allocation would be flagged impure
        SAFE_CONSTRUCTOR_CLASSES.add("java.lang.Object");
        SAFE_CONSTRUCTOR_CLASSES.add("java.lang.String");
        SAFE_CONSTRUCTOR_CLASSES.add("java.lang.StringBuilder");
        SAFE_CONSTRUCTOR_CLASSES.add("java.lang.StringBuffer");
        SAFE_CONSTRUCTOR_CLASSES.add("java.lang.Integer");
        SAFE_CONSTRUCTOR_CLASSES.add("java.lang.Long");
        SAFE_CONSTRUCTOR_CLASSES.add("java.lang.Double");
        SAFE_CONSTRUCTOR_CLASSES.add("java.lang.Float");
        SAFE_CONSTRUCTOR_CLASSES.add("java.lang.Boolean");
        SAFE_CONSTRUCTOR_CLASSES.add("java.lang.Byte");
        SAFE_CONSTRUCTOR_CLASSES.add("java.lang.Short");
        SAFE_CONSTRUCTOR_CLASSES.add("java.lang.Character");
        SAFE_CONSTRUCTOR_CLASSES.add("java.lang.Number");
        SAFE_CONSTRUCTOR_CLASSES.add("java.util.ArrayList");
        SAFE_CONSTRUCTOR_CLASSES.add("java.util.LinkedList");
        SAFE_CONSTRUCTOR_CLASSES.add("java.util.HashMap");
        SAFE_CONSTRUCTOR_CLASSES.add("java.util.LinkedHashMap");
        SAFE_CONSTRUCTOR_CLASSES.add("java.util.TreeMap");
        SAFE_CONSTRUCTOR_CLASSES.add("java.util.HashSet");
        SAFE_CONSTRUCTOR_CLASSES.add("java.util.LinkedHashSet");
        SAFE_CONSTRUCTOR_CLASSES.add("java.util.TreeSet");
        SAFE_CONSTRUCTOR_CLASSES.add("java.util.Vector");
        SAFE_CONSTRUCTOR_CLASSES.add("java.util.Stack");
        SAFE_CONSTRUCTOR_CLASSES.add("java.util.ArrayDeque");
        SAFE_CONSTRUCTOR_CLASSES.add("java.util.PriorityQueue");
        SAFE_CONSTRUCTOR_CLASSES.add("java.util.Hashtable");
        SAFE_CONSTRUCTOR_CLASSES.add("java.util.Properties");
        SAFE_CONSTRUCTOR_CLASSES.add("java.io.ByteArrayOutputStream");
        SAFE_CONSTRUCTOR_CLASSES.add("java.io.StringWriter");

        // --- Pure query methods (classes where all methods are pure) ---
        SAFE_CLASS_PREFIXES.add("java.lang.Math");
        SAFE_CLASS_PREFIXES.add("java.lang.StrictMath");

        // --- Individual pure methods ---
        // Object
        SAFE_METHOD_SIGNATURES.add("java.lang.Object#hashCode");
        SAFE_METHOD_SIGNATURES.add("java.lang.Object#equals");
        SAFE_METHOD_SIGNATURES.add("java.lang.Object#toString");
        SAFE_METHOD_SIGNATURES.add("java.lang.Object#getClass");

        // String (all String methods are pure — String is immutable)
        SAFE_CLASS_PREFIXES.add("java.lang.String");

        // Wrapper valueOf methods
        SAFE_METHOD_SIGNATURES.add("java.lang.Integer#valueOf");
        SAFE_METHOD_SIGNATURES.add("java.lang.Long#valueOf");
        SAFE_METHOD_SIGNATURES.add("java.lang.Double#valueOf");
        SAFE_METHOD_SIGNATURES.add("java.lang.Float#valueOf");
        SAFE_METHOD_SIGNATURES.add("java.lang.Boolean#valueOf");
        SAFE_METHOD_SIGNATURES.add("java.lang.Byte#valueOf");
        SAFE_METHOD_SIGNATURES.add("java.lang.Short#valueOf");
        SAFE_METHOD_SIGNATURES.add("java.lang.Character#valueOf");

        // Wrapper conversion methods
        SAFE_METHOD_SIGNATURES.add("java.lang.Integer#intValue");
        SAFE_METHOD_SIGNATURES.add("java.lang.Long#longValue");
        SAFE_METHOD_SIGNATURES.add("java.lang.Double#doubleValue");
        SAFE_METHOD_SIGNATURES.add("java.lang.Float#floatValue");
        SAFE_METHOD_SIGNATURES.add("java.lang.Boolean#booleanValue");
        SAFE_METHOD_SIGNATURES.add("java.lang.Byte#byteValue");
        SAFE_METHOD_SIGNATURES.add("java.lang.Short#shortValue");
        SAFE_METHOD_SIGNATURES.add("java.lang.Character#charValue");
        SAFE_METHOD_SIGNATURES.add("java.lang.Number#intValue");
        SAFE_METHOD_SIGNATURES.add("java.lang.Number#longValue");
        SAFE_METHOD_SIGNATURES.add("java.lang.Number#doubleValue");
        SAFE_METHOD_SIGNATURES.add("java.lang.Number#floatValue");

        // Collections query methods
        SAFE_METHOD_SIGNATURES.add("java.util.Collection#size");
        SAFE_METHOD_SIGNATURES.add("java.util.Collection#isEmpty");
        SAFE_METHOD_SIGNATURES.add("java.util.Collection#contains");
        SAFE_METHOD_SIGNATURES.add("java.util.Collection#iterator");
        SAFE_METHOD_SIGNATURES.add("java.util.List#get");
        SAFE_METHOD_SIGNATURES.add("java.util.List#indexOf");
        SAFE_METHOD_SIGNATURES.add("java.util.Map#get");
        SAFE_METHOD_SIGNATURES.add("java.util.Map#containsKey");
        SAFE_METHOD_SIGNATURES.add("java.util.Map#containsValue");
        SAFE_METHOD_SIGNATURES.add("java.util.Map#size");
        SAFE_METHOD_SIGNATURES.add("java.util.Map#isEmpty");
        SAFE_METHOD_SIGNATURES.add("java.util.Map#keySet");
        SAFE_METHOD_SIGNATURES.add("java.util.Map#values");
        SAFE_METHOD_SIGNATURES.add("java.util.Map#entrySet");

        // Arrays
        SAFE_METHOD_SIGNATURES.add("java.util.Arrays#copyOf");
        SAFE_METHOD_SIGNATURES.add("java.util.Arrays#sort");
        SAFE_METHOD_SIGNATURES.add("java.util.Arrays#toString");
        SAFE_METHOD_SIGNATURES.add("java.util.Arrays#asList");

        // System
        SAFE_METHOD_SIGNATURES.add("java.lang.System#identityHashCode");
    }

    /**
     * Check if a method call is known to be safe (pure).
     */
    public static boolean isSafe(MethodSignature methodSig) {
        String className = methodSig.getDeclClassType().getFullyQualifiedName();
        String methodName = methodSig.getName();

        // Check if it's a constructor of a safe class
        if ("<init>".equals(methodName)) {
            return SAFE_CONSTRUCTOR_CLASSES.contains(className);
        }

        // Check if the entire class is safe
        for (String prefix : SAFE_CLASS_PREFIXES) {
            if (className.equals(prefix) || className.startsWith(prefix + ".")) {
                return true;
            }
        }

        // Check individual method
        String key = className + "#" + methodName;
        return SAFE_METHOD_SIGNATURES.contains(key);
    }

    /**
     * Check if a constructor is whitelisted.
     */
    public static boolean isSafeConstructor(String className) {
        return SAFE_CONSTRUCTOR_CLASSES.contains(className);
    }
}
