package edu.uw.cse.purity;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Compiles .java source files to .class files in a temporary directory
 * using the JDK's built-in javax.tools.JavaCompiler.
 */
public class JavaCompiler {

    /**
     * Compiles the given Java source files and returns the path to the
     * directory containing the compiled .class files.
     *
     * @param sourceFiles paths to .java files
     * @return path to temp directory containing .class files
     * @throws IOException if compilation fails or temp dir cannot be created
     */
    public static Path compile(List<String> sourceFiles) throws IOException {
        javax.tools.JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new RuntimeException(
                "No Java compiler available. Ensure you are running with a JDK (not a JRE).");
        }

        Path outputDir = Files.createTempDirectory("purity-analysis-classes");

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                 compiler.getStandardFileManager(diagnostics, null, null)) {

            File[] files = sourceFiles.stream()
                .map(File::new)
                .toArray(File[]::new);

            Iterable<? extends JavaFileObject> compilationUnits =
                fileManager.getJavaFileObjects(files);

            List<String> options = Arrays.asList(
                "-d", outputDir.toString(),
                "-source", "21",
                "-target", "21"
            );

            javax.tools.JavaCompiler.CompilationTask task =
                compiler.getTask(null, fileManager, diagnostics, options, null, compilationUnits);

            boolean success = task.call();
            if (!success) {
                StringBuilder sb = new StringBuilder("Compilation failed:\n");
                diagnostics.getDiagnostics().forEach(d ->
                    sb.append("  ").append(d.toString()).append("\n"));
                throw new RuntimeException(sb.toString());
            }
        }

        return outputDir;
    }
}
