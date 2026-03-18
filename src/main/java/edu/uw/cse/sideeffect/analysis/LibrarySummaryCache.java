package edu.uw.cse.sideeffect.analysis;

import com.google.gson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Disk-backed cache for library (JDK) method summaries.
 * Persists summaries to jdk-cache/ so they can be reused across CLI invocations.
 */
public class LibrarySummaryCache {

    private static final Path CACHE_DIR = Path.of("jdk-cache");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** In-memory mirror of disk cache, loaded at startup. */
    private static final Map<String, MethodSummary> cache = new HashMap<>();

    private LibrarySummaryCache() {}

    /**
     * Load all cached summaries from disk into memory.
     * @return number of entries loaded
     */
    public static int loadFromDisk() {
        cache.clear();
        if (!Files.isDirectory(CACHE_DIR)) return 0;

        int loaded = 0;
        try (Stream<Path> files = Files.list(CACHE_DIR)) {
            for (Path file : files.toList()) {
                if (!file.toString().endsWith(".json")) continue;
                try {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                    MethodSummary summary = MethodSummarySerializer.deserialize(obj);
                    cache.put(summary.getMethodSignature(), summary);
                    loaded++;
                } catch (Exception e) {
                    // Skip corrupt cache files
                    System.err.println("Warning: skipping corrupt cache file " + file.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: could not read cache directory: " + e.getMessage());
        }
        return loaded;
    }

    /** Check if a method signature is in the cache. */
    public static boolean contains(String fullSig) {
        return cache.containsKey(fullSig);
    }

    /** Get a cached summary, or null. */
    public static MethodSummary get(String fullSig) {
        return cache.get(fullSig);
    }

    /** Get all cached entries (for pre-populating SummaryCache). */
    public static Map<String, MethodSummary> getAll() {
        return new HashMap<>(cache);
    }

    /**
     * Save a library method summary to both memory and disk.
     */
    public static void put(String fullSig, MethodSummary summary) {
        cache.put(fullSig, summary);
        writeToDisk(fullSig, summary);
    }

    private static void writeToDisk(String fullSig, MethodSummary summary) {
        try {
            Files.createDirectories(CACHE_DIR);
            String hash = sha256(fullSig);
            Path file = CACHE_DIR.resolve(hash + ".json");
            JsonObject obj = MethodSummarySerializer.serialize(summary);
            Files.writeString(file, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Warning: could not write cache for " + fullSig + ": " + e.getMessage());
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /** Return the number of entries currently in memory. */
    public static int size() {
        return cache.size();
    }
}
