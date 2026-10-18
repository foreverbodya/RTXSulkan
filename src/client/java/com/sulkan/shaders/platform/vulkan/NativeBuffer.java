package com.sulkan.shaders.platform.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import static com.sulkan.shaders.platform.vulkan.VulkanChecks.requireSuccess;

/** Owns a dedicated Vulkan allocation. Close only before submission or after GPU completion. */
public final class NativeBuffer implements AutoCloseable {
    /**
     * Dedicated allocations made by this class only. These are ownership counters,
     * not resident VRAM: RenderPearl textures/buffers, Sodium, NGX and the driver
     * allocate elsewhere. Device-local and host-visible byte counts can overlap.
     * Cumulative counters permit subtraction between phase-boundary snapshots;
     * peaks cover this JVM lifetime and must not be subtracted as phase peaks.
     */
    public record Statistics(long liveBuffers, long liveLogicalBytes, long peakLogicalBytes,
                             long liveAllocations, long liveAllocationBytes, long peakAllocationBytes,
                             long liveDeviceLocalAllocationBytes, long peakDeviceLocalAllocationBytes,
                             long liveHostVisibleAllocationBytes, long peakHostVisibleAllocationBytes,
                             long allocations, long allocatedBytes, long frees, long freedBytes,
                             long allocationFailures, long failedAllocationBytes,
                             long uploadCalls, long uploadBytes, long readbackCalls, long readbackBytes,
                             long successfulMaps) {}

    private static final AtomicLong LIVE_BUFFERS = new AtomicLong();
    private static final AtomicLong LIVE_LOGICAL_BYTES = new AtomicLong(), PEAK_LOGICAL_BYTES = new AtomicLong();
    private static final AtomicLong LIVE_ALLOCATIONS = new AtomicLong(), LIVE_ALLOCATION_BYTES = new AtomicLong();
    private static final AtomicLong PEAK_ALLOCATION_BYTES = new AtomicLong();
    private static final AtomicLong LIVE_DEVICE_LOCAL_BYTES = new AtomicLong(), PEAK_DEVICE_LOCAL_BYTES = new AtomicLong();
    private static final AtomicLong LIVE_HOST_VISIBLE_BYTES = new AtomicLong(), PEAK_HOST_VISIBLE_BYTES = new AtomicLong();
    private static final AtomicLong ALLOCATIONS = new AtomicLong(), ALLOCATED_BYTES = new AtomicLong();
    private static final AtomicLong FREES = new AtomicLong(), FREED_BYTES = new AtomicLong();
    private static final AtomicLong ALLOCATION_FAILURES = new AtomicLong(), FAILED_ALLOCATION_BYTES = new AtomicLong();
    private static final AtomicLong UPLOAD_CALLS = new AtomicLong(), UPLOAD_BYTES = new AtomicLong();
    private static final AtomicLong READBACK_CALLS = new AtomicLong(), READBACK_BYTES = new AtomicLong();
    private static final AtomicLong SUCCESSFUL_MAPS = new AtomicLong();
    private final VkDevice device;
    private final long bytes;
    private final int memoryFlags;
    private long buffer;
    private long memory;
    private long address;
    private long allocationBytes;
    private int allocatedMemoryFlags;
    private boolean tracked;
    private boolean allocationTracked;

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
            int allocationResult = VK10.vkAllocateMemory(device, allocation, null, handle);
            if (allocationResult != VK10.VK_SUCCESS) {
                ALLOCATION_FAILURES.incrementAndGet();
                FAILED_ALLOCATION_BYTES.addAndGet(requirements.size());
                throw new IllegalStateException("vkAllocateMemory failed with VkResult=" + allocationResult
                        + " logicalBytes=" + bytes + " allocationBytes=" + requirements.size()
                        + " memoryType=" + type + " heap=" + properties.memoryTypes(type).heapIndex()
                        + " memoryFlags=" + properties.memoryTypes(type).propertyFlags()
                        + " usage=" + usage);
            }
            memory = handle.get(0);
            allocationBytes = requirements.size();
            allocatedMemoryFlags = properties.memoryTypes(type).propertyFlags();
            allocationTracked = true;
            ALLOCATIONS.incrementAndGet();
            ALLOCATED_BYTES.addAndGet(allocationBytes);
            LIVE_ALLOCATIONS.incrementAndGet();
            addWithPeak(LIVE_ALLOCATION_BYTES, PEAK_ALLOCATION_BYTES, allocationBytes);
            if ((allocatedMemoryFlags & VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0)
                addWithPeak(LIVE_DEVICE_LOCAL_BYTES, PEAK_DEVICE_LOCAL_BYTES, allocationBytes);
            if ((allocatedMemoryFlags & VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0)
                addWithPeak(LIVE_HOST_VISIBLE_BYTES, PEAK_HOST_VISIBLE_BYTES, allocationBytes);
            requireSuccess(VK10.vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory");
            if (addressed) {
                address = VK12.vkGetBufferDeviceAddress(device,
                        VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer));
                if (address == 0) throw new IllegalStateException("Buffer device address is zero");
            }
            LIVE_BUFFERS.incrementAndGet();
            addWithPeak(LIVE_LOGICAL_BYTES, PEAK_LOGICAL_BYTES, bytes);
            tracked = true;
        } catch (Throwable failure) {
            close();
            throw failure;
        }
    }

    public long handle() { ensureOpen(); return buffer; }
    public long bytes() { return bytes; }
    public static long liveBufferCount() { return LIVE_BUFFERS.get(); }
    public static Statistics statistics() {
        return new Statistics(LIVE_BUFFERS.get(), LIVE_LOGICAL_BYTES.get(), PEAK_LOGICAL_BYTES.get(),
                LIVE_ALLOCATIONS.get(), LIVE_ALLOCATION_BYTES.get(), PEAK_ALLOCATION_BYTES.get(),
                LIVE_DEVICE_LOCAL_BYTES.get(), PEAK_DEVICE_LOCAL_BYTES.get(),
                LIVE_HOST_VISIBLE_BYTES.get(), PEAK_HOST_VISIBLE_BYTES.get(),
                ALLOCATIONS.get(), ALLOCATED_BYTES.get(), FREES.get(), FREED_BYTES.get(),
                ALLOCATION_FAILURES.get(), FAILED_ALLOCATION_BYTES.get(),
                UPLOAD_CALLS.get(), UPLOAD_BYTES.get(), READBACK_CALLS.get(), READBACK_BYTES.get(),
                SUCCESSFUL_MAPS.get());
    }

    private static void addWithPeak(AtomicLong live, AtomicLong peak, long bytes) {
        long value = live.addAndGet(bytes);
        peak.accumulateAndGet(value, Math::max);
    }
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
            SUCCESSFUL_MAPS.incrementAndGet();
            try {
                MemoryUtil.memByteBuffer(mapped.get(0), source.remaining()).put(source.duplicate());
                UPLOAD_CALLS.incrementAndGet();
                UPLOAD_BYTES.addAndGet(source.remaining());
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
            SUCCESSFUL_MAPS.incrementAndGet();
            try {
                ByteBuffer snapshot = ByteBuffer.allocate(count).order(ByteOrder.nativeOrder());
                snapshot.put(MemoryUtil.memByteBuffer(mapped.get(0), count)).flip();
                READBACK_CALLS.incrementAndGet();
                READBACK_BYTES.addAndGet(count);
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
            SUCCESSFUL_MAPS.incrementAndGet();
            try {
                ByteBuffer view = MemoryUtil.memByteBuffer(mapped.get(0), count * Float.BYTES).order(ByteOrder.nativeOrder());
                float[] result = new float[count];
                view.asFloatBuffer().get(result);
                READBACK_CALLS.incrementAndGet();
                READBACK_BYTES.addAndGet((long) count * Float.BYTES);
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
        if (allocationTracked) {
            allocationTracked = false;
            LIVE_ALLOCATIONS.decrementAndGet();
            LIVE_ALLOCATION_BYTES.addAndGet(-allocationBytes);
            if ((allocatedMemoryFlags & VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0)
                LIVE_DEVICE_LOCAL_BYTES.addAndGet(-allocationBytes);
            if ((allocatedMemoryFlags & VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0)
                LIVE_HOST_VISIBLE_BYTES.addAndGet(-allocationBytes);
            FREES.incrementAndGet();
            FREED_BYTES.addAndGet(allocationBytes);
        }
        address = 0;
        if (tracked) {
            tracked = false;
            LIVE_BUFFERS.decrementAndGet();
            LIVE_LOGICAL_BYTES.addAndGet(-bytes);
        }
    }
}
