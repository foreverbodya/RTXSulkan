package com.sulkan.shaders.diagnostics;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.sulkan.shaders.platform.vulkan.NativeBuffer;
import com.sulkan.shaders.render.raytracing.*;
import java.util.List;
import org.lwjgl.vulkan.VK10;
import static com.sulkan.shaders.platform.vulkan.VulkanChecks.requireSuccess;

/** Guarded by the existing world-free ray-surface entry point. Actual completion
 * callbacks, no device idle: exercise replacement and competing retiring arenas.
 */
final class RayBudgetRetirementConformance {
    private static int stage;
    private static volatile boolean completed;
    private static long deadline;
    private static final Object OWNER=new Object();
    private static TerrainMesh mesh;
    private static TerrainRayScene scene;
    private static RayPayloadBudget shared;

    static boolean tick(VulkanDevice backend) {
        if(stage==0) {
            deadline=System.nanoTime()+60_000_000_000L;
            mesh=RaySurfaceConformance.mesh(32);
            int bytes=RaySurfaceAttributes.serializeCompact(mesh).remaining();
            require(bytes==128,"Unexpected tiny fixture layout");
            scene=new TerrainRayScene(backend.vkDevice(),new RayAttributeArena(backend.vkDevice(),bytes,1,1024,true));
            replace(1);
            var encoder=backend.createCommandEncoder();
            var view=scene.synchronize(encoder,1);
            require(view.geometry().size()==1 && scene.pendingSections()==0,"Initial tiny scene did not build");
            submit(encoder);stage=1;return false;
        }
        if(System.nanoTime()>deadline)throw new IllegalStateException("GPU page retirement check timed out at stage "+stage);
        if(!completed)return false;
        if(stage==1) {
            replace(2);
            var encoder=backend.createCommandEncoder();
            var retired=scene.synchronize(encoder,1);
            require(retired.geometry().isEmpty() && scene.pendingSections()==1,"Replacement did not retire its old payload first");
            require(scene.statistics().retirementOnlyBuilds()==1,"No retirement-only transaction executed");
            long waits=scene.statistics().budgetWaits();
            // The just-recorded retire transaction has not been submitted yet.
            // Its ranges must remain unavailable, and retry must retain pending work.
            var waiting=scene.synchronize(encoder,1);
            require(waiting.geometry().isEmpty() && scene.pendingSections()==1 && scene.statistics().budgetWaits()>waits,
                "Pending GPU readers did not defer replacement");
            submit(encoder);stage=2;return false;
        }
        if(stage==2) {
            var encoder=backend.createCommandEncoder();
            require(scene.synchronize(encoder,1).geometry().size()==1 && scene.pendingSections()==0,
                "Replacement did not resume after completion");
            scene.retire(encoder);submit(encoder);stage=3;return false;
        }
        if(stage==3) {
            require(NativeBuffer.liveBufferCount()==0,"Tiny replacement scene leaked native buffers");
            shared=new RayPayloadBudget(128);
            var peer=new RayAttributeArena(backend.vkDevice(),128,1,1024,true,shared);
            scene=new TerrainRayScene(backend.vkDevice(),new RayAttributeArena(backend.vkDevice(),128,1,1024,true,shared));
            var encoder=backend.createCommandEncoder();var commands=encoder.allocateAndBeginTransientCommandBuffer();
            var oldUpload=peer.upload(commands,mesh);
            requireSuccess(VK10.vkEndCommandBuffer(commands),"budget peer upload commands");
            encoder.execute(commands);
            replace(3);
            require(scene.synchronize(encoder,1).geometry().isEmpty() && scene.pendingSections()==1 && scene.statistics().budgetWaits()==1,
                "Competing arena did not defer a page reservation");
            require(shared.snapshot().reservedBytes()==128 && shared.snapshot().arenas()==2,"Shared page cap changed");
            encoder.queueForDestroy(()->{oldUpload.staging().close();peer.release(oldUpload.allocation());peer.close();});
            submit(encoder);stage=4;return false;
        }
        if(stage==4) {
            require(shared.snapshot().reservedBytes()==0 && shared.snapshot().arenas()==1,"Retired peer still owns pages");
            var encoder=backend.createCommandEncoder();
            require(scene.synchronize(encoder,1).geometry().size()==1 && scene.pendingSections()==0,"New arena did not resume");
            scene.retire(encoder);submit(encoder);stage=5;return false;
        }
        require(stage==5 && NativeBuffer.liveBufferCount()==0,"Budget test leaked native buffers");
        require(shared.snapshot().reservedBytes()==0 && shared.snapshot().arenas()==0,"Shared page ownership leaked");
        LogUtils.getLogger().info("SULKAN_RAY_BUDGET_RETIRE_GPU_PASS replacement=true waitsForReaders=true resumes=true peerWait=true sharedCapBytes=128 sharedCapNeverExceeded=true nativeBuffers=0 deviceIdle=false");
        stage=6;return true;
    }
    private static void replace(long revision) {
        scene.enqueue(List.of(new TerrainGeometryCache.Change(TerrainGeometryCache.Kind.REPLACE,OWNER,
            new TerrainGeometryCache.Section(revision,0,0,0,List.of(mesh)))));
    }
    private static void submit(VulkanCommandEncoder encoder) {
        completed=false;encoder.queueForDestroy(()->completed=true);encoder.submit();
    }
    private static void require(boolean value,String message) {if(!value)throw new IllegalStateException(message);}
}
