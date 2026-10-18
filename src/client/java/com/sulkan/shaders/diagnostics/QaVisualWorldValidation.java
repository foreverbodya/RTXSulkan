package com.sulkan.shaders.diagnostics;

import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.NativePackRuntime;
import com.sulkan.shaders.render.raytracing.TerrainGeometryCache;
import com.sulkan.shaders.runtime.ShaderRuntime;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Guarded natural-world capture. Successful execution is not visual acceptance. */
final class QaVisualWorldValidation {
    private static final String SAVE="sulkan-visual-qa", NAME="Sulkan Natural Visual QA";
    private static final long SEED=672026L, SETTLE_NS=20_000_000_000L, MEASURE_NS=20_000_000_000L;
    private static final ArrayList<Double> intervals=new ArrayList<>();
    private static final Map<String,Long> throttle=new java.util.TreeMap<>();
    private static final Map<String,Object> scene=new LinkedHashMap<>();
    private static Path evidence;
    private static int stage;
    private static QaRoughNoiseSequence roughNoise;
    private static QaPerformanceProfile performanceProfile;
    private static boolean pending,stopped;
    private static long started,phaseStart,previousFrame;
    private static String phase=Boolean.getBoolean("sulkan.qaNightOnly")?"night":"day";
    private static long measuredGeometryRevision;
    private static com.sulkan.shaders.render.raytracing.TerrainRayScene.Statistics measuredRayScene;
    private static double[] fixedPose;
    private static double maxEyeError,maxDirectionError,maxProjectionDirectionOffset;
    private static Map<String,Object> lastActualPose;
    private static final boolean BOUNCE_COMPARISON=Boolean.getBoolean("sulkan.qaBounceComparison");
    private static final boolean ATMOSPHERE_COMPARISON=Boolean.getBoolean("sulkan.qaAtmosphereComparison");
    private static final boolean SAMPLING_COMPARISON=Boolean.getBoolean("sulkan.qaSamplingComparison");
    private static final boolean CLOUD_AMBIENT_COMPARISON=Boolean.getBoolean("sulkan.qaCloudAmbientComparison");
    private static final boolean CLOUD_DENSITY_COMPARISON=Boolean.getBoolean("sulkan.qaCloudDensityComparison");
    private static final boolean CLOUD_COLUMN_COMPARISON=Boolean.getBoolean("sulkan.qaCloudColumnComparison");
    private static final boolean LEAF_OPTICS_COMPARISON=Boolean.getBoolean("sulkan.qaLeafOpticsComparison");
    private static final boolean HORIZON_COMPARISON=Boolean.getBoolean("sulkan.qaHorizonComparison");
    private static final boolean SOLID_SHADOW_COMPARISON=Boolean.getBoolean("sulkan.qaSolidShadowComparison");
    private static final boolean CLOUD_LIGHTING_COMPARISON=Boolean.getBoolean("sulkan.qaCloudLightingComparison");
    private static final boolean DISTANT_CLOUD_COMPARISON=Boolean.getBoolean("sulkan.qaDistantCloudComparison");
    private static final boolean CLOUD_DEPTH_COMPARISON=Boolean.getBoolean("sulkan.qaCloudDepthComparison");
    private static final boolean LEAF_BOUNDARY_COMPARISON=Boolean.getBoolean("sulkan.qaLeafBoundaryComparison");
    private enum ComparisonMode {
        ROUGH_DENOISE("ROUGH_DENOISE","rough-denoise","rough-denoise","ROUGH_DENOISE",0,1),
        EMISSIVE("EMISSIVE_SAMPLING","emissive","emissive","EMISSIVE",0,1),
        CLOUD_SOURCE("CLOUD_SOURCE_CACHE","cloud-source","cloud-source","CLOUD_SOURCE",0,1),
        ROUGH_REFLECTION("ROUGH_SPECULAR","rough-specular","rough-specular","ROUGH_SPECULAR",0,1),
        LEAF_SURFACE("FOLIAGE_SURFACE","leaf-surface","leaf-surface","LEAF_SURFACE",0,1),
        SUN_DISK("SUN_SHADOW_SAMPLES","sun-disk","sun-disk","SUN_DISK",1,8),
        SUN_INDIRECT("SUN_INDIRECT_SAMPLES","sun-indirect","sun-indirect","SUN_INDIRECT",8,1),
        BOUNCE("INDIRECT_BOUNCES","bounces","bounce","BOUNCE",1,2),
        ATMOSPHERE("ATM_MULTIPLE_SCATTERING","multiple","atmosphere","ATMOSPHERE",0,1),
        SAMPLING("INDIRECT_SAMPLES","samples","sampling","SAMPLING",1,16),
        CLOUD_AMBIENT("CLOUD_AMBIENT_OCCLUSION","ambient","cloud-ambient","CLOUD_AMBIENT",0,1),
        CLOUD_DENSITY("CLOUD_DENSITY_PROFILE","density","cloud-density","CLOUD_DENSITY",0,1),
        CLOUD_COLUMN("CLOUD_VIEW_DENSITY_SUBSTEPS","column","cloud-column","CLOUD_COLUMN",0,1),
        LEAF_OPTICS("FOLIAGE_OPTICS","optics","leaf-optics","LEAF_OPTICS",0,Integer.getInteger("sulkan.qaComparisonFoliageOptics",1)),
        HORIZON("DISTANT_WORLD_BLEND","horizon","horizon","HORIZON",0,1),
        SOLID_SHADOW("SULKAN_SOLID_SHADOW_PREFLIGHT","solid-shadow","solid-shadow","SOLID_SHADOW",0,1),
        CLOUD_LIGHTING("CLOUD_LIGHTING_VOLUME","cloud-lighting","cloud-lighting","CLOUD_LIGHTING",0,1),
        LEAF_BOUNDARY("LEAF_RADIANCE_BOUNDARY","leaf-boundary","leaf-boundary","LEAF_BOUNDARY",0,1),
        CLOUD_DEPTH("CLOUD_DEPTH_AWARE","cloud-depth","cloud-depth","CLOUD_DEPTH",0,1),
        DISTANT_CLOUD("DISTANT_CLOUD_TARGET_SCREEN","cloud-target","distant-cloud-target","DISTANT_CLOUD_TARGET",0,1);
        final String option,label,file,marker;final int low,high;
        ComparisonMode(String option,String label,String file,String marker,int low,int high) {
            this.option=option;this.label=label;this.file=file+"-comparison.json";
            this.marker="SULKAN_QA_"+marker+"_COMPARISON_PASS trials="+(Boolean.getBoolean("sulkan.qaNightOnly")?3:6)+" geometryMatched=true";this.low=low;this.high=high;
        }
    }
    private static ComparisonMode mode() {
        if(Boolean.getBoolean("sulkan.qaRoughDenoiseComparison"))return ComparisonMode.ROUGH_DENOISE;
        if(Boolean.getBoolean("sulkan.qaEmissiveComparison"))return ComparisonMode.EMISSIVE;
        if(Boolean.getBoolean("sulkan.qaCloudSourceComparison"))return ComparisonMode.CLOUD_SOURCE;
        if(Boolean.getBoolean("sulkan.qaRoughReflectionComparison"))return ComparisonMode.ROUGH_REFLECTION;
        if(Boolean.getBoolean("sulkan.qaLeafSurfaceComparison"))return ComparisonMode.LEAF_SURFACE;
        if(Boolean.getBoolean("sulkan.qaSunIndirectComparison"))return ComparisonMode.SUN_INDIRECT;
        if(Boolean.getBoolean("sulkan.qaSunDiskComparison"))return ComparisonMode.SUN_DISK;
        if(LEAF_BOUNDARY_COMPARISON)return ComparisonMode.LEAF_BOUNDARY;
        if(CLOUD_DEPTH_COMPARISON)return ComparisonMode.CLOUD_DEPTH;
        if(DISTANT_CLOUD_COMPARISON)return ComparisonMode.DISTANT_CLOUD;
        if(CLOUD_LIGHTING_COMPARISON)return ComparisonMode.CLOUD_LIGHTING;
        if(SOLID_SHADOW_COMPARISON)return ComparisonMode.SOLID_SHADOW;
        if(HORIZON_COMPARISON)return ComparisonMode.HORIZON;
        if(LEAF_OPTICS_COMPARISON)return ComparisonMode.LEAF_OPTICS;
        if(CLOUD_COLUMN_COMPARISON)return ComparisonMode.CLOUD_COLUMN;
        if(CLOUD_DENSITY_COMPARISON)return ComparisonMode.CLOUD_DENSITY;
        if(CLOUD_AMBIENT_COMPARISON)return ComparisonMode.CLOUD_AMBIENT;
        if(SAMPLING_COMPARISON)return ComparisonMode.SAMPLING;
        if(ATMOSPHERE_COMPARISON)return ComparisonMode.ATMOSPHERE;
        return BOUNCE_COMPARISON?ComparisonMode.BOUNCE:null;
    }
    private static final ComparisonMode COMPARISON_MODE=mode();
    private static final boolean COMPARISON=COMPARISON_MODE!=null;
    private static final boolean LIGHTING_AUDIT=Boolean.getBoolean("sulkan.qaLightingAudit");
    private static final boolean CLOUD_PREVIEW=Boolean.getBoolean("sulkan.qaCloudPreview");
    private static final boolean CLOUD_CAMERA_AUDIT=Boolean.getBoolean("sulkan.qaCloudCameraAudit");
    private static final boolean CLOUD_MOTION_AUDIT=Boolean.getBoolean("sulkan.qaCloudMotionAudit");
    private static CloudCameraMotionAudit cloudMotion;
    private static double[] cloudCameraReference;
    private static final boolean DRAW_DISTANCE_32=Boolean.getBoolean("sulkan.qaDrawDistance32");
    private static final boolean INTERIOR=Boolean.getBoolean("sulkan.qaInteriorVisual");
    private static final boolean FROZEN_WORLD=Boolean.getBoolean("sulkan.qaPerformanceProfile") || COMPARISON || LIGHTING_AUDIT || CLOUD_PREVIEW || DRAW_DISTANCE_32 || INTERIOR;
    private static String beforeDistanceReloadGeometry;
    private static float cameraPitch(){
        String requested=System.getProperty("sulkan.qaVisualPitch");
        if(requested==null)return CLOUD_PREVIEW?-10:15;
        float pitch=Float.parseFloat(requested);
        if(!Float.isFinite(pitch) || Math.abs(pitch)>80)throw new IllegalArgumentException("QA camera pitch outside [-80,80]");
        return pitch;
    }
    private static int comparisonTrial;
    private static long settleRevision=-1,stableSince;
    private static String measuredGeometryHash;
    private static final Map<String,String> referenceGeometry=new LinkedHashMap<>();
    private static final List<Object> comparisonReports=new ArrayList<>();
    private static int bounceCount(){return BOUNCE_COMPARISON?(comparisonTrial==1?
        Integer.getInteger("sulkan.qaComparisonHighBounces",2):Integer.getInteger("sulkan.qaComparisonLowBounces",1)):
        Integer.getInteger("sulkan.qaIndirectBounces",1);}
    private static String comparisonOption(){return COMPARISON_MODE.option;}
    private static int comparisonValue(){return BOUNCE_COMPARISON?bounceCount():comparisonTrial==1?COMPARISON_MODE.high:COMPARISON_MODE.low;}
    private static String captureName(){return COMPARISON?phase+"-"+COMPARISON_MODE.label+"-"+comparisonValue()+"-trial-"+(comparisonTrial+1):phase;}

    private static long cameraBobFrame;
    static void prepareCameraFrame() {
        var client=Minecraft.getInstance();
        if(stopped || stage<1 || client.getSingleplayerServer()==null
            || !NAME.equals(client.getSingleplayerServer().getWorldData().getLevelName()))return;
        if(!client.options.bobView().get())throw new IllegalStateException("Camera bob audit requires Minecraft bobView enabled");
        if(stage==3 && !pending)cameraBobFrame++;
        var entity=client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.entityRenderState;
        if(!entity.isPlayer)throw new IllegalStateException("Camera bob audit requires the player camera");
        // Feed actual Minecraft bobView(), before it composes the world projection.
        // This is a controlled 64-frame animation phase, not simulated walking input.
        entity.backwardsInterpolatedWalkDistance=(cameraBobFrame%64)/32.0f;
        entity.bob=.05f;
    }

    static void tick(Minecraft client) {
        if(stopped) return;
        try {
            if(started!=0 && System.nanoTime()-started>Long.getLong("sulkan.qaTimeoutSeconds",600L)*1_000_000_000L)
                throw new IllegalStateException("Natural visual world timed out at stage "+stage);
            if(NativePackRuntime.failure()!=null) throw new IllegalStateException(NativePackRuntime.failure());
            if(fixedPose!=null && client.player!=null && client.getSingleplayerServer()!=null
                && NAME.equals(client.getSingleplayerServer().getWorldData().getLevelName())) {
                // The directory/world identity was verified before this controller activates.
                client.mouseHandler.releaseMouse();
                for(var key:List.of(client.options.keyUp,client.options.keyDown,client.options.keyLeft,client.options.keyRight,
                    client.options.keyJump,client.options.keyShift,client.options.keySprint)) key.setDown(false);
                applyPose(client);
            }
            if(pending) return;
            if(stage==0) {
                if(client.gui.overlay()!=null || !(client.gui.screen() instanceof TitleScreen) || ShaderRuntime.resourceReloading()) return;
                if(!"RayLighting".equals(ShaderRuntime.config().selectedPackId()))throw new IllegalStateException("Natural visual QA expected RayLighting; active pack="+ShaderRuntime.config().selectedPackId());
                if(DRAW_DISTANCE_32 && (COMPARISON || CLOUD_CAMERA_AUDIT || CLOUD_MOTION_AUDIT || LIGHTING_AUDIT
                    || client.options.getEffectiveRenderDistance()!=16))throw new IllegalStateException("Draw-distance check requires initial distance 16 and no other comparison/path");
                var directory=Path.of(System.getProperty("sulkan.qaDirectory")).toRealPath();
                if(!directory.equals(client.gameDirectory.toPath().toRealPath()) ||
                    !Files.readString(directory.resolve("SULKAN_QA_DISPOSABLE")).trim().equals("sulkan-cinematic-disposable-v1"))
                    throw new IllegalStateException("Visual capture requires a marked disposable game directory");
                if(Files.exists(directory.resolve("saves").resolve(SAVE))) throw new IllegalStateException("Refusing to overwrite visual world");
                evidence=directory.resolve("evidence");Files.createDirectories(evidence);
                started=System.nanoTime();stage=1;
                client.getTutorial().setStep(TutorialSteps.NONE);
                client.createWorldOpenFlows().createFreshLevel(SAVE,
                    new LevelSettings(NAME,GameType.CREATIVE,new LevelSettings.DifficultySettings(Difficulty.PEACEFUL,false,false),true,WorldDataConfiguration.DEFAULT),
                    new WorldOptions(SEED,false,false),WorldPresets::createNormalWorldDimensions,client.gui.screen());
                return;
            }
            if(client.level==null || client.gui.overlay()!=null || ShaderRuntime.resourceReloading()) return;
            var server=client.getSingleplayerServer();
            if(server==null || !NAME.equals(server.getWorldData().getLevelName())) throw new IllegalStateException("Unexpected visual world");
            if(stage==1) {
                if(client.player==null) return;
                pending=true;
                server.execute(() -> {
                    try {
                        var world=server.overworld();
                        if(INTERIOR) {
                            if(!Boolean.getBoolean("sulkan.qaNightOnly"))throw new IllegalStateException("Interior requires a direct night capture");
                            var room=QaInteriorScene.create(server,cameraPitch());
                            client.execute(()->{scene.putAll(room.description());fixedPose=room.pose();stage=2;phaseStart=System.nanoTime();pending=false;});
                            return;
                        }
                        var forest=world.findClosestBiome3d(b -> b.is(Biomes.FOREST) || b.is(Biomes.BIRCH_FOREST),new BlockPos(0,64,0),4096,32,64);
                        if(forest==null) throw new IllegalStateException("Fixed seed has no nearby forest");
                        var river=world.findClosestBiome3d(b -> b.is(Biomes.RIVER),forest.getFirst(),512,16,64);
                        if(river==null) throw new IllegalStateException("Fixed forest has no nearby river");
                        var f=forest.getFirst();var r=river.getFirst();
                        LogUtils.getLogger().info("SULKAN_QA_VISUAL_LOCATED forest={} river={}",f,r);
                        double dx=f.getX()-r.getX(),dz=f.getZ()-r.getZ(),length=Math.hypot(dx,dz);
                        if(length<1) {dx=1;dz=0;length=1;}
                        dx/=length;dz/=length;
                        double x=r.getX()+.5-dx*28,z=r.getZ()+.5-dz*28;
                        // Level.getHeight returns minY for an unloaded chunk. Biome search
                        // samples noise only; explicitly generate before measuring terrain.
                        world.getChunk((int)Math.floor(x)>>4,(int)Math.floor(z)>>4);
                        int height=world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,(int)Math.floor(x),(int)Math.floor(z));
                        int cloudCameraHeight=Integer.getInteger("sulkan.qaCloudCameraHeight",0);
                        if(cloudCameraHeight<0 || cloudCameraHeight>384 || (cloudCameraHeight>0 && !CLOUD_PREVIEW))throw new IllegalArgumentException("Invalid QA cloud camera height");
                        double y=cloudCameraHeight>0?cloudCameraHeight:Math.max(74,height+10);
                        double yaw=Math.toDegrees(Math.atan2(-dx,dz));
                        List<Integer> cloudTree=List.of();
                        if(Boolean.getBoolean("sulkan.qaCloudInteriorTree")) {
                            if(cloudCameraHeight==0)throw new IllegalArgumentException("Interior reference tree requires explicit height");
                            int tx=(int)Math.floor(x-Math.sin(Math.toRadians(yaw))*18),tz=(int)Math.floor(z+Math.cos(Math.toRadians(yaw))*18),ty=(int)y;
                            world.getChunk(tx>>4,tz>>4);
                            for(int ly=-8;ly<=4;ly++)world.setBlock(new BlockPos(tx,ty+ly,tz),net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState(),3);
                            var leaves=net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT,true);
                            for(int ly=2;ly<=6;ly++) {
                                int radius=ly==6?2:3;
                                for(int ax=-radius;ax<=radius;ax++)for(int az=-radius;az<=radius;az++)
                                    if(ax*ax+az*az<=radius*radius+2 && !(ax==0 && az==0 && ly<=4))
                                        world.setBlock(new BlockPos(tx+ax,ty+ly,tz+az),leaves,3);
                            }
                            cloudTree=List.of(tx,ty,tz);
                        }
                        var source=server.createCommandSourceStack().withSuppressedOutput();
                        if(FROZEN_WORLD) {
                            server.getCommands().performPrefixedCommand(source,"gamerule minecraft:random_tick_speed 0");
                            server.getCommands().performPrefixedCommand(source,"tick freeze");
                            if(!server.tickRateManager().isFrozen())throw new IllegalStateException("Comparison server did not freeze");
                        }
                        for(var command:new String[]{"weather clear",Boolean.getBoolean("sulkan.qaPerformanceProfile")?"time set "+Integer.getInteger("sulkan.qaProfileTime",3000):Boolean.getBoolean("sulkan.qaNightOnly")?"time set 18000":"time set 3000","gamerule minecraft:advance_time false","gamemode spectator @p",
                            String.format(Locale.ROOT,"tp @p %.5f %.5f %.5f %.5f %.5f",x,y,z,yaw,(double)cameraPitch())})
                            server.getCommands().performPrefixedCommand(source,command);
                        int waterColumns=0;
                        for(int ax=-24;ax<=24;ax+=4) for(int az=-24;az<=24;az+=4) {
                            int bx=r.getX()+ax,bz=r.getZ()+az;
                            world.getChunk(bx>>4,bz>>4);
                            int top=world.getHeight(Heightmap.Types.WORLD_SURFACE,bx,bz)-1;
                            if(world.getFluidState(new BlockPos(bx,top,bz)).is(net.minecraft.tags.FluidTags.WATER)) waterColumns++;
                        }
                        if(waterColumns==0) throw new IllegalStateException("Located river contains no exposed water columns");
                        var description=new LinkedHashMap<String,Object>();
                        description.put("seed",SEED);description.put("generator","normal");
                        if(!cloudTree.isEmpty())description.put("elevatedReferenceTree",cloudTree);
                        description.put("bounceComparison",BOUNCE_COMPARISON);
                        description.put("atmosphereComparison",ATMOSPHERE_COMPARISON);
                        description.put("samplingComparison",SAMPLING_COMPARISON);
                        description.put("cloudAmbientComparison",CLOUD_AMBIENT_COMPARISON);
                        description.put("cloudDensityComparison",CLOUD_DENSITY_COMPARISON);
                        description.put("cloudColumnComparison",CLOUD_COLUMN_COMPARISON);
                        description.put("leafOpticsComparison",LEAF_OPTICS_COMPARISON);
                        description.put("horizonComparison",HORIZON_COMPARISON);
                        description.put("lightingAudit",LIGHTING_AUDIT);
                        description.put("forest",java.util.List.of(f.getX(),f.getY(),f.getZ()));
                        description.put("river",java.util.List.of(r.getX(),r.getY(),r.getZ()));
                        description.put("camera",java.util.List.of(x,y,z,yaw,cameraPitch()));description.put("exposedWaterColumns",waterColumns);
                        client.execute(() -> {scene.putAll(description);fixedPose=new double[]{x,y,z,yaw};stage=2;phaseStart=System.nanoTime();pending=false;});
                    } catch(Throwable problem) {client.execute(() -> fail(client,problem));}
                });
            } else if(stage==2 && System.nanoTime()-phaseStart>=SETTLE_NS && NativePackRuntime.renderedFrames()>100) {
                var stats=TerrainGeometryCache.statistics();
                if(stats.failure()!=null || stats.sections()<10 || stats.triangles()<10000) throw new IllegalStateException("Natural scene not populated: "+stats);
                var rays=NativePackRuntime.diagnosticRayScene();
                if(rays.pendingSections()!=0) return;
                if(DRAW_DISTANCE_32) {
                    var sodium=net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.instanceNullable();
                    if(sodium==null || !sodium.isTerrainRenderComplete())return;
                }
                var captured=TerrainGeometryCache.snapshot();
                long expectedSections=captured.stream().filter(section->!section.meshes().isEmpty()).count();
                long expectedMeshes=captured.stream().mapToLong(section->section.meshes().size()).sum();
                if(rays.sections()!=expectedSections || rays.meshes()!=expectedMeshes || rays.triangles()!=stats.triangles())
                    throw new IllegalStateException("Loaded ray scene does not cover all cached geometry: cache="+stats+" rays="+rays);
                if(FROZEN_WORLD) {
                    if(!server.tickRateManager().isFrozen())throw new IllegalStateException("Comparison server resumed ticking");
                    if(COMPARISON && !Double.valueOf(comparisonValue()).equals(NativePackRuntime.diagnosticOptions().get(comparisonOption())))throw new IllegalStateException("Active comparison option differs from requested trial");
                    if(settleRevision!=stats.revision()){settleRevision=stats.revision();stableSince=System.nanoTime();return;}
                    if(System.nanoTime()-stableSince<5_000_000_000L)return;
                    measuredGeometryHash=NaturalSceneFingerprint.capture(evidence.resolve(captureName()+"-source-geometry.json"));
                    var reference=referenceGeometry.putIfAbsent(LIGHTING_AUDIT?"audit":phase,measuredGeometryHash);
                    if(reference!=null && !reference.equals(measuredGeometryHash))throw new IllegalStateException("Comparison geometry changed between trials phase="+phase+" expected="+reference+" actual="+measuredGeometryHash);
                }
                if(Boolean.getBoolean("sulkan.qaPerformanceProfile")){performanceProfile=new QaPerformanceProfile(evidence,fixedPose,scene);stage=5;return;}
                measuredGeometryRevision=stats.revision();measuredRayScene=rays;
                roughNoise=Boolean.getBoolean("sulkan.qaRoughNoiseSequence")?new QaRoughNoiseSequence(evidence,captureName()):null;
                NativePackRuntime.diagnosticBeginGpuTimings(captureName(),evidence);
                intervals.clear();throttle.clear();previousFrame=0;maxEyeError=0;maxDirectionError=0;phaseStart=System.nanoTime();stage=3;
                if(phase.equals("camera-motion"))cloudMotion=new CloudCameraMotionAudit(evidence,fixedPose);
                LogUtils.getLogger().info("SULKAN_QA_VISUAL_MEASURE_BEGIN phase={} geometry={}",phase,stats);
            }
        } catch(Throwable problem) {fail(client,problem);}
    }

    static void frameRendered() {
        if(stopped || pending) return;
        if(stage==5){try{validatePose(Minecraft.getInstance());if(performanceProfile.observe()){stopped=true;Minecraft.getInstance().stop();}else{fixedPose=performanceProfile.pose();applyPose(Minecraft.getInstance());}}catch(Throwable problem){fail(Minecraft.getInstance(),problem);}return;}
        if(stage!=3)return;
        var client=Minecraft.getInstance();
        try {
            validatePose(client);
            boolean moving=phase.equals("camera-motion");
            boolean pathFinished=!moving || cloudMotion.observe();
            long now=System.nanoTime();
            if(previousFrame!=0) intervals.add((now-previousFrame)*1e-6);
            previousFrame=now;
            throttle.merge(client.getFramerateLimitTracker().getThrottleReason().name(),1L,Long::sum);
            if(!pathFinished || now-phaseStart<MEASURE_NS || intervals.size()<100 || !NativePackRuntime.diagnosticGpuTimingsReady()) {
                if(moving){fixedPose=cloudMotion.nextPose();applyPose(client);}
                return;
            }
            if(FROZEN_WORLD && !moving && (measuredGeometryRevision!=TerrainGeometryCache.statistics().revision() || NativePackRuntime.diagnosticRayScene().pendingSections()!=0))
                throw new IllegalStateException("Comparison geometry changed during timing");
            if(roughNoise!=null && !roughNoise.observe())return;
            pending=true;stage=4;
            var sorted=intervals.stream().sorted().toList();
            if(sorted.size()<100) throw new IllegalStateException("Too few natural-world frame samples: "+sorted.size());
            var report=new LinkedHashMap<String,Object>(scene);
            double mean=sorted.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            report.put("roughNoiseSequenceIncludesReadbacks",roughNoise!=null);
            report.put("phase",phase);report.put("time",phase.equals("night")?18000:phase.equals("day") || phase.startsWith("camera-")?3000:12600);
            if(CLOUD_CAMERA_AUDIT)report.put("requestedCamera",List.of(fixedPose[0],fixedPose[1],fixedPose[2],fixedPose[3],cameraPitch()));
            report.put("frames",sorted.size());report.put("meanFrameMs",mean);report.put("meanFPS",1000/mean);
            report.put("p50FrameMs",sorted.get(sorted.size()/2));report.put("p95FrameMs",sorted.get((int)((sorted.size()-1)*.95)));
            report.put("p99FrameMs",sorted.get((int)((sorted.size()-1)*.99)));report.put("maxFrameMs",sorted.getLast());
            report.put("measuredSeconds",(now-phaseStart)*1e-9);report.put("throttleReasons",Map.copyOf(throttle));
            report.put("maxFps",Integer.getInteger("sulkan.qaFrameCap",60));report.put("vsync",client.options.enableVsync().get());
            report.put("inactivityFpsLimit",client.options.inactivityFpsLimit().get().getSerializedName());
            report.put("unthrottled",throttle.keySet().equals(java.util.Set.of("NONE")));
            report.put("renderDistance",client.options.getEffectiveRenderDistance());
            report.put("emissiveProposalTriangles",NativePackRuntime.diagnosticFrame().getInt(1140));
            report.put("actualPoseEnd",lastActualPose);report.put("maxEyeErrorMeters",maxEyeError);report.put("maxDirectionErrorRadians",maxDirectionError);
            report.put("poseCheckedEveryFrame",true);
            report.put("geometry",TerrainGeometryCache.statistics());report.put("reconstruction",NativePackRuntime.diagnosticReconstruction());
            var rays=NativePackRuntime.diagnosticRayScene();
            report.put("raySceneStart",measuredRayScene);report.put("raySceneEnd",rays);
            report.put("steadyGeometry",measuredGeometryRevision==TerrainGeometryCache.statistics().revision() && rays.pendingSections()==0);
            report.put("scope",moving?"Continuous camera audit includes GPU readbacks and disk I/O; cadence is diagnostic only, not performance or visual acceptance.":"Natural forest/river wall cadence including CPU pose guard, without GPU readback during interval; actual shader camera checked every frame. Not GPU timings, full cinematic performance or visual acceptance.");
            report.put("diffuseBounces",bounceCount());
            report.put("diffusePathsPerPixelPerLobe",NativePackRuntime.diagnosticOptions().get("INDIRECT_SAMPLES"));
            if(FROZEN_WORLD) {
                report.put("geometrySha256",measuredGeometryHash);
                report.put("serverFrozen",client.getSingleplayerServer().tickRateManager().isFrozen());
                if(moving)report.put("geometrySha256End",NaturalSceneFingerprint.capture(evidence.resolve(captureName()+"-source-geometry-end.json")));
                else if(!measuredGeometryHash.equals(NaturalSceneFingerprint.capture()))throw new IllegalStateException("Frozen source geometry changed during measurement");
            }
            if(COMPARISON) {
                report.put("trial",comparisonTrial+1);report.put("geometrySha256",measuredGeometryHash);report.put("activeOptions",NativePackRuntime.diagnosticOptions());
                report.put("comparisonOption",comparisonOption());report.put("comparisonValue",comparisonValue());
                report.put("serverFrozen",client.getSingleplayerServer().tickRateManager().isFrozen());
                if(!measuredGeometryHash.equals(NaturalSceneFingerprint.capture()))throw new IllegalStateException("Comparison geometry digest changed during timing");
                comparisonReports.add(report);
            }
            String capture=captureName();
            write(capture+"-cadence.json",report);
            // Begin all readbacks only after the timing interval ends, at world-frame completion.
            var hdr=NativePackRuntime.diagnosticReadback((String)NativePackRuntime.diagnosticReconstruction().get("colorResource"));
            var dlss=WorldDlssConformance.capture();
            var indirect=Boolean.getBoolean("sulkan.qaIndirectTemporal")
                ? NativePackRuntime.diagnosticReadback("indirect.accum")
                : CompletableFuture.<ByteBuffer>completedFuture(null);
            var leafIndirect=Boolean.getBoolean("sulkan.qaLeafIndirect")
                ? NativePackRuntime.diagnosticReadback("leaf.indirect.accum")
                : CompletableFuture.<ByteBuffer>completedFuture(null);
            var exposure=Boolean.getBoolean("sulkan.qaAutoExposure")?ExposureConformance.capture("natural-"+phase):CompletableFuture.<Map<String,Object>>completedFuture(null);
            // Retain a completed metering result even when a separate HDR gate
            // rejects the frame. Its verdict must not erase unrelated evidence.
            exposure=exposure.thenApply(value->{
                if(value!=null)try {write(capture+"-exposure.json",value);}catch(Exception problem){throw new java.util.concurrent.CompletionException(problem);}
                return value;
            });
            var toneMap=Boolean.getBoolean("sulkan.qaToneMapComparison")?ToneMapConformance.capture(evidence,capture):CompletableFuture.<Map<String,Object>>completedFuture(null);
            var lightingAudit=Boolean.getBoolean("sulkan.qaLightingAudit")?NaturalLightingAudit.capture(evidence,capture):CompletableFuture.<Map<String,Object>>completedFuture(null);
            var rough=Boolean.getBoolean("sulkan.qaRoughReflectionComparison")?RoughReflectionConformance.capture(evidence,capture):CompletableFuture.<Map<String,Object>>completedFuture(null);
            String cloudPhase=COMPARISON?capture:phase;
            var cloudField=Boolean.getBoolean("sulkan.qaCloudDiagnostic")?CloudFieldConformance.capture(evidence,cloudPhase):CompletableFuture.<Map<String,Object>>completedFuture(null);
            var cloudPreview=CLOUD_PREVIEW?CloudPreviewConformance.capture(evidence,cloudPhase):CompletableFuture.<Map<String,Object>>completedFuture(null);
            var cloudShadow=CLOUD_PREVIEW?CloudShadowConformance.capture(evidence,cloudPhase):CompletableFuture.<Map<String,Object>>completedFuture(null);
            var cloudScattering=CLOUD_PREVIEW?CloudScatteringConformance.capture(evidence,cloudPhase):CompletableFuture.<Map<String,Object>>completedFuture(null);
            var cloudReconstruction=CLOUD_PREVIEW?CloudReconstructionConformance.capture(evidence,cloudPhase):CompletableFuture.<Map<String,Object>>completedFuture(null);
            var distantWorld=Boolean.getBoolean("sulkan.qaDistantWorldDiagnostic")?DistantWorldConformance.capture(evidence,capture):CompletableFuture.<Map<String,Object>>completedFuture(null);
            var cloudColumn=CLOUD_PREVIEW && Boolean.getBoolean("sulkan.qaCloudDiagnostic")?CloudColumnConformance.capture(evidence,cloudPhase):CompletableFuture.<Map<String,Object>>completedFuture(null);
            CompletableFuture.allOf(hdr,dlss,indirect,leafIndirect,exposure,toneMap,lightingAudit,rough,cloudField,cloudPreview,cloudShadow,cloudScattering,cloudReconstruction,cloudColumn,distantWorld).whenComplete((unused,error) -> client.execute(() -> {
                if(error!=null) {fail(client,error);return;}
                try {
                    ByteBuffer pixels=hdr.join();float maximum=0;
                    for(int i=0;i<pixels.remaining();i+=8) for(int c=0;c<3;c++) {
                        float value=Float.float16ToFloat(pixels.getShort(i+c*2));
                        if(!Float.isFinite(value) || value<0) throw new IllegalStateException("Invalid natural-world HDR at byte "+(i+c*2));
                        maximum=Math.max(maximum,value);
                    }
                    double brightnessGain=((Number)dlss.join().getOrDefault("brightnessExposureGain",1d)).doubleValue();
                    if(maximum*brightnessGain<.05) throw new IllegalStateException("Natural HDR appears blank in its declared brightness domain");
                    var output=new LinkedHashMap<String,Object>(dlss.join());output.put("inputHdrPeak",maximum);output.put("inputExposedPeak",maximum*brightnessGain);
                    write(capture+"-dlss.json",output);
                    if(COMPARISON)write(capture+"-hdr-statistics.json",NaturalSceneFingerprint.hdrStatistics(pixels,
                        ((Number)NativePackRuntime.diagnosticReconstruction().get("inputWidth")).intValue(),
                        ((Number)NativePackRuntime.diagnosticReconstruction().get("inputHeight")).intValue()));
                    if(indirect.join()!=null) write(capture+"-indirect-history.json",historyHistogram(indirect.join()));
                    if(leafIndirect.join()!=null) write(capture+"-leaf-indirect-history.json",historyHistogram(leafIndirect.join()));
                    Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image -> {
                        try(image) {
                            image.writeToFile(evidence.resolve("natural-"+capture+".png"));
                            LogUtils.getLogger().info("SULKAN_QA_VISUAL_CAPTURE_PASS phase={}",capture);
                            if(CLOUD_MOTION_AUDIT && phase.equals("day")) {
                                phase="camera-motion";settleRevision=-1;stage=2;phaseStart=System.nanoTime();pending=false;return;
                            }
                            if(CLOUD_CAMERA_AUDIT) {
                                String next=null;
                                if(phase.equals("day")) {
                                    cloudCameraReference=fixedPose.clone();fixedPose[0]+=4;fixedPose[1]+=1.5;next="camera-translation";
                                }else if(phase.equals("camera-translation")) {
                                    fixedPose[3]+=18;next="camera-rotation";
                                }else if(phase.equals("camera-rotation")) {
                                    fixedPose=cloudCameraReference.clone();next="camera-return";
                                }
                                if(next!=null) {
                                    phase=next;settleRevision=-1;stage=2;phaseStart=System.nanoTime();pending=false;return;
                                }
                            }
                            if(COMPARISON && comparisonTrial<2) {
                                comparisonTrial++;
                                NativePackRuntime.applyOptions(ShaderRuntime.config().selectedPackId(),Map.of(comparisonOption(),(double)comparisonValue()));
                                settleRevision=-1;stage=2;phaseStart=System.nanoTime();pending=false;
                                return;
                            }
                            if(DRAW_DISTANCE_32 && phase.equals("day")) {
                                client.options.renderDistance().set(32);client.options.save();
                                NativePackRuntime.requestGeometryRebuild();
                                LogUtils.getLogger().info("SULKAN_QA_DRAW_DISTANCE_CHANGED from=16 to=32");
                            }
                            if(DRAW_DISTANCE_32 && phase.equals("sunset")) {
                                if(client.options.getEffectiveRenderDistance()!=32)throw new IllegalStateException("Render distance did not reach 32");
                                beforeDistanceReloadGeometry=measuredGeometryHash;
                                NativePackRuntime.applyOptions(ShaderRuntime.config().selectedPackId(),Map.of("DISTANT_WORLD_BLEND",
                                    NativePackRuntime.diagnosticOptions().get("DISTANT_WORLD_BLEND")));
                                phase="reload-32";settleRevision=-1;stage=2;phaseStart=System.nanoTime();pending=false;return;
                            }
                            if(DRAW_DISTANCE_32 && phase.equals("reload-32")) {
                                var reloadedRays=NativePackRuntime.diagnosticRayScene();
                                if(client.options.getEffectiveRenderDistance()!=32 || !beforeDistanceReloadGeometry.equals(measuredGeometryHash)
                                    || reloadedRays.pendingSections()!=0 || reloadedRays.attributePageBudget().arenas()!=1)
                                    throw new IllegalStateException("32-chunk reload did not restore one complete matching scene");
                                write("draw-distance32.json",Map.of("passed",true,"initialDistance",16,"finalDistance",32,
                                    "reloaded",true,"sourceGeometrySha256",measuredGeometryHash,"geometry",TerrainGeometryCache.statistics(),
                                    "rayScene",reloadedRays,"scope","One frozen natural world, actual option increase 16 to 32 plus same-pack renderer replacement, complete cached geometry and bounded shared attribute pages. Not long travel, all worlds or cinematic/gameplay acceptance."));
                                LogUtils.getLogger().info("SULKAN_QA_DRAW_DISTANCE32_PASS increased=true reloaded=true completeGeometry=true singleArena=true");
                            }
                            if(phase.equals("day") || phase.equals("camera-return") || phase.equals("camera-motion")) {
                                if(COMPARISON)comparisonTrial=0;
                                var server=client.getSingleplayerServer();
                                server.execute(() -> {
                                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"time set 12600");
                                    client.execute(() -> {phase="sunset";stage=2;phaseStart=System.nanoTime();pending=false;});
                                });
                            } else if(phase.equals("sunset") && Boolean.getBoolean("sulkan.qaNightDisplay")) {
                                var server=client.getSingleplayerServer();
                                server.execute(() -> {
                                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"time set 18000");
                                    client.execute(() -> {phase="night";settleRevision=-1;stage=2;phaseStart=System.nanoTime();pending=false;});
                                });
                            } else {
                                if(COMPARISON) {
                                    if(comparisonReports.size()!=(Boolean.getBoolean("sulkan.qaNightOnly")?3:6))throw new IllegalStateException("Comparison has an incomplete capture count");
                                    write(COMPARISON_MODE.file,Map.of("trials",comparisonReports,"scope",
                                        "Same frozen loaded world, exact source geometry equality, low/high/low selected option per phase. Wall cadence and stochastic HDR/display frames, not convergence or gameplay."));
                                    LogUtils.getLogger().info(COMPARISON_MODE.marker);
                                }
                                stopped=true;client.stop();
                            }
                        } catch(Throwable problem) {fail(client,problem);}
                    });
                } catch(Throwable problem) {fail(client,problem);}
            }));
        } catch(Throwable problem) {fail(client,problem);}
    }

    private static void write(String name,Object value) throws java.io.IOException {
        Files.writeString(evidence.resolve(name),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(value));
    }
    private static Map<String,Object> historyHistogram(ByteBuffer pixels) {
        long[] histogram=new long[33];long supported=0,totalCount=0;
        for(int i=0;i<pixels.remaining();i+=8) {
            float count=Float.float16ToFloat(pixels.getShort(i+6));
            if(!Float.isFinite(count) || count!=Math.rint(count) || count<0 || count>32)
                throw new IllegalStateException("Invalid indirect history count at byte "+i+": "+count);
            histogram[(int)count]++;
            if(count==0) continue;
            supported++;totalCount+=(int)count;
            for(int channel=0;channel<3;channel++) {
                float value=Float.float16ToFloat(pixels.getShort(i+2*channel));
                if(!Float.isFinite(value) || value<0)
                    throw new IllegalStateException("Invalid indirect history lighting at byte "+i);
            }
        }
        if(supported<100) throw new IllegalStateException("Insufficient natural indirect receiver coverage: "+supported);
        var result=new LinkedHashMap<String,Object>();
        result.put("countHistogram",histogram);result.put("supportedPixels",supported);
        result.put("meanCount",(double)totalCount/supported);
        result.put("fractionAtCap",(double)histogram[32]/supported);
        result.put("fractionRestarted",(double)histogram[1]/supported);
        result.put("scope","One post-timing accumulated-light image; count is capped at 32, then uses 1/32 EMA. Not variance, convergence or history stability over the measurement window.");
        return result;
    }
    private static void applyPose(Minecraft client) {
        var player=client.player;
        player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        player.setPos(fixedPose[0],fixedPose[1],fixedPose[2]);
        player.xo=player.xOld=fixedPose[0];player.yo=player.yOld=fixedPose[1];player.zo=player.zOld=fixedPose[2];
        player.setYRot((float)fixedPose[3]);player.yRotO=(float)fixedPose[3];
        player.setXRot(cameraPitch());player.xRotO=cameraPitch();
    }
    private static void validatePose(Minecraft client) {
        var f=NativePackRuntime.diagnosticFrame();
        var eye=new org.joml.Vector3d((double)f.getFloat(480)+f.getFloat(496),(double)f.getFloat(484)+f.getFloat(500),(double)f.getFloat(488)+f.getFloat(504));
        var expectedEye=new org.joml.Vector3d(fixedPose[0],fixedPose[1]+client.player.getEyeHeight(),fixedPose[2]);
        double[] a=new double[16],b=new double[16];for(int i=0;i<16;i++){a[i]=f.getFloat(128+i*4);b[i]=f.getFloat(192+i*4);}
        var projected=new org.joml.Matrix4d(a).transform(new org.joml.Vector4d(0,0,1,1));
        var inverseView=new org.joml.Matrix4d(b);
        var projectedDirection=new org.joml.Vector3d(projected.x/projected.w,projected.y/projected.w,projected.z/projected.w)
            .mulDirection(inverseView).normalize();
        var direction=new org.joml.Vector3d(0,0,-1).mulDirection(inverseView).normalize();
        double projectionOffset=Math.acos(Math.clamp(direction.dot(projectedDirection),-1,1));
        maxProjectionDirectionOffset=Math.max(maxProjectionDirectionOffset,projectionOffset);
        var expected=net.minecraft.world.phys.Vec3.directionFromRotation(cameraPitch(),(float)fixedPose[3]);
        var expectedDirection=new org.joml.Vector3d(expected.x,expected.y,expected.z).normalize();
        double distance=eye.distance(expectedEye),angle=Math.acos(Math.max(-1,Math.min(1,direction.dot(expectedDirection))));
        maxEyeError=Math.max(maxEyeError,distance);maxDirectionError=Math.max(maxDirectionError,angle);
        lastActualPose=Map.of("eye",List.of(eye.x,eye.y,eye.z),"forward",List.of(direction.x,direction.y,direction.z),
            "directionBasis","inverse_view_negative_z","projectionDirectionOffsetRadians",projectionOffset,
            "maxProjectionDirectionOffsetRadians",maxProjectionDirectionOffset);
        if(!Double.isFinite(distance) || !Double.isFinite(angle) || distance>.002 || angle>.002)
            throw new IllegalStateException("Visual camera escaped commanded pose: eyeError="+distance+" angle="+angle+" actual="+lastActualPose+" expectedEye="+expectedEye+" expectedDirection="+expectedDirection);
    }
    private static void fail(Minecraft client,Throwable problem) {
        if(stopped) return;
        stopped=true;LogUtils.getLogger().error("SULKAN_QA_VISUAL_FAIL stage={}",stage,problem);client.stop();
    }
}
