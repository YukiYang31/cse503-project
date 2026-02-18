package edu.uw.cse.purity.analysis;

import java.util.HashMap;
import java.util.Map;

/**
 * Cache of method summaries for inter-procedural analysis.
 * Stores summaries keyed by both full signature and sub-signature
 * to support virtual/interface dispatch resolution.
 */
public class SummaryCache {

    private final Map<String, MethodSummary> bySignature = new HashMap<>();
    private final Map<String, MethodSummary> bySubSignature = new HashMap<>();

    /**
     * Store a summary keyed by both full signature and sub-signature.
     *
     * @param fullSig  full method signature (e.g., "&lt;ListItr: boolean hasNext()&gt;")
     * @param subSig   sub-signature (e.g., "boolean hasNext()")
     * @param summary  the method summary to store
     */
    public void put(String fullSig, String subSig, MethodSummary summary) {
        bySignature.put(fullSig, summary);
        if (subSig != null) {
            bySubSignature.put(subSig, summary);
        }
    }

    /**
     * Look up a summary: tries exact full signature first, then falls back to sub-signature.
     * This handles virtual/interface dispatch where the call site uses an interface type
     * but the implementation is in a concrete class.
     */
    public MethodSummary lookup(String fullSig, String subSig) {
        MethodSummary result = bySignature.get(fullSig);
        if (result != null) return result;
        if (subSig != null) {
            return bySubSignature.get(subSig);
        }
        return null;
    }

    public int size() {
        return bySignature.size();
    }
}
