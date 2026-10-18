package com.sulkan.shaders.diagnostics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.sulkan.shaders.graph.StorageImageScope;
import com.sulkan.shaders.platform.vulkan.NativeBuffer;
import com.sulkan.shaders.platform.vulkan.dlss.DlssReconstruction;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static com.sulkan.shaders.platform.vulkan.VulkanChecks.requireSuccess;

/** Actual NGX GPU evaluations of constant HDR frames; not game motion or image-quality validation. */
public final class DlssFrameConformance {
    private DlssFrameConformance() {}

    public static CompletableFuture<JsonObject> verify(VulkanDevice device, Path data, Path runtime) {
        var encoder = device.createCommandEncoder();
        DlssReconstruction reconstruction = new DlssReconstruction(device,encoder,data,runtime,2560,1440);
        var textures = new ArrayList<GpuTexture>();
        var views = new ArrayList<GpuTextureView>();
        NativeBuffer readback = null;
        Throwable failure = null;
        var report = new JsonObject();
        try {
            int ow=2560, oh=1440;
            int iw=reconstruction.extent().inputWidth(), ih=reconstruction.extent().inputHeight();
            if (iw <= 0 || ih <= 0 || iw >= ow || ih >= oh) throw new IllegalStateException("Invalid Quality dimensions");
            GpuFormat[] formats = {GpuFormat.RGBA16_FLOAT, GpuFormat.R32_FLOAT, GpuFormat.RG16_FLOAT, GpuFormat.R32_FLOAT};
            for (int i=0; i<formats.length; i++) {
                int w=i==3 ? 1 : iw, h=i==3 ? 1 : ih;
                try (StorageImageScope scope = new StorageImageScope(true,1)) {
                    GpuTexture texture = device.createTexture("Sulkan DLSS frame probe " + i, 7, formats[i], w,h,1,1);
                    textures.add(texture);
                    views.add(device.createTextureView(texture,0,1));
                }
            }
            int byteCount = Math.multiplyExact(Math.multiplyExact(ow,oh),8);
            readback = new NativeBuffer(device.vkDevice(), byteCount, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            boolean rejectedFormat=false;
            try { reconstruction.record(encoder,new DlssReconstruction.Frame(views.get(0),views.get(2),views.get(2),views.get(3),0,0,true)); }
            catch(IllegalArgumentException expected) { rejectedFormat=expected.getMessage().contains("R32_FLOAT"); }
            if(!rejectedFormat || reconstruction.evaluations()!=0) throw new IllegalStateException("Invalid depth format was not rejected before evaluation");
            report.addProperty("invalidDepthFormatRejected",true);
            var frames = new JsonArray();
            for (int frame=0; frame<4; frame++) {
                float[] color = frame<2 ? new float[]{.18f,.36f,.72f} : new float[]{2f,.12f,.04f};
                boolean reset = frame==0 || frame==2;
                encoder.clearColorTexture(textures.get(0),new Vector4f(color[0],color[1],color[2],1));
                encoder.clearColorTexture(textures.get(1),new Vector4f(.5f,0,0,0));
                encoder.clearColorTexture(textures.get(2),new Vector4f(0));
                encoder.clearColorTexture(textures.get(3),new Vector4f(1));
                // RGB NaNs make an unwritten output fail independently of SDK success.
                encoder.clearColorTexture(reconstruction.output().texture(),new Vector4f(Float.NaN));
                reconstruction.record(encoder,new DlssReconstruction.Frame(views.get(0),views.get(1),views.get(2),views.get(3),0,0,reset));
                var commands=encoder.allocateAndBeginTransientCommandBuffer();
                try (MemoryStack stack=MemoryStack.stackPush()) {
                    barrier(commands,stack,VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                            VK10.VK_ACCESS_MEMORY_WRITE_BIT,VK10.VK_ACCESS_TRANSFER_READ_BIT);
                    var copy=VkBufferImageCopy.calloc(1,stack).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
                    copy.imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                    copy.imageExtent().set(ow,oh,1);
                    VK10.vkCmdCopyImageToBuffer(commands,((VulkanGpuTexture)reconstruction.output().texture()).vkImage(),VK10.VK_IMAGE_LAYOUT_GENERAL,readback.handle(),copy);
                    barrier(commands,stack,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,VK10.VK_PIPELINE_STAGE_HOST_BIT,
                            VK10.VK_ACCESS_TRANSFER_WRITE_BIT,VK10.VK_ACCESS_HOST_READ_BIT);
                }
                requireSuccess(VK10.vkEndCommandBuffer(commands),"end DLSS evaluation");
                encoder.execute(commands); encoder.submit();
                // Deliberately confined to this world-free diagnostic. Normal frames must not wait globally.
                requireSuccess(VK10.vkDeviceWaitIdle(device.vkDevice()),"complete DLSS evaluation diagnostic");
                ByteBuffer output=readback.readBytes(byteCount);
                savePreview(output,ow,oh,data.getParent().resolve("dlss-frame-"+frame+".png"));
                double maxError=0;
                for (int pixel=0; pixel<ow*oh; pixel++) for (int channel=0; channel<3; channel++) {
                    float actual=Float.float16ToFloat(output.getShort(pixel*8+channel*2));
                    double error=Math.abs(actual-color[channel]);
                    if (!Float.isFinite(actual) || actual<0 || error>Math.max(.005,.005*color[channel])) {
                        throw new IllegalStateException("DLSS constant HDR frame="+frame+" pixel="+pixel+" channel="+channel+" expected="+color[channel]+" actual="+actual);
                    }
                    maxError=Math.max(maxError,error);
                }
                var result=new JsonObject();
                result.addProperty("frame",frame); result.addProperty("reset",reset); result.addProperty("maxRgbError",maxError);
                var expected=new JsonArray(); for(float value:color) expected.add(value); result.add("expectedRgb",expected);
                result.addProperty("rgbPixelsChecked",ow*oh);
                frames.add(result);
            }
            report.addProperty("inputWidth",iw); report.addProperty("inputHeight",ih);
            report.addProperty("outputWidth",ow); report.addProperty("outputHeight",oh);
            report.add("frames",frames);
            if(reconstruction.evaluations()!=4) throw new IllegalStateException("Incorrect reconstruction evaluation count");
            report.addProperty("reconstructionEvaluations",reconstruction.evaluations());
            report.addProperty("scope","Four real NGX evaluations of constant linear HDR frames including values above 1, zero motion, reset and repeated history; not gameplay quality, jitter, motion or FPS");
        } catch (Throwable problem) { failure=problem; }
        // Complete any submitted diagnostic work before resource ownership can retire.
        int idle=VK10.vkDeviceWaitIdle(device.vkDevice());
        if (idle!=VK10.VK_SUCCESS && failure==null) failure=new IllegalStateException("DLSS diagnostic wait failed: "+idle);
        final NativeBuffer ownedReadback=readback;
        var result=new CompletableFuture<JsonObject>();
        var retiring=reconstruction.retire(encoder);
        boolean rejectedOutput=false,rejectedDuplicateRetire=false;
        try { reconstruction.output(); } catch(IllegalStateException expected) { rejectedOutput=true; }
        try { reconstruction.retire(encoder); } catch(IllegalStateException expected) { rejectedDuplicateRetire=true; }
        if(!rejectedOutput || !rejectedDuplicateRetire) {
            var guardFailure=new IllegalStateException("Retiring generation remained usable");
            if(failure==null) failure=guardFailure; else failure.addSuppressed(guardFailure);
        }
        report.addProperty("retiredOutputRejected",true);
        report.addProperty("duplicateRetirementRejected",true);
        final Throwable problem=failure;
        retiring.whenComplete((retirement,error) -> {
            Throwable outcome = error!=null ? error : problem;
            try {
                if (outcome==null) {
                    if (retirement.get("liveContexts").getAsInt()!=0 || !retirement.get("lastContext").getAsBoolean()) throw new IllegalStateException("NGX diagnostic context remained alive");
                    report.add("retirement",retirement);
                }
            } catch (Throwable closeFailure) { outcome=closeFailure; }
            finally {
                try {
                    views.forEach(GpuTextureView::close); textures.forEach(GpuTexture::close);
                    if (ownedReadback!=null) ownedReadback.close();
                } catch (Throwable cleanupFailure) { if (outcome==null) outcome=cleanupFailure; else outcome.addSuppressed(cleanupFailure); }
            }
            if (outcome!=null) result.completeExceptionally(outcome); else result.complete(report);
        });
        encoder.submit();
        return result;
    }
    private static void barrier(VkCommandBuffer commands,MemoryStack stack,int source,int target,int write,int read) {
        VK10.vkCmdPipelineBarrier(commands,source,target,0,VkMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(write).dstAccessMask(read),null,null);
    }
    private static void savePreview(ByteBuffer data,int width,int height,Path path) throws java.io.IOException {
        var image=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int pixel=0; pixel<width*height; pixel++) {
            int rgb=0;
            for (int channel=0; channel<3; channel++) {
                float value=Float.float16ToFloat(data.getShort(pixel*8+channel*2));
                double linear=Float.isFinite(value) ? Math.max(0,Math.min(1,value)) : 0;
                double encoded=linear<=.0031308 ? 12.92*linear : 1.055*Math.pow(linear,1.0/2.4)-.055;
                rgb=(rgb<<8)|Math.max(0,Math.min(255,(int)Math.round(encoded*255)));
            }
            image.setRGB(pixel%width,pixel/width,rgb);
        }
        if (!javax.imageio.ImageIO.write(image,"png",path.toFile())) throw new java.io.IOException("No PNG writer");
    }
}
