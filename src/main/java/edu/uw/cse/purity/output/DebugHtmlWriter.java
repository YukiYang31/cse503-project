package edu.uw.cse.purity.output;

import edu.uw.cse.purity.graph.Node;
import edu.uw.cse.purity.graph.PointsToGraph;
import edu.uw.cse.purity.graph.PointsToGraph.MutatedField;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Accumulates debug data for a single method and writes a self-contained HTML file
 * with visual points-to graph renderings via viz.js.
 */
public class DebugHtmlWriter implements Closeable {

    public record SourceFile(String fileName, String content) {}
    private record TraceEntry(int stepNumber, String stmtText, String dotSource, String mutatedFieldsText) {}

    private final String methodSig;
    private final Path outputPath;

    private List<SourceFile> sourceFiles = List.of();
    private final List<String> jimpleStatements = new ArrayList<>();
    private final List<TraceEntry> traceEntries = new ArrayList<>();
    private int stepCounter = 0;

    private String exitGraphDot;
    private String prestateNodesText;
    private String mutatedFieldsText;
    private boolean hasGlobalSideEffect;
    private String purityResult;
    private String purityReason;

    private DebugHtmlWriter(String methodSig, Path outputPath) {
        this.methodSig = methodSig;
        this.outputPath = outputPath;
    }

    /**
     * Create a DebugHtmlWriter for a method. Creates the debug/ directory if needed.
     */
    public static DebugHtmlWriter create(String methodSig) throws IOException {
        Path debugDir = Path.of("debug");
        Files.createDirectories(debugDir);
        String safeName = GraphPrinter.sanitizeFileName(methodSig);
        Path filePath = debugDir.resolve(safeName + "_debug.html");
        return new DebugHtmlWriter(methodSig, filePath);
    }

    public void setSourceCode(List<SourceFile> sources) {
        this.sourceFiles = sources;
    }

    public void addJimpleStatement(String stmtText) {
        jimpleStatements.add(stmtText);
    }

    /**
     * Record a trace entry: generates a DOT string from the current graph state.
     */
    public void addTraceEntry(String stmtText, PointsToGraph graph) {
        stepCounter++;
        String dotSource = GraphPrinter.generateDotString(graph, "Step " + stepCounter);
        String wText = formatMutatedFields(graph.getMutatedFields());
        traceEntries.add(new TraceEntry(stepCounter, stmtText, dotSource, wText));
    }

    public void setExitGraph(PointsToGraph graph) {
        this.exitGraphDot = GraphPrinter.generateDotString(graph, "Exit Graph");
        this.hasGlobalSideEffect = graph.hasGlobalSideEffect();
    }

    public void setPrestateNodes(Set<Node> nodes) {
        List<String> ids = nodes.stream().map(Node::getId).sorted().toList();
        this.prestateNodesText = "{" + String.join(", ", ids) + "}";
    }

    private static String formatMutatedFields(Set<MutatedField> mutations) {
        List<String> mutStrs = new ArrayList<>();
        for (MutatedField mf : mutations) {
            String fieldName = mf.field() != null ? mf.field().getName() : "[]";
            mutStrs.add("(" + mf.node().getId() + ", " + fieldName + ")");
        }
        mutStrs.sort(String::compareTo);
        return "{" + String.join(", ", mutStrs) + "}";
    }

    public void setMutatedFields(Set<MutatedField> mutations) {
        this.mutatedFieldsText = formatMutatedFields(mutations);
    }

    public void setPurityResult(String result, String reason) {
        this.purityResult = result;
        this.purityReason = reason;
    }

    @Override
    public void close() throws IOException {
        try (PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(outputPath.toFile())))) {
            writeHtml(out);
        }
        System.out.println("Debug HTML written to: " + outputPath);
    }

    private void writeHtml(PrintWriter out) {
        out.println("<!DOCTYPE html>");
        out.println("<html lang=\"en\">");
        out.println("<head>");
        out.println("<meta charset=\"UTF-8\">");
        out.println("<title>Debug: " + escapeHtml(methodSig) + "</title>");
        out.println("<script src=\"https://unpkg.com/@viz-js/viz@3.11.0/lib/viz-standalone.js\"></script>");
        out.println("<style>");
        out.println(CSS);
        out.println("</style>");
        out.println("</head>");
        out.println("<body>");

        // Header
        out.println("<h1>Debug Trace</h1>");
        out.println("<p class=\"method-sig\">" + escapeHtml(methodSig) + "</p>");

        // Java Source Code
        if (!sourceFiles.isEmpty()) {
            out.println("<h2>Java Source Code</h2>");
            for (SourceFile sf : sourceFiles) {
                out.println("<p class=\"source-filename\">" + escapeHtml(sf.fileName()) + "</p>");
                out.println("<pre class=\"source-code\">");
                String[] lines = sf.content().split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    out.println(String.format("<span class=\"line-num\">%3d</span>  %s",
                        i + 1, escapeHtml(lines[i])));
                }
                out.println("</pre>");
            }
        }

        // Jimple Body
        out.println("<h2>Jimple Body</h2>");
        out.println("<pre class=\"jimple\">");
        for (int i = 0; i < jimpleStatements.size(); i++) {
            out.println(String.format("<span class=\"line-num\">%3d</span>  %s",
                i + 1, escapeHtml(jimpleStatements.get(i))));
        }
        out.println("</pre>");

        // Analysis Trace
        out.println("<h2>Analysis Trace (Key Milestones)</h2>");
        if (traceEntries.isEmpty()) {
            out.println("<p class=\"muted\">No key milestone statements found.</p>");
        }
        for (TraceEntry entry : traceEntries) {
            out.println("<div class=\"step\">");
            out.println("<h3>Step " + entry.stepNumber + "</h3>");
            out.println("<pre class=\"jimple\">" + escapeHtml(entry.stmtText) + "</pre>");
            out.println("<div class=\"graph-container\" id=\"graph-" + entry.stepNumber + "\">");
            out.println("<p class=\"loading\">Rendering graph...</p>");
            out.println("</div>");
            out.println("<p class=\"data\"><strong>W = </strong>" + escapeHtml(entry.mutatedFieldsText) + "</p>");
            out.println("</div>");
        }

        // Exit Graph
        out.println("<h2>Exit Graph</h2>");
        out.println("<div class=\"graph-container\" id=\"exit-graph\">");
        out.println("<p class=\"loading\">Rendering graph...</p>");
        out.println("</div>");

        // Prestate Nodes
        out.println("<h2>Prestate Nodes</h2>");
        out.println("<p class=\"data\">" + escapeHtml(prestateNodesText != null ? prestateNodesText : "{}") + "</p>");

        // Set W
        out.println("<h2>Set W (Mutated Fields)</h2>");
        out.println("<p class=\"data\">" + escapeHtml(mutatedFieldsText != null ? mutatedFieldsText : "{}") + "</p>");
        if (hasGlobalSideEffect) {
            out.println("<p class=\"warning\">Global Side Effect: YES</p>");
        }

        // Purity Result
        out.println("<h2>Purity Result</h2>");
        String resultClass = "PURE".equals(purityResult) ? "pure" : "impure";
        String resultText = purityResult != null ? purityResult : "UNKNOWN";
        if (purityReason != null) {
            resultText += " (" + purityReason + ")";
        }
        out.println("<p class=\"verdict " + resultClass + "\">" + escapeHtml(resultText) + "</p>");

        // JavaScript: DOT sources and viz.js rendering
        out.println("<script>");
        out.println("const graphs = {");
        for (TraceEntry entry : traceEntries) {
            out.println("  \"graph-" + entry.stepNumber + "\": " + jsStringLiteral(entry.dotSource) + ",");
        }
        if (exitGraphDot != null) {
            out.println("  \"exit-graph\": " + jsStringLiteral(exitGraphDot));
        }
        out.println("};");
        out.println();
        out.println("if (typeof Viz !== 'undefined') {");
        out.println("  Viz.instance().then(viz => {");
        out.println("    for (const [id, dot] of Object.entries(graphs)) {");
        out.println("      try {");
        out.println("        const svg = viz.renderSVGElement(dot);");
        out.println("        const container = document.getElementById(id);");
        out.println("        container.innerHTML = '';");
        out.println("        container.appendChild(svg);");
        out.println("      } catch (e) {");
        out.println("        document.getElementById(id).innerHTML = '<pre class=\"error\">' + e.message + '</pre>';");
        out.println("      }");
        out.println("    }");
        out.println("  });");
        out.println("} else {");
        out.println("  for (const [id, dot] of Object.entries(graphs)) {");
        out.println("    document.getElementById(id).innerHTML = '<p class=\"error\">viz.js failed to load. DOT source:</p><pre>' + dot + '</pre>';");
        out.println("  }");
        out.println("}");
        out.println("</script>");

        out.println("</body>");
        out.println("</html>");
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * Encode a string as a JavaScript string literal using backtick template syntax.
     * Escapes backticks, backslashes, and ${} to prevent template injection.
     */
    private static String jsStringLiteral(String s) {
        String escaped = s.replace("\\", "\\\\")
                          .replace("`", "\\`")
                          .replace("${", "\\${");
        return "`" + escaped + "`";
    }

    private static final String CSS = """
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            max-width: 1400px;
            margin: 0 auto;
            padding: 20px 40px;
            background: #fafafa;
            color: #333;
        }
        h1 {
            color: #1a1a2e;
            border-bottom: 3px solid #16213e;
            padding-bottom: 10px;
        }
        h2 {
            color: #16213e;
            border-bottom: 2px solid #e2e8f0;
            padding-bottom: 8px;
            margin-top: 40px;
        }
        h3 {
            color: #4a5568;
            margin-bottom: 8px;
        }
        .method-sig {
            font-family: 'JetBrains Mono', 'Fira Code', monospace;
            font-size: 14px;
            background: #e2e8f0;
            padding: 8px 16px;
            border-radius: 6px;
            display: inline-block;
        }
        .source-filename {
            font-family: 'JetBrains Mono', 'Fira Code', monospace;
            font-size: 12px;
            color: #94a3b8;
            margin-bottom: 4px;
        }
        .source-code {
            background: #1a1b26;
            color: #a9b1d6;
            padding: 16px 20px;
            border-radius: 8px;
            font-family: 'JetBrains Mono', 'Fira Code', monospace;
            font-size: 13px;
            line-height: 1.5;
            overflow-x: auto;
            border-left: 4px solid #7aa2f7;
        }
        .jimple {
            background: #1e1e2e;
            color: #cdd6f4;
            padding: 16px 20px;
            border-radius: 8px;
            font-family: 'JetBrains Mono', 'Fira Code', monospace;
            font-size: 13px;
            line-height: 1.5;
            overflow-x: auto;
        }
        .line-num {
            color: #6c7086;
            user-select: none;
        }
        .step {
            background: white;
            border: 1px solid #e2e8f0;
            border-radius: 8px;
            padding: 16px 20px;
            margin: 16px 0;
            box-shadow: 0 1px 3px rgba(0,0,0,0.05);
        }
        .step h3 {
            margin-top: 0;
        }
        .graph-container {
            background: white;
            border: 1px solid #e2e8f0;
            border-radius: 8px;
            padding: 16px;
            margin: 12px 0;
            overflow-x: auto;
            min-height: 60px;
        }
        .graph-container svg {
            max-width: 100%;
            height: auto;
        }
        .data {
            font-family: 'JetBrains Mono', 'Fira Code', monospace;
            font-size: 14px;
            background: #f1f5f9;
            padding: 10px 16px;
            border-radius: 6px;
        }
        .verdict {
            font-size: 20px;
            font-weight: bold;
            padding: 12px 20px;
            border-radius: 8px;
            display: inline-block;
        }
        .pure {
            color: #166534;
            background: #dcfce7;
            border: 2px solid #86efac;
        }
        .impure {
            color: #991b1b;
            background: #fee2e2;
            border: 2px solid #fca5a5;
        }
        .warning {
            color: #92400e;
            background: #fef3c7;
            padding: 8px 16px;
            border-radius: 6px;
            font-weight: bold;
        }
        .loading {
            color: #9ca3af;
            font-style: italic;
        }
        .muted {
            color: #9ca3af;
        }
        .error {
            color: #dc2626;
        }
        """;
}
