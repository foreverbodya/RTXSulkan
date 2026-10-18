package com.sulkan.shaders.platform.vulkan.dlss;

import com.google.gson.JsonObject;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.sulkan.shaders.graph.StorageImageScope;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkMemoryBarrier;
import static com.sulkan.shaders.platform.vulkan.VulkanChecks.requireSuccess;

/** One immutable output-size generation. The host replaces and retires generations on resize. */
public final class DlssReconstruction {
    public record Extent(int inputWidth,int inputHeight,int outputWidth,int outputHeight) {}
    public record Frame(GpuTextureView color,GpuTextureView depth,GpuTextureView motion,
                        GpuTextureView exposure,float jitterX,float jitterY,boolean reset) {}
    private final DlssSession session;
    private final GpuTexture output;
    private final GpuTextureView outputView;
    private final Extent extent;
    private boolean featureCreated;
    private boolean retired;
    private long evaluations;

    public DlssReconstruction(VulkanDevice device,VulkanCommandEncoder encoder,Path cache,Path runtime,int width,int height) {
        if(width<=0 || height<=0) throw new IllegalArgumentException("Invalid DLSS output extent");
        session=new DlssSession(device,cache,runtime);
        GpuTexture texture=null; GpuTextureView view=null;
        try {
            int[] input=session.qualityDimensions(width,height);
            if(input[0]<=0 || input[1]<=0 || input[0]>width || input[1]>height)
                throw new IllegalStateException("Invalid DLSS Quality extent");
            extent=new Extent(input[0],input[1],width,height);
            try(StorageImageScope scope=new StorageImageScope(true,1)) {
                texture=device.createTexture("Sulkan DLSS reconstructed HDR",7,GpuFormat.RGBA16_FLOAT,width,height,1,1);
                view=device.createTextureView(texture,0,1);
            }
            output=texture; outputView=view;
        } catch(Throwable failure) {
            GpuTexture ownedTexture=texture; GpuTextureView ownedView=view;
            session.retire(encoder).whenComplete((ignored,error) -> {
                if(ownedView!=null) ownedView.close();
                if(ownedTexture!=null) ownedTexture.close();
            });
            throw failure;
        }
    }
    public Extent extent() { return extent; }
    public long evaluations() { return evaluations; }
    public GpuTextureView output() {
        if(retired) throw new IllegalStateException("DLSS generation is retiring");
        return outputView;
    }
    /** No submit, readback or global wait: all NGX work stays on the caller's encoder. */
    public void record(VulkanCommandEncoder encoder,Frame frame) {
        if(retired) throw new IllegalStateException("DLSS generation is retiring");
        require(frame.color(),GpuFormat.RGBA16_FLOAT,extent.inputWidth(),extent.inputHeight());
        require(frame.depth(),GpuFormat.R32_FLOAT,extent.inputWidth(),extent.inputHeight());
        require(frame.motion(),GpuFormat.RG16_FLOAT,extent.inputWidth(),extent.inputHeight());
        require(frame.exposure(),GpuFormat.R32_FLOAT,1,1);
        if(!Float.isFinite(frame.jitterX()) || !Float.isFinite(frame.jitterY())) throw new IllegalArgumentException("Invalid DLSS jitter");
        var commands=encoder.allocateAndBeginTransientCommandBuffer();
        try(MemoryStack stack=MemoryStack.stackPush()) {
            barrier(commands,stack);
            if(!featureCreated) {
                session.create(commands,extent.inputWidth(),extent.inputHeight(),extent.outputWidth(),extent.outputHeight());
                featureCreated=true;
            }
            session.evaluate(commands,frame.color(),frame.depth(),frame.motion(),frame.exposure(),outputView,
                    frame.jitterX(),frame.jitterY(),frame.reset() || evaluations==0);
            barrier(commands,stack);
            requireSuccess(VK10.vkEndCommandBuffer(commands),"end DLSS reconstruction");
            encoder.execute(commands);
            evaluations++;
        }
    }
    public CompletableFuture<JsonObject> retire(VulkanCommandEncoder encoder) {
        if(retired) throw new IllegalStateException("DLSS generation already retired");
        var result=session.retire(encoder);
        retired=true;
        return result.whenComplete((ignored,error) -> {
            try { outputView.close(); } finally { output.close(); }
        });
    }
    private static void require(GpuTextureView view,GpuFormat format,int width,int height) {
        if(view==null || view.texture().isClosed() || view.texture().getFormat()!=format
                || view.texture().getWidth(0)!=width || view.texture().getHeight(0)!=height
                || view.texture().getMipLevels()!=1 || view.texture().getDepthOrLayers()!=1)
            throw new IllegalArgumentException("DLSS requires live single-mip "+format+" "+width+"x"+height);
    }
    private static void barrier(org.lwjgl.vulkan.VkCommandBuffer commands,MemoryStack stack) {
        VK10.vkCmdPipelineBarrier(commands,VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,0,
                VkMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(VK10.VK_ACCESS_MEMORY_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT|VK10.VK_ACCESS_MEMORY_WRITE_BIT),null,null);
    }
}
