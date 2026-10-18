package com.sulkan.shaders.diagnostics;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.sulkan.shaders.mixin.GraphDeviceAccessor;
import com.sulkan.shaders.graph.NativePackRuntime;
import com.sulkan.shaders.render.raytracing.TerrainGeometryCache;
import com.sulkan.shaders.render.raytracing.TerrainMesh;
import com.sulkan.shaders.runtime.ShaderRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Guarded, opt-in world lifecycle validation. All mutations are confined to the marked QA directory. */
public final class QaWorldValidation {
    private static final String SAVE_ID = "sulkan-cinematic-qa";
    private static final String WORLD_NAME = "Sulkan Cinematic QA";
    private static int stage;
    private static int ticks;
    private static boolean pending;
    private static boolean stopped;
    private static long started;
    private static long previousRevision;
    private static long afterReloadFrames;
    private static Path evidence;
    private static Runnable afterMaterialReloadCapture;
    private static Runnable afterTemporalCapture;
    private static boolean positiveMotion;
    private static final java.util.Map<Boolean,java.util.Set<String>> motionSweepPhases=new java.util.HashMap<>();
    private static final java.util.Map<Boolean,java.util.List<Object>> motionSweepReports=new java.util.HashMap<>();
    private static double motionTargetX=24;
    private static int motionSweepDirection=1;
    private static double skyMotionYaw=135;
    private static Runnable afterWorldDlssCapture;
    private static Runnable nextTickAction;
    private static Runnable afterFarValidation;
    private static Runnable afterAtmosphereValidation;
    private static Runnable afterAtmosphericLighting;
    private static Runnable nextRenderedFrameAction;
    private static boolean temporalSceneWatch,temporalSceneResetChecked;
    private static long temporalWatchedEpoch;
    private static long temporalLastEpoch=Long.MIN_VALUE;
    private static boolean temporalMotionChecked,temporalMotionInFlight;
    private static long sceneEpoch(java.nio.ByteBuffer frame) {
        return Integer.toUnsignedLong(frame.getInt(1120))|((long)frame.getInt(1124)<<32);
    }
    private static void afterStableIndirectFrames(int consecutive,long epoch,Runnable action) {
        nextRenderedFrameAction=() -> {
            var frame=NativePackRuntime.diagnosticFrame();long current=sceneEpoch(frame);
            boolean ready=frame.getInt(1128)==0 && frame.getInt(1136)==1 && frame.getFloat(396)>.5;
            int count=ready && current==epoch?consecutive+1:0;
            double x=.5-frame.getFloat(1072)+frame.getFloat(1080),y=.5-frame.getFloat(1076)+frame.getFloat(1084);
            boolean unambiguous=Math.abs(x-Math.rint(x))>.001 && Math.abs(y-Math.rint(y))>.001;
            if(count>=64 && unambiguous) action.run();else afterStableIndirectFrames(count,current,action);
        };
    }
    private static final java.util.Set<String> lightingJitters=new java.util.HashSet<>();
    private static final int FAR_X=16_000_000,FAR_Z=-16_000_000;
    private static final java.util.Map<String,Long> steadyThrottleReasons=new java.util.TreeMap<>();
    private static final java.util.Map<String,Long> motionThrottleReasons=new java.util.TreeMap<>();
    private static final java.util.ArrayList<Double> frameIntervalsMs = new java.util.ArrayList<>();
    private static final java.util.ArrayList<Double> motionFrameIntervalsMs = new java.util.ArrayList<>();
    private static long previousFrameEnd;
    private static int cadenceStage;
    private static boolean exposureResetCaptured;

    /** Wall cadence includes throttling and submission stalls; it is not a GPU timestamp. */
    public static void prepareCameraFrame() {
        if(!Boolean.getBoolean("sulkan.qaVisualWorld"))throw new IllegalStateException("Camera bob audit requires the disposable natural world");
        QaVisualWorldValidation.prepareCameraFrame();
    }

    /** Wall cadence includes throttling and submission stalls; it is not a GPU timestamp. */
    public static void frameRendered() {
        if(Boolean.getBoolean("sulkan.qaVisualWorld")) { QaVisualWorldValidation.frameRendered(); return; }
        if (System.getProperty("sulkan.qaDirectory") == null) return;
        if((stage==30 || stage==31) && !pending && !stopped) {
            try {
                int sign=stage==30?1:-1;
                var check=SkyMotionConformance.capture(sign);
                if(check!=null) {
                    pending=true;
                    check.whenComplete((result,error)->Minecraft.getInstance().execute(()->{
                        var client=Minecraft.getInstance();
                        if(error!=null){fail(client,error);return;}
                        try {
                            Files.writeString(evidence.resolve(sign>0?"sky-motion-positive.json":"sky-motion-negative.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result));
                            LogUtils.getLogger().info("SULKAN_QA_SKY_MOTION_PASS sign={} {}",sign,result);
                            if(sign>0){stage=31;ticks=0;pending=false;}
                            else client.getSingleplayerServer().execute(()->{
                                var server=client.getSingleplayerServer();
                                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"tp @p "+motionTargetX+" 104 25 135 22");
                                client.execute(()->{stage=32;ticks=0;pending=false;});
                            });
                        }catch(Throwable problem){fail(client,problem);}
                    }));
                }
            }catch(Throwable problem){fail(Minecraft.getInstance(),problem);}
            return;
        }
        if(Boolean.getBoolean("sulkan.qaAutoExposure") && !exposureResetCaptured && evidence!=null && NativePackRuntime.renderedFrames()>0
            && NativePackRuntime.diagnosticFrame().getFloat(396)<.5) {
            exposureResetCaptured=true;
            ExposureConformance.verify(Minecraft.getInstance(),evidence,"reset",()->{},failure->fail(Minecraft.getInstance(),failure));
        }
        if(Boolean.getBoolean("sulkan.qaIndirectTemporal") && NativePackRuntime.renderedFrames()>0) {
            var frame=NativePackRuntime.diagnosticFrame();long epoch=sceneEpoch(frame);
            double movement=frame.getFloat(384)*frame.getFloat(384)+frame.getFloat(388)*frame.getFloat(388)+frame.getFloat(392)*frame.getFloat(392);
            if(stage==7 && !temporalMotionChecked && !temporalMotionInFlight && epoch==temporalLastEpoch
                && frame.getInt(1128)==0 && frame.getInt(1136)==1 && frame.getFloat(396)>.5 && movement>1e-10) {
                temporalMotionInFlight=true;
                IndirectTemporalConformance.verify(Minecraft.getInstance(),evidence,"motion",() -> {
                    temporalMotionChecked=true;temporalMotionInFlight=false;
                },failure -> fail(Minecraft.getInstance(),failure));
            }
            temporalLastEpoch=epoch;
        }
        if(temporalSceneWatch && TerrainGeometryCache.statistics().revision()>previousRevision && !hasVertex(4,97,4)) {
            var frame=NativePackRuntime.diagnosticFrame();
            if(frame.getInt(1128)==0 && frame.getInt(1136)==1 && sceneEpoch(frame)!=temporalWatchedEpoch) {
                temporalSceneWatch=false;
                IndirectTemporalConformance.verify(Minecraft.getInstance(),evidence,"scene-change",() -> temporalSceneResetChecked=true,
                    failure -> fail(Minecraft.getInstance(),failure));
            }
        }
        if(nextRenderedFrameAction!=null) {
            var action=nextRenderedFrameAction;nextRenderedFrameAction=null;
            try { action.run(); } catch(Throwable problem) { fail(Minecraft.getInstance(),problem); }
        }
        if(stage==16 && !pending && !stopped) {
            var frame=NativePackRuntime.diagnosticFrame();
            String jitter=Float.toHexString(frame.getFloat(1072))+","+Float.toHexString(frame.getFloat(1076));
            if(!lightingJitters.contains(jitter)) {
                pending=true;
                DeferredLightingConformance.verify(Minecraft.getInstance(),evidence,() -> {
                    try {
                        lightingJitters.add(jitter);
                        Files.copy(evidence.resolve("deferred-lighting.json"),evidence.resolve("atmospheric-lighting-jitter-"+lightingJitters.size()+".json"));
                        if(lightingJitters.size()==32) {
                            LogUtils.getLogger().info("SULKAN_QA_ATMOSPHERIC_LIGHTING_PHASES_PASS phases=32 endOfWorldFrame=true");
                            nextRenderedFrameAction=afterAtmosphericLighting;
                        } else pending=false;
                    } catch(Throwable problem) { fail(Minecraft.getInstance(),problem); }
                },failure -> fail(Minecraft.getInstance(),failure));
            }
        }
        boolean steady=stage==2 && ticks>=20 && ticks<100 && !pending;
        if (!steady && stage!=7) { previousFrameEnd=0; cadenceStage=0; return; }
        if(cadenceStage!=stage) { previousFrameEnd=0; cadenceStage=stage; }
        long now=System.nanoTime();
        if(previousFrameEnd!=0) (steady ? frameIntervalsMs : motionFrameIntervalsMs).add((now-previousFrameEnd)*1e-6);
        previousFrameEnd=now;
        (steady ? steadyThrottleReasons : motionThrottleReasons).merge(Minecraft.getInstance().getFramerateLimitTracker().getThrottleReason().name(),1L,Long::sum);
        if(stage==7 && !pending && !stopped) {
            var client=Minecraft.getInstance();
            try { captureCameraMotion(client); } catch(Throwable failure) { fail(client,failure); }
        }
    }

    private static void saveFrameCadence() throws java.io.IOException {
        saveFrameCadence(frameIntervalsMs,"world-frame-cadence.json",
                "QA fixed-camera engineering world before readback; wall cadence includes CPU/GPU stalls and configured cap; not completed cinematic performance");
    }

    private static void saveFrameCadence(java.util.List<Double> intervals,String filename,String scope) throws java.io.IOException {
        if(intervals.isEmpty()) throw new IllegalStateException("No frame intervals recorded for "+filename);
        var sorted=intervals.stream().sorted().toList();
        double mean=sorted.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        var report=new java.util.LinkedHashMap<String,Object>(java.util.Map.of("frames",sorted.size(),"meanFrameMs",mean,"meanFPS",1000/mean,
                "p50FrameMs",sorted.get(sorted.size()/2),"p95FrameMs",sorted.get((int)Math.floor((sorted.size()-1)*.95)),
                "maxFrameMs",sorted.get(sorted.size()-1),
                "scope",scope));
        report.put("maxFpsOption",Integer.getInteger("sulkan.qaFrameCap",60));
        var target=Minecraft.getInstance().gameRenderer.mainRenderTarget();
        report.put("outputWidth",target.width); report.put("outputHeight",target.height);
        report.put("throttleReasons",java.util.Map.copyOf(intervals==frameIntervalsMs ? steadyThrottleReasons : motionThrottleReasons));
        report.put("vsyncEnabled",Minecraft.getInstance().options.enableVsync().get());
        Files.writeString(evidence.resolve(filename),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
        LogUtils.getLogger().info("SULKAN_QA_FRAME_CADENCE {}",report);
    }

    private QaWorldValidation() {}

    public static void tick(Minecraft client) {
        if(Boolean.getBoolean("sulkan.qaVisualWorld")) { QaVisualWorldValidation.tick(client); return; }
        String requestedDirectory = System.getProperty("sulkan.qaDirectory");
        if (requestedDirectory == null || stopped) return;
        try {
            if(nextTickAction!=null) { var action=nextTickAction; nextTickAction=null; action.run(); }
            if (started != 0 && System.nanoTime() - started > Long.getLong("sulkan.qaTimeoutSeconds",180L)*1_000_000_000L) throw new IllegalStateException("QA world timed out at stage " + stage);
            if(stage>0 && client.player!=null && client.getSingleplayerServer()!=null
                && WORLD_NAME.equals(client.getSingleplayerServer().getWorldData().getLevelName())) {
                // Applies only after the disposable directory and world identity checks.
                // Controlled server teleports remain the source of QA camera motion.
                client.mouseHandler.releaseMouse();
                for(var key:java.util.List.of(client.options.keyUp,client.options.keyDown,client.options.keyLeft,client.options.keyRight,
                    client.options.keyJump,client.options.keyShift,client.options.keySprint)) key.setDown(false);
                client.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            }
            if (pending) return;
            if (NativePackRuntime.failure() != null) throw new IllegalStateException(NativePackRuntime.failure());
            if (stage == 0) {
                if (client.gui.overlay() != null || !(client.gui.screen() instanceof TitleScreen) || ShaderRuntime.resourceReloading()) return;
                Path directory = Path.of(requestedDirectory).toRealPath();
                if (!directory.equals(client.gameDirectory.toPath().toRealPath())
                        || !Files.readString(directory.resolve("SULKAN_QA_DISPOSABLE")).trim().equals("sulkan-cinematic-disposable-v1")) {
                    throw new IllegalStateException("QA requires matching, explicitly marked disposable game directory");
                }
                if (Files.exists(directory.resolve("saves").resolve(SAVE_ID))) throw new IllegalStateException("Fresh QA run must not overwrite an existing world");
                evidence = Path.of(System.getProperty("sulkan.qaEvidence", directory.resolve("evidence").toString())).toAbsolutePath();
                Files.createDirectories(evidence);
                TerrainCodecConformance.verify();
                if(Boolean.getBoolean("sulkan.qaRayOrigin"))
                    RayOriginConformance.verify((VulkanDevice)((GraphDeviceAccessor)RenderSystem.getDevice()).sulkan$backend(),evidence);
                client.getTutorial().setStep(TutorialSteps.NONE);
                started = System.nanoTime();
                stage = 1;
                client.createWorldOpenFlows().createFreshLevel(SAVE_ID,
                        new LevelSettings(WORLD_NAME, GameType.CREATIVE,
                                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT),
                        new WorldOptions(672026L, false, false), WorldPresets::createTestWorldDimensions, client.gui.screen());
                return;
            }
            if (client.level == null || client.gui.overlay() != null || ShaderRuntime.resourceReloading()) return;
            var server = client.getSingleplayerServer();
            if (server == null || !WORLD_NAME.equals(server.getWorldData().getLevelName())) throw new IllegalStateException("QA loaded an unexpected world");
            ticks++;
            if(Boolean.getBoolean("sulkan.qaHeldItemCapture") && stage==2) {
                QaHeldItemCapture.tick(client,evidence);
                return;
            }
            if (stage == 1 && ticks >= 80) {
                pending = true;
                server.execute(() -> {
                    try {
                        var source = server.createCommandSourceStack().withSuppressedOutput();
                        for (String command : new String[]{"forceload add -16 -16 32 32", "weather clear", "time set 3000", "gamerule minecraft:advance_time false",
                                "fill 0 94 0 23 95 23 minecraft:smooth_quartz", "setblock 4 96 4 minecraft:stone",
                                "fill 10 96 8 10 99 8 minecraft:oak_log", "fill 9 100 7 11 101 9 minecraft:oak_leaves[persistent=true]",
                                "fill 14 96 2 20 96 8 minecraft:water", "fill 4 96 13 4 99 13 minecraft:glass",
                                "summon minecraft:pig 16 96 14 {NoAI:1b,Invulnerable:1b}", "gamemode spectator @p", "tp @p 24 104 25 135 22"}) {
                            server.getCommands().performPrefixedCommand(source, command);
                        }
                        if (!server.overworld().getBlockState(new BlockPos(4, 96, 4)).is(Blocks.STONE)
                                || !server.overworld().getBlockState(new BlockPos(10, 96, 8)).is(Blocks.OAK_LOG)
                                || !server.overworld().getBlockState(new BlockPos(14, 96, 2)).is(Blocks.WATER)) {
                            throw new IllegalStateException("QA fixture commands did not produce the required blocks");
                        }
                        if (Boolean.getBoolean("sulkan.qaRayLighting")) {
                            server.getCommands().performPrefixedCommand(source, "setblock 18 96 18 minecraft:glowstone");
                            server.getCommands().performPrefixedCommand(source, "setblock 6 96 15 minecraft:iron_block");
                        }
                        if(Boolean.getBoolean("sulkan.qaAlphaTraversal")) {
                            // A nearby wall provides enough texel interiors to test both alpha outcomes
                            // without reducing the coverage threshold or relaxing edge tolerances.
                            // z>=24 is outside the mirror fixture's outgoing ray paths.
                            server.getCommands().performPrefixedCommand(source,"fill 18 102 24 21 105 24 minecraft:oak_leaves[persistent=true]");
                            if(!server.overworld().getBlockState(new BlockPos(20,104,24)).is(Blocks.OAK_LEAVES))
                                throw new IllegalStateException("Alpha fixture commands failed");
                        }
                        if (Boolean.getBoolean("sulkan.qaReflections")) {
                            server.getCommands().performPrefixedCommand(source, "fill 12 95 12 22 95 22 minecraft:iron_block");
                            server.getCommands().performPrefixedCommand(source, "fill 12 112 10 19 112 17 minecraft:glowstone");
                            if(!server.overworld().getBlockState(new BlockPos(21,95,21)).is(Blocks.IRON_BLOCK)
                                    || !server.overworld().getBlockState(new BlockPos(16,112,14)).is(Blocks.GLOWSTONE))
                                throw new IllegalStateException("Reflection fixture commands failed");
                        }
                        client.execute(() -> { stage = 2; ticks = 0; pending = false; });
                    } catch (Throwable failure) { client.execute(() -> fail(client, failure)); }
                });
            } else if (stage == 2 && ticks >= 100 && NativePackRuntime.renderedFrames() >= 100) {
                saveFrameCadence();
                var stats = TerrainGeometryCache.statistics();
                if (stats.failure() != null || stats.triangles() == 0 || !hasVertex(4, 97, 4)) throw new IllegalStateException("Fixture geometry was not captured: " + stats);
                long cutout = count(TerrainMesh.Opacity.CUTOUT);
                long translucent = count(TerrainMesh.Opacity.TRANSLUCENT);
                if (cutout == 0 || translucent == 0) throw new IllegalStateException("Cutout or translucent geometry missing from capture");
                LogUtils.getLogger().info("SULKAN_QA_GEOMETRY_PASS sections={} triangles={} bytes={} cutout={} translucent={}", stats.sections(), stats.triangles(), stats.ownedBytes(), cutout, translucent);
                if (Boolean.getBoolean("sulkan.qaRayVisibility")) {
                    if (NativePackRuntime.rayDispatches() < 40) throw new IllegalStateException("Ray visibility graph did not execute");
                    LogUtils.getLogger().info("SULKAN_QA_RAY_VISIBILITY_DISPATCHED count={}", NativePackRuntime.rayDispatches());
                }
                RayQueryConformance.verifyTerrainScene((VulkanDevice) ((GraphDeviceAccessor) RenderSystem.getDevice()).sulkan$backend(), false);
                Runnable finishNear = () -> capture(client, "world-baseline.png", () -> {
                    previousRevision = TerrainGeometryCache.statistics().revision();
                    pending = true;
                    server.execute(() -> {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "setblock 4 96 4 minecraft:air");
                        if(Boolean.getBoolean("sulkan.qaReflections"))
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "fill 12 112 10 19 112 17 minecraft:air");
                        client.execute(() -> {
                            if(Boolean.getBoolean("sulkan.qaIndirectTemporal")) {
                                temporalWatchedEpoch=sceneEpoch(NativePackRuntime.diagnosticFrame());temporalSceneResetChecked=false;temporalSceneWatch=true;
                            }
                            stage = 3; ticks = 0; pending = false;
                        });
                    });
                });
                Runnable nextFixture=Boolean.getBoolean("sulkan.qaRayOrigin") ? () -> beginFarFixture(client,finishNear) : finishNear;
                Runnable captureReady=Boolean.getBoolean("sulkan.qaAtmosphere") ? () -> beginAtmosphereSunset(client,nextFixture) : nextFixture;
                if (Boolean.getBoolean("sulkan.qaRayLighting")) {
                    pending = true;
                    Runnable ready = captureReady;
                    if (Boolean.getBoolean("sulkan.qaTemporalInputs")) {
                        afterTemporalCapture=captureReady;
                        if(Boolean.getBoolean("sulkan.qaWorldDlss")) {
                            afterWorldDlssCapture=captureReady;
                            afterTemporalCapture=() -> {
                                pending=true;
                                WorldDlssConformance.capture().whenComplete((result,failure) -> client.execute(() -> {
                                    if(failure!=null) { fail(client,failure); return; }
                                    try {
                                        Files.writeString(evidence.resolve("world-dlss.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result));
                                        LogUtils.getLogger().info("SULKAN_QA_WORLD_DLSS_PASS {}",result);
                                        nextTickAction=() -> {
                                            client.getWindow().setWindowed(1920,1080);
                                            stage=8; ticks=0; pending=false;
                                        };
                                    } catch(Throwable problem) { fail(client,problem); }
                                }));
                            };
                        }
                        ready=() -> {
                            if(!Boolean.getBoolean("sulkan.temporalJitter")) { stage=7; ticks=0; pending=false; return; }
                            var stationary=CameraMotionConformance.capture(0);
                            if(stationary==null) throw new IllegalStateException("Jitter-only check requires a stationary valid frame");
                            stationary.whenComplete((result,failure) -> client.execute(() -> {
                                if(failure!=null) { fail(client,failure); return; }
                                try {
                                    Files.writeString(evidence.resolve("camera-jitter-only.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result));
                                    LogUtils.getLogger().info("SULKAN_QA_JITTER_ONLY_PASS {}",result);
                                    stage=7; ticks=0; pending=false;
                                } catch(Throwable problem) { fail(client,problem); }
                            }));
                        };
                    }
                    if (Boolean.getBoolean("sulkan.qaRayHitMaterials")) {
                        afterMaterialReloadCapture = captureReady;
                        ready = () -> {
                            try { Files.copy(evidence.resolve("deferred-lighting.json"), evidence.resolve("deferred-lighting-before-atlas-reload.json")); }
                            catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
                            ShaderRuntime.applyConfig(client, ShaderRuntime.config(), true).whenComplete((ignored, failure) -> client.execute(() -> {
                                if (failure != null) { fail(client, failure); return; }
                                afterReloadFrames = NativePackRuntime.renderedFrames();
                                stage = 6; ticks = 0; pending = false;
                            }));
                        };
                    }
                    Runnable afterLighting=ready;
                    Runnable verifyLighting=() -> {
                        if(Boolean.getBoolean("sulkan.qaReflections"))
                            ReflectionConformance.verify(client,evidence,false,afterLighting,failure -> fail(client,failure));
                        else DeferredLightingConformance.verify(client,evidence,afterLighting,failure -> fail(client,failure));
                    };
                    Runnable verifyModel=Boolean.getBoolean("sulkan.qaAtmosphere")
                        ? () -> AtmosphereConformance.verify(client,evidence,"day",() -> {
                            lightingJitters.clear();
                            afterAtmosphericLighting=Boolean.getBoolean("sulkan.qaReflections")
                                ? () -> ReflectionConformance.verify(client,evidence,false,afterLighting,failure -> fail(client,failure)) : afterLighting;
                            stage=16;ticks=0;pending=false;
                        },failure -> fail(client,failure))
                        : verifyLighting;
                    Runnable checkedModel=Boolean.getBoolean("sulkan.qaFoliage")
                        ? () -> nextRenderedFrameAction=() -> FoliageConformance.verify(client,evidence,
                            () -> nextRenderedFrameAction=verifyModel,failure -> fail(client,failure)) : verifyModel;
                    Runnable exposureCheckedModel=Boolean.getBoolean("sulkan.qaAutoExposure")
                        ? () -> nextRenderedFrameAction=() -> ExposureConformance.verify(client,evidence,"steady",
                            () -> nextRenderedFrameAction=checkedModel,failure -> fail(client,failure)) : checkedModel;
                    Runnable waterCheckedModel=Boolean.getBoolean("sulkan.qaWaterHdr")
                        ? () -> nextRenderedFrameAction=() -> WaterConformance.verify(client,evidence,
                            () -> nextRenderedFrameAction=exposureCheckedModel,failure -> fail(client,failure)) : exposureCheckedModel;
                    Runnable spatialCheckedModel=Boolean.getBoolean("sulkan.qaIndirectSpatial")
                        ? () -> nextRenderedFrameAction=() -> IndirectSpatialConformance.verify(client,evidence,
                            () -> nextRenderedFrameAction=waterCheckedModel,failure -> fail(client,failure)) : waterCheckedModel;
                    Runnable indirectCheckedModel=Boolean.getBoolean("sulkan.qaIndirectDiagnostic")
                        ? () -> nextRenderedFrameAction=() -> IndirectConformance.verify(client,evidence,
                            () -> {
                                if(Boolean.getBoolean("sulkan.qaIndirectTemporal")) afterStableIndirectFrames(0,-1,() ->
                                    IndirectTemporalConformance.verify(client,evidence,"steady",() -> nextRenderedFrameAction=spatialCheckedModel,failure -> fail(client,failure)));
                                else nextRenderedFrameAction=spatialCheckedModel;
                            },failure -> fail(client,failure)) : spatialCheckedModel;
                    if(Boolean.getBoolean("sulkan.qaAlphaTraversal"))
                        AlphaTraversalConformance.verify(client,evidence,indirectCheckedModel,failure -> fail(client,failure));
                    else indirectCheckedModel.run();
                } else captureReady.run();
            } else if (stage == 6 && ticks >= 100 && NativePackRuntime.renderedFrames() >= afterReloadFrames + 40) {
                pending = true;
                DeferredLightingConformance.verify(client, evidence, () -> {
                    LogUtils.getLogger().info("SULKAN_QA_RAY_ATLAS_RELOAD_PASS frames={} selectedPack={}", NativePackRuntime.renderedFrames(), ShaderRuntime.config().selectedPackId());
                    afterMaterialReloadCapture.run();
                }, failure -> fail(client, failure));
            } else if (stage == 7) {
                if (ticks%4==0) {
                    if(Boolean.getBoolean("sulkan.qaMotionPhaseSweep")) {
                        if(motionTargetX>=25.25)motionSweepDirection=-1;
                        else if(motionTargetX<=24)motionSweepDirection=1;
                        motionTargetX+=motionSweepDirection*.0625;
                    }else motionTargetX += positiveMotion ? -.0625 : .0625;
                    double x=motionTargetX;
                    server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                            "tp @p "+x+" 104 25 135 22"));
                }
            } else if(stage==30 || stage==31) {
                if(ticks%4==0) {
                    skyMotionYaw+=stage==30?.5:-.5;
                    double yaw=skyMotionYaw;
                    server.execute(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                        "tp @p "+motionTargetX+" 104 25 "+yaw+" 22"));
                }
            } else if(stage==32 && ticks>=10) {
                pending=true;afterTemporalCapture.run();
            } else if(stage==8 && ticks>=80 && NativePackRuntime.reconstructionEvaluations()>=40) {
                pending=true;
                WorldDlssConformance.capture().whenComplete((result,failure) -> client.execute(() -> {
                    if(failure!=null) { fail(client,failure); return; }
                    try {
                        if(((Number)result.get("outputWidth")).intValue()!=1920 || ((Number)result.get("outputHeight")).intValue()!=1080)
                            throw new IllegalStateException("DLSS generation did not follow the resized output");
                        Files.writeString(evidence.resolve("world-dlss-resized.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result));
                        LogUtils.getLogger().info("SULKAN_QA_WORLD_DLSS_RESIZE_PASS {}",result);
                        afterWorldDlssCapture.run();
                    } catch(Throwable problem) { fail(client,problem); }
                }));
            } else if(stage==12 && ticks>=80) {
                pending=true;
                capture(client,"world-atmosphere-sunset-matched.png",() -> {
                    var frame=NativePackRuntime.diagnosticFrame();
                    double yaw=Math.toDegrees(Math.atan2(-frame.getFloat(448),frame.getFloat(456)));
                    server.execute(() -> {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                            String.format(java.util.Locale.ROOT,"tp @p 24 104 25 %.5f 8",yaw));
                        client.execute(() -> { stage=14;ticks=0;pending=false; });
                    });
                });
            } else if(stage==14 && ticks>=80) {
                pending=true;
                AtmosphereConformance.verify(client,evidence,"sunset",() -> capture(client,"world-atmosphere-sunset-sun.png",() ->
                    server.execute(() -> {
                        var source=server.createCommandSourceStack().withSuppressedOutput();
                        server.getCommands().performPrefixedCommand(source,"time set 3000");
                        server.getCommands().performPrefixedCommand(source,"tp @p 24 104 25 135 22");
                        client.execute(() -> { stage=13;ticks=0;pending=false; });
                    })),failure -> fail(client,failure));
            } else if(stage==13 && ticks>=80) {
                pending=true;afterAtmosphereValidation.run();
            } else if(stage==9 && ticks>=100) {
                pending=true;
                var frame=NativePackRuntime.diagnosticFrame();
                if(frame.getFloat(1088)!=FAR_X || frame.getFloat(1092)!=0 || frame.getFloat(1096)!=FAR_Z
                    || Math.abs(frame.getFloat(1104)-24.0625)>.001 || Math.abs(frame.getFloat(1112)-25.03125)>.001)
                    throw new IllegalStateException("Far-world frame and TLAS origin did not follow the camera");
                ReflectionConformance.verify(client,evidence,false,new ReflectionConformance.Fixture(FAR_X,FAR_Z,"far-reflection"),() -> {
                    Runnable remove=() -> capture(client,"world-far-reflection.png",() -> {
                        server.execute(() -> {
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                                "fill "+(FAR_X+12)+" 112 "+(FAR_Z+10)+" "+(FAR_X+19)+" 112 "+(FAR_Z+17)+" minecraft:air");
                            client.execute(() -> { stage=10;ticks=0;pending=false; });
                        });
                    });
                    if(Boolean.getBoolean("sulkan.qaWorldDlss")) WorldDlssConformance.capture().whenComplete((result,error) -> client.execute(() -> {
                        if(error!=null) { fail(client,error);return; }
                        try {
                            Files.writeString(evidence.resolve("world-dlss-far.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result));
                            LogUtils.getLogger().info("SULKAN_QA_FAR_WORLD_DLSS_PASS {}",result);remove.run();
                        } catch(Throwable problem) { fail(client,problem); }
                    })); else remove.run();
                },failure -> fail(client,failure));
            } else if(stage==10 && ticks>=80) {
                pending=true;
                ReflectionConformance.verify(client,evidence,true,new ReflectionConformance.Fixture(FAR_X,FAR_Z,"far-reflection"),() ->
                    capture(client,"world-far-reflection-removed.png",() -> server.execute(() -> {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"tp @p 24 104 25 135 22");
                        client.execute(() -> { stage=11;ticks=0;pending=false; });
                    })),failure -> fail(client,failure));
            } else if(stage==11 && ticks>=100) {
                var frame=NativePackRuntime.diagnosticFrame();
                if(frame.getFloat(1088)!=0 || frame.getFloat(1092)!=0 || frame.getFloat(1096)!=0 || !hasVertex(4,97,4))
                    throw new IllegalStateException("Near-world scene did not recover after far teleport");
                pending=true;
                LogUtils.getLogger().info("SULKAN_QA_RAY_ORIGIN_RETURN_PASS");
                afterFarValidation.run();
            } else if (stage == 3 && ticks >= 80) {
                if(Boolean.getBoolean("sulkan.qaIndirectTemporal") && !temporalSceneResetChecked) return;
                finishGeometryStage(client,server);
            } else if (stage == 4 && ticks >= 80 && NativePackRuntime.renderedFrames() >= afterReloadFrames + 40) {
                LogUtils.getLogger().info("SULKAN_QA_RESOURCE_RELOAD_PASS frames={}", NativePackRuntime.renderedFrames());
                client.getWindow().setWindowed(1024, 576);
                stage = 5; ticks = 0;
            } else if (stage == 5 && ticks >= 80) {
                long liveRayBuffers = com.sulkan.shaders.platform.vulkan.NativeBuffer.liveBufferCount();
                if (liveRayBuffers != 0) throw new IllegalStateException("Deferred ray scene retirement leaked " + liveRayBuffers + " native buffers");
                LogUtils.getLogger().info("SULKAN_QA_SCENE_RETIRE_PASS liveBuffers={}", liveRayBuffers);
                var target = client.gameRenderer.mainRenderTarget();
                if (target.width != 1024 || target.height != 576) throw new IllegalStateException("Resize did not reach main render target: " + target.width + "x" + target.height);
                LogUtils.getLogger().info("SULKAN_QA_RESIZE_PASS extent={}x{}", target.width, target.height);
                capture(client, "world-after-reload-resize.png", () -> {
                    LogUtils.getLogger().info("SULKAN_QA_WORLD_PASS frames={} geometryRevision={}", NativePackRuntime.renderedFrames(), TerrainGeometryCache.statistics().revision());
                    stopped = true;
                    client.stop();
                });
            }
        } catch (Throwable failure) { fail(client, failure); }
    }

    private static void beginFarFixture(Minecraft client,Runnable after) {
        pending=true;afterFarValidation=after;
        // Resize only at a tick boundary, not within an asynchronous render readback callback.
        nextTickAction=() -> {
            if(Boolean.getBoolean("sulkan.qaWorldDlss")) client.getWindow().setWindowed(2560,1440);
            var server=client.getSingleplayerServer();
            server.execute(() -> {
                try {
                    var source=server.createCommandSourceStack().withSuppressedOutput();
                    for(String command:new String[]{
                        "forceload add "+FAR_X+" "+FAR_Z+" "+(FAR_X+31)+" "+(FAR_Z+31),
                        "fill "+FAR_X+" 94 "+FAR_Z+" "+(FAR_X+23)+" 95 "+(FAR_Z+23)+" minecraft:smooth_quartz",
                        "fill "+(FAR_X+12)+" 95 "+(FAR_Z+12)+" "+(FAR_X+22)+" 95 "+(FAR_Z+22)+" minecraft:iron_block",
                        "fill "+(FAR_X+12)+" 112 "+(FAR_Z+10)+" "+(FAR_X+19)+" 112 "+(FAR_Z+17)+" minecraft:glowstone",
                        String.format(java.util.Locale.ROOT,"tp @p %.4f 104 %.5f 135 22",FAR_X+24.0625,FAR_Z+25.03125)}) server.getCommands().performPrefixedCommand(source,command);
                    if(!server.overworld().getBlockState(new BlockPos(FAR_X+21,95,FAR_Z+21)).is(Blocks.IRON_BLOCK)
                        || !server.overworld().getBlockState(new BlockPos(FAR_X+16,112,FAR_Z+14)).is(Blocks.GLOWSTONE))
                        throw new IllegalStateException("Far fixture commands failed");
                    client.execute(() -> { stage=9;ticks=0;pending=false; });
                } catch(Throwable problem) { client.execute(() -> fail(client,problem)); }
            });
        };
    }

    private static void beginAtmosphereSunset(Minecraft client,Runnable after) {
        pending=true;afterAtmosphereValidation=after;
        capture(client,"world-atmosphere-day.png",() -> client.getSingleplayerServer().execute(() -> {
            var server=client.getSingleplayerServer();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"time set 12600");
            client.execute(() -> { stage=12;ticks=0;pending=false; });
        }));
    }

    private static void captureCameraMotion(Minecraft client) {
                boolean sweep=Boolean.getBoolean("sulkan.qaMotionPhaseSweep");
                var seen=motionSweepPhases.computeIfAbsent(positiveMotion,unused -> new java.util.HashSet<>());
                if(sweep) {
                    var frame=NativePackRuntime.diagnosticFrame();
                    String jitter=Float.toHexString(frame.getFloat(1072))+","+Float.toHexString(frame.getFloat(1076));
                    if(seen.contains(jitter))return;
                }
                var motion=CameraMotionConformance.capture(positiveMotion ? -1 : 1);
                if(motion!=null) {
                    pending=true;
                    motion.whenComplete((result,failure) -> client.execute(() -> {
                        if(failure!=null) { fail(client,failure); return; }
                        try {
                            if(sweep) {
                                var jitter=(java.util.List<?>)result.get("jitterPixels");
                                seen.add(Float.toHexString(((Number)jitter.get(0)).floatValue())+","+Float.toHexString(((Number)jitter.get(1)).floatValue()));
                                motionSweepReports.computeIfAbsent(positiveMotion,unused -> new java.util.ArrayList<>()).add(result);
                                Files.writeString(evidence.resolve(positiveMotion?"camera-motion-sweep-negative.jsonl":"camera-motion-sweep-positive.jsonl"),
                                    new com.google.gson.Gson().toJson(result)+"\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);
                                if(seen.size()<32){pending=false;return;}
                                Files.writeString(evidence.resolve(positiveMotion?"camera-motion-sweep-negative.json":"camera-motion-sweep-positive.json"),
                                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(motionSweepReports.get(positiveMotion)));
                                LogUtils.getLogger().info("SULKAN_QA_CAMERA_SWEEP_PASS sign={} phases=32",positiveMotion?-1:1);
                            }
                            Files.writeString(evidence.resolve(positiveMotion ? "camera-motion-negative.json" : "camera-motion-positive.json"),
                                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result));
                            if(!positiveMotion) { positiveMotion=true; ticks=0; pending=false; }
                            else {
                                if(Boolean.getBoolean("sulkan.qaIndirectTemporal") && !temporalMotionChecked) {pending=false;return;}
                                saveFrameCadence(motionFrameIntervalsMs,"motion-frame-cadence.json",
                                        "QA camera teleports plus asynchronous readback and CPU validation; configured cap and diagnostic overhead, not completed cinematic performance");
                                LogUtils.getLogger().info("SULKAN_QA_CAMERA_MOTION_PASS twoDirections=true {}",result);
                                if(Boolean.getBoolean("sulkan.qaSkyMotion")){stage=30;ticks=0;skyMotionYaw=135;pending=false;}
                                else afterTemporalCapture.run();
                            }
                        } catch(Throwable problem) { fail(client,problem); }
                    }));
                }
    }
    private static void finishGeometryStage(Minecraft client,net.minecraft.server.MinecraftServer server) throws Exception {
                if (TerrainGeometryCache.statistics().revision() <= previousRevision || hasVertex(4, 97, 4)) {
                    throw new IllegalStateException("Removed block is still present in captured terrain geometry");
                }
                LogUtils.getLogger().info("SULKAN_QA_GEOMETRY_REBUILD_PASS revision={}", TerrainGeometryCache.statistics().revision());
                RayQueryConformance.verifyTerrainScene((VulkanDevice) ((GraphDeviceAccessor) RenderSystem.getDevice()).sulkan$backend(), true);
                pending = true;
                if(Boolean.getBoolean("sulkan.qaReflections")) {
                    Runnable verifyReflections=() -> ReflectionConformance.verify(client,evidence,true,() -> {
                        try { capture(client,"world-reflection-removed.png",() -> reloadAfterGeometry(client)); }
                        catch(Throwable failure) { fail(client,failure); }
                    },failure -> fail(client,failure));
                    if(Boolean.getBoolean("sulkan.qaIndirectDiagnostic"))
                        nextRenderedFrameAction=() -> IndirectConformance.verify(client,evidence,true,
                            () -> nextRenderedFrameAction=verifyReflections,failure -> fail(client,failure));
                    else verifyReflections.run();
                } else reloadAfterGeometry(client);
    }

    private static void reloadAfterGeometry(Minecraft client) {
                var reloadConfig = Boolean.getBoolean("sulkan.qaRayVisibility")
                        ? ShaderRuntime.config().withSelection(true, "__builtin__") : ShaderRuntime.config();
                ShaderRuntime.applyConfig(client, reloadConfig, true).whenComplete((ignored, failure) -> client.execute(() -> {
                    if (failure != null) { fail(client, failure); return; }
                    afterReloadFrames = NativePackRuntime.renderedFrames();
                    stage = 4; ticks = 0; pending = false;
                }));
    }

    private static long count(TerrainMesh.Opacity opacity) {
        return TerrainGeometryCache.snapshot().stream().flatMap(section -> section.meshes().stream())
                .filter(mesh -> mesh.opacity() == opacity).mapToLong(TerrainMesh::triangleCount).sum();
    }

    private static boolean hasVertex(float x, float y, float z) {
        for (var section : TerrainGeometryCache.snapshot()) for (var mesh : section.meshes()) {
            var positions = mesh.positions();
            for (int i = 0; i < mesh.vertexCount(); i++) {
                if (Math.abs(section.x() + positions.get(i * 3) - x) < .0001f
                        && Math.abs(section.y() + positions.get(i * 3 + 1) - y) < .0001f
                        && Math.abs(section.z() + positions.get(i * 3 + 2) - z) < .0001f) return true;
            }
        }
        return false;
    }

    private static void capture(Minecraft client, String name, Runnable next) {
        pending = true;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(evidence.resolve(name));
                // The next operation owns the pending state. Clearing it here races
                // asynchronous server transitions and can repeat/overwrite captures.
                next.run();
            } catch (Throwable failure) { fail(client, failure); }
        });
    }

    private static void fail(Minecraft client, Throwable failure) {
        LogUtils.getLogger().error("SULKAN_QA_WORLD_FAIL stage={}", stage, failure);
        stopped = true;
        pending = false;
        client.stop();
    }
}
