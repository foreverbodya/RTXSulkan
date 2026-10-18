package com.sulkan.shaders.graph;

/** CPU contract checks; deliberately separate from GPU execution evidence. */
public final class RayGraphContract {
    private static final String MANIFEST = """
        {"version":1,"name":"ray contract","resources":{"result":{"format":"RGBA16_FLOAT"}},
        "passes":[{"name":"trace","compute":"trace.comp","rayScene":true,
        "reads":{"Depth":"$depth"},"writes":["result"]}],"present":"result"}
        """;
    public static void main(String[] args) {
        PackGraph graph = PackGraph.parse(MANIFEST);
        PackGraph.Pass pass = graph.schedule().getFirst();
        if (!pass.rayScene() || !pass.selected(java.util.Map.of()).rayScene()) {
            throw new AssertionError("Ray binding was lost during pass selection");
        }
        String source = ComputeProgram.source(graph, pass, "#version 460\nvoid main() {}\n");
        if (!source.contains("binding=3) uniform accelerationStructureEXT SulkanRayScene;")) {
            throw new AssertionError("Acceleration structure binding index differs from descriptor layout");
        }
        if (source.indexOf("#extension GL_EXT_ray_query") > source.indexOf("uniform SulkanFrame")) {
            throw new AssertionError("Extension appears after declarations");
        }
        PackGraph.Pass legacy = PackGraph.parse(MANIFEST.replace(",\"rayScene\":true", "")).schedule().getFirst();
        if (legacy.rayScene() || ComputeProgram.source(graph, legacy, "#version 460\nvoid main() {}\n").contains("SulkanRayScene")) {
            throw new AssertionError("Legacy pass unexpectedly requires ray queries");
        }
        rejected(MANIFEST.replace("\"rayScene\":true", "\"rayScene\":\"true\""), "rayScene must be a boolean");
        rejected(MANIFEST.replace("\"compute\":\"trace.comp\"", "\"fragment\":\"trace.fsh\""), "rayScene requires a compute pass");
        String attributed = MANIFEST.replace("\"rayScene\":true", "\"rayScene\":true,\"rayAttributes\":true")
                .replace("\"Depth\":\"$depth\"", "\"Depth\":\"$depth\",\"Atlas\":\"$terrainAtlas\"");
        PackGraph attributedGraph = PackGraph.parse(attributed);
        PackGraph.Pass attributedPass = attributedGraph.schedule().getFirst();
        if (!attributedPass.rayAttributes() || !attributedPass.selected(java.util.Map.of()).rayAttributes()) {
            throw new AssertionError("Ray attributes lost during selection");
        }
        String attributedSource = ComputeProgram.source(attributedGraph, attributedPass, "#version 460\nvoid main() {}\n");
        if (!attributedSource.contains("binding=4) uniform accelerationStructureEXT SulkanRayScene;")
                || !attributedSource.contains("binding=5) readonly buffer SulkanRayAttributes")) {
            throw new AssertionError("Ray descriptors have inconsistent binding order");
        }
        rejected(attributed.replace("\"rayAttributes\":true", "\"rayAttributes\":1"), "rayAttributes must be a boolean");
        rejected(attributed.replace("\"rayScene\":true", "\"rayScene\":false"), "rayAttributes requires rayScene");
        System.out.println("SULKAN_RAY_GRAPH_CONTRACT_PASS checks=10 scope=CPU_SCHEMA_AND_BINDING_ONLY");
    }
    private static void rejected(String source, String message) {
        try { PackGraph.parse(source); }
        catch (IllegalArgumentException expected) {
            if (expected.getMessage().contains(message)) return;
            throw new AssertionError("Unexpected rejection", expected);
        }
        throw new AssertionError("Invalid manifest was accepted");
    }
}
