package edu.uw.cse.sideeffect.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Records timing data for each phase of the purity analysis pipeline.
 * Uses the Null Object pattern: {@link #NOOP} is a zero-cost singleton
 * whose methods are all empty (no {@code System.nanoTime()}, no allocations).
 */
public class TimingRecorder {

    /** No-op singleton — every method is empty, zero overhead when timing is off. */
    public static final TimingRecorder NOOP = new NoopTimingRecorder();

    /** Per-method timing record. */
    public static class MethodTiming {
        public final String methodSignature;
        public final long dataflowNs;
        public final long purityCheckNs;
        public final int jimpleStmtCount;
        public final int exitGraphNodes;
        public final int exitGraphEdges;

        public MethodTiming(String methodSignature, long dataflowNs, long purityCheckNs,
                            int jimpleStmtCount, int exitGraphNodes, int exitGraphEdges) {
            this.methodSignature = methodSignature;
            this.dataflowNs = dataflowNs;
            this.purityCheckNs = purityCheckNs;
            this.jimpleStmtCount = jimpleStmtCount;
            this.exitGraphNodes = exitGraphNodes;
            this.exitGraphEdges = exitGraphEdges;
        }

        public double dataflowMs() { return dataflowNs / 1_000_000.0; }
        public double purityCheckMs() { return purityCheckNs / 1_000_000.0; }
        public double totalMs() { return (dataflowNs + purityCheckNs) / 1_000_000.0; }
    }

    // Phase-level timings (nanoseconds)
    private long totalStartNs;
    private long totalNs;
    private long compilationNs;
    private long irLoadingNs;
    private long callGraphNs;

    // Per-method data (also used to accumulate dataflow/purity totals)
    private final List<MethodTiming> methods = new ArrayList<>();
    private long dataflowTotalNs;
    private long purityCheckTotalNs;

    // Metadata
    private List<String> sourceFiles = List.of();

    public void startTotal() {
        totalStartNs = System.nanoTime();
    }

    public void endTotal() {
        totalNs = System.nanoTime() - totalStartNs;
    }

    public void recordCompilation(long ns) {
        compilationNs = ns;
    }

    public void recordIrLoading(long ns) {
        irLoadingNs = ns;
    }

    public void recordCallGraph(long ns) {
        callGraphNs = ns;
    }

    public void addMethodTiming(MethodTiming mt) {
        methods.add(mt);
        dataflowTotalNs += mt.dataflowNs;
        purityCheckTotalNs += mt.purityCheckNs;
    }

    public void setSourceFiles(List<String> sourceFiles) {
        this.sourceFiles = sourceFiles;
    }

    // --- Helpers ---

    private double ms(long ns) { return ns / 1_000_000.0; }

    private String pct(long partNs, long wholeNs) {
        if (wholeNs == 0) return " 0.0%";
        double p = 100.0 * partNs / wholeNs;
        return String.format("%5.1f%%", p);
    }

    // --- Terminal report ---

    public void printReport() {
        double totalMs = ms(totalNs);
        System.out.println();
        System.out.println("=== Timing Summary ===");
        System.out.println();
        System.out.println("Phase Timings:");
        System.out.printf("  Total wall-clock time:       %8.1f ms%n", totalMs);
        System.out.printf("  Compilation (javac):         %8.1f ms  (%s)%n",
                ms(compilationNs), pct(compilationNs, totalNs));
        System.out.printf("  SootUp IR loading:           %8.1f ms  (%s)%n",
                ms(irLoadingNs), pct(irLoadingNs, totalNs));
        System.out.printf("  Call graph construction:     %8.1f ms  (%s)%n",
                ms(callGraphNs), pct(callGraphNs, totalNs));
        System.out.printf("  Dataflow analysis (total):   %8.1f ms  (%s)%n",
                ms(dataflowTotalNs), pct(dataflowTotalNs, totalNs));
        System.out.printf("  Purity checking (total):     %8.1f ms  (%s)%n",
                ms(purityCheckTotalNs), pct(purityCheckTotalNs, totalNs));
        long accountedNs = compilationNs + irLoadingNs + callGraphNs + dataflowTotalNs + purityCheckTotalNs;
        long overheadNs = totalNs - accountedNs;
        System.out.printf("  Other / overhead:            %8.1f ms  (%s)%n",
                ms(overheadNs), pct(overheadNs, totalNs));

        // Per-method breakdown
        if (!methods.isEmpty()) {
            System.out.println();
            System.out.println("Per-Method Breakdown:");

            // Compute column width for method name
            int maxSigLen = "Method".length();
            for (MethodTiming mt : methods) {
                maxSigLen = Math.max(maxSigLen, mt.methodSignature.length());
            }
            // Pad to at least 40, cap at 80
            int sigWidth = Math.max(40, Math.min(80, maxSigLen + 2));

            String headerFmt = "  %-" + sigWidth + "s | %11s | %11s | %11s | %5s | %s%n";
            String rowFmt    = "  %-" + sigWidth + "s | %8.1f ms | %8.1f ms | %8.1f ms | %5d | %5d + %d%n";
            String sepMethod = "-".repeat(sigWidth);
            String sep = "  " + sepMethod + "-+-------------+-------------+-------------+-------+------------";

            System.out.printf(headerFmt, "Method", "Dataflow", "Purity", "Total", "Stmts", "Graph (N+E)");
            System.out.println(sep);
            for (MethodTiming mt : methods) {
                System.out.printf(rowFmt, mt.methodSignature,
                        mt.dataflowMs(), mt.purityCheckMs(), mt.totalMs(),
                        mt.jimpleStmtCount, mt.exitGraphNodes, mt.exitGraphEdges);
            }
        }

        // Statistics
        int methodCount = methods.size();
        int totalStmts = methods.stream().mapToInt(m -> m.jimpleStmtCount).sum();
        double avgMs = methodCount > 0
                ? ms(dataflowTotalNs + purityCheckTotalNs) / methodCount
                : 0.0;

        System.out.println();
        System.out.println("Statistics:");
        System.out.printf("  Methods analyzed:    %5d%n", methodCount);
        System.out.printf("  Avg time per method: %8.1f ms%n", avgMs);
        System.out.printf("  Total Jimple stmts:  %5d%n", totalStmts);
    }

    // --- JSON output ---

    public void saveJson() {
        LocalDateTime now = LocalDateTime.now();
        String timestamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"));
        String isoTimestamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        String fileName = "timing_" + timestamp + ".json";
        Path dir = Path.of("timing");
        Path file = dir.resolve(fileName);

        try {
            Files.createDirectories(dir);

            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"timestamp\": \"").append(isoTimestamp).append("\",\n");

            // Source files
            sb.append("  \"sourceFiles\": [");
            for (int i = 0; i < sourceFiles.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("\"").append(escapeJson(sourceFiles.get(i))).append("\"");
            }
            sb.append("],\n");

            // Phase timings
            sb.append("  \"phaseTimings\": {\n");
            sb.append("    \"totalMs\": ").append(roundMs(totalNs)).append(",\n");
            sb.append("    \"compilationMs\": ").append(roundMs(compilationNs)).append(",\n");
            sb.append("    \"irLoadingMs\": ").append(roundMs(irLoadingNs)).append(",\n");
            sb.append("    \"callGraphMs\": ").append(roundMs(callGraphNs)).append(",\n");
            sb.append("    \"dataflowTotalMs\": ").append(roundMs(dataflowTotalNs)).append(",\n");
            sb.append("    \"purityCheckTotalMs\": ").append(roundMs(purityCheckTotalNs)).append(",\n");
            long accountedNs = compilationNs + irLoadingNs + callGraphNs + dataflowTotalNs + purityCheckTotalNs;
            long overheadNs = totalNs - accountedNs;
            sb.append("    \"overheadMs\": ").append(roundMs(overheadNs)).append("\n");
            sb.append("  },\n");

            // Methods
            sb.append("  \"methods\": [\n");
            for (int i = 0; i < methods.size(); i++) {
                MethodTiming mt = methods.get(i);
                sb.append("    {\n");
                sb.append("      \"signature\": \"").append(escapeJson(mt.methodSignature)).append("\",\n");
                sb.append("      \"dataflowMs\": ").append(roundMs(mt.dataflowNs)).append(",\n");
                sb.append("      \"purityCheckMs\": ").append(roundMs(mt.purityCheckNs)).append(",\n");
                sb.append("      \"totalMs\": ").append(roundMs(mt.dataflowNs + mt.purityCheckNs)).append(",\n");
                sb.append("      \"jimpleStmtCount\": ").append(mt.jimpleStmtCount).append(",\n");
                sb.append("      \"graphNodes\": ").append(mt.exitGraphNodes).append(",\n");
                sb.append("      \"graphEdges\": ").append(mt.exitGraphEdges).append("\n");
                sb.append("    }");
                if (i < methods.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("  ],\n");

            // Statistics
            int methodCount = methods.size();
            int totalStmts = methods.stream().mapToInt(m -> m.jimpleStmtCount).sum();
            double avgMs = methodCount > 0
                    ? ms(dataflowTotalNs + purityCheckTotalNs) / methodCount
                    : 0.0;

            sb.append("  \"statistics\": {\n");
            sb.append("    \"methodCount\": ").append(methodCount).append(",\n");
            sb.append("    \"avgTimePerMethodMs\": ").append(round1(avgMs)).append(",\n");
            sb.append("    \"totalJimpleStmts\": ").append(totalStmts).append("\n");
            sb.append("  }\n");

            sb.append("}\n");

            Files.writeString(file, sb.toString());
            System.out.println();
            System.out.println("Timing data saved to: " + file);

        } catch (IOException e) {
            System.err.println("Warning: could not save timing JSON: " + e.getMessage());
        }
    }

    private double roundMs(long ns) {
        return Math.round(ns / 100_000.0) / 10.0; // round to 1 decimal
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // --- Null Object ---

    private static class NoopTimingRecorder extends TimingRecorder {
        @Override public void startTotal() {}
        @Override public void endTotal() {}
        @Override public void recordCompilation(long ns) {}
        @Override public void recordIrLoading(long ns) {}
        @Override public void recordCallGraph(long ns) {}
        @Override public void addMethodTiming(MethodTiming mt) {}
        @Override public void setSourceFiles(List<String> sourceFiles) {}
        @Override public void printReport() {}
        @Override public void saveJson() {}
    }
}
