package com.sulkan.shaders.render.raytracing;

import com.sulkan.shaders.diagnostics.GeometryCpuMetrics;
import static com.sulkan.shaders.diagnostics.GeometryCpuMetrics.Stage.*;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.sulkan.shaders.platform.vulkan.NativeBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static com.sulkan.shaders.platform.vulkan.VulkanChecks.requireSuccess;

/**
 * Incremental render-thread scene. All consumers must record on the supplied
 * graphics encoder, after synchronize(), and must not retain a previous view.
 * Submission and GPU waits belong to Minecraft. This class does neither.
 */
public final class TerrainRayScene {
    public record Geometry(int id, TerrainGeometryCache.Section section, TerrainMesh mesh,
                           AccelerationStructure acceleration,RayAttributeRanges.Allocation attributes) {}
    public record View(long generation, long handle, Map<Integer, Geometry> geometry,NativeBuffer attributeData,RayOrigin origin) {
        public View { geometry = Map.copyOf(geometry); }
        public View(long generation,long handle,Map<Integer,Geometry> geometry) { this(generation,handle,geometry,null,RayOrigin.WORLD); }
        public View(long generation,long handle,Map<Integer,Geometry> geometry,NativeBuffer attributes) { this(generation,handle,geometry,attributes,RayOrigin.WORLD); }
    }
    public record Statistics(int sections, int meshes, int pendingSections, long storageBytes,
                             long generation, long recordedBuilds,long attributeBytes,long attributeCapacity,
                             RayPayloadBudget.Snapshot attributePageBudget,long budgetWaits,long retirementOnlyBuilds,long triangles,long blasBuilds,long tlasBuilds,long blasUpdates,long tlasUpdates,long synchronizations,long syncCpuNanoseconds) {}

    private final AccelerationStructureBuilder builder;
    private final RayAttributeArena attributeArena;
    private final VkDevice device;
    private final RayEmissionSources emissionSources=new RayEmissionSources();
    private boolean sampleEmission;
    private boolean weightedEmission;
    private RayEmissionSources.Snapshot emission=new RayEmissionSources.Snapshot(null,0,false);
    public void sampleEmission(boolean enabled){ensureActive();sampleEmission=enabled;}
    public void weightedEmission(boolean enabled){ensureActive();weightedEmission=enabled;}
    public long emissionAddress(){return emission.address();}
    public int emissionTriangles(){return emission.triangles();}
    public Map<String,Object> emissionStatistics(){return Map.of("triangles",emission.triangles(),"bytes",emission.buffer()==null?0L:emission.buffer().bytes(),"overflowFallback",emission.overflow(),"enabled",sampleEmission,"weighted",weightedEmission);}
    private final Map<Object, List<Geometry>> sections = new IdentityHashMap<>();
    private final Map<Object, TerrainGeometryCache.Change> pending = new IdentityHashMap<>();
    private AccelerationStructure top;
    private View view = new View(0, 0, Map.of());
    private int nextId;
    private long recordedBuilds,blasBuilds,tlasBuilds,synchronizations,syncCpuNanoseconds;
    private com.sulkan.shaders.graph.GpuPassTimings timings;
    private long budgetWaits,retirementOnlyBuilds;
    private int pendingAttributeRetirements;
    private boolean clearRequested;
    private boolean retired;
    private RayOrigin requestedOrigin=RayOrigin.WORLD;

    public void requestOrigin(RayOrigin origin) { ensureActive();requestedOrigin=java.util.Objects.requireNonNull(origin); }
    public void followCamera(double x,double y,double z) {
        requestOrigin(view.origin().contains(x,y,z) ? view.origin() : RayOrigin.near(x,y,z));
    }

    public TerrainRayScene(VkDevice device) { this(device,false); }
    public TerrainRayScene(VkDevice device,boolean attributes) {
        this.device=device;
        builder = new AccelerationStructureBuilder(device);
        attributeArena=attributes ? new RayAttributeArena(device) : null;
    }
    /** Takes ownership of an explicitly bounded arena, used by the small-budget GPU check. */
    public TerrainRayScene(VkDevice device,RayAttributeArena arena) {
        this.device=device;
        builder=new AccelerationStructureBuilder(device);attributeArena=java.util.Objects.requireNonNull(arena);
    }

    /** Coalesces uploads until the next build budget becomes available. */
    public void enqueue(List<TerrainGeometryCache.Change> changes) {
        ensureActive();
        for (var change : changes) {
            if (change.kind() == TerrainGeometryCache.Kind.CLEAR) {
                pending.clear();
                clearRequested = true;
            } else {
                pending.put(change.owner(), change);
            }
        }
    }

    /**
     * Builds complete sections within a mesh budget. A single larger section is
     * allowed to prevent starvation. Removals are always applied immediately.
     * Unchanged BLAS objects are reused; the TLAS changes only with scene contents.
     */
    public View synchronize(VulkanCommandEncoder encoder, int meshBudget) {
        return synchronize(encoder,meshBudget,true);
    }

    public View synchronize(VulkanCommandEncoder encoder,int meshBudget,com.sulkan.shaders.graph.GpuPassTimings timings) {
        try (var synchronization = GeometryCpuMetrics.begin(SCENE_SYNCHRONIZE, 1)) {
            long begin=System.nanoTime();this.timings=timings;
            try {return synchronize(encoder,meshBudget,true);}
            finally {this.timings=null;synchronizations++;syncCpuNanoseconds+=System.nanoTime()-begin;}
        }
    }

    private boolean hasRetirablePending() {
        return clearRequested && !sections.isEmpty() || pending.values().stream().anyMatch(change->sections.containsKey(change.owner()));
    }
    private View synchronize(VulkanCommandEncoder encoder,int meshBudget,boolean allowUploads) {
        ensureActive();
        if (meshBudget < 1) throw new IllegalArgumentException("Mesh budget must be positive");
        if (!clearRequested && pending.isEmpty() && requestedOrigin.equals(view.origin())) return view;
        if(!allowUploads && !hasRetirablePending() && requestedOrigin.equals(view.origin()))return view;
        var next = new IdentityHashMap<Object, List<Geometry>>(sections);
        var replaced = new ArrayList<AccelerationStructure>();
        var created = new ArrayList<AccelerationStructure>();
        var uploaded = new ArrayList<RayAttributeArena.Upload>();
        var retiredAttributes = new ArrayList<RayAttributeRanges.Allocation>();
        var accepted = new ArrayList<Object>();
        if (clearRequested) {
            next.values().forEach(values -> values.forEach(value -> replaced.add(value.acceleration())));
            next.values().forEach(values -> values.forEach(value -> { if (value.attributes()!=null) retiredAttributes.add(value.attributes()); }));
            next.clear();
        }
        var commands = encoder.allocateAndBeginTransientCommandBuffer();
        AccelerationStructure newTop = null;
        RayEmissionSources.Snapshot nextEmission=null;
        boolean recorded = false;
        try {
            AccelerationStructureBuilder.dependency(commands, VK10.VK_PIPELINE_STAGE_HOST_BIT,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK10.VK_ACCESS_HOST_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT);
            int builtMeshes = 0;
            for (var change : pending.values()) {
                int count = change.section() == null ? 0 : change.section().meshes().size();
                if (count > 0 && builtMeshes > 0 && count > meshBudget - builtMeshes) continue;
                var previous = next.remove(change.owner());
                if (previous != null) previous.forEach(value -> replaced.add(value.acceleration()));
                if (previous != null) previous.forEach(value -> { if (value.attributes()!=null) retiredAttributes.add(value.attributes()); });
                if (allowUploads && change.kind() == TerrainGeometryCache.Kind.REPLACE && count > 0) {
                    var values = new ArrayList<Geometry>(count);
                    for (TerrainMesh mesh : change.section().meshes()) {
                        if (attributeArena==null && nextId > 0xffffff) throw new IllegalStateException("Ray geometry IDs exhausted; recreate scene");
                        RayAttributeRanges.Allocation attributes=null;
                        if (attributeArena!=null) {
                            var uploadTiming=timings==null?null:timings.start(encoder,commands,"geometry.upload");
                            var upload=attributeArena.upload(commands,mesh);
                            if(uploadTiming!=null)uploadTiming.finish(true);
                            uploaded.add(upload); attributes=upload.allocation();
                        }
                        var blasTiming=timings==null?null:timings.start(encoder,commands,"blas.build");
                        var bottom = attributes==null ? builder.mesh(commands,mesh) : builder.mesh(commands,mesh,attributeArena,attributes);
                        if(blasTiming!=null)blasTiming.finish(true);
                        created.add(bottom);
                        int id=attributes==null ? nextId++ : attributes.headerWord();
                        values.add(new Geometry(id, change.section(), mesh, bottom,attributes));
                    }
                    next.put(change.owner(), List.copyOf(values));
                    builtMeshes += count;
                }
                // A retirement-only transaction leaves replacements pending.
                // Their old allocations are released by the completion callback,
                // before a later transaction needs the same bounded capacity.
                if(allowUploads || change.kind()!=TerrainGeometryCache.Kind.REPLACE)accepted.add(change.owner());
            }
            var instances = new ArrayList<AccelerationStructureBuilder.Instance>();
            var metadata = new LinkedHashMap<Integer, Geometry>();
            for (var values : next.values()) for (var value : values) {
                var section = value.section();
                int mask = switch (value.mesh().opacity()) {
                    case OPAQUE -> 1;
                    case CUTOUT -> 2;
                    case TRANSLUCENT -> 4;
                };
                instances.add(new AccelerationStructureBuilder.Instance(value.acceleration(),
                        (float)((long)section.x()-requestedOrigin.x()), (float)((long)section.y()-requestedOrigin.y()),
                        (float)((long)section.z()-requestedOrigin.z()), value.id(), mask));
                metadata.put(value.id(), value);
            }
            nextEmission=sampleEmission && attributeArena!=null?emissionSources.create(device,metadata,weightedEmission):new RayEmissionSources.Snapshot(null,0,false);
            AccelerationStructureBuilder.dependency(commands,VK10.VK_PIPELINE_STAGE_HOST_BIT,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK10.VK_ACCESS_HOST_WRITE_BIT,VK10.VK_ACCESS_SHADER_READ_BIT);
            if (!instances.isEmpty()) {
                AccelerationStructureBuilder.dependency(commands, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR, VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
                var tlasTiming=timings==null?null:timings.start(encoder,commands,"tlas.build");
                newTop = builder.instances(commands, instances);
                if(tlasTiming!=null)tlasTiming.finish(true);
                created.add(newTop);
                AccelerationStructureBuilder.dependency(commands, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                        VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR, VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            }
            requireSuccess(VK10.vkEndCommandBuffer(commands), "vkEndCommandBuffer terrain scene");
            encoder.execute(commands);
            recorded = true;
            // Previous frame readers and this build precede these deferred callbacks.
            AccelerationStructure oldTop = top;
            var oldEmission=emission;
            pendingAttributeRetirements+=retiredAttributes.size();
            encoder.queueForDestroy(() -> {
                if (oldTop != null) oldTop.close();
                oldEmission.close();
                replaced.forEach(AccelerationStructure::close);
                created.forEach(AccelerationStructure::releaseBuildInputs);
                uploaded.forEach(upload -> upload.staging().close());
                if (attributeArena!=null) for(var allocation:retiredAttributes) {
                    attributeArena.release(allocation);pendingAttributeRetirements--;
                }
            });
            top = newTop;
            emission=nextEmission;
            sections.clear();
            sections.putAll(next);
            accepted.forEach(pending::remove);
            clearRequested = false;
            recordedBuilds += created.size();
            blasBuilds+=created.size()-(newTop==null?0:1);if(newTop!=null)tlasBuilds++;
            if(!allowUploads)retirementOnlyBuilds++;
            view = new View(view.generation() + 1, top == null ? 0 : top.handle(), metadata,attributeArena==null ? null : attributeArena.buffer(),requestedOrigin);
            return view;
        } catch (Throwable failure) {
            // Recorded-but-not-executed commands will be discarded by the pool.
            // Never destroy resources referenced by submitted work on this path.
            if (!recorded) {
                if(nextEmission!=null)nextEmission.close();
                for (int i = created.size() - 1; i >= 0; i--) created.get(i).close();
                for (var upload : uploaded) { upload.staging().close(); attributeArena.release(upload.allocation()); }
            }
            if(!recorded && failure instanceof RayAttributeArena.PageBudgetException budget) {
                budgetWaits++;
                // A previous renderer can still own pages until its GPU fence.
                // Retain current scene/pending ownership and retry next frame.
                if(budget.waitingForPeer() || pendingAttributeRetirements>0)return view;
                // Retire obsolete geometry first instead of requiring both
                // old and replacement payloads to fit simultaneously forever.
                if(allowUploads && hasRetirablePending())return synchronize(encoder,meshBudget,false);
            }
            throw failure;
        }
    }

    public Statistics statistics() {
        try (var diagnostic = GeometryCpuMetrics.begin(SCENE_STATISTICS, view.geometry().size())) {
            long bytes = top == null ? 0 : top.storageBytes();
            for (var values : sections.values()) for (var value : values) bytes += value.acceleration().storageBytes();
            return new Statistics(sections.size(), view.geometry().size(), pending.size(), bytes,
                    view.generation(), recordedBuilds,attributeArena==null ? 0 : attributeArena.usedBytes(),
                    attributeArena==null ? 0 : attributeArena.capacityBytes(),attributeArena==null ? null : attributeArena.budget(),
                    budgetWaits,retirementOnlyBuilds,view.geometry().values().stream().mapToLong(value->value.mesh().triangleCount()).sum(),blasBuilds,tlasBuilds,0,0,synchronizations,syncCpuNanoseconds);
        }
    }
    public int pendingSections() { return pending.size(); }

    /** Call after the final consumer has been recorded, before device shutdown. */
    public void retire(VulkanCommandEncoder encoder) {
        ensureActive();
        retired = true;
        AccelerationStructure oldTop = top;
        var oldEmission=emission;
        var bottoms = sections.values().stream().flatMap(List::stream).map(Geometry::acceleration).toList();
        var attributes = sections.values().stream().flatMap(List::stream).map(Geometry::attributes).filter(java.util.Objects::nonNull).toList();
        encoder.queueForDestroy(() -> {
            if (oldTop != null) oldTop.close();
            oldEmission.close();
            bottoms.forEach(AccelerationStructure::close);
            if (attributeArena!=null) { attributes.forEach(attributeArena::release); attributeArena.close(); }
            if(Boolean.getBoolean("sulkan.qaPerformanceProfile"))com.mojang.logging.LogUtils.getLogger().info(
                "SULKAN_PROFILE_RAY_RETIRE nativeBuffers={} attributeBudget={} descriptors={}",
                new com.google.gson.Gson().toJson(com.sulkan.shaders.platform.vulkan.NativeBuffer.statistics()),
                attributeArena==null?"null":new com.google.gson.Gson().toJson(attributeArena.budget()),
                new com.google.gson.Gson().toJson(com.sulkan.shaders.graph.ProfileCounters.snapshot()));
        });
        top = null;
        emission=new RayEmissionSources.Snapshot(null,0,false);
        sections.clear();
        pending.clear();
        view = new View(view.generation() + 1, 0, Map.of());
    }

    private void ensureActive() {
        if (retired) throw new IllegalStateException("Terrain ray scene is retired");
    }
}
