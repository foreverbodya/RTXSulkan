package com.sulkan.shaders.render.raytracing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Shared CPU/reference contract for ray-hit attributes from the accepted Sodium mesh. */
public final class RaySurfaceAttributes {
    public static final int HEADER_BYTES = 16;
    public static final int VERTEX_BYTES = 32;
    public static final int COMPACT_VERTEX_BYTES = 28;
    public record Vec2(float x, float y) {}
    public record Vec3(float x, float y, float z) {}
    public record Color(float r, float g, float b, float a) {}
    public record Surface(Vec3 position, Vec3 normal, Vec2 atlasUV, Color tint,
                          float blockLight, float skyLight, int materialClass,
                          int terrainFlags, float windWeight, float occlusion, TerrainMesh.Opacity opacity) {}
    private RaySurfaceAttributes() {}

    /** Barycentric u/v correspond to indexed triangle vertices 1/2, as in rayQueryEXT. */
    public static Surface interpolate(TerrainMesh mesh, int primitive, float u, float v,
                                      float shrinkU, float shrinkV) {
        if (primitive < 0 || primitive >= mesh.triangleCount()) throw new IndexOutOfBoundsException(primitive);
        if (!Float.isFinite(u) || !Float.isFinite(v) || u < 0 || v < 0 || u + v > 1
                || !Float.isFinite(shrinkU) || !Float.isFinite(shrinkV) || shrinkU < 0 || shrinkV < 0) {
            throw new IllegalArgumentException("Invalid hit coordinates or atlas shrink");
        }
        var decoded = mesh.readView();
        var indices = decoded.indices();
        var positions = decoded.positions();
        int a = indices.get(primitive * 3), b = indices.get(primitive * 3 + 1), c = indices.get(primitive * 3 + 2);
        float[] weights = {1 - u - v, u, v};
        int[] vertices = {a,b,c};
        float[] p = new float[3], color = new float[4];
        float texU = 0, texV = 0, block = 0, sky = 0, wind = 0, occlusion = 0;
        int material = decoded.packedMaterial(a), flags = (decoded.packedLightAndData(a) >>> 16) & 255;
        for (int i = 0; i < 3; i++) {
            int vertex = vertices[i];
            int vertexMaterial = decoded.packedMaterial(vertex);
            if ((vertexMaterial & 65535) != (material & 65535) || ((decoded.packedLightAndData(vertex) >>> 16) & 255) != flags) {
                throw new IllegalArgumentException("Triangle flat material attributes differ");
            }
            for (int axis = 0; axis < 3; axis++) p[axis] += positions.get(vertex * 3 + axis) * weights[i];
            int packedColor = decoded.packedBaseColor(vertex);
            occlusion+=decoded.bakedOcclusion(vertex)*weights[i];
            for (int channel = 0; channel < 4; channel++) color[channel] += ((packedColor >>> (channel * 8)) & 255) / 255f * weights[i];
            int uv = decoded.packedTexture(vertex);
            texU += decodeUV(uv & 65535, shrinkU) * weights[i];
            texV += decodeUV(uv >>> 16, shrinkV) * weights[i];
            int light = decoded.packedLightAndData(vertex);
            block += Math.min(1, (light & 255) / 240f) * weights[i];
            sky += Math.min(1, ((light >>> 8) & 255) / 240f) * weights[i];
            wind += (vertexMaterial >>> 16) / 65535f * weights[i];
        }
        float abx = positions.get(b*3)-positions.get(a*3), aby = positions.get(b*3+1)-positions.get(a*3+1), abz = positions.get(b*3+2)-positions.get(a*3+2);
        float acx = positions.get(c*3)-positions.get(a*3), acy = positions.get(c*3+1)-positions.get(a*3+1), acz = positions.get(c*3+2)-positions.get(a*3+2);
        float nx = aby*acz-abz*acy, ny = abz*acx-abx*acz, nz = abx*acy-aby*acx;
        double length = Math.sqrt((double)nx*nx+(double)ny*ny+(double)nz*nz);
        if (!(length > 1e-12)) throw new IllegalArgumentException("Degenerate ray triangle");
        return new Surface(new Vec3(p[0],p[1],p[2]), new Vec3((float)(nx/length),(float)(ny/length),(float)(nz/length)),
                new Vec2(texU,texV), new Color(color[0],color[1],color[2],color[3]), block,sky,
                material & 65535, flags, wind, occlusion, mesh.opacity());
    }

    private static float decodeUV(int packed, float shrink) {
        return (packed & 32767) / 32768f + ((packed >>> 15) * 2 - 1) * shrink;
    }

    /** Header: vertex count, triangle count, opacity, vertex stride. Vertices precede uint indices. */
    public static ByteBuffer serialize(TerrainMesh mesh) {
        int vertexBytes = Math.multiplyExact(mesh.vertexCount(), VERTEX_BYTES);
        int bytes = Math.addExact(HEADER_BYTES, Math.addExact(vertexBytes, Math.multiplyExact(mesh.triangleCount(), 12)));
        ByteBuffer data = ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder());
        data.putInt(mesh.vertexCount()).putInt(mesh.triangleCount()).putInt(mesh.opacity().ordinal()).putInt(VERTEX_BYTES);
        var decoded = mesh.readView();
        var positions = decoded.positions();
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            data.putFloat(positions.get(vertex*3)).putFloat(positions.get(vertex*3+1)).putFloat(positions.get(vertex*3+2)).putFloat(decoded.bakedOcclusion(vertex));
            data.putInt(decoded.packedBaseColor(vertex)).putInt(decoded.packedTexture(vertex)).putInt(decoded.packedLightAndData(vertex)).putInt(decoded.packedMaterial(vertex));
        }
        var indices = decoded.indices();
        while (indices.hasRemaining()) data.putInt(indices.get());
        data.flip();
        return data.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    /** Seven words: packed position pair, base tint, UV, light/flags, material/wind, AO.
     * Canonical quad indices are derived from primitive ID in the shader.
     * Position words preserve the original quantization exactly; BLAS still uses
     * the separately decoded float positions, with build inputs retired after use.
     */
    public static ByteBuffer serializeCompact(TerrainMesh mesh) {
        int bytes = Math.addExact(HEADER_BYTES, Math.multiplyExact(mesh.vertexCount(), COMPACT_VERTEX_BYTES));
        ByteBuffer data = ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder());
        data.putInt(mesh.vertexCount()).putInt(mesh.triangleCount()).putInt(mesh.opacity().ordinal()).putInt(COMPACT_VERTEX_BYTES);
        var decoded = mesh.readView(); var packed = decoded.packedVertices();
        int strideWords = mesh.strideBytes() / 4;
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            data.putInt(packed.get(vertex * strideWords)).putInt(packed.get(vertex * strideWords + 1));
            data.putInt(decoded.packedBaseColor(vertex)).putInt(decoded.packedTexture(vertex))
                .putInt(decoded.packedLightAndData(vertex)).putInt(decoded.packedMaterial(vertex)).putFloat(decoded.bakedOcclusion(vertex));
        }
        return data.flip().asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }
}
