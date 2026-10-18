package com.sulkan.shaders.platform.vulkan.dlss;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.sulkan.shaders.platform.vulkan.DlssFeatures;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Records NGX work on the caller's encoder; retirement is always deferred until GPU completion. */
public final class DlssSession {
    private final Thread owner = Thread.currentThread();
    private long handle;
    private boolean created;
    private int inputWidth, inputHeight, outputWidth, outputHeight;

    private static native long openNative(long instance, long physical, long device, long gipa, long gdpa, String data, String runtime);
    private static native void rayReconstructionNative(long context);
    private static native void evaluateRrNative(long context,long commands,long[] resources,float[] camera,float jitterX,float jitterY,boolean reset,float milliseconds);
    private static native int[] qualityNative(long context, int width, int height);
    private static native void createNative(long context, long commands, int iw, int ih, int ow, int oh);
    private static native void evaluateNative(long context, long commands, long[] resources, float jitterX, float jitterY, boolean reset);
    private static native String closeNative(long context);

    public DlssSession(VulkanDevice device, Path dataDirectory, Path runtimeDirectory) {
        if (!DlssFeatures.enabledFor(device.vkDevice())) throw new IllegalStateException("DLSS device features were not negotiated");
        var physical = device.vkDevice().getPhysicalDevice();
        handle = openNative(physical.getInstance().address(), physical.address(), device.vkDevice().address(),
                VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"), VK.getFunctionProvider().getFunctionAddress("vkGetDeviceProcAddr"),
                dataDirectory.toString(), runtimeDirectory.toString());
        if (handle == 0) throw new IllegalStateException("NGX returned no context");
    }

    public void enableRayReconstruction(){checkOpen();rayReconstructionNative(handle);}

    public int[] qualityDimensions(int outputWidth, int outputHeight) {
        checkOpen();
        return qualityNative(handle, outputWidth, outputHeight);
    }

    public void create(VkCommandBuffer commands, int iw, int ih, int ow, int oh) {
        checkOpen();
        if (created) throw new IllegalStateException("DLSS feature already created");
        createNative(handle, commands.address(), iw, ih, ow, oh);
        inputWidth=iw; inputHeight=ih; outputWidth=ow; outputHeight=oh;
        created = true;
    }

    /** Motion is previous-minus-current displacement in input pixels; depth is reversed, color is linear HDR. */
    public void evaluate(VkCommandBuffer commands, GpuTextureView color, GpuTextureView depth,
            GpuTextureView motion, GpuTextureView exposure, GpuTextureView output, float jitterX, float jitterY, boolean reset) {
        evaluateFrame(commands,color,depth,motion,exposure,output,jitterX,jitterY,reset,null);
    }
    public void evaluateFrame(VkCommandBuffer commands,GpuTextureView color,GpuTextureView depth,
            GpuTextureView motion,GpuTextureView exposure,GpuTextureView output,float jitterX,float jitterY,boolean reset,DlssReconstruction.Guides guides) {
        checkOpen();
        if (!created) throw new IllegalStateException("DLSS feature has not been created");
        var views = guides==null?new GpuTextureView[]{color,depth,motion,exposure,output}:new GpuTextureView[]{color,depth,motion,exposure,output,guides.diffuse(),guides.specular(),guides.normalRoughness(),guides.specularDistance()};
        long[] resources = new long[views.length*6];
        for (int i=0; i<views.length; i++) {
            var texture = views[i].texture();
            if (texture.isClosed() || texture.getMipLevels() != 1 || texture.getDepthOrLayers() != 1) throw new IllegalArgumentException("NGX inputs require live single-mip 2D textures");
            int w = i==3 ? 1 : i==4 ? outputWidth : inputWidth;
            int h = i==3 ? 1 : i==4 ? outputHeight : inputHeight;
            if (texture.getWidth(0) != w || texture.getHeight(0) != h) throw new IllegalArgumentException("DLSS image extent mismatch");
            int offset=i*6;
            resources[offset]=((VulkanGpuTexture)texture).vkImage();
            resources[offset+1]=((VulkanGpuTextureView)views[i]).vkImageView();
            resources[offset+2]=VulkanConst.toVk(texture.getFormat());
            resources[offset+3]=w; resources[offset+4]=h; resources[offset+5]=1;
        }
        if(guides==null)evaluateNative(handle, commands.address(), resources, jitterX, jitterY, reset);
        else evaluateRrNative(handle,commands.address(),resources,guides.camera(),jitterX,jitterY,reset,guides.milliseconds());
    }

    public CompletableFuture<JsonObject> retire(VulkanCommandEncoder encoder) {
        checkOpen();
        long retired = handle;
        var completed = new CompletableFuture<JsonObject>();
        encoder.queueForDestroy(() -> {
            try {
                JsonObject result = JsonParser.parseString(closeNative(retired)).getAsJsonObject();
                for (String key : new String[]{"releaseFeatureResult","destroyParametersResult","destroyCapabilitiesResult","shutdownResult"}) {
                    if (result.get(key).getAsLong() != 1) throw new IllegalStateException("NGX retirement failed: " + result);
                }
                completed.complete(result);
            } catch (Throwable failure) { completed.completeExceptionally(failure); }
        });
        handle = 0;
        return completed;
    }

    private void checkOpen() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("DLSS recording must stay on its renderer thread");
        if (handle == 0) throw new IllegalStateException("DLSS context is retiring or closed");
    }
}
