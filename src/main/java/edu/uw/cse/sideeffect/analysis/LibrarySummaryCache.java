package edu.uw.cse.sideeffect.analysis;

import com.google.gson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Disk-backed cache for library (JDK) method summaries.
 * Persists summaries to jdk-cache/ so they can be reused across CLI invocations.
 */
public class LibrarySummaryCache {

    private static final Path CACHE_DIR = Path.of("jdk-cache");
    private static final Path INDEX_FILE = CACHE_DIR.resolve(".index.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Lazily loaded summaries that were actually needed during this process. */
    private static final Map<String, MethodSummary> cache = new HashMap<>();
    /** Signature -> file name index for all persisted summaries on disk. */
    private static final Map<String, String> fileIndex = new HashMap<>();
    private static boolean indexLoaded = false;

    private LibrarySummaryCache() {}

    /**
     * Load the on-disk cache index.
     * Returns the number of known cached summaries, not the number eagerly deserialized.
     */
    public static synchronized int loadFromDisk() {
        ensureIndexLoaded();
        return fileIndex.size();
    }

    /** Check if a method signature is in the cache. */
    public static synchronized boolean contains(String fullSig) {
        ensureIndexLoaded();
        return cache.containsKey(fullSig) || fileIndex.containsKey(fullSig);
    }

    /** Get a cached summary, or null. */
    public static synchronized MethodSummary get(String fullSig) {
        ensureIndexLoaded();

        MethodSummary cachedSummary = cache.get(fullSig);
        if (cachedSummary != null) {
            return cachedSummary;
        }

        String fileName = fileIndex.get(fullSig);
        if (fileName == null) {
            return null;
        }

        Path file = CACHE_DIR.resolve(fileName);
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            MethodSummary summary = MethodSummarySerializer.deserialize(obj);
            cache.put(summary.getMethodSignature(), summary);
            fileIndex.put(summary.getMethodSignature(), fileName);
            return summary;
        } catch (Exception e) {
            System.err.println("Warning: skipping corrupt cache file " + file.getFileName() + ": " + e.getMessage());
            return null;
        }
    }

    /** Get all cached entries (for pre-populating SummaryCache). */
    public static synchronized Map<String, MethodSummary> getAll() {
        ensureIndexLoaded();
        Map<String, MethodSummary> all = new HashMap<>();
        for (String sig : fileIndex.keySet()) {
            MethodSummary summary = get(sig);
            if (summary != null) {
                all.put(sig, summary);
            }
        }
        return all;
    }

    /**
     * Save a library method summary to both memory and disk.
     */
    public static synchronized void put(String fullSig, MethodSummary summary) {
        ensureIndexLoaded();
        cache.put(fullSig, summary);
        writeToDisk(fullSig, summary);
    }

    private static void writeToDisk(String fullSig, MethodSummary summary) {
        try {
            Files.createDirectories(CACHE_DIR);
            String fileName = sanitizeFileName(fullSig);
            Path file = CACHE_DIR.resolve(fileName + ".json");
            JsonObject obj = MethodSummarySerializer.serialize(summary);
            Files.writeString(file, GSON.toJson(obj), StandardCharsets.UTF_8);
            fileIndex.put(fullSig, file.getFileName().toString());
            writeIndexFile();
        } catch (IOException e) {
            System.err.println("Warning: could not write cache for " + fullSig + ": " + e.getMessage());
        }
    }

    /**
     * Convert a method signature to a readable, filesystem-safe filename.
     * e.g. "&lt;java.util.ArrayList: boolean add(java.lang.Object)&gt;"
     *   → "java.util.ArrayList_boolean_add(java.lang.Object)"
     */
    private static String sanitizeFileName(String sig) {
        return sig.replace('<', ' ')
                  .replace('>', ' ')
                  .replace(':', '_')
                  .replace('/', '_')
                  .replace('\\', '_')
                  .trim();
    }

    /** Return the number of entries currently in memory. */
    public static synchronized int size() {
        ensureIndexLoaded();
        return fileIndex.size();
    }

    private static void ensureIndexLoaded() {
        if (indexLoaded) return;

        cache.clear();
        fileIndex.clear();
        indexLoaded = true;

        if (!Files.isDirectory(CACHE_DIR)) {
            return;
        }

        if (loadIndexFile()) {
            return;
        }

        rebuildIndexFromCacheFiles();
    }

    private static boolean loadIndexFile() {
        if (!Files.isRegularFile(INDEX_FILE)) {
            return false;
        }

        try {
            String json = Files.readString(INDEX_FILE, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject entries = root.getAsJsonObject("entries");
            if (entries == null) {
                return false;
            }
            for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                fileIndex.put(entry.getKey(), entry.getValue().getAsString());
            }
            return true;
        } catch (Exception e) {
            System.err.println("Warning: could not read cache index: " + e.getMessage());
            fileIndex.clear();
            return false;
        }
    }

    private static void rebuildIndexFromCacheFiles() {
        try (Stream<Path> files = Files.list(CACHE_DIR)) {
            for (Path file : files.toList()) {
                if (!Files.isRegularFile(file)) continue;
                if (!file.toString().endsWith(".json")) continue;
                if (file.getFileName().equals(INDEX_FILE.getFileName())) continue;
                try {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                    JsonElement sigElem = obj.get("sig");
                    if (sigElem == null) continue;
                    fileIndex.put(sigElem.getAsString(), file.getFileName().toString());
                } catch (Exception e) {
                    System.err.println("Warning: skipping corrupt cache file " + file.getFileName() + ": " + e.getMessage());
                }
            }
            writeIndexFile();
        } catch (IOException e) {
            System.err.println("Warning: could not read cache directory: " + e.getMessage());
        }
    }

    private static void writeIndexFile() {
        try {
            Files.createDirectories(CACHE_DIR);
            JsonObject root = new JsonObject();
            JsonObject entries = new JsonObject();
            for (Map.Entry<String, String> entry : fileIndex.entrySet()) {
                entries.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("entries", entries);
            Files.writeString(INDEX_FILE, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Warning: could not write cache index: " + e.getMessage());
        }
    }
}
