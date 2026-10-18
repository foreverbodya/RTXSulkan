package com.sulkan.shaders.diagnostics;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.sulkan.shaders.platform.vulkan.NativeBuffer;
import com.sulkan.shaders.render.raytracing.AccelerationStructureBuilder;
import com.sulkan.shaders.render.raytracing.RayOrigin;
import com.sulkan.shaders.render.raytracing.TerrainGeometryCache;
import com.sulkan.shaders.render.raytracing.TerrainMesh;
import com.sulkan.shaders.render.raytracing.TerrainRayScene;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static com.sulkan.shaders.platform.vulkan.VulkanChecks.requireSuccess;

/** Guarded GPU precision/rebase test. Explicit waits belong only to this diagnostic. */
final class RayOriginConformance {
    private static final int FAR_X=16_000_000,FAR_Z=-16_000_000;
    // x,y,z,dx,tMax,expectedDistance, all in the untranslated fixture's coordinates.
    private static final double[][] RAYS={
        {4.0625,97.5,1.5,1,1,.0625},{4.09375,97.5,1.5,1,1,.03125},
        {4.25,97.5,1.5,-1,1,.125},{4.0625,97.5,2.5,1,1,-1},
        {4.0625,97.5,1.5,1,.01,-1},{4.0625,98.5,1.5,1,1,-1}
    };
    static void verify(VulkanDevice backend,Path evidence) throws Exception {
        if(!Boolean.getBoolean("sulkan.qaRayOrigin")) throw new IllegalStateException("Ray origin QA is disabled");
        verifyContract();
        var device=backend.vkDevice();int host=VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        var scene=new TerrainRayScene(device);
        try(var rays=new NativeBuffer(device,RAYS.length*48L,VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,host);
            var output=new NativeBuffer(device,RAYS.length*16L,VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,host);
            var decoded=new NativeBuffer(device,RAYS.length*16L,VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,host);
            var pipeline=new RayQueryConformance.QueryPipeline(device)) {
            scene.enqueue(List.of(new TerrainGeometryCache.Change(TerrainGeometryCache.Kind.REPLACE,new Object(),
                new TerrainGeometryCache.Section(1,FAR_X,96,FAR_Z,List.of(mesh())))));
            var encoder=backend.createCommandEncoder();
            var initial=scene.synchronize(encoder,1);
            long blas=initial.geometry().values().iterator().next().acceleration().handle();
            var phases=new ArrayList<Object>();
            float[] legacy=trace(backend,initial,rays,output,decoded,pipeline);
            boolean controlLostPrecision=false;
            for(int i=0;i<RAYS.length;i++) {
                for(int channel=0;channel<4;channel++) if(!Float.isFinite(legacy[i*4+channel]))
                    throw new IllegalStateException("Nonfinite legacy control readback");
                if(legacy[i*4]!=0 && legacy[i*4]!=1) throw new IllegalStateException("Invalid legacy control hit flag");
                if((legacy[i*4]==0 ? -1 : legacy[i*4+1])!=RAYS[i][5]) controlLostPrecision=true;
            }
            if(!controlLostPrecision) throw new IllegalStateException("Far world-space precision-loss control did not discriminate");
            var far=new RayOrigin(FAR_X,0,FAR_Z);
            for(var origin:List.of(far,new RayOrigin(FAR_X+256,0,FAR_Z+256),far)) {
                long builds=scene.statistics().recordedBuilds();
                scene.requestOrigin(origin);
                var view=scene.synchronize(backend.createCommandEncoder(),1);
                if(!view.origin().equals(origin) || scene.statistics().recordedBuilds()!=builds+1
                    || view.geometry().values().iterator().next().acceleration().handle()!=blas)
                    throw new IllegalStateException("Origin change rebuilt a BLAS or failed to rebuild the TLAS");
                var actual=trace(backend,view,rays,output,decoded,pipeline);
                for(int i=0;i<RAYS.length;i++) {
                    double distance=actual[i*4]==0 ? -1 : actual[i*4+1];
                    if(!Double.isFinite(distance) || Math.abs(distance-RAYS[i][5])>1e-6)
                        throw new IllegalStateException("Far precision ray="+i+" origin="+origin+" expected="+RAYS[i][5]+" actual="+distance);
                }
                long stableBuilds=scene.statistics().recordedBuilds();
                scene.followCamera(FAR_X+4.1,97.5,FAR_Z+1.5);
                if(scene.synchronize(backend.createCommandEncoder(),1)!=view || scene.statistics().recordedBuilds()!=stableBuilds)
                    throw new IllegalStateException("Small camera motion rebuilt the acceleration structures");
                phases.add(java.util.Map.of("origin",origin,"distances",actual,"blasReused",true,"smallMotionReusedView",true));
            }
            var report=new LinkedHashMap<String,Object>();report.put("legacyControlLostPrecision",true);
            report.put("manifestAndOriginContract",true);
            report.put("legacyControlReadback",legacy);report.put("raysPerPhase",RAYS.length);report.put("phases",phases);
            report.put("scope","Actual hardware queries of a Sodium-encoded sub-block quad at +/-16M; origin changes rebuild only TLAS, preserve BLAS, and small motion preserves the view. Synthetic scene, separate from far-world reflection validation.");
            Files.writeString(evidence.resolve("ray-origin-precision.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
            com.mojang.logging.LogUtils.getLogger().info("SULKAN_QA_RAY_ORIGIN_PRECISION_PASS {}",report);
        } finally {
            var encoder=backend.createCommandEncoder();scene.retire(encoder);encoder.submit();
            requireSuccess(VK10.vkDeviceWaitIdle(device),"ray origin diagnostic retirement");
        }
    }
    private static void verifyContract() {
        String manifest="""
            {"version":1,"name":"Ray origin contract","resources":{"color":{"format":"RGBA16_FLOAT"}},
            "passes":[{"name":"display","fragment":"shaders/display.fsh","reads":{},"writes":["color"]}],"present":"color"%s}
            """;
        if(com.sulkan.shaders.graph.PackGraph.parse(manifest.formatted("")).cameraRelativeRays())
            throw new IllegalStateException("Legacy packs no longer default to world rays");
        var relative=com.sulkan.shaders.graph.PackGraph.parse(manifest.formatted(",\"rayCoordinates\":\"camera_relative\""));
        if(!relative.cameraRelativeRays() || !relative.withOptions(java.util.Map.of()).cameraRelativeRays())
            throw new IllegalStateException("Relative ray contract lost during option application");
        try { com.sulkan.shaders.graph.PackGraph.parse(manifest.formatted(",\"rayCoordinates\":\"invalid\""));
            throw new IllegalStateException("Unknown ray coordinate mode accepted");
        } catch(IllegalArgumentException expected) {}
        try { new RayOrigin(1,0,0);throw new IllegalStateException("Unaligned ray origin accepted"); }
        catch(IllegalArgumentException expected) {}
        try { RayOrigin.near(Double.NaN,0,0);throw new IllegalStateException("Nonfinite ray camera accepted"); }
        catch(IllegalArgumentException expected) {}
        if(!RayOrigin.WORLD.contains(256,0,-256) || RayOrigin.WORLD.contains(256.01,0,0)
            || !RayOrigin.near(FAR_X+.0625,97.5,FAR_Z+.03125).equals(new RayOrigin(FAR_X,0,FAR_Z)))
            throw new IllegalStateException("Ray origin snapping/hysteresis contract failed");
    }
    private static TerrainMesh mesh() {
        var vertices=ChunkVertexEncoder.Vertex.uninitializedQuad();
        float[][] points={{4.125f,1,1},{4.125f,2,1},{4.125f,2,2},{4.125f,1,2}};
        long memory=MemoryUtil.nmemCalloc(1,80);
        try {
            for(int i=0;i<4;i++) {
                vertices[i].x=points[i][0];vertices[i].y=points[i][1];vertices[i].z=points[i][2];
                vertices[i].u=.5f;vertices[i].v=.5f;vertices[i].color=-1;vertices[i].light=0x00f000f0;vertices[i].ao=1;
            }
            new CompactChunkVertex().getEncoder().write(memory,0,vertices,0);
            return TerrainMesh.copyCompressedQuads(MemoryUtil.memByteBuffer(memory,80),4,TerrainMesh.Opacity.OPAQUE);
        } finally { MemoryUtil.nmemFree(memory); }
    }
    private static float[] trace(VulkanDevice backend,TerrainRayScene.View view,NativeBuffer rays,NativeBuffer output,
            NativeBuffer decoded,RayQueryConformance.QueryPipeline pipeline) {
        try(var stack=MemoryStack.stackPush()) {
            ByteBuffer input=stack.malloc(RAYS.length*48),sentinel=stack.malloc(RAYS.length*16);
            for(var ray:RAYS) {
                input.putFloat((float)(FAR_X+ray[0]-view.origin().x())).putFloat((float)(ray[1]-view.origin().y()))
                    .putFloat((float)(FAR_Z+ray[2]-view.origin().z())).putFloat(.001f);
                input.putFloat((float)ray[3]).putFloat(0).putFloat(0).putFloat((float)ray[4]);
                input.putInt(1).putInt(0).putInt(0).putInt(0);
                for(int c=0;c<4;c++) sentinel.putFloat(Float.NaN);
            }
            rays.write(input.flip());output.write(sentinel.flip());decoded.write(sentinel);
            var encoder=backend.createCommandEncoder();var commands=encoder.allocateAndBeginTransientCommandBuffer();
            AccelerationStructureBuilder.dependency(commands,VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR|VK10.VK_PIPELINE_STAGE_HOST_BIT,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR|VK10.VK_ACCESS_HOST_WRITE_BIT,
                VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR|VK10.VK_ACCESS_SHADER_READ_BIT|VK10.VK_ACCESS_SHADER_WRITE_BIT);
            pipeline.record(commands,view.handle(),rays,output,decoded,RAYS.length,stack);
            AccelerationStructureBuilder.dependency(commands,VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK10.VK_PIPELINE_STAGE_HOST_BIT,
                VK10.VK_ACCESS_SHADER_WRITE_BIT,VK10.VK_ACCESS_HOST_READ_BIT);
            requireSuccess(VK10.vkEndCommandBuffer(commands),"ray origin diagnostic commands");encoder.execute(commands);encoder.submit();
            requireSuccess(VK10.vkDeviceWaitIdle(backend.vkDevice()),"ray origin diagnostic readback");
            return output.readFloats(RAYS.length*4);
        }
    }
}
