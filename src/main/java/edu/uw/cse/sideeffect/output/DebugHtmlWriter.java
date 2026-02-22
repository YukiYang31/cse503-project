package edu.uw.cse.sideeffect.output;

import edu.uw.cse.sideeffect.graph.Node;
import edu.uw.cse.sideeffect.graph.PointsToGraph;
import edu.uw.cse.sideeffect.graph.PointsToGraph.MutatedField;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import sootup.core.jimple.basic.Local;
import sootup.core.signatures.FieldSignature;


/**
 * Accumulates debug data for a single method and writes a self-contained HTML file
 * with visual points-to graph renderings via viz.js.
 */
public class DebugHtmlWriter implements Closeable {

    public record SourceFile(String fileName, String content) {}
    private record TraceEntry(int stepNumber, String stmtText, String dotSource, String mutatedFieldsText,
                               String calleeGraphDot, String calleeSig, String muPrimeText,
                               String preSimplificationDot) {}

    private final String methodSig;
    private final Path outputPath;

    private List<SourceFile> sourceFiles = List.of();
    private List<String> bytecodeLines = List.of();
    private final List<String> jimpleStatements = new ArrayList<>();
    private final List<TraceEntry> traceEntries = new ArrayList<>();
    private int stepCounter = 0;

    // Pending callee graph info (set before addTraceEntry, consumed by addTraceEntry)
    private String pendingCalleeGraphDot;
    private String pendingCalleeSig;
    private String pendingMuPrimeText;
    private String pendingPreSimplificationDot;

    private String exitGraphDot;
    private String insideEdgesText;     // I
    private String outsideEdgesText;    // O
    private String localVariablesText;  // L
    private String escapedNodesText;    // E
    private String prestateNodesText;
    private String globallyEscapedNodesText;
    private String mutatedFieldsText;
    private String sideEffectResult;
    private String sideEffectReason;
    private String callGraphDot;   // DOT source for the dependency graph

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

    public void setBytecode(List<String> lines) {
        this.bytecodeLines = lines;
    }

    public void addJimpleStatement(String stmtText) {
        jimpleStatements.add(stmtText);
    }

    /**
     * Buffer a callee's exit graph to be included in the next trace entry.
     * Call this before the trace entry is recorded (i.e., before the call to addTraceEntry).
     *
     * @param calleeExitGraph the callee's exit graph (before instantiation)
     * @param calleeSig       the callee method signature
     */
    public void setNextCalleeGraph(PointsToGraph calleeExitGraph, String calleeSig) {
        this.pendingCalleeGraphDot = GraphPrinter.generateDotString(calleeExitGraph, "Callee: " + calleeSig);
        this.pendingCalleeSig = calleeSig;
    }

    /**
     * Buffer the mu' (extended node mapping) text to be included in the next trace entry.
     * Call this after instantiation but before the trace entry is recorded.
     *
     * @param muPrimeText the formatted mu' mapping string
     */
    public void setNextMuPrime(String muPrimeText) {
        this.pendingMuPrimeText = muPrimeText;
    }

    /**
     * Buffer the combined (pre-simplification) graph to be included in the next trace entry.
     * Call this after graph combination (Step 2) but before simplification (Step 3).
     *
     * @param graph the caller graph after combining callee edges but before removing captured nodes
     */
    public void setNextPreSimplificationGraph(PointsToGraph graph) {
        this.pendingPreSimplificationDot = GraphPrinter.generateDotString(graph, "Combined Graph (before simplification)");
    }

    /**
     * Record a trace entry: generates a DOT string from the current graph state.
     * If a pending callee graph was set via {@link #setNextCalleeGraph}, it is
     * included in this entry and then cleared.
     */
    public void addTraceEntry(String stmtText, PointsToGraph graph) {
        stepCounter++;
        String dotSource = GraphPrinter.generateDotString(graph, "Step " + stepCounter);
        String wText = formatMutatedFields(graph.getMutatedFields());
        // Consume pending callee graph if present
        String calleeDot = pendingCalleeGraphDot;
        String calleeSig = pendingCalleeSig;
        String muPrime = pendingMuPrimeText;
        String preSimplDot = pendingPreSimplificationDot;
        pendingCalleeGraphDot = null;
        pendingCalleeSig = null;
        pendingMuPrimeText = null;
        pendingPreSimplificationDot = null;
        traceEntries.add(new TraceEntry(stepCounter, stmtText, dotSource, wText, calleeDot, calleeSig, muPrime, preSimplDot));
    }

    public void setExitGraph(PointsToGraph graph) {
        this.exitGraphDot = GraphPrinter.generateDotString(graph, "Exit Graph");
    }

    /** Set inside edges text (I) from the exit graph. */
    public void setInsideEdges(PointsToGraph graph) {
        this.insideEdgesText = formatEdgeMap(graph.getInsideEdges());
    }

    /** Set outside edges text (O) from the exit graph. */
    public void setOutsideEdges(PointsToGraph graph) {
        this.outsideEdgesText = formatEdgeMap(graph.getOutsideEdges());
    }

    /** Set local variables text (L) from the exit graph. */
    public void setLocalVariables(PointsToGraph graph) {
        Map<Local, Set<Node>> varMap = graph.getVarPointsTo();
        if (varMap.isEmpty()) {
            this.localVariablesText = "(none)";
            return;
        }
        List<Map.Entry<Local, Set<Node>>> entries = new ArrayList<>(varMap.entrySet());
        entries.sort(Comparator.comparing(e -> e.getKey().getName()));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Local, Set<Node>> entry : entries) {
            Set<Node> targets = entry.getValue();
            String varName = entry.getKey().getName();
            if (!targets.isEmpty() && !varName.startsWith("$stack")) {
                String typeName = simpleTypeName(entry.getKey().getType().toString());
                List<String> ids = targets.stream().map(Node::getId).sorted().toList();
                if (sb.length() > 0) sb.append("\n");
                sb.append(varName).append(": ").append(typeName).append(" \u2192 {").append(String.join(", ", ids)).append("}");
            }
        }
        this.localVariablesText = sb.length() > 0 ? sb.toString() : "(none)";
    }

    /** Set escaped nodes text (E) — the raw set E, not the BFS closure set B. */
    public void setEscapedNodes(Set<Node> nodes) {
        List<String> ids = nodes.stream().map(Node::getId).sorted().toList();
        this.escapedNodesText = "{" + String.join(", ", ids) + "}";
    }

    private static String formatEdgeMap(Map<Node, Map<FieldSignature, Set<Node>>> edgeMap) {
        if (edgeMap.isEmpty()) return "(none)";
        List<Node> sources = new ArrayList<>(edgeMap.keySet());
        sources.sort(Comparator.comparing(Node::getId));
        StringBuilder sb = new StringBuilder();
        for (Node source : sources) {
            Map<FieldSignature, Set<Node>> fieldMap = edgeMap.get(source);
            List<FieldSignature> fields = new ArrayList<>(fieldMap.keySet());
            fields.sort(Comparator.comparing(f -> f != null ? f.getName() : "[]"));
            for (FieldSignature field : fields) {
                String fieldName = field != null ? field.getName() : "[]";
                List<Node> targets = new ArrayList<>(fieldMap.get(field));
                targets.sort(Comparator.comparing(Node::getId));
                for (Node target : targets) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(source.getId()).append(" --").append(fieldName).append("--> ").append(target.getId());
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : "(none)";
    }

    public void setPrestateNodes(Set<Node> nodes) {
        List<String> ids = nodes.stream().map(Node::getId).sorted().toList();
        this.prestateNodesText = "{" + String.join(", ", ids) + "}";
    }

    public void setGloballyEscapedNodes(Set<Node> nodes) {
        List<String> ids = nodes.stream().map(Node::getId).sorted().toList();
        this.globallyEscapedNodesText = "{" + String.join(", ", ids) + "}";
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

    public void setSideEffectResult(String result, String reason) {
        this.sideEffectResult = result;
        this.sideEffectReason = reason;
    }

    /**
     * Build a visual dependency graph (DOT) showing the entire call graph,
     * with the current method highlighted.
     * @param currentMethodSig  the signature of the current method
     * @param callGraph         caller sig → set of callee sigs
     */
    public void setCallGraph(String currentMethodSig, Map<String, Set<String>> callGraph) {
        // Collect all nodes that appear in the call graph (as caller or callee)
        Set<String> allNodes = new LinkedHashSet<>();
        List<String[]> edges = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : callGraph.entrySet()) {
            String caller = entry.getKey();
            allNodes.add(caller);
            for (String callee : entry.getValue()) {
                allNodes.add(callee);
                edges.add(new String[]{caller, callee});
            }
        }
        // Also include the current method even if it has no edges
        allNodes.add(currentMethodSig);

        // Sort edges for deterministic output
        edges.sort((a, b) -> {
            int cmp = a[0].compareTo(b[0]);
            return cmp != 0 ? cmp : a[1].compareTo(b[1]);
        });

        // Build DOT
        StringBuilder dot = new StringBuilder();
        dot.append("digraph CallGraph {\n");
        dot.append("  rankdir=LR;\n");
        dot.append("  node [shape=box, style=filled, fillcolor=\"#f1f5f9\", "
                 + "fontname=\"Helvetica\", fontsize=11];\n");
        dot.append("  edge [color=\"#64748b\"];\n");

        // Assign stable node IDs
        Map<String, String> nodeIds = new LinkedHashMap<>();
        int idx = 0;
        List<String> sortedNodes = new ArrayList<>(allNodes);
        sortedNodes.sort(String::compareTo);
        for (String sig : sortedNodes) {
            nodeIds.put(sig, "n" + idx++);
        }

        // Emit nodes
        for (String sig : sortedNodes) {
            String nid = nodeIds.get(sig);
            String label = shortLabel(sig);
            if (sig.equals(currentMethodSig)) {
                // Highlight the current method
                dot.append("  ").append(nid)
                   .append(" [label=\"").append(dotEscape(label))
                   .append("\", fillcolor=\"#dbeafe\", penwidth=2.5, color=\"#2563eb\"];\n");
            } else {
                dot.append("  ").append(nid)
                   .append(" [label=\"").append(dotEscape(label)).append("\"];\n");
            }
        }

        // Emit edges
        for (String[] edge : edges) {
            String fromId = nodeIds.get(edge[0]);
            String toId = nodeIds.get(edge[1]);
            if (fromId != null && toId != null) {
                dot.append("  ").append(fromId).append(" -> ").append(toId).append(";\n");
            }
        }

        dot.append("}\n");
        this.callGraphDot = dot.toString();
    }

    /**
     * Produce a short human-readable label from a full method signature.
     * e.g. "<PaperMain: void flipAll(List)>" → "PaperMain.flipAll(List)"
     */
    private static String shortLabel(String sig) {
        // Strip < and >
        String s = sig;
        if (s.startsWith("<")) s = s.substring(1);
        if (s.endsWith(">")) s = s.substring(0, s.length() - 1);
        // Split on ": "
        int colonIdx = s.indexOf(": ");
        if (colonIdx < 0) return s;
        String className = s.substring(0, colonIdx);
        String rest = s.substring(colonIdx + 2); // e.g. "void flipAll(List)"
        // Extract method name + params: skip the return type
        int parenIdx = rest.indexOf('(');
        if (parenIdx < 0) return className + "." + rest;
        // Find the method name (last token before '(')
        String beforeParen = rest.substring(0, parenIdx);
        int spaceIdx = beforeParen.lastIndexOf(' ');
        String methodName = spaceIdx >= 0 ? beforeParen.substring(spaceIdx + 1) : beforeParen;
        String params = rest.substring(parenIdx);
        return className + "." + methodName + params;
    }

    private static String simpleTypeName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static String dotEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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

        // Bytecode
        if (!bytecodeLines.isEmpty()) {
            out.println("<h2>Bytecode</h2>");
            out.println("<pre class=\"bytecode\">");
            for (int i = 0; i < bytecodeLines.size(); i++) {
                out.println(String.format("<span class=\"line-num\">%3d</span>  %s",
                    i + 1, escapeHtml(bytecodeLines.get(i))));
            }
            out.println("</pre>");
        }

        // Jimple Body
        out.println("<h2>Jimple Body</h2>");
        out.println("<pre class=\"jimple\">");
        for (int i = 0; i < jimpleStatements.size(); i++) {
            out.println(String.format("<span class=\"line-num\">%3d</span>  %s",
                i + 1, escapeHtml(jimpleStatements.get(i))));
        }
        out.println("</pre>");

        // Call Graph (Dependency Graph)
        if (callGraphDot != null) {
            out.println("<h2>Call Dependency Graph</h2>");
            out.println("<p class=\"muted\">Full call graph. Current method highlighted in blue.</p>");
            out.println("<div class=\"graph-container\" id=\"call-graph\">");
            out.println("<p class=\"loading\">Rendering graph...</p>");
            out.println("</div>");
        }

        // Analysis Trace
        out.println("<h2>Analysis Trace (Key Milestones)</h2>");
        if (traceEntries.isEmpty()) {
            out.println("<p class=\"muted\">No key milestone statements found.</p>");
        }
        for (TraceEntry entry : traceEntries) {
            out.println("<div class=\"step\">");
            out.println("<h3>Step " + entry.stepNumber + "</h3>");
            out.println("<pre class=\"jimple\">" + escapeHtml(entry.stmtText) + "</pre>");
            // If this step involved a callee graph merge, show the callee exit graph and mu'
            if (entry.calleeGraphDot != null) {
                out.println("<h4 style=\"color:#2563eb;margin-top:12px;\">\u2B07 Callee Exit Graph: " + escapeHtml(entry.calleeSig) + "</h4>");
                out.println("<div class=\"graph-container\" id=\"callee-graph-" + entry.stepNumber + "\"  style=\"border-left:4px solid #2563eb;\">");
                out.println("<p class=\"loading\">Rendering callee graph...</p>");
                out.println("</div>");
                if (entry.muPrimeText != null) {
                    out.println("<h4 style=\"color:#7c3aed;margin-top:12px;\">\u03BC\u2032 (Node Mapping)</h4>");
                    out.println("<pre class=\"data\" style=\"border-left:4px solid #7c3aed;\">" + escapeHtml(entry.muPrimeText) + "</pre>");
                }
                if (entry.preSimplificationDot != null) {
                    out.println("<h4 style=\"color:#d97706;margin-top:12px;\">\u2B07 Combined Graph (before simplification)</h4>");
                    out.println("<div class=\"graph-container\" id=\"pre-simpl-graph-" + entry.stepNumber + "\"  style=\"border-left:4px solid #d97706;\">");
                    out.println("<p class=\"loading\">Rendering pre-simplification graph...</p>");
                    out.println("</div>");
                }
                out.println("<h4 style=\"color:#16a34a;margin-top:12px;\">\u2B07 Caller Graph (after merge, after node simplification)</h4>");
            }
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

        // Graph Components G = ⟨I, O, L, E⟩
        out.println("<h2>Graph Components G = \u27e8I, O, L, E\u27e9</h2>");

        out.println("<h3>I (Inside Edges)</h3>");
        out.println("<pre class=\"data\">"
            + escapeHtml(insideEdgesText != null ? insideEdgesText : "(none)") + "</pre>");

        out.println("<h3>O (Outside Edges)</h3>");
        out.println("<pre class=\"data\">"
            + escapeHtml(outsideEdgesText != null ? outsideEdgesText : "(none)") + "</pre>");

        out.println("<h3>L (Local Variables)</h3>");
        out.println("<pre class=\"data\">"
            + escapeHtml(localVariablesText != null ? localVariablesText : "(none)") + "</pre>");

        out.println("<h3>E (Globally Escaped)</h3>");
        out.println("<p class=\"data\">"
            + escapeHtml(escapedNodesText != null ? escapedNodesText : "{}") + "</p>");

        // Set A (Prestate Nodes)
        out.println("<h2>Set A (Prestate Nodes)</h2>");
        out.println("<p class=\"data\">" + escapeHtml(prestateNodesText != null ? prestateNodesText : "{}") + "</p>");

        // Set B (Globally Escaped Nodes)
        out.println("<h2>Set B (Globally Escaped Nodes)</h2>");
        out.println("<p class=\"data\">" + escapeHtml(globallyEscapedNodesText != null ? globallyEscapedNodesText : "{}") + "</p>");

        // Set W
        out.println("<h2>Set W (Mutated Fields)</h2>");
        out.println("<p class=\"data\">" + escapeHtml(mutatedFieldsText != null ? mutatedFieldsText : "{}") + "</p>");

        // Side-Effect Analysis Result
        out.println("<h2>Side-Effect Analysis Result</h2>");
        String resultClass = "SIDE_EFFECT_FREE".equals(sideEffectResult) ? "side-effect-free" : "side-effecting";
        String resultText = sideEffectResult != null ? sideEffectResult : "UNKNOWN";
        if (sideEffectReason != null) {
            resultText += " (" + sideEffectReason + ")";
        }
        out.println("<p class=\"verdict " + resultClass + "\">" + escapeHtml(resultText) + "</p>");

        // JavaScript: DOT sources and viz.js rendering
        out.println("<script>");
        out.println("const graphs = {");
        for (TraceEntry entry : traceEntries) {
            if (entry.calleeGraphDot != null) {
                out.println("  \"callee-graph-" + entry.stepNumber + "\": " + jsStringLiteral(entry.calleeGraphDot) + ",");
            }
            if (entry.preSimplificationDot != null) {
                out.println("  \"pre-simpl-graph-" + entry.stepNumber + "\": " + jsStringLiteral(entry.preSimplificationDot) + ",");
            }
            out.println("  \"graph-" + entry.stepNumber + "\": " + jsStringLiteral(entry.dotSource) + ",");
        }
        if (exitGraphDot != null) {
            out.println("  \"exit-graph\": " + jsStringLiteral(exitGraphDot) + ",");
        }
        if (callGraphDot != null) {
            out.println("  \"call-graph\": " + jsStringLiteral(callGraphDot));
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
        .bytecode {
            background: #1c2333;
            color: #b0c4de;
            padding: 16px 20px;
            border-radius: 8px;
            font-family: 'JetBrains Mono', 'Fira Code', monospace;
            font-size: 13px;
            line-height: 1.5;
            overflow-x: auto;
            border-left: 4px solid #f0ad4e;
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
        .side-effect-free {
            color: #166534;
            background: #dcfce7;
            border: 2px solid #86efac;
        }
        .side-effecting {
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
