package com.sulkan.shaders.diagnostics;

import com.sulkan.shaders.render.raytracing.TerrainMesh;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;

/** Standalone CPU check: no Minecraft process, GPU, or user world. Run with -Xmx256m. */
public final class CheckTerrainMeshStorage {
    record Fixture(TerrainMesh mesh, int[] words) {}
    static int cases;
    static long checkedWords, checkedPositions, checkedIndices, payloadBytes, expandedBytes;

    static Fixture fixture(int stride, int vertices, boolean random, int seed) {
        var rng = new Random(seed);
        var source = ByteBuffer.allocate(vertices * stride + 24).order(ByteOrder.nativeOrder());
        source.position(12); source.limit(12 + vertices * stride);
        int words = stride / 4;
        int[] expected = new int[vertices * words];
        for (int vertex = 0; vertex < vertices; vertex++) for (int word = 0; word < words; word++) {
            int value = random ? rng.nextInt() : switch (word) {
                case 0 -> ((vertex / 4) % 1024) * 0x00100401;
                case 1 -> (vertex % 4) * 0x00100401;
                case 2, 6 -> 0xff80c040;
                case 3 -> (vertex % 4) * 8192;
                case 4 -> 0x0ff000f0;
                case 5 -> 0x55aa0003;
                default -> 0;
            };
            if (stride == 32 && word == 7) value = Float.floatToRawIntBits((vertex % 17) / 16f);
            expected[vertex * words + word] = value;
            source.putInt(12 + (vertex * words + word) * 4, value);
        }
        var opacity = TerrainMesh.Opacity.values()[Math.floorMod(seed, 3)];
        var mesh = TerrainMesh.copyCompressedQuads(source, vertices, opacity);
        require(source.position() == 12 && source.limit() == 12 + vertices * stride, "Input buffer state modified");
        for (int i = source.position(); i < source.limit(); i++) source.put(i, (byte) 0x55);
        return new Fixture(mesh, expected);
    }

    static void verify(Fixture fixture) {
        var mesh = fixture.mesh(); var view = mesh.readView();
        var packed = view.packedVertices(); var positions = view.positions(); var indices = view.indices();
        int words = mesh.strideBytes() / 4;
        require(packed.isReadOnly() && positions.isReadOnly() && indices.isReadOnly(), "Writable geometry snapshot");
        for (int i = 0; i < fixture.words().length; i++) {
            require(packed.get(i) == fixture.words()[i], "Packed bits changed at " + i); checkedWords++;
        }
        for (int v = 0; v < mesh.vertexCount(); v++) {
            int high = fixture.words()[v * words], low = fixture.words()[v * words + 1];
            for (int axis = 0; axis < 3; axis++) {
                int shift = axis * 10;
                int quantized = ((high >>> shift) & 0x3ff) * 1024 + ((low >>> shift) & 0x3ff);
                float expected = quantized / 32768f - 8f;
                require(Float.floatToRawIntBits(positions.get(v * 3 + axis)) == Float.floatToRawIntBits(expected), "Position bits changed");
                checkedPositions++;
            }
            require(view.packedColor(v) == fixture.words()[v * words + 2], "Color changed");
            require(view.packedTexture(v) == fixture.words()[v * words + 3], "UV changed");
            require(view.packedLightAndData(v) == fixture.words()[v * words + 4], "Light/flags changed");
            require(view.packedMaterial(v) == (words >= 6 ? fixture.words()[v * words + 5] : 0), "Material changed");
            require(view.packedBaseColor(v) == fixture.words()[v * words + (words == 8 ? 6 : 2)], "Base tint changed");
            require(Float.floatToRawIntBits(view.bakedOcclusion(v)) == (words == 8 ? fixture.words()[v * words + 7] : Float.floatToRawIntBits(1f)), "AO changed");
        }
        int[] topology = {0, 1, 2, 2, 3, 0};
        for (int i = 0; i < indices.limit(); i++) {
            require(indices.get(i) == (i / 6) * 4 + topology[i % 6], "Topology changed"); checkedIndices++;
        }
        require(mesh.expandedBytes() == (long) mesh.vertexCount() * (mesh.strideBytes() + 18), "Expanded accounting differs");
        require(mesh.ownedBytes() <= (long) mesh.vertexCount() * mesh.strideBytes(), "Payload grew beyond raw storage");
        payloadBytes += mesh.ownedBytes(); expandedBytes += mesh.expandedBytes(); cases++;
        require(TerrainMesh.decodeStatistics().bytes() <= TerrainMesh.DECODED_BUDGET_BYTES, "Decode cache exceeds budget");
    }

    static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new IllegalStateException("Malformed input was accepted");
    }

    public static void main(String[] args) throws Exception {
        Path report = Path.of(args[0]); require(!Files.exists(report), "Use a new report path");
        TerrainMesh.clearDecodedCache();
        for (int stride : new int[]{20, 24, 32}) for (boolean random : new boolean[]{false, true})
            for (int vertices : new int[]{4, 512, 4096}) {
                var fixture = fixture(stride, vertices, random, vertices + stride);
                verify(fixture); fixture.mesh().evictDecoded(); verify(fixture);
            }
        var first = fixture(32, 65536, false, 99);
        verify(first);
        var pinned = first.mesh().readView();
        long before = TerrainMesh.decodeStatistics().decodes();
        var pressure = new ArrayList<Fixture>();
        for (int i = 0; i < 6; i++) {
            var entry = fixture(32, 65536, true, i); pressure.add(entry); verify(entry);
        }
        long afterPressure = TerrainMesh.decodeStatistics().decodes();
        require(TerrainMesh.decodeStatistics().evictions() > 0, "No eviction exercised");
        verify(first);
        require(TerrainMesh.decodeStatistics().decodes() > afterPressure && afterPressure > before, "Evicted mesh was not reacquired");
        require(pinned.packedVertices().get(0) == first.words()[0], "Pinned data invalidated by eviction");
        var oversized = fixture(32, 200000, false, 12);
        require(oversized.mesh().expandedBytes() > TerrainMesh.DECODED_BUDGET_BYTES, "Run this probe with -Xmx256m");
        verify(oversized);
        var corrupt = fixture(32, 512, false, 7);
        var field = TerrainMesh.class.getDeclaredField("payload"); field.setAccessible(true);
        byte[] payload = (byte[]) field.get(corrupt.mesh());
        require(payload.length < 512 * 32, "Corruption case must use DEFLATE");
        payload[payload.length - 1] ^= 1;
        boolean corruptRejected = false;
        try { corrupt.mesh().readView(); } catch (IllegalStateException expected) { corruptRejected = true; }
        require(corruptRejected, "Corrupt compressed stream accepted");
        rejects(() -> TerrainMesh.copyCompressedQuads(ByteBuffer.allocate(0), 0, TerrainMesh.Opacity.OPAQUE));
        rejects(() -> TerrainMesh.copyCompressedQuads(ByteBuffer.allocate(64), 4, TerrainMesh.Opacity.OPAQUE));
        var invalidAo = ByteBuffer.allocate(128).order(ByteOrder.nativeOrder()); invalidAo.putFloat(28, Float.NaN);
        rejects(() -> TerrainMesh.copyCompressedQuads(invalidAo, 4, TerrainMesh.Opacity.OPAQUE));
        long evictions = TerrainMesh.decodeStatistics().evictions();
        TerrainMesh.clearDecodedCache();
        require(TerrainMesh.decodeStatistics().bytes() == 0 && TerrainMesh.decodeStatistics().meshes() == 0, "Clear did not release cache ownership");
        require(pinned.packedVertices().get(0) == first.words()[0], "Clear invalidated pinned view");
        verify(first); TerrainMesh.clearDecodedCache();
        String json = "{\n  \"passed\": true,\n  \"casesIncludingReacquisition\": " + cases
            + ",\n  \"checkedPackedWords\": " + checkedWords + ",\n  \"checkedPositionBits\": " + checkedPositions
            + ",\n  \"checkedIndices\": " + checkedIndices + ",\n  \"payloadBytesAcrossChecks\": " + payloadBytes
            + ",\n  \"expandedBytesAcrossChecks\": " + expandedBytes + ",\n  \"decodeBudgetBytes\": " + TerrainMesh.DECODED_BUDGET_BYTES
            + ",\n  \"evictions\": " + evictions + ",\n  \"corruptStreamRejected\": true,\n  \"scope\": \"Independent CPU packed bits, position bits, topology, legacy/material attributes, source lifetime, eviction/reacquisition, pinned views, oversized operation, malformed/corrupt input. Not GPU or 32-chunk acceptance.\"\n}\n";
        Files.writeString(report, json); System.out.print(json);
    }
}
