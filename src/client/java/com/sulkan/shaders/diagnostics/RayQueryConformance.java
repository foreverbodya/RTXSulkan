package com.sulkan.shaders.diagnostics;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.sulkan.shaders.mixin.GraphDeviceAccessor;
import com.sulkan.shaders.platform.vulkan.NativeBuffer;
import com.sulkan.shaders.render.raytracing.AccelerationStructure;
import com.sulkan.shaders.render.raytracing.AccelerationStructureBuilder;
import com.sulkan.shaders.render.raytracing.TerrainGeometryCache;
import com.sulkan.shaders.render.raytracing.TerrainMesh;
import com.sulkan.shaders.render.raytracing.TerrainRayScene;
import com.sulkan.shaders.runtime.NativeShaderCompiler;
import com.sulkan.shaders.runtime.ShaderRuntime;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static com.sulkan.shaders.platform.vulkan.VulkanChecks.requireSuccess;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

/** Explicit opt-in GPU test. It never creates, loads, or changes a Minecraft world. */
public final class RayQueryConformance {
    private static boolean finished;
    private static TerrainRayScene terrainScene;
    private record SectionKey(int x, int y, int z) {}
    private static final java.util.Map<SectionKey, Object> terrainOwners = new java.util.HashMap<>();
    private static final java.util.Map<SectionKey, Long> terrainRevisions = new java.util.HashMap<>();
    private static final float[] TRIANGLE = {-1, -1, 0, 1, -1, 0, 0, 1, 0};
    private static final String SHADER = """
            #version 460
            #extension GL_EXT_ray_query : require
            layout(local_size_x=8) in;
            layout(set=0,binding=0) uniform accelerationStructureEXT scene;
            struct Ray { vec4 originMin; vec4 directionMax; uvec4 mask; };
            layout(std430,set=0,binding=1) readonly buffer Rays { Ray rays[]; };
            layout(std430,set=0,binding=2) writeonly buffer Results { vec4 hits[]; };
            layout(std430,set=0,binding=3) writeonly buffer Diagnostics { vec4 decoded[]; };
            void main() {
                uint index = gl_GlobalInvocationID.x;
                if (index >= rays.length() || index >= hits.length()) return;
                Ray ray = rays[index];
                decoded[index] = vec4(ray.originMin.z, ray.directionMax.z, ray.directionMax.w, float(ray.mask.x));
                rayQueryEXT query;
                rayQueryInitializeEXT(query, scene, gl_RayFlagsOpaqueEXT, ray.mask.x,
                    ray.originMin.xyz, ray.originMin.w, ray.directionMax.xyz, ray.directionMax.w);
                while (rayQueryProceedEXT(query)) {}
                vec4 result = vec4(0, 0, -1, -1);
                if (rayQueryGetIntersectionTypeEXT(query, true) == gl_RayQueryCommittedIntersectionTriangleEXT) {
                    result = vec4(1, rayQueryGetIntersectionTEXT(query, true),
                        float(rayQueryGetIntersectionPrimitiveIndexEXT(query, true)),
                        float(rayQueryGetIntersectionInstanceCustomIndexEXT(query, true)));
                }
                hits[index] = result;
            }
            """;

    private RayQueryConformance() {}

    public static void tick(Minecraft client) {
        if (finished || !Boolean.getBoolean("sulkan.rtConformance") || client.gui.overlay() != null
                || ShaderRuntime.resourceReloading()) return;
        finished = true;
        try {
            if (!(((GraphDeviceAccessor) RenderSystem.getDevice()).sulkan$backend() instanceof VulkanDevice device)) {
                throw new IllegalStateException("Conformance requires Minecraft's Vulkan device");
            }
            if (!device.vkDevice().getCapabilities().VK_KHR_acceleration_structure
                    || !device.vkDevice().getCapabilities().VK_KHR_ray_query) {
                throw new IllegalStateException("Conformance requires negotiated ray queries");
            }
            verify(device);
        } catch (Throwable failure) {
            LogUtils.getLogger().error("SULKAN_RT_CONFORMANCE_FAIL", failure);
        } finally {
            client.stop();
        }
    }

    private record Ray(float x, float y, float z, float dx, float dy, float dz, float min, float max, int mask) {}
    private static final Ray[] RAYS = {
            new Ray(0, 0, 2, 0, 0, -1, 0, 10, 255),
            new Ray(2, 0, 2, 0, 0, -1, 0, 10, 255),
            new Ray(3, 0, 3, 0, 0, -1, 0, 10, 255),
            new Ray(3, 0, 3, 0, 0, -1, 0, 10, 1),
            new Ray(0, 0, 2, 0, 0, 1, 0, 10, 255),
            new Ray(0, 0, 2, 0, 0, -1, 0, 1, 255),
            new Ray(0, -.5f, 3, 0, 0, -1, 2.5f, 10, 255),
            new Ray(0, 0, -2, 0, 0, 1, 0, 10, 255),
            new Ray(-.5f, .2f, 2, .25f, 0, -1, 0, 10, 255),
            new Ray(3, 0, 2, 0, 0, -1, 0, 10, 2),
            new Ray(0, 0, 2, 0, 0, -1, 0, 10, 0),
            new Ray(0, .8f, 2, .5f, 0, -1, 0, 10, 255)
    };

    private static void verify(VulkanDevice backend) throws Exception {
        VkDevice device = backend.vkDevice();
        int hostMemory = VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        try (NativeBuffer rays = new NativeBuffer(device, RAYS.length * 48L, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, hostMemory);
             NativeBuffer results = new NativeBuffer(device, RAYS.length * 16L, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, hostMemory);
             NativeBuffer diagnostics = new NativeBuffer(device, RAYS.length * 16L, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, hostMemory);
             MemoryStack stack = MemoryStack.stackPush();
             QueryPipeline pipeline = new QueryPipeline(device)) {
            ByteBuffer input = stack.malloc(RAYS.length * 48);
            ByteBuffer sentinel = stack.malloc(RAYS.length * 16);
            for (Ray ray : RAYS) {
                input.putFloat(ray.x).putFloat(ray.y).putFloat(ray.z).putFloat(ray.min);
                input.putFloat(ray.dx).putFloat(ray.dy).putFloat(ray.dz).putFloat(ray.max);
                input.putInt(ray.mask).putInt(0).putInt(0).putInt(0);
                for (int c = 0; c < 4; c++) sentinel.putFloat(Float.NaN);
            }
            rays.write(input.flip());
            results.write(sentinel.flip());
            diagnostics.write(sentinel);
            VulkanCommandEncoder encoder = backend.createCommandEncoder();
            VkCommandBuffer commands = encoder.allocateAndBeginTransientCommandBuffer();
            AccelerationStructure bottom = null;
            AccelerationStructure top = null;
            boolean executed = false;
            boolean completed = false;
            try {
                AccelerationStructureBuilder builder = new AccelerationStructureBuilder(device);
                AccelerationStructureBuilder.dependency(commands, VK10.VK_PIPELINE_STAGE_HOST_BIT,
                        VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK10.VK_ACCESS_HOST_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT);
                bottom = builder.triangles(commands, TRIANGLE);
                AccelerationStructureBuilder.dependency(commands, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                        VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
                top = builder.instances(commands, List.of(
                        new AccelerationStructureBuilder.Instance(bottom, 0, 0, 0, 11, 1),
                        new AccelerationStructureBuilder.Instance(bottom, 3, 0, 0, 22, 2)));
                AccelerationStructureBuilder.dependency(commands, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK10.VK_PIPELINE_STAGE_HOST_BIT,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR | VK10.VK_ACCESS_HOST_WRITE_BIT,
                        VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
                pipeline.record(commands, top.handle(), rays, results, diagnostics, RAYS.length, stack);
                AccelerationStructureBuilder.dependency(commands, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_HOST_BIT,
                        VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_HOST_READ_BIT);
                requireSuccess(VK10.vkEndCommandBuffer(commands), "vkEndCommandBuffer conformance");
                encoder.execute(commands);
                executed = true;
                encoder.submit();
                // Diagnostic readback only. Production rendering must use deferred retirement, never this global wait.
                requireSuccess(VK10.vkDeviceWaitIdle(device), "vkDeviceWaitIdle conformance");
                completed = true;
                float[] actual = results.readFloats(RAYS.length * 4);
                float[] decoded = diagnostics.readFloats(RAYS.length * 4);
                for (int i = 0; i < RAYS.length; i++) {
                    float[] expectedInput = {RAYS[i].z, RAYS[i].dz, RAYS[i].max, RAYS[i].mask};
                    for (int c = 0; c < 4; c++) {
                        if (decoded[i * 4 + c] != expectedInput[c]) {
                            throw new IllegalStateException("Ray input serialization mismatch: ray=" + i + " channel=" + c + " expected=" + expectedInput[c] + " actual=" + decoded[i * 4 + c]);
                        }
                    }
                }
                LogUtils.getLogger().info("SULKAN_RT_INPUT_SERIALIZATION_PASS rays={}", RAYS.length);
                LogUtils.getLogger().info("SULKAN_RT_CONFORMANCE_READBACK {}", java.util.Arrays.toString(actual));
                for (int i = 0; i < RAYS.length; i++) {
                    float[] expected = reference(RAYS[i]);
                    for (int c = 0; c < 4; c++) {
                        float value = actual[i * 4 + c];
                        if (!Float.isFinite(value) || Math.abs(value - expected[c]) > 0.0001f) {
                            throw new IllegalStateException("Ray=" + i + " channel=" + c + " expected=" + expected[c] + " actual=" + value);
                        }
                    }
                }
                LogUtils.getLogger().info("SULKAN_RT_CONFORMANCE_PASS rays={} instances=2 translation=true masks=true clipping=true readback=true", RAYS.length);
            } finally {
                if (executed && !completed) VK10.vkDeviceWaitIdle(device);
                if (top != null) top.close();
                if (bottom != null) bottom.close();
            }
        }
    }

    /** QA fixture only: trace indexed meshes copied from Sodium before and after removing its stone block. */
    public static void verifyTerrainScene(VulkanDevice backend, boolean removed) throws Exception {
        Ray[] raysToTest = {
                new Ray(4.5f, 100, 4.5f, 0, -1, 0, 0, 10, 255),
                new Ray(2, 96.5f, 4.5f, 1, 0, 0, 0, 5, 255),
                new Ray(25, 100, 25, 0, -1, 0, 0, 10, 255),
                new Ray(16, 110, 10, 0, -1, 0, 0, 20, 255)
        };
        float[] distances = removed ? new float[]{4, -1, -1, 14} : new float[]{3, 2, -1, 14};
        VkDevice device = backend.vkDevice();
        int hostMemory = VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        try (NativeBuffer rays = new NativeBuffer(device, raysToTest.length * 48L, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, hostMemory);
             NativeBuffer results = new NativeBuffer(device, raysToTest.length * 16L, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, hostMemory);
             NativeBuffer diagnostics = new NativeBuffer(device, raysToTest.length * 16L, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, hostMemory);
             MemoryStack stack = MemoryStack.stackPush(); QueryPipeline pipeline = new QueryPipeline(device)) {
            ByteBuffer input = stack.malloc(raysToTest.length * 48);
            ByteBuffer sentinel = stack.malloc(raysToTest.length * 16);
            for (Ray ray : raysToTest) {
                input.putFloat(ray.x).putFloat(ray.y).putFloat(ray.z).putFloat(ray.min);
                input.putFloat(ray.dx).putFloat(ray.dy).putFloat(ray.dz).putFloat(ray.max);
                input.putInt(ray.mask).putInt(0).putInt(0).putInt(0);
                for (int c = 0; c < 4; c++) sentinel.putFloat(Float.NaN);
            }
            rays.write(input.flip()); results.write(sentinel.flip()); diagnostics.write(sentinel);
            VulkanCommandEncoder encoder = backend.createCommandEncoder();
            if (terrainScene == null) terrainScene = new TerrainRayScene(device,Boolean.getBoolean("sulkan.qaRayAttributes"));
            List<TerrainGeometryCache.Change> changes = new ArrayList<>();
            var present = new java.util.HashSet<SectionKey>();
            for (var section : TerrainGeometryCache.snapshot()) {
                if (section.x() < 0 || section.x() > 16 || section.z() < 0 || section.z() > 16 || section.y() < 80 || section.y() > 96) continue;
                SectionKey key = new SectionKey(section.x(), section.y(), section.z());
                present.add(key);
                Object owner = terrainOwners.computeIfAbsent(key, ignored -> new Object());
                if (!java.util.Objects.equals(terrainRevisions.get(key), section.revision())) {
                    var meshes = section.meshes().stream().filter(mesh -> mesh.opacity() == TerrainMesh.Opacity.OPAQUE).toList();
                    changes.add(new TerrainGeometryCache.Change(TerrainGeometryCache.Kind.REPLACE, owner,
                            new TerrainGeometryCache.Section(section.revision(), section.x(), section.y(), section.z(), meshes)));
                    terrainRevisions.put(key, section.revision());
                }
            }
            for (var key : List.copyOf(terrainOwners.keySet())) if (!present.contains(key)) {
                changes.add(new TerrainGeometryCache.Change(TerrainGeometryCache.Kind.REMOVE, terrainOwners.remove(key), null));
                terrainRevisions.remove(key);
            }
            terrainScene.enqueue(changes);
            TerrainRayScene.View sceneView = terrainScene.synchronize(encoder, 1);
            int batches = 1;
            while (terrainScene.statistics().pendingSections() > 0) {
                if (++batches > 128) throw new IllegalStateException("Terrain scene budget did not drain");
                sceneView = terrainScene.synchronize(encoder, 1);
            }
            if (sceneView.handle() == 0) throw new IllegalStateException("No captured fixture mesh for world ray verification");
            long builds = terrainScene.statistics().recordedBuilds();
            if (terrainScene.synchronize(encoder, 1) != sceneView || terrainScene.statistics().recordedBuilds() != builds) {
                throw new IllegalStateException("Unchanged scene was rebuilt");
            }
            VkCommandBuffer commands = encoder.allocateAndBeginTransientCommandBuffer();
            boolean executed = false;
            boolean completed = false;
            try {
                AccelerationStructureBuilder.dependency(commands, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK10.VK_PIPELINE_STAGE_HOST_BIT,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR | VK10.VK_ACCESS_HOST_WRITE_BIT,
                        VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
                pipeline.record(commands, sceneView.handle(), rays, results, diagnostics, raysToTest.length, stack);
                AccelerationStructureBuilder.dependency(commands, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_HOST_BIT,
                        VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_HOST_READ_BIT);
                requireSuccess(VK10.vkEndCommandBuffer(commands), "world query command buffer");
                encoder.execute(commands); executed = true; encoder.submit();
                requireSuccess(VK10.vkDeviceWaitIdle(device), "world conformance readback"); completed = true;
                float[] actual = results.readFloats(raysToTest.length * 4);
                for (int i = 0; i < raysToTest.length; i++) {
                    boolean expectedHit = distances[i] >= 0;
                    if (!Float.isFinite(actual[i * 4]) || actual[i * 4] != (expectedHit ? 1 : 0)
                            || expectedHit && (!Float.isFinite(actual[i * 4 + 1]) || Math.abs(actual[i * 4 + 1] - distances[i]) > .0001f)) {
                        throw new IllegalStateException("World ray=" + i + " removed=" + removed + " expectedDistance=" + distances[i] + " actual=" + java.util.Arrays.toString(actual));
                    }
                    if (expectedHit) {
                        float instanceValue = actual[i * 4 + 3];
                        float primitiveValue = actual[i * 4 + 2];
                        var geometry = sceneView.geometry().get((int) instanceValue);
                        if (!Float.isFinite(instanceValue) || instanceValue != (int) instanceValue
                                || !Float.isFinite(primitiveValue) || primitiveValue != (int) primitiveValue
                                || geometry == null || primitiveValue < 0 || primitiveValue >= geometry.mesh().triangleCount()) {
                            throw new IllegalStateException("World ray returned an invalid instance or primitive");
                        }
                    }
                }
                LogUtils.getLogger().info("SULKAN_QA_WORLD_RAYS_PASS removed={} meshes={} rays={} distances={}", removed, sceneView.geometry().size(), raysToTest.length, actual);
                LogUtils.getLogger().info("SULKAN_QA_INCREMENTAL_SCENE_PASS removed={} batches={} changes={} statistics={}", removed, batches, changes.size(), terrainScene.statistics());
                if (Boolean.getBoolean("sulkan.qaRayAttributes")) {
                    if (sceneView.attributeData()==null || terrainScene.statistics().attributeBytes()==0) throw new IllegalStateException("World attribute arena was not populated");
                    for (var geometry : sceneView.geometry().values()) {
                        if (geometry.attributes()==null || geometry.attributes().headerWord()!=geometry.id()) throw new IllegalStateException("Ray instance attribute address differs");
                    }
                    LogUtils.getLogger().info("SULKAN_QA_RAY_ATTRIBUTE_ARENA_PASS removed={} usedBytes={} capacity={} sharedGeometryInput=true",removed,
                            terrainScene.statistics().attributeBytes(),terrainScene.statistics().attributeCapacity());
                }
                if (removed) {
                    terrainScene.enqueue(List.of(new TerrainGeometryCache.Change(TerrainGeometryCache.Kind.CLEAR, null, null)));
                    if (terrainScene.synchronize(encoder, 1).handle() != 0 || terrainScene.statistics().meshes() != 0) {
                        throw new IllegalStateException("Cleared terrain scene retains geometry");
                    }
                    terrainScene.retire(encoder);
                    terrainScene = null;
                    encoder.submit();
                    LogUtils.getLogger().info("SULKAN_QA_SCENE_RETIRE_RECORDED");
                }
            } finally {
                if (executed && !completed) VK10.vkDeviceWaitIdle(device);
            }
        }
    }

    /** Analytic ray/plane intersection and triangle bounds; independent of Vulkan traversal. */
    private static float[] reference(Ray ray) {
        if (ray.dz == 0) return new float[]{0, 0, -1, -1};
        float t = -ray.z / ray.dz;
        if (t < ray.min || t > ray.max) return new float[]{0, 0, -1, -1};
        float x = ray.x + t * ray.dx;
        float y = ray.y + t * ray.dy;
        for (int i = 0; i < 2; i++) {
            if ((ray.mask & (1 << i)) != 0 && y >= -1 && y <= 1 && Math.abs(x - i * 3) <= (1 - y) * .5f) {
                return new float[]{1, t, 0, i == 0 ? 11 : 22};
            }
        }
        return new float[]{0, 0, -1, -1};
    }

    static final class QueryPipeline implements AutoCloseable {
        private final VkDevice device;
        private final int extraBuffers;
        private long descriptors;
        private long layout;
        private long pipeline;
        private long pool;
        private long set;

        QueryPipeline(VkDevice device) throws Exception {
            this(device, SHADER, 0);
        }

        QueryPipeline(VkDevice device, String shader, int extraBuffers) throws Exception {
            if (extraBuffers < 0) throw new IllegalArgumentException("Negative extra binding count");
            this.device = device;
            this.extraBuffers = extraBuffers;
            try (MemoryStack stack = MemoryStack.stackPush(); NativeShaderCompiler compiler = new NativeShaderCompiler();
                 NativeShaderCompiler.ComputeModule code = compiler.compileCompute("sulkan/rt-conformance.csh", shader)) {
                VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(4 + extraBuffers, stack);
                for (int i = 0; i < 4 + extraBuffers; i++) bindings.get(i).binding(i).descriptorCount(1)
                        .descriptorType(i == 0 ? VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR : VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
                var result = stack.mallocLong(1);
                requireSuccess(VK10.vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, result), "query descriptor layout");
                descriptors = result.get(0);
                requireSuccess(VK10.vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(descriptors)), null, result), "query pipeline layout");
                layout = result.get(0);
                requireSuccess(VK10.vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code.code()), null, result), "query shader module");
                long module = result.get(0);
                try {
                    VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(layout);
                    info.get(0).stage().sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
                    requireSuccess(VK10.vkCreateComputePipelines(device, 0, info, null, result), "query compute pipeline");
                    pipeline = result.get(0);
                } finally { VK10.vkDestroyShaderModule(device, module, null); }
                VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
                sizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
                sizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(3 + extraBuffers);
                requireSuccess(VK10.vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, result), "query descriptor pool");
                pool = result.get(0);
                requireSuccess(VK10.vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(descriptors)), result), "query descriptor set");
                set = result.get(0);
            } catch (Throwable failure) { close(); throw failure; }
        }

        void record(VkCommandBuffer commands, long scene, NativeBuffer rays, NativeBuffer output, NativeBuffer diagnostics, int rayCount, MemoryStack stack) {
            record(commands, scene, rays, output, diagnostics, rayCount, stack, new NativeBuffer[0]);
        }

        void record(VkCommandBuffer commands, long scene, NativeBuffer rays, NativeBuffer output, NativeBuffer diagnostics, int rayCount, MemoryStack stack, NativeBuffer... extra) {
            if (extra.length != extraBuffers) throw new IllegalArgumentException("Diagnostic descriptor count differs");
            VkWriteDescriptorSetAccelerationStructureKHR acceleration = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                    .sType$Default().pAccelerationStructures(stack.longs(scene));
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4 + extraBuffers, stack);
            writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).pNext(acceleration.address());
            NativeBuffer[] buffers = new NativeBuffer[3 + extraBuffers];
            buffers[0] = rays; buffers[1] = output; buffers[2] = diagnostics;
            System.arraycopy(extra, 0, buffers, 3, extra.length);
            for (int i = 0; i < buffers.length; i++) writes.get(i + 1).sType$Default().dstSet(set).dstBinding(i + 1).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack)
                            .buffer(buffers[i].handle()).offset(0).range(buffers[i].bytes()));
            VK10.vkUpdateDescriptorSets(device, writes, null);
            VK10.vkCmdBindPipeline(commands, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(commands, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, stack.longs(set), null);
            VK10.vkCmdDispatch(commands, (rayCount + 7) / 8, 1, 1);
        }

        @Override public void close() {
            if (pool != 0) { VK10.vkDestroyDescriptorPool(device, pool, null); pool = 0; }
            if (pipeline != 0) { VK10.vkDestroyPipeline(device, pipeline, null); pipeline = 0; }
            if (layout != 0) { VK10.vkDestroyPipelineLayout(device, layout, null); layout = 0; }
            if (descriptors != 0) { VK10.vkDestroyDescriptorSetLayout(device, descriptors, null); descriptors = 0; }
        }
    }
}
