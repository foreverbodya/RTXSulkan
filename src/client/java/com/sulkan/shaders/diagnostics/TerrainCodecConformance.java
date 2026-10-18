package com.sulkan.shaders.diagnostics;

import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.MaterialChunkVertex;
import com.sulkan.shaders.graph.MaterialContext;
import com.sulkan.shaders.graph.MaterialTable;
import com.sulkan.shaders.graph.NativePackRuntime;
import com.sulkan.shaders.render.raytracing.TerrainMesh;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.system.MemoryUtil;

public final class TerrainCodecConformance {
    private TerrainCodecConformance() {}

    public static void verify() {
        ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
        float[][] coordinates = {{.12345f, 1.23456f, 2.34567f}, {15.99999f, 16.75f, -.5f}, {7.125f, -3.25f, 20.5f}, {0, 0, 0}};
        for (int i = 0; i < vertices.length; i++) {
            vertices[i].x = coordinates[i][0]; vertices[i].y = coordinates[i][1]; vertices[i].z = coordinates[i][2];
            vertices[i].u = .125f + i * .0625f; vertices[i].v = .25f + i * .03125f;
            vertices[i].color = 0xffbbaa99+i; vertices[i].light = 0x00f000f0; vertices[i].ao = (i+1)*.25f;
        }
        long compact = MemoryUtil.nmemCalloc(1, 80);
        long material = MemoryUtil.nmemCalloc(1, 144);
        try {
            new CompactChunkVertex().getEncoder().write(compact, 3, vertices, 4);
            TerrainMesh first = TerrainMesh.copyCompressedQuads(MemoryUtil.memByteBuffer(compact, 80), 4, TerrainMesh.Opacity.OPAQUE);
            MaterialTable table = NativePackRuntime.materials();
            if (table == null || table.id(Blocks.OAK_LEAVES.defaultBlockState()) == 0) throw new IllegalStateException("QA codec requires an assigned leaf material");
            int expectedMaterial = table.id(Blocks.OAK_LEAVES.defaultBlockState());
            MaterialContext.enter(Blocks.OAK_LEAVES.defaultBlockState());
            MemoryUtil.memPutLong(material+128,0x1357246813572468L);
            try {
                if(new MaterialChunkVertex(table).getEncoder().write(material, 3, vertices, 4)!=material+128)
                    throw new IllegalStateException("Material encoder returned the wrong stride");
            }
            finally { MaterialContext.leave(); }
            if(MemoryUtil.memGetLong(material+128)!=0x1357246813572468L) throw new IllegalStateException("Material encoder overwrote its vertex allocation");
            TerrainMesh second = TerrainMesh.copyCompressedQuads(MemoryUtil.memByteBuffer(material, 128), 4, TerrainMesh.Opacity.OPAQUE);
            MemoryUtil.memSet(compact, 0x55, 80);
            MemoryUtil.memSet(material, 0x55, 128);
            for (TerrainMesh mesh : new TerrainMesh[]{first, second}) {
                for (int i = 0; i < 4; i++) for (int axis = 0; axis < 3; axis++) {
                    float decoded = mesh.positions().get(i * 3 + axis);
                    if (Math.abs(decoded - coordinates[i][axis]) > .00004f) {
                        throw new IllegalStateException("Sodium position decoding mismatch: stride=" + mesh.strideBytes() + " vertex=" + i + " axis=" + axis);
                    }
                }
                if (mesh.vertexCount() != 4 || mesh.triangleCount() != 2) throw new IllegalStateException("Invalid decoded mesh topology");
                if (!mesh.positions().isReadOnly() || !mesh.indices().isReadOnly() || !mesh.packedVertices().isReadOnly()) {
                    throw new IllegalStateException("Captured geometry must be immutable");
                }
            }
            for (int i = 0; i < 4; i++) {
                if ((second.packedMaterial(i) & 65535) != expectedMaterial) throw new IllegalStateException("Material ID lost during capture");
                if(second.packedBaseColor(i)!=vertices[i].color || second.bakedOcclusion(i)!=vertices[i].ao
                    || second.packedColor(i)!=first.packedColor(i)) throw new IllegalStateException("Separated tint/AO or legacy baked color differs");
                if(first.bakedOcclusion(i)!=1 || first.packedBaseColor(i)!=first.packedColor(i)) throw new IllegalStateException("Legacy material fallback differs");
            }
            LogUtils.getLogger().info("SULKAN_RT_TERRAIN_CODEC_PASS strides=20,32 originalEncoder=true independentCoordinates=true sourceLifetime=true materialId=true separateTintOcclusion=true legacyBakedColorPreserved=true bounds=true");
        } finally {
            MemoryUtil.nmemFree(compact);
            MemoryUtil.nmemFree(material);
        }
    }
}
