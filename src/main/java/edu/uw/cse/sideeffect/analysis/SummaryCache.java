package edu.uw.cse.sideeffect.analysis;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Cache of method summaries for inter-procedural analysis.
 * Stores summaries keyed by exact full signature only.
 */
public class SummaryCache {

    private final Map<String, MethodSummary> bySignature = new HashMap<>();

    /** Store a summary keyed by its full method signature. */
    public void put(String fullSig, MethodSummary summary) {
        bySignature.put(fullSig, summary);
    }

    /** Exact lookup by full signature only. */
    public MethodSummary lookup(String fullSig) {
        return bySignature.get(fullSig);
    }

    /** Bulk-insert summaries (e.g. from SafeMethods pre-population or disk cache). */
    public void putAll(Map<String, MethodSummary> entries) {
        for (var entry : entries.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    public boolean containsFullSig(String fullSig) {
        return bySignature.containsKey(fullSig);
    }

    public Set<String> keySetSnapshot() {
        return Set.copyOf(bySignature.keySet());
    }

    public int size() {
        return bySignature.size();
    }
}
