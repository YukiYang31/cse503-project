package edu.uw.cse.sideeffect.output;

import edu.uw.cse.sideeffect.analysis.MethodSummary;
import edu.uw.cse.sideeffect.analysis.SideEffectChecker;
import edu.uw.cse.sideeffect.graph.*;
import edu.uw.cse.sideeffect.graph.PointsToGraph.EdgeTarget;
import edu.uw.cse.sideeffect.graph.PointsToGraph.MutatedField;
import java.io.*;
import java.util.*;
import sootup.core.jimple.basic.Local;
import sootup.core.signatures.FieldSignature;
import sootup.java.core.JavaSootClass;
import sootup.java.core.JavaSootMethod;


/**
 * Prints points-to graph summaries in text and DOT (Graphviz) format.
 *
 * DOT color scheme:
 *   InsideNode    -> green box       (newly allocated, mutations OK)
 *   ParameterNode -> blue ellipse    (prestate, mutations = side-effecting)
 *   LoadNode      -> red diamond     (external/unknown, mutations = side-effecting)
 *   GlobalNode    -> orange octagon  (static field escape)
 *   InsideEdge    -> solid arrow
 *   OutsideEdge   -> dashed arrow
 */
public class GraphPrinter {

    /**
     * Print a text summary of the points-to graph to stdout,
     * presented as G = ⟨I, O, L, E⟩ per Salcianu &amp; Rinard (2005).
     */
    public static void printTextSummary(MethodSummary summary) {
        PointsToGraph graph = summary.getExitGraph();
        String sig = summary.getMethodSignature();

        System.out.println("--- Points-To Graph for " + sig + " ---");
        System.out.println("G = \u27e8I, O, L, E\u27e9");
        System.out.println();

        // Nodes
        Set<Node> allNodes = graph.getAllNodes();
        List<Node> sortedNodes = new ArrayList<>(allNodes);
        sortedNodes.sort(Comparator.comparing(Node::getId));

        System.out.println("Nodes:");
        if (sortedNodes.isEmpty()) {
            System.out.println("  (none)");
        }
        for (Node n : sortedNodes) {
            System.out.println("  " + n.getId() + " [" + nodeKindLabel(n) + "]" + nodeDescription(n));
        }
        System.out.println();

        // I (Inside Edges)
        System.out.println("I (Inside Edges):");
        Map<Node, Map<FieldSignature, Set<Node>>> insideEdges = graph.getInsideEdges();
        printFilteredEdges(insideEdges);
        System.out.println();

        // O (Outside Edges)
        System.out.println("O (Outside Edges):");
        Map<Node, Map<FieldSignature, Set<Node>>> outsideEdges = graph.getOutsideEdges();
        printFilteredEdges(outsideEdges);
        System.out.println();

        // L (Local Variables)
        System.out.println("L (Local Variables):");
        Map<Local, Set<Node>> varMap = graph.getVarPointsTo();
        if (varMap.isEmpty()) {
            System.out.println("  (none)");
        }
        List<Map.Entry<Local, Set<Node>>> varEntries = new ArrayList<>(varMap.entrySet());
        varEntries.sort(Comparator.comparing(e -> e.getKey().getName()));
        for (Map.Entry<Local, Set<Node>> entry : varEntries) {
            Set<Node> targets = entry.getValue();
            String varName = entry.getKey().getName();
            if (!targets.isEmpty() && !varName.startsWith("$stack")) {
                String typeName = simpleTypeName(entry.getKey().getType().toString());
                List<String> ids = targets.stream().map(Node::getId).sorted().toList();
                System.out.println("  " + varName + ": " + typeName + " -> {" + String.join(", ", ids) + "}");
            }
        }
        System.out.println();

        // E (Globally Escaped)
        Set<Node> escaped = graph.getGlobalEscaped();
        List<String> escapedIds = escaped.stream().map(Node::getId).sorted().toList();
        System.out.println("E (Globally Escaped): {" + String.join(", ", escapedIds) + "}");
        System.out.println();

        // ---  Side-Effect Analysis ---
        System.out.println("--- Side-Effect Analysis ---");

        // W (Mutated Fields)
        Set<MutatedField> mutations = graph.getMutatedFields();
        List<String> mutStrs = new ArrayList<>();
        for (MutatedField mf : mutations) {
            String fieldName = mf.field() != null ? mf.field().getName() : "[]";
            mutStrs.add("\u27e8" + mf.node().getId() + ", " + fieldName + "\u27e9");
        }
        mutStrs.sort(String::compareTo);
        System.out.println("W (Mutated Fields): {" + String.join(", ", mutStrs) + "}");

        // Prestate Nodes
        Set<Node> prestateNodes = SideEffectChecker.computePrestateNodes(graph);
        List<String> prestateIds = prestateNodes.stream().map(Node::getId).sorted().toList();
        System.out.println("Prestate Nodes: {" + String.join(", ", prestateIds) + "}");

        System.out.println();
    }

    /**
     * Print a filtered edge map (used for I and O sections).
     */
    private static void printFilteredEdges(Map<Node, Map<FieldSignature, Set<Node>>> edgeMap) {
        boolean hasEdges = false;
        List<Node> sources = new ArrayList<>(edgeMap.keySet());
        sources.sort(Comparator.comparing(Node::getId));
        for (Node source : sources) {
            Map<FieldSignature, Set<Node>> fieldMap = edgeMap.get(source);
            List<FieldSignature> fields = new ArrayList<>(fieldMap.keySet());
            fields.sort(Comparator.comparing(f -> f != null ? f.getName() : "[]"));
            for (FieldSignature field : fields) {
                String fieldName = field != null ? field.getName() : "[]";
                List<Node> targets = new ArrayList<>(fieldMap.get(field));
                targets.sort(Comparator.comparing(Node::getId));
                for (Node target : targets) {
                    System.out.println("  " + source.getId() + " --" + fieldName + "--> " + target.getId());
                    hasEdges = true;
                }
            }
        }
        if (!hasEdges) {
            System.out.println("  (none)");
        }
    }

    /**
     * Generate a DOT string for the given points-to graph.
     */
    public static String generateDotString(PointsToGraph graph, String label) {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);

        out.println("digraph \"" + escapeDot(label) + "\" {");
        out.println("  rankdir=LR;");
        out.println("  node [fontname=\"Helvetica\", fontsize=10];");
        out.println("  edge [fontname=\"Helvetica\", fontsize=9];");
        out.println();

        Set<Node> allNodes = graph.getAllNodes();
        Set<Node> prestateNodes = SideEffectChecker.computePrestateNodes(graph);

        // Emit nodes
        for (Node n : allNodes) {
            String attrs = dotNodeAttrs(n);
            out.println("  \"" + escapeDot(n.getId()) + "\" [" + attrs + "];");
        }
        out.println();

        // Emit variable mapping as record nodes
        Map<Local, Set<Node>> varMap = graph.getVarPointsTo();
        for (Map.Entry<Local, Set<Node>> entry : varMap.entrySet()) {
            String varName = entry.getKey().getName();
            if (entry.getValue().isEmpty()) continue;
            if (varName.startsWith("$stack")) continue; // skip Jimple temporaries
            String typeName = simpleTypeName(entry.getKey().getType().toString());
            String displayLabel = varName + ": " + typeName;
            out.println("  \"var_" + escapeDot(varName) + "\" [label=\"" + escapeDot(displayLabel)
                + "\", shape=plaintext, fontcolor=gray75];");
            for (Node target : entry.getValue()) {
                out.println("  \"var_" + escapeDot(varName) + "\" -> \""
                    + escapeDot(target.getId()) + "\" [style=solid, color=gray75];");
            }
        }
        out.println();

        // Emit heap edges
        Map<Node, Map<FieldSignature, Set<EdgeTarget>>> edges = graph.getEdges();
        for (Map.Entry<Node, Map<FieldSignature, Set<EdgeTarget>>> entry : edges.entrySet()) {
            Node source = entry.getKey();
            for (Map.Entry<FieldSignature, Set<EdgeTarget>> fe : entry.getValue().entrySet()) {
                String fieldName = fe.getKey() != null ? fe.getKey().getName() : "[]";
                for (EdgeTarget et : fe.getValue()) {
                    String style = et.type() == EdgeType.INSIDE
                        ? "style=solid, color=black"
                        : "style=dashed, color=gray60";
                    out.println("  \"" + escapeDot(source.getId()) + "\" -> \""
                        + escapeDot(et.target().getId()) + "\" [label=\"" + escapeDot(fieldName)
                        + "\", " + style + "];");
                }
            }
        }

        // Mark mutated nodes
        Set<MutatedField> mutations = graph.getMutatedFields();
        Set<Node> mutatedNodes = new HashSet<>();
        for (MutatedField mf : mutations) {
            mutatedNodes.add(mf.node());
        }
        for (Node mn : mutatedNodes) {
            if (prestateNodes.contains(mn)) {
                out.println("  \"" + escapeDot(mn.getId())
                    + "\" [penwidth=3.0, color=red];");
            }
        }

        out.println("}");
        out.flush();
        return sw.toString();
    }

    /**
     * Write a DOT file for the points-to graph.
     * File is named based on the method signature.
     */
    public static void writeDotFile(MethodSummary summary) {
        PointsToGraph graph = summary.getExitGraph();
        String sig = summary.getMethodSignature();
        String fileName = sanitizeFileName(sig) + ".dot";

        File dotDir = new File("dot-graph");
        if (!dotDir.exists()) {
            dotDir.mkdirs();
        }

        try (PrintWriter out = new PrintWriter(new FileWriter(new File(dotDir, fileName)))) {
            out.print(generateDotString(graph, sig));
            System.out.println("DOT output written to: dot-graph/" + fileName);
        } catch (IOException e) {
            System.err.println("Error writing DOT file dot-graph/" + fileName + ": " + e.getMessage());
        }
    }

    /**
     * Write a DOT file showing the inter-file override dependency graph.
     * Blue arrows = call edges between analyzed methods.
     * Red arrows  = override edges (overriding method → base method, labeled "overrides").
     *
     * This is a separate graph from the per-method points-to DOT files and uses its own
     * color scheme — no overlap with the solid/dashed InsideEdge/OutsideEdge convention.
     *
     * Output: dot-graph/override-dependency.dot
     */
    public static void writeOverrideDependencyDot(
            Map<String, Set<String>> callGraph,
            Map<String, Set<String>> overrideGraph) {

        File dotDir = new File("dot-graph");
        if (!dotDir.exists()) dotDir.mkdirs();

        String fileName = "override-dependency.dot";
        try (PrintWriter out = new PrintWriter(new FileWriter(new File(dotDir, fileName)))) {

            out.println("digraph \"Override Dependency Graph\" {");
            out.println("  rankdir=LR;");
            out.println("  node [fontname=\"Helvetica\", fontsize=10, shape=box,"
                + " style=filled, fillcolor=lightyellow];");
            out.println("  edge [fontname=\"Helvetica\", fontsize=9];");
            out.println();

            // Node set: all methods in the call graph plus any overriding methods
            Set<String> allMethods = new LinkedHashSet<>(callGraph.keySet());
            for (Set<String> overrides : overrideGraph.values()) {
                allMethods.addAll(overrides);
            }

            // Emit nodes with short readable labels
            for (String sig : allMethods) {
                String label = shortMethodLabel(sig);
                out.println("  \"" + escapeDot(sig) + "\" [label=\"" + escapeDot(label) + "\"];");
            }
            out.println();

            // Emit call edges (blue) — only between methods in the analyzed set
            for (Map.Entry<String, Set<String>> entry : callGraph.entrySet()) {
                for (String callee : entry.getValue()) {
                    if (allMethods.contains(callee)) {
                        out.println("  \"" + escapeDot(entry.getKey()) + "\" -> \""
                            + escapeDot(callee) + "\" [color=blue];");
                    }
                }
            }
            out.println();

            // Emit override edges (red): overridingMethod → baseMethod
            for (Map.Entry<String, Set<String>> entry : overrideGraph.entrySet()) {
                String baseSig = entry.getKey();
                for (String overrideSig : entry.getValue()) {
                    out.println("  \"" + escapeDot(overrideSig) + "\" -> \""
                        + escapeDot(baseSig) + "\" [color=red, label=\"overrides\","
                        + " style=bold, fontcolor=red];");
                }
            }

            out.println("}");
            out.flush();
            System.out.println("Override dependency graph written to: dot-graph/" + fileName);
        } catch (IOException e) {
            System.err.println("Error writing override dependency DOT file: " + e.getMessage());
        }
    }

    /**
     * Produce a short human-readable label from a full SootUp method signature.
     * Input:  {@code <some.pkg.ClassName: ReturnType methodName(ParamType1, ParamType2)>}
     * Output: {@code ClassName.methodName(ParamType1, ParamType2)}  (using simple class names)
     */
    private static String shortMethodLabel(String sig) {
        try {
            // sig format: <pkg.ClassName: ReturnType methodName(ParamTypes)>
            int ltIdx  = sig.indexOf('<');
            int colon  = sig.indexOf(':');
            int gtIdx  = sig.lastIndexOf('>');
            if (ltIdx < 0 || colon < 0 || gtIdx < 0) return sig;

            String className = sig.substring(ltIdx + 1, colon).trim();
            int lastDot = className.lastIndexOf('.');
            if (lastDot >= 0) className = className.substring(lastDot + 1);

            // rest = "ReturnType methodName(ParamTypes)"
            String rest = sig.substring(colon + 1, gtIdx).trim();
            int spaceIdx = rest.indexOf(' ');
            if (spaceIdx < 0) return className + "." + rest;
            String methodPart = rest.substring(spaceIdx + 1).trim();

            int parenOpen  = methodPart.indexOf('(');
            int parenClose = methodPart.lastIndexOf(')');
            if (parenOpen < 0 || parenClose < parenOpen) return className + "." + methodPart;

            String methodName = methodPart.substring(0, parenOpen);
            String rawParams  = methodPart.substring(parenOpen + 1, parenClose);

            // Simplify each param type to its simple name
            String simplifiedParams;
            if (rawParams.isEmpty()) {
                simplifiedParams = "";
            } else {
                String[] parts = rawParams.split(",");
                List<String> simplified = new ArrayList<>();
                for (String p : parts) {
                    String t = p.trim();
                    int d = t.lastIndexOf('.');
                    simplified.add(d >= 0 ? t.substring(d + 1) : t);
                }
                simplifiedParams = String.join(", ", simplified);
            }
            return className + "." + methodName + "(" + simplifiedParams + ")";
        } catch (Exception e) {
            return sig;
        }
    }

    // --- Helpers ---

    private static String nodeKindLabel(Node n) {
        return switch (n.getKind()) {
            case INSIDE -> "InsideNode";
            case PARAMETER -> "ParameterNode";
            case LOAD -> "LoadNode";
            case GLOBAL -> "GlobalNode";
        };
    }

    private static String nodeDescription(Node n) {
        if (n instanceof InsideNode in) {
            return " " + in.getLabel();
        } else if (n instanceof ParameterNode pn) {
            return " " + pn.getLabel();
        } else if (n instanceof LoadNode ln) {
            return " " + ln.getLabel();
        } else if (n instanceof GlobalNode) {
            return " (static fields)";
        }
        return "";
    }

    private static String dotNodeAttrs(Node n) {
        return switch (n.getKind()) {
            case INSIDE -> {
                String label = n.getId();
                if (n instanceof InsideNode in) label = n.getId() + "\n" + in.getLabel();
                yield "label=\"" + escapeDot(label) + "\", shape=box, style=filled, fillcolor=palegreen";
            }
            case PARAMETER -> {
                String label = n.getId();
                if (n instanceof ParameterNode pn) label = n.getId() + "\n" + pn.getLabel();
                yield "label=\"" + escapeDot(label) + "\", shape=ellipse, style=filled, fillcolor=lightblue";
            }
            case LOAD -> {
                String label = n.getId();
                if (n instanceof LoadNode ln) label = n.getId() + "\n" + ln.getLabel();
                yield "label=\"" + escapeDot(label) + "\", shape=diamond, style=filled, fillcolor=lightsalmon";
            }
            case GLOBAL ->
                "label=\"GBL\\n(static)\", shape=octagon, style=filled, fillcolor=orange";
        };
    }

    /**
     * Generate a safe filename base from a method signature (no extension).
     */
    public static String sanitizeFileName(String sig) {
        String name = sig.replaceAll("[<>: (),]", "_")
                        .replaceAll("_+", "_")
                        .replaceAll("^_|_$", "");
        if (name.length() > 80) {
            name = name.substring(0, 80);
        }
        return name;
    }

    private static String simpleTypeName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static String escapeDot(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
