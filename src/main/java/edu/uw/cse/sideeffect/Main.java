package edu.uw.cse.sideeffect;

import edu.uw.cse.sideeffect.util.TimingRecorder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;


/**
 * CLI entry point for the purity analysis tool.
 *
 * Usage:
 *   ./gradlew run --args="MyFile.java [--show-graph] [--no-merge] [--method <name>] [--timing]"
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
        boolean merge = false;
        boolean debug = false;
        boolean timing = false;
        String methodFilter = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--show-graph" -> showGraph = true;
                case "--merge" -> merge = true;
                case "--debug" -> debug = true;
                case "--timing" -> timing = true;
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

        AnalysisConfig config = new AnalysisConfig(showGraph, merge, methodFilter, debug, timing);

        TimingRecorder timer = config.timing ? new TimingRecorder() : TimingRecorder.NOOP;
        timer.setSourceFiles(sourceFiles);
        timer.startTotal();

        try {
            // Step 1: Compile .java to .class
            System.out.println("Compiling source files...");
            long compileStart = System.nanoTime();
            Path classDir = JavaCompiler.compile(sourceFiles);
            long compileNs = System.nanoTime() - compileStart;
            timer.recordCompilation(compileNs);
            System.out.println("Compiled to: " + classDir);

            // Step 2: Run purity analysis via SootUp
            List<Path> sourcePaths = sourceFiles.stream().map(Path::of).toList();
            SideEffectAnalysisRunner runner = new SideEffectAnalysisRunner(config, classDir, sourcePaths, timer);
            runner.run();

            timer.endTotal();
            timer.printReport();
            timer.saveJson();

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
        System.out.println("  --merge         Enable node merging (Madhavan et al. 2011 optimization)");
        System.out.println("  --method <name> Analyze only the specified method");
        System.out.println("  --debug         Write per-method HTML debug traces to debug/ directory");
        System.out.println("                  (implies --show-graph and --timing)");
        System.out.println("  --timing        Print timing summary and save JSON to timing/ directory");
        System.out.println("  --help, -h      Show this help message");
    }
}
