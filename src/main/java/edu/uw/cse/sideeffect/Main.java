package edu.uw.cse.sideeffect;

import edu.uw.cse.sideeffect.util.TimingRecorder;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * CLI entry point for the side-effect analysis tool.
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

        // Verify all source files exist before attempting compilation
        boolean anyMissing = false;
        for (String f : sourceFiles) {
            if (!Files.exists(Path.of(f))) {
                System.err.println("Error: file not found: " + Path.of(f).toAbsolutePath());
                anyMissing = true;
            }
        }
        if (anyMissing) {
            System.err.println("Paths are resolved relative to the working directory: "
                + Path.of("").toAbsolutePath());
            System.exit(1);
        }

        AnalysisConfig config = new AnalysisConfig(showGraph, merge, methodFilter, debug, timing);

        TimingRecorder timer = config.timing ? new TimingRecorder() : TimingRecorder.NOOP;
        timer.setSourceFiles(sourceFiles);
        timer.startTotal();

        try {
            // Detect if source files are JDK sources (can't compile standalone)
            Set<String> jrtClassNames = detectJdkClasses(sourceFiles);
            List<Path> sourcePaths = sourceFiles.stream().map(Path::of).toList();
            SideEffectAnalysisRunner runner;

            if (!jrtClassNames.isEmpty()) {
                // JDK source: load pre-compiled classes from JDK runtime (jrt:/ filesystem)
                System.out.println("Detected JDK source file(s). Using JRT runtime classes (skipping compilation).");
                System.out.println("Target classes: " + jrtClassNames);
                runner = SideEffectAnalysisRunner.forJrt(config, jrtClassNames, sourcePaths, timer);
            } else {
                // Normal source: compile first
                System.out.println("Compiling source files...");
                long compileStart = System.nanoTime();
                Path classDir = JavaCompiler.compile(sourceFiles);
                long compileNs = System.nanoTime() - compileStart;
                timer.recordCompilation(compileNs);
                System.out.println("Compiled to: " + classDir);
                runner = new SideEffectAnalysisRunner(config, classDir, sourcePaths, timer);
            }

            // Step 2: Run side-effect analysis via SootUp
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

    /**
     * Detect JDK source files and extract their fully qualified class names,
     * including inner classes discovered from the JRT filesystem.
     * JDK sources match the pattern: jdk/src/<module>/share/classes/<package/path>/Class.java
     * These cannot be compiled standalone, so we load from the JRT filesystem instead.
     *
     * @return set of FQCNs for JDK classes (top-level + inner), or empty set if none are JDK sources
     */
    private static Set<String> detectJdkClasses(List<String> sourceFiles) {
        // Pattern: jdk/src/<module>/share/classes/<path>.java
        Pattern jdkPattern = Pattern.compile(
            "jdk/src/([^/]+)/share/classes/(.+)\\.java$");
        Set<String> classNames = new HashSet<>();
        for (String file : sourceFiles) {
            // Normalize to forward slashes for matching
            String normalized = file.replace('\\', '/');
            Matcher m = jdkPattern.matcher(normalized);
            if (m.find()) {
                String module = m.group(1);  // e.g., "java.base"
                String classPath = m.group(2);  // e.g., "java/util/HashMap"
                String topLevelFqcn = classPath.replace('/', '.');
                classNames.add(topLevelFqcn);

                // Discover inner classes from the JRT filesystem
                // e.g., for HashMap, find HashMap$Node.class, HashMap$KeySet.class, etc.
                String simpleClassName = classPath.substring(classPath.lastIndexOf('/') + 1);
                String packageDir = classPath.substring(0, classPath.lastIndexOf('/') + 1);
                try {
                    FileSystem jrtFs = FileSystems.getFileSystem(URI.create("jrt:/"));
                    Path moduleDir = jrtFs.getPath("/modules/" + module + "/" + packageDir);
                    if (Files.isDirectory(moduleDir)) {
                        String prefix = simpleClassName + "$";
                        Files.list(moduleDir)
                            .map(p -> p.getFileName().toString())
                            .filter(name -> name.startsWith(prefix) && name.endsWith(".class"))
                            .forEach(name -> {
                                // HashMap$Node.class -> java.util.HashMap$Node
                                String innerFqcn = (packageDir + name.replace(".class", ""))
                                    .replace('/', '.');
                                classNames.add(innerFqcn);
                            });
                    }
                } catch (Exception e) {
                    // JRT not available or scan failed — proceed with top-level only
                    System.err.println("Warning: could not scan JRT for inner classes: " + e.getMessage());
                }
            }
        }
        return classNames;
    }

    private static void printUsage() {
        System.out.println("Java Side-Effect Analysis Tool");
        System.out.println();
        System.out.println("Usage: ./gradlew run --args=\"<file.java> [options]\"");
        System.out.println();
        System.out.println("  Supports both user source files and JDK source files.");
        System.out.println("  JDK files (under jdk/src/) are loaded from runtime bytecode.");
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
