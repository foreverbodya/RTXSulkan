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
    private DlssReconstruction generation;
    private TextureTarget world;
    private volatile Throwable retirementFailure;
    java.util.Map<String,Object> diagnostic() {
        var size=generation.extent();
        return java.util.Map.of("inputWidth",size.inputWidth(),"inputHeight",size.inputHeight(),
                "outputWidth",size.outputWidth(),"outputHeight",size.outputHeight(),"evaluations",generation.evaluations());
    }
    WorldReconstruction(VulkanDevice device,RenderTarget output) throws IOException {
        this.device=device;
        loadLibrary(Path.of(requiredProperty("sulkan.dlssLibrary")).toRealPath());
        runtime=Path.of(requiredProperty("sulkan.dlssRuntime")).toRealPath();
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
        if(retirementFailure!=null) throw new IllegalStateException("DLSS retirement failed",retirementFailure);
        if(generation!=null && generation.extent().outputWidth()==output.width && generation.extent().outputHeight()==output.height) return;
        var encoder=device.createCommandEncoder();
        var replacement=new DlssReconstruction(device,encoder,cache,runtime,output.width,output.height);
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
    GpuTextureView output() { return generation.output(); }
    long bytes() {
        return (long)world.width*world.height*(world.getColorTexture().getFormat().blockSize()+world.getDepthTexture().getFormat().blockSize())
                +(long)generation.extent().outputWidth()*generation.extent().outputHeight()*8;
    }
    void beginFrame() {
        var encoder=device.createCommandEncoder();
        encoder.clearColorTexture(world.getColorTexture(),new Vector4f(0));
        encoder.clearDepthTexture(world.getDepthTexture(),0);
    }
    void record(VulkanCommandEncoder encoder,DlssReconstruction.Frame frame) { generation.record(encoder,frame); }
    long evaluations() { return generation.evaluations(); }
    @Override public void close() {
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
