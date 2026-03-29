package edu.uw.cse.sideeffect.analysis;

import java.util.HashMap;
import java.util.Map;

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

}
