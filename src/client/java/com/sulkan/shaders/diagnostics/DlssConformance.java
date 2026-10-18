package com.sulkan.shaders.diagnostics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.sulkan.shaders.mixin.GraphDeviceAccessor;
import com.sulkan.shaders.runtime.ShaderRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.vulkan.VK;

/** Guarded world-free NGX capability check, optionally followed by constant-HDR frame evaluation. */
public final class DlssConformance {
    private static boolean finished;
    private DlssConformance() {}

    private static native String probe(long instance, long physicalDevice, long device,
            long getInstanceProc, long getDeviceProc, String dataDirectory, String runtimeDirectory);

    public static void tick(Minecraft client) {
        String requested = System.getProperty("sulkan.dlssProbeDirectory");
        if (requested == null || finished || client.gui.overlay() != null
                || ShaderRuntime.resourceReloading() || !(client.gui.screen() instanceof TitleScreen)) return;
        finished = true;
        boolean asynchronous = false;
        try {
            Path directory = Path.of(requested).toRealPath();
            if (!directory.equals(client.gameDirectory.toPath().toRealPath())
                    || !Files.readString(directory.resolve("SULKAN_QA_DISPOSABLE")).trim().equals("sulkan-cinematic-disposable-v1")) {
                throw new IllegalStateException("DLSS probe requires a matching, marked disposable game directory");
            }
            var backend = ((GraphDeviceAccessor)RenderSystem.getDevice()).sulkan$backend();
            if (!(backend instanceof VulkanDevice device)) throw new IllegalStateException("DLSS probe requires Vulkan");
            Path library = Path.of(System.getProperty("sulkan.dlssProbeLibrary")).toRealPath();
            Path runtime = Path.of(System.getProperty("sulkan.dlssProbeRuntime")).toRealPath();
            if (!Files.isRegularFile(runtime.resolve("nvngx_dlss.dll"))) throw new IllegalStateException("External DLSS runtime missing");
            Path data = directory.resolve("ngx-data");
            Files.createDirectories(data);
            System.load(library.toString());
            var physical = device.vkDevice().getPhysicalDevice();
            var instance = physical.getInstance();
            JsonObject result = JsonParser.parseString(probe(instance.address(), physical.address(), device.vkDevice().address(),
                    VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"),
                    VK.getFunctionProvider().getFunctionAddress("vkGetDeviceProcAddr"), data.toString(), runtime.toString())).getAsJsonObject();
            JsonObject extensions = new JsonObject();
            for (var name : result.getAsJsonArray("deviceExtensions")) {
                String extension = name.getAsString();
                extensions.addProperty(extension, device.vkDevice().getCapabilities().getClass().getField(extension).getBoolean(device.vkDevice().getCapabilities()));
            }
            result.add("deviceExtensionCapabilities", extensions);
            result.addProperty("dlssFeaturesNegotiated", com.sulkan.shaders.platform.vulkan.DlssFeatures.enabledFor(device.vkDevice()));
            result.addProperty("scope", "NGX initialization and DLSS capability/optimal Quality settings on Minecraft's device; no feature creation or frame evaluation");
            Files.writeString(directory.resolve("dlss-probe.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result));
            if (result.get("sdkApi").getAsInt() != com.sulkan.shaders.platform.vulkan.DlssFeatures.SDK_API) throw new IllegalStateException("SDK API differs from the negotiated feature contract");
            if (com.sulkan.shaders.platform.vulkan.DlssFeatures.requested()) {
                java.util.Set<String> required = new java.util.HashSet<>();
                result.getAsJsonArray("deviceExtensions").forEach(name -> required.add(name.getAsString()));
                if (!required.equals(com.sulkan.shaders.platform.vulkan.DlssFeatures.SUPER_RESOLUTION.extensions())) {
                    throw new IllegalStateException("SDK device extension requirements differ from the pinned feature contract");
                }
                if (!com.sulkan.shaders.platform.vulkan.DlssFeatures.enabledFor(device.vkDevice())) throw new IllegalStateException("DLSS feature set was not negotiated");
                for (var entry : extensions.entrySet()) if (!entry.getValue().getAsBoolean()) throw new IllegalStateException("Required DLSS extension not enabled: " + entry.getKey());
            }
            for (String key : new String[]{"initResult","capabilityResult","availabilityResult","optimalResult","destroyParametersResult","shutdownResult"}) {
                if (!result.has(key) || result.get(key).getAsLong() != 1) throw new IllegalStateException("NGX probe failed at " + key + ": " + result);
            }
            if (result.get("available").getAsInt() != 1 || result.get("qualityWidth").getAsInt() <= 0 || result.get("qualityHeight").getAsInt() <= 0) {
                throw new IllegalStateException("DLSS not available or optimal dimensions invalid: " + result);
            }
            LogUtils.getLogger().info("SULKAN_DLSS_CAPABILITY_PASS {}", result);
            if (Boolean.getBoolean("sulkan.dlssFrameProbe")) {
                var frameResult = DlssFrameConformance.verify(device,data,runtime);
                asynchronous = true;
                frameResult.whenComplete((frames,error) -> client.execute(() -> {
                    try {
                        if (error!=null) throw new IllegalStateException("DLSS frame evaluation failed",error);
                        Files.writeString(directory.resolve("dlss-frames.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(frames));
                        LogUtils.getLogger().info("SULKAN_DLSS_FRAME_PASS {}",frames);
                    } catch (Throwable failure) { LogUtils.getLogger().error("SULKAN_DLSS_FRAME_FAIL",failure); }
                    finally { client.stop(); }
                }));
            }
        } catch (Throwable failure) {
            LogUtils.getLogger().error("SULKAN_DLSS_CAPABILITY_FAIL", failure);
        } finally {
            if (!asynchronous) client.stop();
        }
    }
}
