package com.sulkan.shaders.render.raytracing;

import com.sulkan.shaders.platform.vulkan.NativeBuffer;
import java.util.List;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VkDevice;

/** Owns storage and build inputs. A TLAS also needs all referenced BLAS objects to remain alive. */
public final class AccelerationStructure implements AutoCloseable {
    private final VkDevice device;
    private final NativeBuffer storage;
    private final List<NativeBuffer> buildBuffers;
    private final long deviceAddress;
    private long handle;
    private boolean buildInputsReleased;

    AccelerationStructure(VkDevice device, long handle, long deviceAddress,
                          NativeBuffer storage, List<NativeBuffer> buildBuffers) {
        this.device = device;
        this.handle = handle;
        this.deviceAddress = deviceAddress;
        this.storage = storage;
        this.buildBuffers = List.copyOf(buildBuffers);
    }

    public long handle() {
        if (handle == 0) throw new IllegalStateException("Acceleration structure is closed");
        return handle;
    }

    public long deviceAddress() { handle(); return deviceAddress; }

    public long storageBytes() { return storage.bytes(); }

    /** Called by the encoder's deferred destruction queue after the build completes. */
    public void releaseBuildInputs() {
        if (!buildInputsReleased) {
            buildInputsReleased = true;
            for (NativeBuffer input : buildBuffers) input.close();
        }
    }

    /** Caller must guarantee GPU completion and destruction of referencing TLAS objects. */
    @Override public void close() {
        if (handle != 0) {
            KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(device, handle, null);
            handle = 0;
            storage.close();
            releaseBuildInputs();
        }
    }
}
