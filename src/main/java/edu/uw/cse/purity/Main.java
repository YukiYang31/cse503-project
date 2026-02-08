package edu.uw.cse.purity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI entry point for the purity analysis tool.
 *
 * Usage:
 *   ./gradlew run --args="MyFile.java [--show-graph] [--no-merge] [--method <name>]"
 */
public class Main {

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        // Parse CLI arguments
        List<String> sourceFiles = new ArrayList<>();
        boolean showGraph = false;
        boolean noMerge = false;
        boolean debug = false;
        String methodFilter = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--show-graph" -> showGraph = true;
                case "--no-merge" -> noMerge = true;
                case "--debug" -> debug = true;
                case "--method" -> {
                    if (i + 1 < args.length) {
                        methodFilter = args[++i];
                    } else {
                        System.err.println("Error: --method requires a method name argument");
                        System.exit(1);
                    }
                }
                case "--help", "-h" -> {
                    printUsage();
                    System.exit(0);
                }
                default -> {
                    if (args[i].startsWith("--")) {
                        System.err.println("Unknown option: " + args[i]);
                        System.exit(1);
                    }
                    sourceFiles.add(args[i]);
                }
            }
        }

        if (sourceFiles.isEmpty()) {
            System.err.println("Error: no Java source files specified");
            printUsage();
            System.exit(1);
        }

        AnalysisConfig config = new AnalysisConfig(showGraph, noMerge, methodFilter, debug);

        try {
            // Step 1: Compile .java to .class
            System.out.println("Compiling source files...");
            Path classDir = JavaCompiler.compile(sourceFiles);
            System.out.println("Compiled to: " + classDir);

            // Step 2: Run purity analysis via SootUp
            List<Path> sourcePaths = sourceFiles.stream().map(Path::of).toList();
            PurityAnalysisRunner runner = new PurityAnalysisRunner(config, classDir, sourcePaths);
            runner.run();

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("Java Purity Analysis Tool");
        System.out.println();
        System.out.println("Usage: ./gradlew run --args=\"<file.java> [options]\"");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --show-graph    Print points-to graphs and generate DOT files");
        System.out.println("  --no-merge      Disable node merging (show pure 2005-style graphs)");
        System.out.println("  --method <name> Analyze only the specified method");
        System.out.println("  --debug         Write per-method HTML debug traces to debug/ directory");
        System.out.println("  --help, -h      Show this help message");
    }
}
