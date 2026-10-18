package com.sulkan.shaders.platform.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import static com.sulkan.shaders.platform.vulkan.VulkanChecks.requireSuccess;

/** Owns a dedicated Vulkan allocation. Close only before submission or after GPU completion. */
public final class NativeBuffer implements AutoCloseable {
    private static final java.util.concurrent.atomic.AtomicLong LIVE_BUFFERS = new java.util.concurrent.atomic.AtomicLong();
    private final VkDevice device;
    private final long bytes;
    private final int memoryFlags;
    private long buffer;
    private long memory;
    private long address;
    private boolean tracked;

    public NativeBuffer(VkDevice device, long bytes, int usage, int requiredMemoryFlags) {
        if (bytes <= 0) throw new IllegalArgumentException("Buffer size must be positive");
        this.device = device;
        this.bytes = bytes;
        this.memoryFlags = requiredMemoryFlags;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handle = stack.mallocLong(1);
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(bytes).usage(usage).sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            requireSuccess(VK10.vkCreateBuffer(device, info, null, handle), "vkCreateBuffer");
            buffer = handle.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            VK10.vkGetBufferMemoryRequirements(device, buffer, requirements);
            VkPhysicalDeviceMemoryProperties properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), properties);
            int type = -1;
            for (int i = 0; i < properties.memoryTypeCount(); i++) {
                if ((requirements.memoryTypeBits() & (1 << i)) != 0
                        && (properties.memoryTypes(i).propertyFlags() & requiredMemoryFlags) == requiredMemoryFlags) {
                    type = i;
                    break;
                }
            }
            if (type < 0) throw new IllegalStateException("No memory type satisfies flags=" + requiredMemoryFlags);
            VkMemoryAllocateInfo allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(requirements.size()).memoryTypeIndex(type);
            boolean addressed = (usage & VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0;
            if (addressed) {
                allocation.pNext(VkMemoryAllocateFlagsInfo.calloc(stack).sType$Default()
                        .flags(VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT).address());
            }
            requireSuccess(VK10.vkAllocateMemory(device, allocation, null, handle), "vkAllocateMemory");
            memory = handle.get(0);
            requireSuccess(VK10.vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory");
            if (addressed) {
                address = VK12.vkGetBufferDeviceAddress(device,
                        VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer));
                if (address == 0) throw new IllegalStateException("Buffer device address is zero");
            }
            LIVE_BUFFERS.incrementAndGet();
            tracked = true;
        } catch (Throwable failure) {
            close();
            throw failure;
        }
    }

    public long handle() { ensureOpen(); return buffer; }
    public long bytes() { return bytes; }
    public static long liveBufferCount() { return LIVE_BUFFERS.get(); }
    public long deviceAddress() {
        ensureOpen();
        if (address == 0) throw new IllegalStateException("Buffer was not allocated for device addressing");
        return address;
    }

    public void write(ByteBuffer source) {
        ensureHostCoherent();
        if (source.remaining() > bytes) throw new IllegalArgumentException("Buffer upload exceeds allocation");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer mapped = stack.mallocPointer(1);
            requireSuccess(VK10.vkMapMemory(device, memory, 0, bytes, 0, mapped), "vkMapMemory upload");
            try {
                MemoryUtil.memByteBuffer(mapped.get(0), source.remaining()).put(source.duplicate());
            } finally { VK10.vkUnmapMemory(device, memory); }
        }
    }

    /** Requires an explicit GPU completion dependency before calling. */
    public ByteBuffer readBytes(int count) {
        ensureHostCoherent();
        if (count < 0 || count > bytes) throw new IllegalArgumentException("Invalid readback size");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer mapped = stack.mallocPointer(1);
            requireSuccess(VK10.vkMapMemory(device, memory, 0, bytes, 0, mapped), "vkMapMemory readback");
            try {
                ByteBuffer snapshot = ByteBuffer.allocate(count).order(ByteOrder.nativeOrder());
                snapshot.put(MemoryUtil.memByteBuffer(mapped.get(0), count)).flip();
                return snapshot;
            } finally { VK10.vkUnmapMemory(device, memory); }
        }
    }

    /** Requires an explicit GPU completion dependency before calling. */
    public float[] readFloats(int count) {
        ensureHostCoherent();
        if (count < 0 || (long) count * Float.BYTES > bytes) throw new IllegalArgumentException("Invalid readback size");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer mapped = stack.mallocPointer(1);
            requireSuccess(VK10.vkMapMemory(device, memory, 0, bytes, 0, mapped), "vkMapMemory readback");
            try {
                ByteBuffer view = MemoryUtil.memByteBuffer(mapped.get(0), count * Float.BYTES).order(ByteOrder.nativeOrder());
                float[] result = new float[count];
                view.asFloatBuffer().get(result);
                return result;
            } finally { VK10.vkUnmapMemory(device, memory); }
        }
    }

    private void ensureHostCoherent() {
        ensureOpen();
        int needed = VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        if ((memoryFlags & needed) != needed) throw new IllegalStateException("Mapping requires host-coherent allocation");
    }

    private void ensureOpen() {
        if (buffer == 0) throw new IllegalStateException("Buffer is closed");
    }

    @Override public void close() {
        if (buffer != 0) { VK10.vkDestroyBuffer(device, buffer, null); buffer = 0; }
        if (memory != 0) { VK10.vkFreeMemory(device, memory, null); memory = 0; }
        address = 0;
        if (tracked) {
            tracked = false;
            LIVE_BUFFERS.decrementAndGet();
        }
    }
}
