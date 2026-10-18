package com.sulkan.shaders.render.raytracing;

import com.sulkan.shaders.platform.vulkan.NativeBuffer;
import java.nio.ByteBuffer;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import static com.sulkan.shaders.platform.vulkan.VulkanChecks.requireSuccess;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

/** Records AS builds into an existing Vulkan command buffer; it does not submit or wait. */
public final class AccelerationStructureBuilder {
    private final VkDevice device;
    private final long scratchAlignment;
    private static final int INPUT_USAGE = VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
            | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
    private static final int HOST_MEMORY = VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;

    public AccelerationStructureBuilder(VkDevice device) {
        this.device = device;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceAccelerationStructurePropertiesKHR acceleration = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(acceleration.address());
            VK11.vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), properties);
            scratchAlignment = acceleration.minAccelerationStructureScratchOffsetAlignment();
            if (scratchAlignment <= 0 || (scratchAlignment & (scratchAlignment - 1)) != 0) {
                throw new IllegalStateException("Invalid acceleration structure scratch alignment: " + scratchAlignment);
            }
        }
    }

    /** Positions are consecutive non-indexed triangles, three float32 coordinates per vertex. */
    public AccelerationStructure triangles(VkCommandBuffer commands, float[] positions) {
        if (positions.length == 0 || positions.length % 9 != 0) throw new IllegalArgumentException("Expected complete nonempty triangles");
        for (float value : positions) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite vertex coordinate");
        NativeBuffer vertices = new NativeBuffer(device, (long) positions.length * Float.BYTES, INPUT_USAGE, HOST_MEMORY);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = MemoryUtil.memAlloc(Math.multiplyExact(positions.length, Float.BYTES));
            try {
                data.asFloatBuffer().put(positions);
                vertices.write(data);
                if (Boolean.getBoolean("sulkan.rtConformance") && !java.util.Arrays.equals(vertices.readFloats(positions.length), positions)) {
                    throw new IllegalStateException("Vertex serialization mismatch");
                }
            } finally { MemoryUtil.memFree(data); }
            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
            geometry.get(0).geometry().triangles().sType$Default().vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT)
                    .vertexStride(3L * Float.BYTES).maxVertex(positions.length / 3 - 1).indexType(VK_INDEX_TYPE_NONE_KHR);
            geometry.get(0).geometry().triangles().vertexData().deviceAddress(vertices.deviceAddress());
            return build(commands, geometry, positions.length / 9, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, List.of(vertices), stack);
        } catch (Throwable failure) {
            vertices.close();
            throw failure;
        }
    }

    /** Builds the actual captured, indexed quad mesh; cutout geometry remains non-opaque. */
    public AccelerationStructure mesh(VkCommandBuffer commands, TerrainMesh mesh) {
        var decoded = mesh.readView();
        NativeBuffer vertices = new NativeBuffer(device, (long) mesh.vertexCount() * 3 * Float.BYTES, INPUT_USAGE, HOST_MEMORY);
        NativeBuffer indices = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            indices = new NativeBuffer(device, (long) mesh.triangleCount() * 3 * Integer.BYTES, INPUT_USAGE, HOST_MEMORY);
            ByteBuffer positionData = MemoryUtil.memAlloc(Math.multiplyExact(mesh.vertexCount(), 3 * Float.BYTES));
            try { positionData.asFloatBuffer().put(decoded.positions()); vertices.write(positionData); }
            finally { MemoryUtil.memFree(positionData); }
            ByteBuffer indexData = MemoryUtil.memAlloc(Math.multiplyExact(mesh.triangleCount(), 3 * Integer.BYTES));
            try { indexData.asIntBuffer().put(decoded.indices()); indices.write(indexData); }
            finally { MemoryUtil.memFree(indexData); }
            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                    .flags(mesh.opacity() == TerrainMesh.Opacity.OPAQUE ? VK_GEOMETRY_OPAQUE_BIT_KHR : 0);
            geometry.get(0).geometry().triangles().sType$Default().vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT)
                    .vertexStride(3L * Float.BYTES).maxVertex(mesh.vertexCount() - 1).indexType(VK10.VK_INDEX_TYPE_UINT32);
            geometry.get(0).geometry().triangles().vertexData().deviceAddress(vertices.deviceAddress());
            geometry.get(0).geometry().triangles().indexData().deviceAddress(indices.deviceAddress());
            return build(commands, geometry, mesh.triangleCount(), VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, List.of(vertices, indices), stack);
        } catch (Throwable failure) {
            if (indices != null) indices.close();
            vertices.close();
            throw failure;
        }
    }

    /** Compact ray payloads retain packed positions; float BLAS inputs retire after build.
     * Legacy payloads can serve as persistent BLAS inputs directly.
     */
    public AccelerationStructure mesh(VkCommandBuffer commands,TerrainMesh mesh,RayAttributeArena arena,RayAttributeRanges.Allocation allocation) {
        if (arena.compactVertices()) return mesh(commands,mesh);
        try (MemoryStack stack=MemoryStack.stackPush()) {
            long vertexAddress=arena.meshAddress(allocation)+RaySurfaceAttributes.HEADER_BYTES;
            var geometry=VkAccelerationStructureGeometryKHR.calloc(1,stack);
            geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                    .flags(mesh.opacity()==TerrainMesh.Opacity.OPAQUE ? VK_GEOMETRY_OPAQUE_BIT_KHR : 0);
            geometry.get(0).geometry().triangles().sType$Default().vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT)
                    .vertexStride(RaySurfaceAttributes.VERTEX_BYTES).maxVertex(mesh.vertexCount()-1).indexType(VK10.VK_INDEX_TYPE_UINT32);
            geometry.get(0).geometry().triangles().vertexData().deviceAddress(vertexAddress);
            geometry.get(0).geometry().triangles().indexData().deviceAddress(vertexAddress+(long)mesh.vertexCount()*RaySurfaceAttributes.VERTEX_BYTES);
            return build(commands,geometry,mesh.triangleCount(),VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,List.of(),stack);
        }
    }

    public record Instance(AccelerationStructure geometry, float x, float y, float z, int id, int mask) {
        public Instance {
            if (geometry == null || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) throw new IllegalArgumentException("Invalid instance");
            if (id < 0 || id > 0xffffff || mask < 0 || mask > 0xff) throw new IllegalArgumentException("Invalid packed instance field");
        }
    }

    /** Translation-only instances for the initial world/chunk geometry contract. */
    public AccelerationStructure instances(VkCommandBuffer commands, List<Instance> scene) {
        if (scene.isEmpty()) throw new IllegalArgumentException("Expected at least one scene instance");
        NativeBuffer input = new NativeBuffer(device, (long) scene.size() * VkAccelerationStructureInstanceKHR.SIZEOF, INPUT_USAGE, HOST_MEMORY);
        try (VkAccelerationStructureInstanceKHR.Buffer instances = VkAccelerationStructureInstanceKHR.calloc(scene.size());
             MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < scene.size(); i++) {
                Instance source = scene.get(i);
                VkAccelerationStructureInstanceKHR instance = instances.get(i);
                instance.transform().matrix(0, 1).matrix(5, 1).matrix(10, 1)
                        .matrix(3, source.x()).matrix(7, source.y()).matrix(11, source.z());
                instance.instanceCustomIndex(source.id()).mask(source.mask())
                        .instanceShaderBindingTableRecordOffset(0).flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                        .accelerationStructureReference(source.geometry().deviceAddress());
            }
            input.write(MemoryUtil.memByteBuffer(instances.address(), scene.size() * VkAccelerationStructureInstanceKHR.SIZEOF));
            if (Boolean.getBoolean("sulkan.rtConformance")) {
                com.mojang.logging.LogUtils.getLogger().info("SULKAN_RT_INSTANCES count={} firstMask={} firstTransform={}", scene.size(), instances.get(0).mask(), input.readFloats(12));
            }
            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
            geometry.get(0).geometry().instances().sType$Default().arrayOfPointers(false).data().deviceAddress(input.deviceAddress());
            return build(commands, geometry, scene.size(), VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, List.of(input), stack);
        } catch (Throwable failure) {
            input.close();
            throw failure;
        }
    }

    private AccelerationStructure build(VkCommandBuffer commands, VkAccelerationStructureGeometryKHR.Buffer geometry,
                                        int primitives, int type, List<NativeBuffer> inputs, MemoryStack stack) {
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer builds = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        VkAccelerationStructureBuildGeometryInfoKHR build = builds.get(0).sType$Default().type(type)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(geometry.remaining()).pGeometries(geometry);
        VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
        vkGetAccelerationStructureBuildSizesKHR(device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, build, stack.ints(primitives), sizes);
        NativeBuffer storage = null;
        NativeBuffer scratch = null;
        long handle = 0;
        try {
            storage = new NativeBuffer(device, sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                    | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            scratch = new NativeBuffer(device, Math.addExact(sizes.buildScratchSize(), scratchAlignment - 1),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            var result = stack.mallocLong(1);
            requireSuccess(vkCreateAccelerationStructureKHR(device, VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                    .buffer(storage.handle()).size(sizes.accelerationStructureSize()).type(type), null, result), "vkCreateAccelerationStructureKHR");
            handle = result.get(0);
            long address = vkGetAccelerationStructureDeviceAddressKHR(device,
                    VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(handle));
            if (address == 0) throw new IllegalStateException("Acceleration structure address is zero");
            build.dstAccelerationStructure(handle).scratchData().deviceAddress((scratch.deviceAddress() + scratchAlignment - 1) & -scratchAlignment);
            VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack).primitiveCount(primitives);
            if (Boolean.getBoolean("sulkan.rtConformance")) {
                com.mojang.logging.LogUtils.getLogger().info("SULKAN_RT_BUILD type={} geometries={} primitives={} storageBytes={} scratchBytes={}", type, build.geometryCount(), range.get(0).primitiveCount(), sizes.accelerationStructureSize(), sizes.buildScratchSize());
            }
            vkCmdBuildAccelerationStructuresKHR(commands, builds, stack.pointers(range.address()));
            var owned = new java.util.ArrayList<>(inputs);
            owned.add(scratch);
            return new AccelerationStructure(device, handle, address, storage, owned);
        } catch (Throwable failure) {
            if (handle != 0) vkDestroyAccelerationStructureKHR(device, handle, null);
            if (scratch != null) scratch.close();
            if (storage != null) storage.close();
            throw failure;
        }
    }

    public static void dependency(VkCommandBuffer commands, int sourceStage, int destinationStage, int sourceAccess, int destinationAccess) {
        com.sulkan.shaders.graph.ProfileCounters.asBarrier();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barriers = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(sourceAccess).dstAccessMask(destinationAccess);
            VK10.vkCmdPipelineBarrier(commands, sourceStage, destinationStage, 0, barriers, null, null);
        }
    }
}
