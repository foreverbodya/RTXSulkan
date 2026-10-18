package com.sulkan.shaders.diagnostics;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.sulkan.shaders.mixin.GraphDeviceAccessor;
import com.sulkan.shaders.platform.vulkan.NativeBuffer;
import com.sulkan.shaders.platform.vulkan.RayTracingFeatures;
import com.sulkan.shaders.render.raytracing.*;
import com.sulkan.shaders.runtime.ShaderRuntime;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static com.sulkan.shaders.platform.vulkan.VulkanChecks.requireSuccess;

/** Opt-in world-free diagnostic of actual ray barycentrics and the shared attribute decoder. */
public final class RaySurfaceConformance {
    private static boolean finished;
    private static boolean surfaceVerified;
    private static final float[][] RAYS = {{1,.5f,2,-1},{1,1.5f,2,-1},{.25f,.1f,3,-1},
            {.25f,1.9f,1,-1},{1,.5f,-2,1},{2.2f,1,2,-1}};
    private static final String HEADER = """
        #version 460
        #extension GL_EXT_ray_query : require
        #extension GL_EXT_buffer_reference : require
        #extension GL_EXT_buffer_reference_uvec2 : require
        layout(local_size_x=8) in;
        layout(set=0,binding=0) uniform accelerationStructureEXT scene;
        struct Ray { vec4 originMin; vec4 directionMax; uvec4 mask; };
        layout(std430,set=0,binding=1) readonly buffer Rays { Ray rays[]; };
        layout(std430,set=0,binding=2) writeonly buffer Results { vec4 hits[]; };
        layout(std430,set=0,binding=3) writeonly buffer Diagnostics { vec4 decoded[]; };
        layout(std430,set=0,binding=4) readonly buffer Attributes { uint words[]; } SulkanRayAttributeData;
        struct Sample { vec4 uvBary; vec4 color; vec4 positionPrimitive; vec4 normalFlags; vec4 lightWindMaterial; vec4 occlusion; };
        layout(std430,set=0,binding=5) writeonly buffer Samples { Sample samples[]; };
        """;
    private static final String BODY = """
        void main() {
            uint i = gl_GlobalInvocationID.x;
            if (i >= rays.length()) return;
            Ray ray = rays[i];
            rayQueryEXT query;
            rayQueryInitializeEXT(query, scene, gl_RayFlagsOpaqueEXT, ray.mask.x,
                ray.originMin.xyz, ray.originMin.w, ray.directionMax.xyz, ray.directionMax.w);
            while (rayQueryProceedEXT(query)) {}
            decoded[i] = vec4(ray.originMin.z,ray.directionMax.z,ray.directionMax.w,float(ray.mask.x));
            hits[i] = vec4(0,0,-1,-1);
            samples[i] = Sample(vec4(0),vec4(0),vec4(0),vec4(0),vec4(0),vec4(0));
            if (rayQueryGetIntersectionTypeEXT(query,true) != gl_RayQueryCommittedIntersectionNoneEXT) {
                uint primitive = rayQueryGetIntersectionPrimitiveIndexEXT(query,true);
                vec2 bary = rayQueryGetIntersectionBarycentricsEXT(query,true);
                uint header = rayQueryGetIntersectionInstanceCustomIndexEXT(query,true);
                SulkanRaySurface surface = sulkanRayAttributes(header,primitive,bary,vec2(.01,.02));
                hits[i] = vec4(1,rayQueryGetIntersectionTEXT(query,true),float(primitive),
                    float(rayQueryGetIntersectionInstanceCustomIndexEXT(query,true)));
                samples[i] = Sample(vec4(surface.uv,bary),surface.color,vec4(surface.position,float(primitive)),
                    vec4(surface.normal,float(surface.flags)),vec4(surface.light,surface.wind,float(surface.material)),vec4(surface.occlusion,0,0,0));
            }
        }
        """;
    private RaySurfaceConformance() {}

    public static void tick(Minecraft client) {
        if (finished || !Boolean.getBoolean("sulkan.raySurfaceConformance") || client.gui.overlay() != null || ShaderRuntime.resourceReloading()) return;
        try {
            var backend = ((GraphDeviceAccessor)RenderSystem.getDevice()).sulkan$backend();
            if (!(backend instanceof VulkanDevice device) || !RayTracingFeatures.enabledFor(device.vkDevice())) {
                throw new IllegalStateException("Ray attribute diagnostic requires negotiated Vulkan ray queries");
            }
            String helper;
            try (var stream = RaySurfaceConformance.class.getResourceAsStream("/assets/sulkan/shaders/include/ray_surface_attributes.glsl")) {
                if (stream == null) throw new IllegalStateException("Attribute shader helper missing");
                helper = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if(!surfaceVerified) {
                for (int stride : new int[]{20,24,32}) for (boolean compact : new boolean[]{false,true})
                    verify(device, mesh(stride), HEADER + helper + BODY,compact);
                if (NativeBuffer.liveBufferCount() != 0) throw new IllegalStateException("Ray attribute diagnostic leaked buffers");
                surfaceVerified=true;
            }
            if(!RayBudgetRetirementConformance.tick(device))return;
            finished=true;
            LogUtils.getLogger().info("SULKAN_RAY_SURFACE_GPU_PASS strides=20,24,32 rays=36 payloads=legacy32,compact28 hitBarycentrics=true UV=true color=true occlusion=true lights=true material=true wind=true position=true normal=true missedWritesDetected=true pooled=true deviceLocal=true nonzeroHeader=true canonicalQuadTopology=true legacySharedBLAS=true compactBuildInputsRetired=true");
        } catch (Throwable failure) {finished=true; LogUtils.getLogger().error("SULKAN_RAY_SURFACE_GPU_FAIL", failure); }
        finally { if(finished)client.stop(); }
    }

    private static void verify(VulkanDevice backend, TerrainMesh mesh, String shader,boolean compact) throws Exception {
        VkDevice device = backend.vkDevice();
        int host = VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        try (NativeBuffer input = new NativeBuffer(device,RAYS.length*48L,VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,host);
             NativeBuffer hits = new NativeBuffer(device,RAYS.length*16L,VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,host);
             NativeBuffer decoded = new NativeBuffer(device,RAYS.length*16L,VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,host);
             RayAttributeArena arena = new RayAttributeArena(device,((compact?RaySurfaceAttributes.serializeCompact(mesh):RaySurfaceAttributes.serialize(mesh)).remaining()+15)/16*16,2,1024,compact);
             NativeBuffer samples = new NativeBuffer(device,RAYS.length*96L,VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,host);
             var pipeline = new RayQueryConformance.QueryPipeline(device,shader,2);
             MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer rayData = stack.malloc(RAYS.length*48);
            for (float[] ray : RAYS) rayData.putFloat(ray[0]).putFloat(ray[1]).putFloat(ray[2]).putFloat(0)
                    .putFloat(0).putFloat(0).putFloat(ray[3]).putFloat(10).putInt(255).putInt(0).putInt(0).putInt(0);
            input.write(rayData.flip());
            ByteBuffer sentinel = stack.malloc(RAYS.length*96);
            while (sentinel.hasRemaining()) sentinel.putFloat(Float.NaN);
            samples.write(sentinel.flip());
            hits.write(sentinel.duplicate().limit(RAYS.length*16));
            var encoder = backend.createCommandEncoder();
            var commands = encoder.allocateAndBeginTransientCommandBuffer();
            AccelerationStructure bottom = null, top = null;
            RayAttributeArena.Upload unused=null,attributeUpload=null;
            boolean executed = false;
            try {
                var builder = new AccelerationStructureBuilder(device);
                AccelerationStructureBuilder.dependency(commands,VK10.VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        VK10.VK_ACCESS_HOST_WRITE_BIT,VK10.VK_ACCESS_SHADER_READ_BIT);
                unused=arena.upload(commands,mesh);
                attributeUpload=arena.upload(commands,mesh);
                if(arena.pageCount()!=2) throw new IllegalStateException("Attribute GPU probe did not cross payload pages");
                bottom = builder.mesh(commands,mesh,arena,attributeUpload.allocation());
                AccelerationStructureBuilder.dependency(commands,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
                top = builder.instances(commands,List.of(new AccelerationStructureBuilder.Instance(bottom,0,0,0,attributeUpload.allocation().headerWord(),255)));
                AccelerationStructureBuilder.dependency(commands,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR|VK10.VK_PIPELINE_STAGE_HOST_BIT,VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR|VK10.VK_ACCESS_HOST_WRITE_BIT,VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR|VK10.VK_ACCESS_SHADER_READ_BIT);
                pipeline.record(commands,top.handle(),input,hits,decoded,RAYS.length,stack,arena.buffer(),samples);
                AccelerationStructureBuilder.dependency(commands,VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK10.VK_PIPELINE_STAGE_HOST_BIT,
                        VK10.VK_ACCESS_SHADER_WRITE_BIT,VK10.VK_ACCESS_HOST_READ_BIT);
                requireSuccess(VK10.vkEndCommandBuffer(commands),"attribute query commands");
                encoder.execute(commands); executed = true; encoder.submit();
                requireSuccess(VK10.vkDeviceWaitIdle(device),"attribute diagnostic readback");
                compare(mesh,attributeUpload.allocation().headerWord(),hits.readFloats(RAYS.length*4),samples.readFloats(RAYS.length*24));
            } finally {
                if (executed) VK10.vkDeviceWaitIdle(device);
                if (top != null) top.close();
                if (bottom != null) bottom.close();
                if (unused!=null) { unused.staging().close(); arena.release(unused.allocation()); }
                if (attributeUpload!=null) { attributeUpload.staging().close(); arena.release(attributeUpload.allocation()); }
            }
        }
    }

    private static void compare(TerrainMesh mesh,int headerWord, float[] hits, float[] samples) {
        for (int i = 0; i < RAYS.length; i++) {
            float[] ray = RAYS[i];
            boolean hit = ray[0] >= 0 && ray[0] <= 2 && ray[1] >= 0 && ray[1] <= 2;
            close(hits[i*4],hit ? 1 : 0);
            if (!hit) {
                for (int j = 0; j < 24; j++) close(samples[i*24+j],0);
                continue;
            }
            int primitive = ray[0] > ray[1] ? 0 : 1;
            float u = primitive == 0 ? (ray[0]-ray[1])/2 : (ray[1]-ray[0])/2;
            float v = primitive == 0 ? ray[1]/2 : 1-ray[1]/2;
            close(hits[i*4+1],Math.abs(ray[2])); close(hits[i*4+2],primitive); close(hits[i*4+3],headerWord);
            var s = RaySurfaceAttributes.interpolate(mesh,primitive,u,v,.01f,.02f);
            float[] expected = {s.atlasUV().x(),s.atlasUV().y(),u,v,s.tint().r(),s.tint().g(),s.tint().b(),s.tint().a(),
                    ray[0],ray[1],0,primitive,0,0,1,s.terrainFlags(),s.blockLight(),s.skyLight(),s.windWeight(),s.materialClass(),s.occlusion(),0,0,0};
            for (int j = 0; j < expected.length; j++) close(samples[i*24+j],expected[j]);
        }
    }
    private static void close(float actual,float expected) {
        if (!Float.isFinite(actual) || Math.abs(actual-expected) > .0001f) throw new IllegalStateException("GPU attribute expected="+expected+" actual="+actual);
    }
    static TerrainMesh mesh(int stride) {
        ByteBuffer data = ByteBuffer.allocate(stride*4).order(ByteOrder.nativeOrder());
        int[][] p = {{0,0,0},{2,0,0},{2,2,0},{0,2,0}};
        int[] color = {0xff0000ff,0xff00ff00,0xffff0000,0xffffffff};
        int[] uv = {(4096|32768)|(8192<<16),12288|(8192<<16),12288|((16384|32768)<<16),(4096|32768)|((16384|32768)<<16)};
        int[] block = {0,120,240,0}, sky = {240,120,0,240}, wind = {0,32768,65535,0};
        for (int i = 0; i < 4; i++) {
            int high = 0,low = 0;
            for (int axis = 0; axis < 3; axis++) { int c = (p[i][axis]+8)*32768; high |= (c>>>10)<<(axis*10); low |= (c&1023)<<(axis*10); }
            data.putInt(high).putInt(low).putInt(stride==32 ? 0xff000000 : color[i]).putInt(uv[i]).putInt(block[i]|(sky[i]<<8)|(3<<16));
            if (stride >= 24) data.putInt(17|(wind[i]<<16));
            if (stride == 32) data.putInt(color[i]).putFloat((i+1)*.2f);
        }
        return TerrainMesh.copyCompressedQuads(data.flip(),4,TerrainMesh.Opacity.OPAQUE);
    }
}
