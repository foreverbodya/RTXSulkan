package com.sulkan.shaders.render.raytracing;

import com.sulkan.shaders.diagnostics.GeometryCpuMetrics;
import static com.sulkan.shaders.diagnostics.GeometryCpuMetrics.Stage.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Immutable accepted Sodium vertices. Lossless payloads survive decoded-cache eviction. */
public final class TerrainMesh {
    public enum Opacity { OPAQUE, CUTOUT, TRANSLUCENT }
    // Reserve part of the former overall resident-array budget for decoded data.
    public static final long RESIDENT_BUDGET_BYTES = Math.min(512L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4);
    public static final long DECODED_BUDGET_BYTES = RESIDENT_BUDGET_BYTES / 8;
    public static final long PAYLOAD_BUDGET_BYTES = RESIDENT_BUDGET_BYTES - DECODED_BUDGET_BYTES;
    private static final LinkedHashMap<TerrainMesh, Decoded> DECODED = new LinkedHashMap<>();
    private static long decodedBytes, decodeCount, evictionCount;
    private final Opacity opacity;
    private final int wordsPerVertex, vertexCount;
    private final byte[] payload;
    private final boolean compressed;
    private volatile Decoded decoded;

    private record Decoded(int[] packed, float[] positions, int[] indices) {
        long bytes() { return ((long) packed.length + positions.length + indices.length) * Integer.BYTES; }
    }
    public record DecodeStatistics(long bytes, long budgetBytes, int meshes, long decodes, long evictions) {}

    private TerrainMesh(Opacity opacity, int wordsPerVertex, int vertexCount, byte[] payload, boolean compressed) {
        this.opacity = opacity; this.wordsPerVertex = wordsPerVertex; this.vertexCount = vertexCount;
        this.payload = payload; this.compressed = compressed;
    }

    public static TerrainMesh copyCompressedQuads(ByteBuffer source, int vertexCount, Opacity opacity) {
        try (var capture = GeometryCpuMetrics.begin(CAPTURE, vertexCount)) {
            Objects.requireNonNull(source); Objects.requireNonNull(opacity);
            if (vertexCount <= 0 || vertexCount % 4 != 0 || source.remaining() % vertexCount != 0)
                throw new IllegalArgumentException("Invalid quad mesh dimensions");
            int stride = source.remaining() / vertexCount;
            if (stride != 20 && stride != 24 && stride != 32)
                throw new IllegalArgumentException("Unsupported Sodium vertex stride: " + stride);
            int words = stride / Integer.BYTES;
            // Separate word byte lanes across vertices before DEFLATE. This is
            // reversible for every bit, including material flags and float AO.
            byte[] planes;
            try (var lanes = GeometryCpuMetrics.begin(CAPTURE_LANES, vertexCount)) {
                planes = new byte[Math.multiplyExact(vertexCount, stride)];
                var input = source.duplicate().order(ByteOrder.nativeOrder());
                int start = input.position();
                for (int vertex = 0; vertex < vertexCount; vertex++) for (int word = 0; word < words; word++) {
                    int value = input.getInt(start + vertex * stride + word * 4);
                    if (stride == 32 && word == 7) {
                        float ao = Float.intBitsToFloat(value);
                        if (!Float.isFinite(ao) || ao < 0 || ao > 1)
                            throw new IllegalArgumentException("Invalid baked vertex occlusion: " + ao);
                    }
                    for (int lane = 0; lane < 4; lane++)
                        planes[(word * 4 + lane) * vertexCount + vertex] = (byte) (value >>> (lane * 8));
                }
            }
            byte[] zipped;
            try (var deflate = GeometryCpuMetrics.begin(CAPTURE_DEFLATE, planes.length)) {
                var compressor = new Deflater(Deflater.BEST_SPEED);
                try {
                    compressor.setInput(planes); compressor.finish();
                    var output = new ByteArrayOutputStream(Math.min(planes.length, 8192));
                    byte[] block = new byte[8192];
                    while (!compressor.finished()) {
                        int count = compressor.deflate(block);
                        if (count <= 0) throw new IllegalStateException("Terrain compression made no progress");
                        output.write(block, 0, count);
                    }
                    zipped = output.toByteArray();
                } finally { compressor.end(); }
            }
            return new TerrainMesh(opacity, words, vertexCount,
                zipped.length < planes.length ? zipped : planes, zipped.length < planes.length);
        }
    }

    private Decoded data() {
        Decoded ready = decoded;
        if (ready != null) { GeometryCpuMetrics.countCacheHit(); return ready; }
        synchronized (DECODED) {
            if (decoded != null) { GeometryCpuMetrics.countCacheHit(); return decoded; }
            try (var miss = GeometryCpuMetrics.begin(DECODE_MISS, vertexCount)) {
                int length = Math.multiplyExact(vertexCount, strideBytes());
                byte[] planes = payload;
                if (compressed) {
                    try (var inflate = GeometryCpuMetrics.begin(DECODE_INFLATE, length)) {
                        planes = new byte[Math.addExact(length, 1)];
                        var inflater = new Inflater();
                        try {
                            inflater.setInput(payload);
                            int offset = 0;
                            while (!inflater.finished() && offset < planes.length) {
                                int count = inflater.inflate(planes, offset, planes.length - offset);
                                if (count == 0 && inflater.finished()) break;
                                if (count <= 0) throw new IllegalStateException("Incomplete terrain payload");
                                offset += count;
                            }
                            if (!inflater.finished() || offset != length || inflater.getRemaining() != 0)
                                throw new IllegalStateException("Terrain payload extent changed");
                        } catch (DataFormatException failure) {
                            throw new IllegalStateException("Invalid compressed terrain payload", failure);
                        } finally { inflater.end(); }
                    }
                }
                try (var reconstruct = GeometryCpuMetrics.begin(DECODE_RECONSTRUCT, vertexCount)) {
                    int[] packed = new int[Math.multiplyExact(vertexCount, wordsPerVertex)];
                    float[] positions = new float[Math.multiplyExact(vertexCount, 3)];
                    int[] indices = new int[Math.multiplyExact(vertexCount / 4, 6)];
                    for (int vertex = 0; vertex < vertexCount; vertex++) {
                        for (int word = 0; word < wordsPerVertex; word++) {
                            int value = 0;
                            for (int lane = 0; lane < 4; lane++)
                                value |= (planes[(word * 4 + lane) * vertexCount + vertex] & 255) << (lane * 8);
                            packed[vertex * wordsPerVertex + word] = value;
                        }
                        int high = packed[vertex * wordsPerVertex], low = packed[vertex * wordsPerVertex + 1];
                        for (int axis = 0; axis < 3; axis++) {
                            int shift = axis * 10;
                            int coordinate = (((high >>> shift) & 1023) << 10) | ((low >>> shift) & 1023);
                            positions[vertex * 3 + axis] = coordinate * (32.0f / 1048576.0f) - 8.0f;
                        }
                    }
                    int[] order = {0, 1, 2, 2, 3, 0};
                    for (int quad = 0; quad < vertexCount / 4; quad++)
                        for (int index = 0; index < order.length; index++) indices[quad * 6 + index] = quad * 4 + order[index];
                    ready = new Decoded(packed, positions, indices); decodeCount++;
                }
                // FIFO by decode time: hot vertex access does not mutate bookkeeping.
                // Single oversize operations are supported but not retained here.
                try (var retain = GeometryCpuMetrics.begin(DECODE_RETAIN, ready.bytes())) {
                    if (ready.bytes() <= DECODED_BUDGET_BYTES) {
                        var iterator = DECODED.entrySet().iterator();
                        while (decodedBytes + ready.bytes() > DECODED_BUDGET_BYTES && iterator.hasNext()) {
                            var oldest = iterator.next(); decodedBytes -= oldest.getValue().bytes();
                            oldest.getKey().decoded = null; iterator.remove(); evictionCount++;
                        }
                        DECODED.put(this, ready); decodedBytes += ready.bytes(); decoded = ready;
                    }
                }
                return ready;
            }
        }
    }

    /** Pins one immutable decode for an operation, including an oversize mesh. */
    public ReadView readView() { return new ReadView(this, data()); }
    public static final class ReadView {
        private final TerrainMesh mesh;
        private final Decoded data;
        private ReadView(TerrainMesh mesh, Decoded data) { this.mesh = mesh; this.data = data; }
        public FloatBuffer positions() { return FloatBuffer.wrap(data.positions).asReadOnlyBuffer(); }
        public IntBuffer indices() { return IntBuffer.wrap(data.indices).asReadOnlyBuffer(); }
        public IntBuffer packedVertices() { return IntBuffer.wrap(data.packed).asReadOnlyBuffer(); }
        private int word(int vertex, int offset) { mesh.checkVertex(vertex); return data.packed[vertex * mesh.wordsPerVertex + offset]; }
        public int packedColor(int vertex) { return word(vertex, 2); }
        public int packedTexture(int vertex) { return word(vertex, 3); }
        public int packedLightAndData(int vertex) { return word(vertex, 4); }
        public int packedMaterial(int vertex) { mesh.checkVertex(vertex); return mesh.wordsPerVertex >= 6 ? word(vertex, 5) : 0; }
        public int packedBaseColor(int vertex) { return word(vertex, mesh.wordsPerVertex == 8 ? 6 : 2); }
        public float bakedOcclusion(int vertex) { mesh.checkVertex(vertex); return mesh.wordsPerVertex == 8 ? Float.intBitsToFloat(word(vertex, 7)) : 1; }
    }

    public void evictDecoded() {
        synchronized (DECODED) {
            var removed = DECODED.remove(this);
            if (removed != null) { decodedBytes -= removed.bytes(); evictionCount++; }
            decoded = null;
        }
    }
    public static void clearDecodedCache() {
        synchronized (DECODED) {
            DECODED.keySet().forEach(mesh -> mesh.decoded = null);
            evictionCount += DECODED.size(); DECODED.clear(); decodedBytes = 0;
        }
    }
    public static DecodeStatistics decodeStatistics() {
        synchronized (DECODED) { return new DecodeStatistics(decodedBytes, DECODED_BUDGET_BYTES, DECODED.size(), decodeCount, evictionCount); }
    }
    public Opacity opacity() { return opacity; }
    public int vertexCount() { return vertexCount; }
    public int triangleCount() { return vertexCount / 2; }
    public int strideBytes() { return wordsPerVertex * Integer.BYTES; }
    public FloatBuffer positions() { return FloatBuffer.wrap(data().positions).asReadOnlyBuffer(); }
    public IntBuffer indices() { return IntBuffer.wrap(data().indices).asReadOnlyBuffer(); }
    public IntBuffer packedVertices() { return IntBuffer.wrap(data().packed).asReadOnlyBuffer(); }
    public long ownedBytes() { return payload.length; }
    public long expandedBytes() { return (long) vertexCount * (strideBytes() + 18); }
    public int packedColor(int vertex) { checkVertex(vertex); return data().packed[vertex * wordsPerVertex + 2]; }
    public int packedTexture(int vertex) { checkVertex(vertex); return data().packed[vertex * wordsPerVertex + 3]; }
    public int packedLightAndData(int vertex) { checkVertex(vertex); return data().packed[vertex * wordsPerVertex + 4]; }
    public int packedMaterial(int vertex) { checkVertex(vertex); return wordsPerVertex >= 6 ? data().packed[vertex * wordsPerVertex + 5] : 0; }
    /** Legacy formats cannot recover the pre-occlusion tint; retain their baked color. */
    public int packedBaseColor(int vertex) { checkVertex(vertex); return data().packed[vertex * wordsPerVertex + (wordsPerVertex == 8 ? 6 : 2)]; }
    public float bakedOcclusion(int vertex) { checkVertex(vertex); return wordsPerVertex == 8 ? Float.intBitsToFloat(data().packed[vertex * wordsPerVertex + 7]) : 1; }
    private void checkVertex(int vertex) {
        if (vertex < 0 || vertex >= vertexCount) throw new IndexOutOfBoundsException(vertex);
    }
}
