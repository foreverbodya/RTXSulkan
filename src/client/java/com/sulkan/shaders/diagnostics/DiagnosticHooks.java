package com.sulkan.shaders.diagnostics;

import com.sulkan.shaders.graph.GraphGpuSmoke;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/** Optional diagnostic entry points. Requests are fixed by JVM arguments at startup. */
public final class DiagnosticHooks {
    private static final boolean WORLD_REQUESTED = System.getProperty("sulkan.qaDirectory") != null
        || Boolean.getBoolean("sulkan.qaVisualWorld");

    private DiagnosticHooks() {}

    public static void register() {
        if (FabricLoader.getInstance().isDevelopmentEnvironment() && smokeRequested())
            ClientTickEvents.END_CLIENT_TICK.register(GraphGpuSmoke::tick);
        if (Boolean.getBoolean("sulkan.rtConformance"))
            ClientTickEvents.END_CLIENT_TICK.register(RayQueryConformance::tick);
        if (Boolean.getBoolean("sulkan.raySurfaceConformance"))
            ClientTickEvents.END_CLIENT_TICK.register(RaySurfaceConformance::tick);
        if (System.getProperty("sulkan.screenConformanceDirectory") != null)
            ClientTickEvents.END_CLIENT_TICK.register(ShaderSelectionConformance::tick);
        if (System.getProperty("sulkan.dlssProbeDirectory") != null)
            ClientTickEvents.END_CLIENT_TICK.register(DlssConformance::tick);
        if (WORLD_REQUESTED)
            ClientTickEvents.END_CLIENT_TICK.register(QaWorldValidation::tick);
    }

    public static void frameRendered() {
        if (WORLD_REQUESTED) QaWorldValidation.frameRendered();
    }

    public static void prepareCameraFrame() {
        if (WORLD_REQUESTED && Boolean.getBoolean("sulkan.qaCameraBob")) QaWorldValidation.prepareCameraFrame();
    }

    private static boolean smokeRequested() {
        return System.getProperty("sulkan.graphSmoke") != null
            || Boolean.getBoolean("sulkan.reflectionSmoke")
            || Boolean.getBoolean("sulkan.environmentSmoke")
            || Boolean.getBoolean("sulkan.shadowSmoke")
            || Boolean.getBoolean("sulkan.switchSmoke")
            || Boolean.getBoolean("sulkan.worldSmoke");
    }
}
