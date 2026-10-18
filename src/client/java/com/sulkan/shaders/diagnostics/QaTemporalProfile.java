package com.sulkan.shaders.diagnostics;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.NativePackRuntime;
import com.sulkan.shaders.render.raytracing.TerrainGeometryCache;
import com.sulkan.shaders.runtime.ShaderRuntime;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/** Untimed sequential real-world A/A/B/A, with independent graph and native histories. */
public final class QaTemporalProfile {
    private static final int WARMUP=256,FRAMES=128,PREPARE_STABLE_FRAMES=16;
    private static final float STEP=1.0f/60.0f;
    private static final List<String> PHASES=List.of("reference-1","reference-2","candidate","reference-3");
    private static volatile FrameClock currentClock;
    private static final Executor WRITER=Executors.newSingleThreadExecutor(task->{
        var thread=new Thread(task,"Sulkan independent temporal evidence");thread.setDaemon(true);return thread;
    });

    /** Null outside an armed quality sequence. The host must guard its use with qaTemporalProfile. */
    public record FrameClock(int index,float seconds,float delta) {}
    public static FrameClock clock() { return currentClock; }

    private final Path directory,manifestPath;
    private final double[] base;
    private final Map<String,Object> scene;
    private final Map<String,Double> options;
    private final String sequence,referenceManifest,candidateManifest;
    private final Map<String,String> inputFormats=new LinkedHashMap<>();
    private final List<Map<String,Object>> reports=new ArrayList<>();
    private final List<CompletableFuture<Void>> writes=new ArrayList<>();
    private final List<Map<String,Object>> frames=new ArrayList<>();
    private final byte[][] referenceUniforms=new byte[FRAMES][];
    private int phaseIndex,logicalIndex=-1,stableFrames,prepMotionFrames;
    private long prepareRevision=-1,sequenceRevision,evaluationsAtFirstFrame;
    private String referenceGeometry,phaseGeometry;
    private Path phaseDirectory;
    private boolean draining,done;

    public QaTemporalProfile(Path directory,double[] pose,Map<String,Object> scene)throws Exception {
        if(!Boolean.getBoolean("sulkan.qaTemporalProfile") || !Boolean.getBoolean("sulkan.qaReadback"))
            throw new IllegalStateException("Independent temporal QA requires explicit temporal/readback flags");
        if(Boolean.getBoolean("sulkan.qaWorkgroupExperiment") || Boolean.getBoolean("sulkan.qaNativeFrameComparison")
                || Boolean.getBoolean("sulkan.qaRayQueryCounts"))
            throw new IllegalStateException("Independent temporal QA cannot share replay or counted pipelines");
        if(currentClock!=null)throw new IllegalStateException("Another temporal sequence is armed");
        this.directory=directory;this.base=pose.clone();this.scene=new LinkedHashMap<>(scene);
        this.options=Map.copyOf(NativePackRuntime.diagnosticOptions());
        sequence=System.getProperty("sulkan.qaProfilePlan","motion");
        if(!Set.of("static","motion").contains(sequence))throw new IllegalArgumentException("Temporal plan must be one static or motion sequence");
        manifestPath=directory.getParent().resolve("shaders/RayLighting/sulkan.json");
        candidateManifest=Files.readString(manifestPath);
        var candidate=JsonParser.parseString(candidateManifest).getAsJsonObject();
        int environmentBindings=0;
        for(var element:candidate.getAsJsonArray("passes")) {
            var pass=element.getAsJsonObject();
            if(pass.has("reads") && pass.getAsJsonObject("reads").has("CloudExactNoise")) {
                if(!pass.get("name").getAsString().equals("cloud.environment"))
                    throw new IllegalStateException("Temporal comparison must change only environment noise storage");
                environmentBindings++;
            }
        }
        if(environmentBindings!=1)throw new IllegalStateException("Missing environment-only 3D-noise binding");
        var reference=candidate.deepCopy();
        if(reference.getAsJsonObject("resources").remove("cloud.exact.noise")==null)
            throw new IllegalStateException("Independent temporal comparison requires the environment 3D-noise candidate");
        var passes=new com.google.gson.JsonArray();
        for(var element:reference.getAsJsonArray("passes")) {
            var pass=element.getAsJsonObject();
            if(pass.get("name").getAsString().equals("cloud.exact.noise"))continue;
            if(pass.has("reads"))pass.getAsJsonObject("reads").remove("CloudExactNoise");
            passes.add(pass);
        }
        reference.add("passes",passes);referenceManifest=json(reference);
        var reconstruction=candidate.getAsJsonObject("reconstruction");
        for(String slot:List.of("color","depth","motion","exposure"))addInput(candidate,reconstruction.get(slot).getAsString());
        if(reconstruction.has("displayReads"))for(var entry:reconstruction.getAsJsonObject("displayReads").entrySet())addInput(candidate,entry.getValue().getAsString());
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("temporal-manifest-reference.json"),referenceManifest);
        Files.writeString(directory.resolve("temporal-manifest-candidate.json"),candidateManifest);
        writeJson(directory.resolve("temporal-plan.json"),Map.of(
            "phases",PHASES,"sequence",sequence,"warmupFrames",WARMUP,"captureFrames",FRAMES,
            "preparationMotionFramesPerPhase",prepMotionTarget(),
            "logicalSecondsPerFrame",STEP,"sparseInputCaptureIndices",List.of(0,63,127),"inputFormats",inputFormats,
            "scope","Untimed independent complete graph/native history sequences in the same frozen world. One normal graph and native evaluation per logical frame; full native displays and actual uniforms on every adjacent capture frame. Logical QA time deliberately replaces wall cadence; this is not FPS or production-memory evidence."));
        configurePhase();
    }

    private void addInput(JsonObject manifest,String resource) {
        inputFormats.putIfAbsent(resource,manifest.getAsJsonObject("resources").getAsJsonObject(resource).get("format").getAsString());
    }

    private String phase() { return PHASES.get(phaseIndex); }
    private int prepMotionTarget() { return sequence.equals("motion")?2*FRAMES:0; }

    private void configurePhase()throws Exception {
        currentClock=null;logicalIndex=-1;draining=false;stableFrames=0;prepareRevision=-1;prepMotionFrames=0;
        writes.clear();frames.clear();
        phaseDirectory=directory.resolve("temporal-"+sequence+"-"+phase());Files.createDirectory(phaseDirectory);
        System.setProperty("sulkan.qaProfilePhase","temporal-"+sequence+"-"+phase());
        System.clearProperty("sulkan.qaProfileVerify");System.clearProperty("sulkan.qaProfileDisabled");
        System.setProperty("sulkan.qaProfileGroupY","8");
        Files.writeString(manifestPath,phase().equals("candidate")?candidateManifest:referenceManifest);
        try { NativePackRuntime.applyOptions(ShaderRuntime.config().selectedPackId(),options); }
        catch(Exception failure) { recordFailure(failure);throw failure; }
        LogUtils.getLogger().info("SULKAN_TEMPORAL_PHASE_PREPARE phase={} sequence={} graphReloadOrdinal={}",phase(),sequence,phaseIndex+1);
    }

    /** Camera for the next actual rendered frame, also while preparing or draining. */
    public double[] pose() {
        var pose=base.clone();int index=logicalIndex<0?(prepMotionFrames<prepMotionTarget()?prepMotionFrames:0):logicalIndex;
        if(sequence.equals("motion")) {
            double s=Math.sin(Math.floorMod(index,FRAMES)*2.0*Math.PI/FRAMES);
            pose[0]+=2*s;pose[2]+=s;pose[3]+=12*s;
        }
        return pose;
    }

    /** Called once after a normal completed world frame, before applying pose() for the next frame. */
    public boolean observe()throws Exception {
        try { return observeFrame(); }
        catch(Exception failure) { recordFailure(failure);throw failure; }
    }

    private boolean observeFrame()throws Exception {
        if(done)return true;
        for(var pending:writes)if(pending.isCompletedExceptionally())pending.join();
        if(draining) {
            if(writes.stream().anyMatch(pending->!pending.isDone()))return false;
            finishPhase();
            if(++phaseIndex==PHASES.size()) {
                done=true;currentClock=null;
                writeJson(directory.resolve("temporal-profile.json"),Map.of(
                    "complete",true,"scene",scene,"sequence",sequence,"phases",reports,
                    "sourceGeometrySha256",referenceGeometry,"options",options,
                    "scope","Execution and matching-input witness only; independent histories and adjacent logical frames. Image-quality acceptance requires analysis of all paired native frames, including A/A controls. Includes asynchronous readbacks and encoding; no performance claim."));
                LogUtils.getLogger().info("SULKAN_TEMPORAL_PROFILE_PASS sequence={} phases={} captureFrames={}",sequence,PHASES.size(),FRAMES);
                return true;
            }
            configurePhase();return false;
        }
        if(logicalIndex<0) {
            // Render the complete camera sweep twice before taking any reference
            // fingerprint. Discovering geometry later must still fail the sequence.
            if(prepMotionFrames<prepMotionTarget()) {
                prepMotionFrames++;stableFrames=0;
                if(prepMotionFrames==prepMotionTarget())LogUtils.getLogger().info("SULKAN_TEMPORAL_PREP_MOTION_PASS phase={} frames={} clockActive=false",phase(),prepMotionFrames);
                return false;
            }
            var memory=TerrainGeometryCache.memoryStatistics();
            var sodium=net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.instanceNullable();
            boolean terrainComplete=sodium!=null && sodium.isTerrainRenderComplete();
            if(!terrainComplete || NativePackRuntime.diagnosticRayScene().pendingSections()!=0) {stableFrames=0;return false;}
            if(memory.revision()!=prepareRevision) {prepareRevision=memory.revision();stableFrames=0;return false;}
            if(++stableFrames<PREPARE_STABLE_FRAMES)return false;
            phaseGeometry=NaturalSceneFingerprint.capture(phaseDirectory.resolve("source-geometry.json"));
            if(referenceGeometry==null)referenceGeometry=phaseGeometry;
            if(!referenceGeometry.equals(phaseGeometry))throw new IllegalStateException("Temporal phase geometry changed: "+phase());
            writeJson(phaseDirectory.resolve("preparation.json"),Map.of("phase",phase(),"prepMotionFrames",prepMotionFrames,
                "stableFrames",stableFrames,"sodiumTerrainRenderComplete",terrainComplete,"sourceRevision",memory.revision(),
                "sourceGeometrySha256",phaseGeometry,"logicalClockActive",false));
            sequenceRevision=memory.revision();logicalIndex=0;setClock();
            LogUtils.getLogger().info("SULKAN_TEMPORAL_PHASE_BEGIN phase={} geometry={} logicalFrames={}",phase(),phaseGeometry,WARMUP+FRAMES);
            return false;
        }
        var actual=NativePackRuntime.diagnosticFrame().order(ByteOrder.nativeOrder());
        validateClock(actual);
        if(TerrainGeometryCache.memoryStatistics().revision()!=sequenceRevision || NativePackRuntime.diagnosticRayScene().pendingSections()!=0)
            throw new IllegalStateException("Geometry changed inside temporal sequence phase="+phase()+" index="+logicalIndex);
        long evaluations=NativePackRuntime.reconstructionEvaluations();
        if(logicalIndex==0)evaluationsAtFirstFrame=evaluations;
        if(evaluations!=evaluationsAtFirstFrame+logicalIndex)throw new IllegalStateException("Temporal sequence did not use one native evaluation per logical frame");
        if(logicalIndex>=WARMUP)capture(actual,logicalIndex-WARMUP,evaluations);
        if(logicalIndex==WARMUP+FRAMES-1) {currentClock=null;draining=true;return false;}
        logicalIndex++;setClock();return false;
    }

    private void setClock() { currentClock=new FrameClock(logicalIndex,logicalIndex/60.0f,logicalIndex==0?0:STEP); }

    private void validateClock(ByteBuffer frame) {
        boolean history=frame.getFloat(396)>.5f;
        if(frame.getInt(1132)!=logicalIndex || Float.compare(frame.getFloat(408),logicalIndex)!=0
                || history!=(logicalIndex!=0) || Float.compare(frame.getFloat(400),currentClock.seconds())!=0
                || Float.compare(frame.getFloat(404),currentClock.delta())!=0)
            throw new IllegalStateException("Unexpected reset/logical clock phase="+phase()+" expected="+currentClock
                +" shaderFrame="+frame.getInt(1132)+" time="+frame.getFloat(400)+" delta="+frame.getFloat(404)+" history="+history);
    }

    private void capture(ByteBuffer frame,int index,long evaluations)throws Exception {
        var dimensions=NativePackRuntime.diagnosticReconstruction();
        int width=((Number)dimensions.get("outputWidth")).intValue(),height=((Number)dimensions.get("outputHeight")).intValue();
        byte[] uniform=bytes(frame);int firstMismatch=-1;
        if(phaseIndex==0)referenceUniforms[index]=uniform;
        else {
            // AS generation is local to a newly created graph. Its identity is
            // independently checked by the exact source hash and pending=0 above.
            byte[] reference=referenceUniforms[index];
            for(int p=0;p<uniform.length;p++)if((p<1120 || p>=1132) && uniform[p]!=reference[p]) {firstMismatch=p;break;}
            if(firstMismatch!=-1) {
                // Preserve the small witness before aborting; normal captures stay asynchronous.
                String prefix=String.format(Locale.ROOT,"%03d",index);
                Files.write(phaseDirectory.resolve(prefix+"-mismatch-reference-frame.bin"),reference);
                Files.write(phaseDirectory.resolve(prefix+"-mismatch-actual-frame.bin"),uniform);
                writeJson(phaseDirectory.resolve(prefix+"-uniform-mismatch.json"),Map.of("firstMismatchByte",firstMismatch,"phase",phase(),"captureIndex",index));
                throw new IllegalStateException("Temporal physical frame inputs differ phase="+phase()+" capture="+index+" byte="+firstMismatch);
            }
        }
        String prefix=String.format(Locale.ROOT,"%03d",index);Path output=phaseDirectory;
        var record=new LinkedHashMap<String,Object>();
        record.put("index",index);record.put("logicalFrame",logicalIndex);record.put("historyValid",true);
        record.put("pose",pose());record.put("nativeEvaluations",evaluations);record.put("uniformSha256",sha256(uniform));
        record.put("uniformsMatchReferenceOutsideAsIdentity",firstMismatch==-1);
        record.put("currentPreviousJitter",List.of(frame.getFloat(1072),frame.getFloat(1076),frame.getFloat(1080),frame.getFloat(1084)));
        frames.add(record);
        var display=NativePackRuntime.diagnosticReadback("$display");
        writes.add(display.thenAcceptAsync(pixels->{
            try {
                if(pixels.remaining()!=width*height*4)throw new IllegalStateException("Temporal display extent");
                Files.write(output.resolve(prefix+"-frame.bin"),uniform);
                try(var png=new NativeImage(width,height,false)) {
                    for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                        int p=(y*width+x)*4;png.setPixelABGR(x,height-1-y,(pixels.get(p)&255)|((pixels.get(p+1)&255)<<8)|((pixels.get(p+2)&255)<<16)|0xff000000);
                    }
                    png.writeToFile(output.resolve(prefix+"-display.png"));
                }
            }catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
        },WRITER));
        if(index==0 || index==63 || index==127)for(var input:inputFormats.entrySet()) {
            String resource=input.getKey();var copied=NativePackRuntime.diagnosticReadback(resource);
            writes.add(copied.thenAcceptAsync(pixels->{
                try(var compressed=new GZIPOutputStream(Files.newOutputStream(output.resolve(prefix+"-"+resource+".bin.gz")))) {
                    compressed.write(bytes(pixels));
                }catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
            },WRITER));
        }
    }

    private void finishPhase()throws Exception {
        String endGeometry=NaturalSceneFingerprint.capture();
        if(!phaseGeometry.equals(endGeometry))throw new IllegalStateException("Temporal phase ended with different geometry");
        var report=new LinkedHashMap<String,Object>();report.put("phase",phase());report.put("graphReloadOrdinal",phaseIndex+1);
        report.put("sequence",sequence);report.put("sourceGeometrySha256",phaseGeometry);report.put("warmupFrames",WARMUP);
        report.put("prepMotionFrames",prepMotionFrames);report.put("preparationStableFrames",stableFrames);
        report.put("captureFrames",FRAMES);report.put("frames",List.copyOf(frames));report.put("inputFormats",inputFormats);
        report.put("reconstruction",NativePackRuntime.diagnosticReconstruction());
        report.put("uniformComparisonExcludedByteRange",List.of(1120,1132));
        report.put("uniformComparisonScope","All 1152 actual uniform bytes except graph-local AS generation/pending words; pending is required zero and exact geometry is checked independently. Raw uniforms are retained without modification.");
        writeJson(phaseDirectory.resolve("sequence.json"),report);reports.add(report);
        LogUtils.getLogger().info("SULKAN_TEMPORAL_PHASE_PASS phase={} sequence={} captures={} adjacent=true",phase(),sequence,frames.size());
    }

    private void recordFailure(Exception failure) {
        try {
            var report=new LinkedHashMap<String,Object>();
            report.put("complete",false);report.put("phase",phaseIndex<PHASES.size()?phase():"complete");
            report.put("sequence",sequence);report.put("logicalFrame",logicalIndex);report.put("prepMotionFrames",prepMotionFrames);
            report.put("preparationMotionTarget",prepMotionTarget());report.put("stableFrames",stableFrames);
            report.put("preparationRevision",prepareRevision);report.put("armedSequenceRevision",sequenceRevision);
            report.put("clock",currentClock);report.put("capturedFrames",frames.size());report.put("failure",failure.toString());
            report.put("terrainMemory",TerrainGeometryCache.memoryStatistics());
            var sodium=net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.instanceNullable();
            report.put("sodiumTerrainRenderComplete",sodium!=null && sodium.isTerrainRenderComplete());
            try { report.put("rayScene",NativePackRuntime.diagnosticRayScene()); }
            catch(Exception unavailable) { report.put("raySceneUnavailable",unavailable.toString()); }
            Path output=phaseDirectory==null?directory:phaseDirectory;
            if(logicalIndex>=0)Files.write(output.resolve("failure-frame.bin"),bytes(NativePackRuntime.diagnosticFrame()));
            writeJson(output.resolve("temporal-failure.json"),report);
        }catch(Exception witnessFailure) { failure.addSuppressed(witnessFailure); }
    }

    private static byte[] bytes(ByteBuffer source) { byte[] result=new byte[source.remaining()];source.duplicate().get(result);return result; }
    private static String sha256(byte[] data)throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
    private static String json(Object value) { return new GsonBuilder().setPrettyPrinting().create().toJson(value); }
    private static void writeJson(Path path,Object value)throws Exception { Files.writeString(path,json(value)); }
}
