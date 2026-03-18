package edu.uw.cse.sideeffect.analysis;

import com.google.gson.*;
import edu.uw.cse.sideeffect.graph.*;
import edu.uw.cse.sideeffect.graph.PointsToGraph.EdgeTarget;
import edu.uw.cse.sideeffect.graph.PointsToGraph.MutatedField;
import sootup.core.signatures.FieldSignature;

import java.util.*;

/**
 * JSON serialization/deserialization for MethodSummary + PointsToGraph.
 * Uses deterministic semantic IDs so that cached summaries are stable across runs.
 */
public class MethodSummarySerializer {

    /**
     * Serialize a MethodSummary to a JsonObject.
     */
    public static JsonObject serialize(MethodSummary summary) {
        JsonObject root = new JsonObject();
        root.addProperty("sig", summary.getMethodSignature());
        root.addProperty("result", summary.getResult().name());

        JsonArray reasonsArr = new JsonArray();
        for (String r : summary.getReasons()) {
            reasonsArr.add(r);
        }
        root.add("reasons", reasonsArr);

        // Serialize return targets
        JsonArray returnArr = new JsonArray();
        for (Node n : summary.getReturnTargets()) {
            returnArr.add(serializeNode(n));
        }
        root.add("returnTargets", returnArr);

        // Serialize exit graph
        root.add("exitGraph", serializeGraph(summary.getExitGraph()));

        return root;
    }

    /**
     * Deserialize a MethodSummary from a JsonObject.
     */
    public static MethodSummary deserialize(JsonObject root) {
        String sig = root.get("sig").getAsString();
        MethodSummary.SideEffectResult result =
                MethodSummary.SideEffectResult.valueOf(root.get("result").getAsString());

        List<String> reasons = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("reasons")) {
            reasons.add(e.getAsString());
        }

        // Deserialize nodes (build lookup map)
        Map<String, Node> nodeMap = new HashMap<>();
        JsonObject graphObj = root.getAsJsonObject("exitGraph");

        // Deserialize all nodes first
        for (JsonElement e : graphObj.getAsJsonArray("nodes")) {
            Node n = deserializeNode(e.getAsJsonObject());
            nodeMap.put(n.getId(), n);
        }

        // Deserialize exit graph
        PointsToGraph exitGraph = deserializeGraph(graphObj, nodeMap);

        // Deserialize return targets
        Set<Node> returnTargets = new HashSet<>();
        for (JsonElement e : root.getAsJsonArray("returnTargets")) {
            JsonObject nodeObj = e.getAsJsonObject();
            String id = nodeObj.get("id").getAsString();
            Node n = nodeMap.get(id);
            if (n != null) returnTargets.add(n);
        }

        return new MethodSummary(sig, exitGraph, result, reasons, returnTargets);
    }

    // --- Node serialization ---

    private static JsonObject serializeNode(Node n) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", n.getId());
        obj.addProperty("kind", n.getKind().name());
        if (n instanceof ParameterNode pn) {
            obj.addProperty("paramIndex", pn.getParamIndex());
            obj.addProperty("label", pn.getLabel());
        } else if (n instanceof InsideNode in) {
            obj.addProperty("label", in.getLabel());
        } else if (n instanceof LoadNode ln) {
            obj.addProperty("label", ln.getLabel());
        }
        return obj;
    }

    private static Node deserializeNode(JsonObject obj) {
        String id = obj.get("id").getAsString();
        String kind = obj.get("kind").getAsString();
        return switch (kind) {
            case "GLOBAL" -> GlobalNode.INSTANCE;
            case "PARAMETER" -> new ParameterNode(
                    obj.get("paramIndex").getAsInt(),
                    obj.has("label") ? obj.get("label").getAsString() : "parameter");
            case "INSIDE" -> {
                // Extract site index from id "I<num>"
                int siteIndex = Integer.parseInt(id.substring(1));
                yield new InsideNode(siteIndex,
                        obj.has("label") ? obj.get("label").getAsString() : "cached");
            }
            case "LOAD" -> {
                int siteIndex = Integer.parseInt(id.substring(1));
                yield new LoadNode(siteIndex,
                        obj.has("label") ? obj.get("label").getAsString() : "cached");
            }
            default -> throw new IllegalArgumentException("Unknown node kind: " + kind);
        };
    }

    // --- Graph serialization ---

    private static JsonObject serializeGraph(PointsToGraph graph) {
        JsonObject obj = new JsonObject();

        // Collect and serialize all nodes
        Set<Node> allNodes = graph.getAllNodes();
        JsonArray nodesArr = new JsonArray();
        for (Node n : allNodes) {
            nodesArr.add(serializeNode(n));
        }
        obj.add("nodes", nodesArr);

        // Serialize edges
        JsonArray edgesArr = new JsonArray();
        for (var srcEntry : graph.getEdges().entrySet()) {
            Node src = srcEntry.getKey();
            for (var fieldEntry : srcEntry.getValue().entrySet()) {
                FieldSignature field = fieldEntry.getKey();
                for (EdgeTarget et : fieldEntry.getValue()) {
                    JsonObject edgeObj = new JsonObject();
                    edgeObj.addProperty("src", src.getId());
                    edgeObj.addProperty("field", field != null ? field.toString() : null);
                    edgeObj.addProperty("target", et.target().getId());
                    edgeObj.addProperty("type", et.type().name());
                    edgesArr.add(edgeObj);
                }
            }
        }
        obj.add("edges", edgesArr);

        // Serialize mutated fields
        JsonArray mutArr = new JsonArray();
        for (MutatedField mf : graph.getMutatedFields()) {
            JsonObject mfObj = new JsonObject();
            mfObj.addProperty("node", mf.node().getId());
            mfObj.addProperty("field", mf.field() != null ? mf.field().toString() : null);
            mutArr.add(mfObj);
        }
        obj.add("mutatedFields", mutArr);

        // Serialize global escaped
        JsonArray escArr = new JsonArray();
        for (Node n : graph.getGlobalEscaped()) {
            escArr.add(n.getId());
        }
        obj.add("globalEscaped", escArr);

        // Serialize counters
        obj.addProperty("insideNodeCounter", graph.getInsideNodeCounter());
        obj.addProperty("loadNodeCounter", graph.getLoadNodeCounter());

        return obj;
    }

    private static PointsToGraph deserializeGraph(JsonObject obj, Map<String, Node> nodeMap) {
        PointsToGraph graph = new PointsToGraph();

        // Deserialize edges
        for (JsonElement e : obj.getAsJsonArray("edges")) {
            JsonObject edgeObj = e.getAsJsonObject();
            String srcId = edgeObj.get("src").getAsString();
            String targetId = edgeObj.get("target").getAsString();
            String typeStr = edgeObj.get("type").getAsString();

            Node src = nodeMap.get(srcId);
            Node target = nodeMap.get(targetId);
            if (src == null || target == null) continue;

            // We cannot fully reconstruct FieldSignature without SootUp context,
            // so we store/load them as null. This means the cached graph won't have
            // field-precise edges, but the verdict + reasons are preserved.
            // For instantiation, what matters is the node structure.
            EdgeType type = EdgeType.valueOf(typeStr);
            if (type == EdgeType.INSIDE) {
                graph.addInsideEdge(src, null, target);
            } else {
                graph.addOutsideEdge(src, null, target);
            }
        }

        // Deserialize mutated fields
        for (JsonElement e : obj.getAsJsonArray("mutatedFields")) {
            JsonObject mfObj = e.getAsJsonObject();
            String nodeId = mfObj.get("node").getAsString();
            Node node = nodeMap.get(nodeId);
            if (node != null) {
                graph.recordMutation(node, null);
            }
        }

        // Deserialize global escaped
        for (JsonElement e : obj.getAsJsonArray("globalEscaped")) {
            String nodeId = e.getAsString();
            Node node = nodeMap.get(nodeId);
            if (node != null) {
                graph.markGlobalEscaped(node);
            }
        }

        // Restore counters
        if (obj.has("insideNodeCounter")) {
            graph.setInsideNodeCounter(obj.get("insideNodeCounter").getAsInt());
        }
        if (obj.has("loadNodeCounter")) {
            graph.setLoadNodeCounter(obj.get("loadNodeCounter").getAsInt());
        }

        return graph;
    }
}
