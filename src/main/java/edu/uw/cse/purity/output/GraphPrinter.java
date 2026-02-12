package edu.uw.cse.purity.output;

import edu.uw.cse.purity.analysis.MethodSummary;
import edu.uw.cse.purity.analysis.PurityChecker;
import edu.uw.cse.purity.graph.*;
import edu.uw.cse.purity.graph.PointsToGraph.EdgeTarget;
import edu.uw.cse.purity.graph.PointsToGraph.MutatedField;
import sootup.core.jimple.basic.Local;
import sootup.core.signatures.FieldSignature;

import java.io.*;
import java.util.*;

/**
 * Prints points-to graph summaries in text and DOT (Graphviz) format.
 *
 * DOT color scheme:
 *   InsideNode    -> green box       (newly allocated, mutations OK)
 *   ParameterNode -> blue ellipse    (prestate, mutations = impure)
 *   LoadNode      -> red diamond     (external/unknown, mutations = impure)
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
            if (!targets.isEmpty()) {
                List<String> ids = targets.stream().map(Node::getId).sorted().toList();
                System.out.println("  " + entry.getKey().getName() + " -> {" + String.join(", ", ids) + "}");
            }
        }
        System.out.println();

        // E (Globally Escaped)
        Set<Node> escaped = graph.getGlobalEscaped();
        List<String> escapedIds = escaped.stream().map(Node::getId).sorted().toList();
        System.out.println("E (Globally Escaped): {" + String.join(", ", escapedIds) + "}");
        System.out.println();

        // --- Purity Analysis ---
        System.out.println("--- Purity Analysis ---");

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
        Set<Node> prestateNodes = PurityChecker.computePrestateNodes(graph);
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
        Set<Node> prestateNodes = PurityChecker.computePrestateNodes(graph);

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
            out.println("  \"var_" + escapeDot(varName) + "\" [label=\"" + escapeDot(varName)
                + "\", shape=plaintext, fontcolor=gray40];");
            for (Node target : entry.getValue()) {
                out.println("  \"var_" + escapeDot(varName) + "\" -> \""
                    + escapeDot(target.getId()) + "\" [style=dotted, color=gray60];");
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
                        : "style=dashed, color=gray30";
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
            return " param " + pn.getParamIndex();
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
                if (n instanceof InsideNode in) label = n.getId() + "\\n" + in.getLabel();
                yield "label=\"" + escapeDot(label) + "\", shape=box, style=filled, fillcolor=palegreen";
            }
            case PARAMETER -> {
                String label = n.getId();
                if (n instanceof ParameterNode pn) label = n.getId() + "\\nparam " + pn.getParamIndex();
                yield "label=\"" + escapeDot(label) + "\", shape=ellipse, style=filled, fillcolor=lightblue";
            }
            case LOAD -> {
                String label = n.getId();
                if (n instanceof LoadNode ln) label = n.getId() + "\\n" + ln.getLabel();
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

    private static String escapeDot(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
