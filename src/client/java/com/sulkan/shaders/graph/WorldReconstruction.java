package com.sulkan.shaders.graph;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.sulkan.shaders.platform.vulkan.dlss.DlssReconstruction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.joml.Vector4f;

/** Owns a low-resolution world target and the matching SDK-selected reconstruction generation. */
final class WorldReconstruction implements AutoCloseable {
    private static Path loadedLibrary;
    private final VulkanDevice device;
    private final Path cache,runtime;
    private final boolean rayReconstruction;
    private DlssReconstruction generation;
    private TextureTarget world;
    // Explicit diagnostic control: render the world at output resolution without
    // creating or evaluating NGX. Never enabled by a production pack option.
    private final boolean nativeDiagnostic=Boolean.getBoolean("sulkan.qaFoliageNative") && Boolean.getBoolean("sulkan.qaReadback");
    private GpuTextureView nativeOutput;
    private long nativeFrames;
    private volatile Throwable retirementFailure;
    java.util.Map<String,Object> diagnostic() {
        if(nativeDiagnostic)return java.util.Map.of("inputWidth",world.width,"inputHeight",world.height,
            "outputWidth",world.width,"outputHeight",world.height,"evaluations",nativeFrames,"nativeEvaluations",0,"mode","native-no-dlss");
        var size=generation.extent();
        return java.util.Map.of("inputWidth",size.inputWidth(),"inputHeight",size.inputHeight(),
                "outputWidth",size.outputWidth(),"outputHeight",size.outputHeight(),"evaluations",generation.evaluations(),"mode",rayReconstruction?"dlss-ray-reconstruction":"dlss-super-resolution");
    }
    WorldReconstruction(VulkanDevice device,RenderTarget output) throws IOException {
        this(device,output,false);
    }
    WorldReconstruction(VulkanDevice device,RenderTarget output,boolean rr) throws IOException {
        this.device=device;rayReconstruction=rr;
        if(nativeDiagnostic){runtime=null;cache=null;prepare(output);return;}
        loadLibrary(Path.of(requiredProperty("sulkan.dlssLibrary")).toRealPath());
        runtime=Path.of(requiredProperty("sulkan.dlssRuntime")).toRealPath();
        if(rr && !Files.isRegularFile(runtime.resolve("nvngx_dlssd.dll")))throw new IOException("RR runtime is missing");
        if(!Files.isRegularFile(runtime.resolve("nvngx_dlss.dll"))) throw new IOException("DLSS runtime is missing");
        cache=net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("sulkan/ngx-cache");
        Files.createDirectories(cache);
        prepare(output);
    }
    private static String requiredProperty(String name) throws IOException {
        String value=System.getProperty(name);
        if(value==null || value.isBlank()) throw new IOException("DLSS requires an explicit "+name+" path");
        return value;
    }
    private static synchronized void loadLibrary(Path library) throws IOException {
        if(loadedLibrary!=null && !loadedLibrary.equals(library)) throw new IOException("A different DLSS adapter is already loaded");
        if(loadedLibrary==null) { System.load(library.toString()); loadedLibrary=library; }
    }
    void prepare(RenderTarget output) {
        if(nativeDiagnostic){
            if(world!=null && world.width==output.width && world.height==output.height)return;
            if(world!=null)throw new IllegalStateException("Native foliage diagnostic does not resize during capture");
            world=new TextureTarget("Sulkan native diagnostic world",output.width,output.height,
                output.getColorTexture().getFormat(),output.getDepthTexture().getFormat());return;
        }
        if(retirementFailure!=null) throw new IllegalStateException("DLSS retirement failed",retirementFailure);
        if(generation!=null && generation.extent().outputWidth()==output.width && generation.extent().outputHeight()==output.height) return;
        var encoder=device.createCommandEncoder();
        var replacement=new DlssReconstruction(device,encoder,cache,runtime,output.width,output.height,rayReconstruction);
        TextureTarget target;
        try {
            var size=replacement.extent();
            target=new TextureTarget("Sulkan DLSS world",size.inputWidth(),size.inputHeight(),
                    output.getColorTexture().getFormat(),output.getDepthTexture().getFormat());
        } catch(Throwable failure) { replacement.retire(encoder); throw failure; }
        var old=generation; var oldWorld=world;
        generation=replacement; world=target;
        if(old!=null) old.retire(encoder).whenComplete((result,error) -> retired(oldWorld,result,error));
    }
    RenderTarget world() { return world; }
    GpuTextureView output() { return nativeDiagnostic?nativeOutput:generation.output(); }
    long bytes() {
        if(nativeDiagnostic)return (long)world.width*world.height*(world.getColorTexture().getFormat().blockSize()+world.getDepthTexture().getFormat().blockSize());
        return (long)world.width*world.height*(world.getColorTexture().getFormat().blockSize()+world.getDepthTexture().getFormat().blockSize())
                +(long)generation.extent().outputWidth()*generation.extent().outputHeight()*8;
    }
    void beginFrame() {
        var encoder=device.createCommandEncoder();
        encoder.clearColorTexture(world.getColorTexture(),new Vector4f(0));
        encoder.clearDepthTexture(world.getDepthTexture(),0);
    }
    void record(VulkanCommandEncoder encoder,DlssReconstruction.Frame frame) {
        if(nativeDiagnostic){nativeOutput=frame.color();nativeFrames++;return;}
        generation.record(encoder,frame);
    }
    long evaluations() { return nativeDiagnostic?nativeFrames:generation.evaluations(); }
    @Override public void close() {
        if(nativeDiagnostic){if(world!=null){var target=world;world=null;device.createCommandEncoder().queueForDestroy(target::destroyBuffers);}return;}
        if(generation==null) return;
        var owned=generation; var target=world; generation=null; world=null;
        owned.retire(device.createCommandEncoder()).whenComplete((result,error) -> retired(target,result,error));
    }
    private void retired(TextureTarget target,com.google.gson.JsonObject result,Throwable error) {
        try { target.destroyBuffers(); } catch(Throwable failure) { if(error==null) error=failure; else error.addSuppressed(failure); }
        if(error!=null) {
            retirementFailure=error;
            com.mojang.logging.LogUtils.getLogger().error("SULKAN_DLSS_WORLD_RETIRE_FAIL",error);
        } else com.mojang.logging.LogUtils.getLogger().info("SULKAN_DLSS_WORLD_RETIRE_PASS lastContext={} liveContexts={}",
                result.get("lastContext").getAsBoolean(),result.get("liveContexts").getAsInt());
    }
}
