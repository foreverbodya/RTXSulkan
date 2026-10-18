package com.sulkan.shaders.diagnostics;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.NativePackRuntime;
import com.sulkan.shaders.platform.vulkan.NativeBuffer;
import com.sulkan.shaders.render.raytracing.TerrainGeometryCache;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;

/** Real-world profiling, with deterministic frame-index camera paths and no timed readbacks. */
final class QaPerformanceProfile {
    private static final int FRAMES=128,WARMUP=256;
    private final Path directory;
    private final double[] base;
    private final List<String> plan;
    private final Map<String,Object> scene;
    private final List<Object> reports=new ArrayList<>();
    private final List<Double> wall=new ArrayList<>(),cpu=new ArrayList<>();
    private final List<Double> telemetryWall=new ArrayList<>(),telemetryCpu=new ArrayList<>();
    private final List<Object> perFrame=new ArrayList<>();
    private final java.lang.management.ThreadMXBean thread=ManagementFactory.getThreadMXBean();
    private int phaseIndex,frame=-WARMUP;
    private long previousTime,previousCpu;
    private Object rayStart,memoryStart,countersStart,rayEnd,memoryEnd,countersEnd;
    private Object geometryPreparationStart,geometryPreparationEnd,geometryCpuStart,geometryCpuEnd;
    private long preparationStartNs,preparationEndNs;
    private String geometryEnd;
    private long endEpochMs;
    private String geometryStart;
    private long startEpochMs;
    private boolean measuring,timingSaved;
    private final boolean fastPreparation=Boolean.getBoolean("sulkan.qaProfilePrepareGeometry");
    private final boolean canonicalComparison=Boolean.getBoolean("sulkan.qaCanonicalPackComparison");
    private final String canonicalReference,canonicalCandidate;
    private boolean preparingReload;
    private int verificationFrame;
    private CompletableFuture<Void> image;
    private CompletableFuture<Map<String,Object>> rayCounts;

    QaPerformanceProfile(Path directory,double[] pose,Map<String,Object> scene)throws Exception {
        this.directory=directory;this.base=pose.clone();this.scene=new LinkedHashMap<>(scene);
        if(canonicalComparison){
            canonicalCandidate=Files.readString(directory.getParent().resolve("shaders/RayLighting/sulkan.json"));
            var reference=com.google.gson.JsonParser.parseString(canonicalCandidate).getAsJsonObject();
            if(reference.getAsJsonObject("resources").remove("cloud.exact.noise")==null)throw new IllegalStateException("Canonical comparison requires a 3D cloud candidate pack");
            var passes=new com.google.gson.JsonArray();
            for(var element:reference.getAsJsonArray("passes")){
                var pass=element.getAsJsonObject();if(pass.get("name").getAsString().equals("cloud.exact.noise"))continue;
                if(pass.has("reads"))pass.getAsJsonObject("reads").remove("CloudExactNoise");passes.add(pass);
            }
            reference.add("passes",passes);canonicalReference=new GsonBuilder().setPrettyPrinting().create().toJson(reference);
            Files.writeString(directory.resolve("manifest-reference.json"),canonicalReference);
            Files.writeString(directory.resolve("manifest-candidate.json"),canonicalCandidate);
        }else{canonicalReference=canonicalCandidate=null;}
        plan=List.of(System.getProperty("sulkan.qaProfilePlan","static,motion,static-repeat,stream,settled").split(","));
        if(thread.isCurrentThreadCpuTimeSupported() && !thread.isThreadCpuTimeEnabled())thread.setThreadCpuTimeEnabled(true);
        write("profile-plan.json",Map.of("plan",plan,"framesPerPhase",FRAMES,"warmupFrames",WARMUP,"experimentKind",System.getProperty("sulkan.qaComputeExperiment","workgroup"),
            "path","motion: X +/-2m, Z +/-1m, yaw +/-12deg, one sine cycle/128 rendered frames; stream: +4m X/frame for 512m; time does not select path positions",
            "timingScope","No GPU readbacks or disk writes during timed frames; CPU thread time is current render-thread active CPU between world-completion callbacks, excludes blocked GPU waits. Wall includes engine/ticks/pose guard. Vulkan allocation counters exclude engine, Sodium and NGX; device-wide VRAM/usage sampled externally."));
        System.clearProperty("sulkan.qaProfilePrepareGeometry");
        configure();
    }
    private String phase(){return plan.get(phaseIndex);}
    private int phaseFrames(){return path().equals("travel")?2048:FRAMES;}
    private String path(){return phase().replace("narrow-","").replace("-repeat","");}
    private void configure()throws Exception{
        geometryPreparationStart=GeometryCpuMetrics.snapshot();preparationStartNs=System.nanoTime();
        System.setProperty("sulkan.qaProfilePhase",phase());
        System.setProperty("sulkan.qaProfileGroupY",phase().startsWith("narrow-")?"4":"8");
        String group=phase().startsWith("off-")?phase().substring(4):"";
        System.setProperty("sulkan.qaProfileDisabled",group);
        if(canonicalComparison || path().equals("reload")){
            if(canonicalComparison)Files.writeString(directory.getParent().resolve("shaders/RayLighting/sulkan.json"),phase().startsWith("narrow-")?canonicalCandidate:canonicalReference);
            if(fastPreparation){System.setProperty("sulkan.qaProfilePrepareGeometry","true");preparingReload=true;}
            NativePackRuntime.applyOptions(com.sulkan.shaders.runtime.ShaderRuntime.config().selectedPackId(),NativePackRuntime.diagnosticOptions());
        }
        if(path().equals("resize"))Minecraft.getInstance().getWindow().setWindowed(1920,1080);
        LogUtils.getLogger().info("SULKAN_PROFILE_PHASE_CONFIG phase={} disabled={}",phase(),group);
    }
    double[] pose() {
        double[] pose=base.clone();int n=verificationFrame>0?verificationFrame*16:frame<0?Math.floorMod(frame,FRAMES):Math.min(FRAMES-1,frame);
        if(frame<0 && !path().equals("motion"))n=0;
        if(path().equals("motion")) {
            double s=Math.sin(n*2*Math.PI/FRAMES);pose[0]+=2*s;pose[2]+=s;pose[3]+=12*s;
        } else if(path().equals("travel"))pose[0]+=Math.max(0,frame)*4;
        else if(path().equals("stream") || path().equals("settled"))pose[0]+=path().equals("stream")?n*4:508;
        return pose;
    }
    boolean observe()throws Exception {
        if(image!=null) {
            if(!image.isDone())return false;image.join();image=null;
            if(++phaseIndex==plan.size()) {
                write("performance-profile.json",Map.of("scene",scene,"reports",reports,"complete",true,
                    "scope","Profiling execution only. Pass-off images are not quality-preserving optimizations. No claim of long-travel leak freedom or final renderer acceptance."));
                LogUtils.getLogger().info("SULKAN_PERFORMANCE_PROFILE_PASS phases={}",reports.size());return true;
            }
            frame=-WARMUP;verificationFrame=0;measuring=false;timingSaved=false;rayCounts=null;configure();return false;
        }
        if(frame<0){if(path().equals("resize") && frame==-WARMUP+64)Minecraft.getInstance().getWindow().setWindowed(2560,1440);frame++;return false;}
        if(!measuring) {
            if(!java.util.Set.of("stream","travel").contains(path()) && NativePackRuntime.diagnosticRayScene().pendingSections()!=0)return false;
            if(preparingReload){System.clearProperty("sulkan.qaProfilePrepareGeometry");preparingReload=false;frame=-WARMUP;return false;}
            if(Boolean.getBoolean("sulkan.qaCloudNoisePairs") || Boolean.getBoolean("sulkan.qaRayQueryCounts")) {
                // A separate counted pipeline: never start FPS/GPU timing windows here.
                if(rayCounts==null)rayCounts=Boolean.getBoolean("sulkan.qaCloudNoisePairs")?QaCloudNoisePairs.capture(directory,phase()):QaRayQueryCounts.capture(directory,phase());
                if(!rayCounts.isDone())return false;
                reports.add(rayCounts.join());image=CompletableFuture.completedFuture(null);
                LogUtils.getLogger().info(Boolean.getBoolean("sulkan.qaCloudNoisePairs")?"SULKAN_CLOUD_NOISE_PAIRS_PASS phase={}":"SULKAN_RAY_QUERY_COUNT_PHASE_PASS phase={}",phase());return false;
            }
            // Keep fingerprint decoding outside both preparation and timed CPU scopes.
            geometryPreparationEnd=GeometryCpuMetrics.snapshot();preparationEndNs=System.nanoTime();
            startEpochMs=System.currentTimeMillis();
            geometryStart=NaturalSceneFingerprint.capture();rayStart=NativePackRuntime.diagnosticRayScene();memoryStart=NativeBuffer.statistics();
            countersStart=com.sulkan.shaders.graph.ProfileCounters.snapshot();
            wall.clear();cpu.clear();telemetryWall.clear();telemetryCpu.clear();perFrame.clear();previousTime=previousCpu=0;
            System.setProperty("sulkan.qaGpuTimingSamples",Integer.toString(FRAMES));
            NativePackRuntime.diagnosticBeginGpuTimings("profile-"+phase(),directory);
            geometryCpuStart=GeometryCpuMetrics.snapshot();
            measuring=true;return false; // first timestamped frame is the following rendered frame
        }
        if(frame<phaseFrames()) {
            long now=System.nanoTime(),active=thread.isCurrentThreadCpuTimeSupported()?thread.getCurrentThreadCpuTime():-1;
            if(previousTime!=0)wall.add((now-previousTime)*1e-6);
            if(previousCpu>0 && active>=0)cpu.add((active-previousCpu)*1e-6);
            previousTime=now;previousCpu=active;
            long telemetryStart=System.nanoTime(),telemetryActive=thread.isCurrentThreadCpuTimeSupported()?thread.getCurrentThreadCpuTime():-1;
            var rays=NativePackRuntime.diagnosticRayScene();
            var terrainMemory=TerrainGeometryCache.memoryStatistics();
            perFrame.add(Map.of("index",frame,"pose",pose(),"pendingSections",rays.pendingSections(),
                "blasBuilds",rays.blasBuilds(),"tlasBuilds",rays.tlasBuilds(),"geometryRevision",terrainMemory.revision(),"terrainCpuMemory",terrainMemory,
                "nativeAllocationBytes",NativeBuffer.statistics().liveAllocationBytes(),"vulkanMemory",NativePackRuntime.diagnosticMemoryBudget(),"throttle",Minecraft.getInstance().getFramerateLimitTracker().getThrottleReason().name()));
            telemetryWall.add((System.nanoTime()-telemetryStart)*1e-6);
            if(telemetryActive>=0)telemetryCpu.add((thread.getCurrentThreadCpuTime()-telemetryActive)*1e-6);
            frame++;
            if(frame==phaseFrames()) {
                endEpochMs=System.currentTimeMillis();rayEnd=NativePackRuntime.diagnosticRayScene();memoryEnd=NativeBuffer.statistics();
                countersEnd=com.sulkan.shaders.graph.ProfileCounters.snapshot();geometryCpuEnd=GeometryCpuMetrics.snapshot();geometryEnd=NaturalSceneFingerprint.capture();
            }
            return false;
        }
        if(!NativePackRuntime.diagnosticGpuTimingsReady())return false;
        if(!timingSaved) {
        var report=new LinkedHashMap<String,Object>(scene);
        report.put("gpuTimingPrefixFrames",FRAMES);report.put("stressOnly",path().equals("travel"));
        report.put("startEpochMs",startEpochMs);report.put("endEpochMs",endEpochMs);
        report.put("experimentKind",System.getProperty("sulkan.qaComputeExperiment","workgroup"));
        report.put("canonicalPackComparison",canonicalComparison);
        report.put("candidateSelected",canonicalComparison || Boolean.getBoolean("sulkan.qaWorkgroupExperiment")?phase().startsWith("narrow-"):System.getProperty("sulkan.qaComputeExperiment","").startsWith("cloud-3d"));
        report.put("workgroupY",Boolean.getBoolean("sulkan.qaWorkgroupExperiment") && phase().startsWith("narrow-") && "workgroup".equals(System.getProperty("sulkan.qaComputeExperiment","workgroup"))?4:8);report.put("phase",phase());report.put("frames",frame);report.put("wallMs",statistics(wall));report.put("renderThreadActiveCpuMs",statistics(cpu));
        report.put("geometryStartSha256",geometryStart);report.put("geometryEndSha256",geometryEnd);
        report.put("rayStart",rayStart);report.put("rayEnd",rayEnd);
        report.put("workCountersStart",countersStart);report.put("workCountersEnd",countersEnd);
        report.put("geometryPreparationCpuStart",geometryPreparationStart);report.put("geometryPreparationCpuEnd",geometryPreparationEnd);
        report.put("geometryPreparationWallMs",(preparationEndNs-preparationStartNs)*1e-6);
        report.put("geometryPreparationScope","Phase configuration/reload through settlement and warmup; elapsed wall includes full rendered warmup frames. Stage scopes are inclusive and exclude the subsequent fingerprint decode. Not a pure loading latency.");
        report.put("geometryCpuStart",geometryCpuStart);report.put("geometryCpuEnd",geometryCpuEnd);
        report.put("qaTelemetryWallMs",statistics(telemetryWall));report.put("qaTelemetryActiveCpuMs",statistics(telemetryCpu));
        report.put("qaTelemetryScope","Per-frame scene/cache/native/Vulkan diagnostics only; raw frame CPU includes this overhead in the following interval. This is not a complete instrumentation-overhead estimate.");
        report.put("nativeMemoryStart",memoryStart);report.put("nativeMemoryEnd",memoryEnd);
        report.put("vulkanMemoryEnd",NativePackRuntime.diagnosticMemoryBudget());
        report.put("reconstruction",NativePackRuntime.diagnosticReconstruction());report.put("options",NativePackRuntime.diagnosticOptions());
        report.put("worldTime",Minecraft.getInstance().level.getDefaultClockTime());
        report.put("poseCheckedEveryFrame",true);
        report.put("sourceRevisionUnchanged",perFrame.stream().map(row->((Map<?,?>)row).get("geometryRevision")).distinct().count()==1);
        report.put("renderDistance",Minecraft.getInstance().options.getEffectiveRenderDistance());
        report.put("asBuildMeshBudget",8);report.put("untimedGeometryPreparationMeshBudget",fastPreparation?128:8);
        report.put("vsync",Minecraft.getInstance().options.enableVsync().get());report.put("perFrame",List.copyOf(perFrame));
        reports.add(report);write("profile-"+phase()+".json",report);
            timingSaved=true;
        }
        if(Boolean.getBoolean("sulkan.qaWorkgroupExperiment") && !Boolean.getBoolean("sulkan.qaSkipImageComparison") && (phase().equals("static") || phase().equals("motion"))) {
            if(verificationFrame<9) {System.setProperty("sulkan.qaProfileVerify",verificationFrame<8?"true":"false");verificationFrame++;return false;}
            if(!com.sulkan.shaders.graph.WorkgroupVerification.ready() || !com.sulkan.shaders.graph.NativeFrameComparison.ready())return false;
            var verification=com.sulkan.shaders.graph.WorkgroupVerification.snapshot();
            write("workgroup-verification.json",verification);
            if(Boolean.getBoolean("sulkan.qaNativeFrameComparison")){
                write("native-frame-comparison.json",com.sulkan.shaders.graph.NativeFrameComparison.snapshot());
                if(verification.stream().anyMatch(row->row.get("pass").equals("frame.self") && !Boolean.TRUE.equals(row.get("byteIdentical"))))throw new IllegalStateException("Full-frame replay self-control changed HDR");
            }
            if(!Boolean.getBoolean("sulkan.qaNativeFrameComparison") && System.getProperty("sulkan.qaComputeExperiment","").startsWith("cloud-3d-env") && verification.stream().anyMatch(row->!Boolean.TRUE.equals(row.get("byteIdentical"))))throw new IllegalStateException("Environment-only storage optimization changed actual GPU output");
        }
        // Performance-only runs must not grow image-readback staging pools or
        // encode PNGs between A/B/A windows. Quality has its own launch.
        if(Boolean.getBoolean("sulkan.qaSkipImageComparison")) {
            image=CompletableFuture.completedFuture(null);
        } else {
        var dimensions=NativePackRuntime.diagnosticReconstruction();int w=((Number)dimensions.get("outputWidth")).intValue(),h=((Number)dimensions.get("outputHeight")).intValue();
        String name=phase();
        image=NativePackRuntime.diagnosticReadback("$display").thenAccept(pixels->{
            try(var png=new NativeImage(w,h,false)) {
                for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
                    int p=(y*w+x)*4;png.setPixelABGR(x,h-1-y,(pixels.get(p)&255)|((pixels.get(p+1)&255)<<8)|((pixels.get(p+2)&255)<<16)|0xff000000);
                }
                png.writeToFile(directory.resolve("profile-"+name+".png"));
            }catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
        });
        }
        LogUtils.getLogger().info("SULKAN_PERFORMANCE_PHASE_PASS phase={} frames={} wallMeanMs={}",phase(),frame,statistics(wall).get("mean"));
        return false;
    }
    private static Map<String,Object> statistics(List<Double> values) {
        if(values.isEmpty())return Map.of("available",false);
        var sorted=values.stream().sorted().toList();double mean=sorted.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        return Map.of("samples",List.copyOf(values),"mean",mean,"p50",sorted.get(sorted.size()/2),"p95",sorted.get((int)((sorted.size()-1)*.95)),"max",sorted.getLast());
    }
    private void write(String name,Object report)throws Exception {Files.writeString(directory.resolve(name),new GsonBuilder().setPrettyPrinting().create().toJson(report));}
}
