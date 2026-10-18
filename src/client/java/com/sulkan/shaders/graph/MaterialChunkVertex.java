package com.sulkan.shaders.graph;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder.Vertex;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import org.lwjgl.system.MemoryUtil;

public final class MaterialChunkVertex implements ChunkVertexType {
   public static final int STRIDE = 32;
   private static final VertexFormat FORMAT = VertexFormat.builder(0)
      .addAttribute("a_Position", GpuFormat.RG32_UINT)
      .addAttribute("a_Color", GpuFormat.RGBA8_UNORM)
      .addAttribute("a_TexCoord", GpuFormat.RG16_UINT)
      .addAttribute("a_LightAndData", GpuFormat.RGBA8_UINT)
      .addAttribute("a_SulkanMaterial", GpuFormat.R32_UINT)
      .addAttribute("a_SulkanBaseColor", GpuFormat.RGBA8_UNORM)
      .addAttribute("a_SulkanOcclusion", GpuFormat.R32_FLOAT)
      .build();
   private final ChunkVertexEncoder encoder;

   public MaterialChunkVertex(MaterialTable materials) {
      ChunkVertexEncoder compact = new CompactChunkVertex().getEncoder();
      this.encoder = (pointer, material, vertices, section) -> {
         compact.write(pointer, material, vertices, section);
         int id = materials.id(MaterialContext.state());
         float minY = Float.POSITIVE_INFINITY;
         float maxY = Float.NEGATIVE_INFINITY;

         for (Vertex v : vertices) {
            minY = Math.min(minY, v.y);
            maxY = Math.max(maxY, v.y);
         }

         for (int vertex = 3; vertex >= 0; vertex--) {
            for (int word = 4; word >= 0; word--) {
               MemoryUtil.memPutInt(pointer + vertex * (long)STRIDE + word * 4L, MemoryUtil.memGetInt(pointer + vertex * 20L + word * 4L));
            }

            int packed = id;
            if (materials.vegetationWind && id >= 2 && id <= 5) {
               float tip = Math.clamp((vertices[vertex].y - minY) / Math.max(maxY - minY, 0.001F), 0.0F, 1.0F);
               float weight = id == 5 ? 1.0F : (id == 3 ? tip * 0.5F : (id == 4 ? 0.5F + tip * 0.5F : tip));
               packed = id | Math.round(weight * 65535.0F) << 16;
            }

            long output = pointer + vertex * (long)STRIDE;
            MemoryUtil.memPutInt(output + 20L, packed);
            // Keep Sodium's baked a_Color for legacy packs. Preserve the inputs
            // separately so physical material color does not contain occlusion.
            MemoryUtil.memPutInt(output + 24L, vertices[vertex].color);
            MemoryUtil.memPutFloat(output + 28L, vertices[vertex].ao);
         }

         return pointer + 4L * STRIDE;
      };
   }

   public VertexFormat getVertexFormat() {
      return FORMAT;
   }

   public ChunkVertexEncoder getEncoder() {
      return this.encoder;
   }
}
