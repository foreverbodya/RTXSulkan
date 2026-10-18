package com.sulkan.shaders.render.raytracing;

import com.mojang.logging.LogUtils;
import com.sulkan.shaders.platform.vulkan.NativeBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/** Stable SSBO address directory and bounded payload pages. Slots/ranges retire after GPU readers. */
public final class RayAttributeArena implements AutoCloseable {
    public record Upload(RayAttributeRanges.Allocation allocation, NativeBuffer staging) {}
    private record Page(NativeBuffer buffer,RayAttributeRanges ranges) {}
    private record Payload(Page page,RayAttributeRanges.Allocation range,RayAttributeRanges.Allocation slot) {}
    private final VkDevice device;
    private final NativeBuffer directory;
    private final RayAttributeRanges slots;
    private final ArrayList<Page> pages=new ArrayList<>();
    private final HashMap<Integer,Payload> live=new HashMap<>();
    private final int pageBytes,maxPages;
    private final boolean compactVertices;
    private final RayPayloadBudget pageBudget;
    private final boolean sharedBudget;
    private static final HashMap<Long,RayPayloadBudget> DEVICE_BUDGETS=new HashMap<>();
    private long used;
    private boolean closed;
    public RayAttributeArena(VkDevice device) {this(device,64*1024*1024,defaultPages(device),16*1024*1024,true,true);}

    private static int defaultPages(VkDevice device) {
        try(MemoryStack stack=MemoryStack.stackPush()) {
            var properties=VkPhysicalDeviceMemoryProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(),properties);
            long localHeap=0;
            for(int i=0;i<properties.memoryHeapCount();i++)
                if((properties.memoryHeaps(i).flags()&VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT)!=0)
                    localHeap=Math.max(localHeap,properties.memoryHeaps(i).size());
            long bytes=RayPayloadBudget.defaultBytes(localHeap);
            LogUtils.getLogger().info("SULKAN_RT_ATTRIBUTE_BUDGET localHeapBytes={} sharedLimitBytes={} pageBytes={}",localHeap,bytes,RayPayloadBudget.PAGE_BYTES);
            return (int)(bytes/RayPayloadBudget.PAGE_BYTES);
        }
    }
    public static final class PageBudgetException extends IllegalStateException {
        private final boolean waitingForPeer;
        private PageBudgetException(boolean waitingForPeer,String message) {super(message);this.waitingForPeer=waitingForPeer;}
        public boolean waitingForPeer() {return waitingForPeer;}
    }

    /** Small explicit budgets support actual cross-page GPU conformance checks. */
    public RayAttributeArena(VkDevice device,int pageBytes,int maxPages,int directoryBytes) {
        this(device,pageBytes,maxPages,directoryBytes,true);
    }
    public RayAttributeArena(VkDevice device,int pageBytes,int maxPages,int directoryBytes,boolean compactVertices) {
        this(device,pageBytes,maxPages,directoryBytes,compactVertices,false,null);
    }
    /** Explicit shared budget for the guarded GPU retirement/overlap check. */
    public RayAttributeArena(VkDevice device,int pageBytes,int maxPages,int directoryBytes,boolean compactVertices,RayPayloadBudget budget) {
        this(device,pageBytes,maxPages,directoryBytes,compactVertices,false,java.util.Objects.requireNonNull(budget));
    }
    private RayAttributeArena(VkDevice device,int pageBytes,int maxPages,int directoryBytes,boolean compactVertices,boolean sharedBudget) {
        this(device,pageBytes,maxPages,directoryBytes,compactVertices,sharedBudget,null);
    }
    private RayAttributeArena(VkDevice device,int pageBytes,int maxPages,int directoryBytes,boolean compactVertices,boolean sharedBudget,RayPayloadBudget explicitBudget) {
        if(pageBytes<=0 || pageBytes%16!=0 || pageBytes>64*1024*1024 || maxPages<1 || maxPages>48
                || directoryBytes<=0 || directoryBytes%16!=0 || directoryBytes>64*1024*1024)
            throw new IllegalArgumentException("Invalid paged attribute budget");
        this.device=device;this.pageBytes=pageBytes;this.maxPages=maxPages;
        this.compactVertices=compactVertices;
        this.sharedBudget=sharedBudget;
        try(MemoryStack stack=MemoryStack.stackPush()) {
            var properties=VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(device.getPhysicalDevice(),properties);
            if(Integer.toUnsignedLong(properties.limits().maxStorageBufferRange())<directoryBytes)
                throw new IllegalStateException("Attribute directory exceeds device storage-buffer range");
        }
        slots=new RayAttributeRanges(directoryBytes);
        directory=new NativeBuffer(device,directoryBytes,VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if(sharedBudget) {
            synchronized(DEVICE_BUDGETS) {
                pageBudget=DEVICE_BUDGETS.computeIfAbsent(device.address(),key->new RayPayloadBudget((long)pageBytes*maxPages));
                pageBudget.register(this);
            }
        } else {pageBudget=explicitBudget==null?new RayPayloadBudget((long)pageBytes*maxPages):explicitBudget;pageBudget.register(this);}
    }
    public Upload upload(VkCommandBuffer commands,TerrainMesh mesh) {
        ensureOpen();var data=compactVertices?RaySurfaceAttributes.serializeCompact(mesh):RaySurfaceAttributes.serialize(mesh);
        if(data.remaining()>pageBytes) throw new IllegalStateException("Terrain mesh exceeds attribute page: bytes="+data.remaining());
        var slot=slots.allocate(16);
        Page page=null;RayAttributeRanges.Allocation range=null;NativeBuffer staging=null;
        try(MemoryStack stack=MemoryStack.stackPush()) {
            for(var candidate:pages) {
                range=candidate.ranges().tryAllocate(data.remaining());
                if(range!=null) {page=candidate;break;}
            }
            if(page==null) {
                if(pages.size()==maxPages) throw new PageBudgetException(false,"Ray attribute payload budget exhausted; used="+used+" budget="+(long)pageBytes*maxPages);
                var reservation=pageBudget.reserve(this,pageBytes);
                if(reservation!=RayPayloadBudget.Result.RESERVED)
                    throw new PageBudgetException(reservation==RayPayloadBudget.Result.WAIT_FOR_PEER,
                        "Ray attribute shared page budget: "+reservation+" "+pageBudget.snapshot());
                NativeBuffer buffer;
                try {buffer=new NativeBuffer(device,pageBytes,VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                        |VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT|KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                        VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);}
                catch(Throwable failure) {pageBudget.release(this,pageBytes);throw failure;}
                page=new Page(buffer,new RayAttributeRanges(pageBytes));pages.add(page);
                range=page.ranges().allocate(data.remaining());
                LogUtils.getLogger().info("SULKAN_RT_ATTRIBUTE_PAGE_ALLOCATED pages={} capacityBytes={} budgetBytes={}",pages.size(),capacityBytes(),(long)pageBytes*maxPages);
            }
            int payloadBytes=data.remaining();long address=page.buffer().deviceAddress()+range.offset();
            var combined=ByteBuffer.allocate(payloadBytes+16).order(ByteOrder.nativeOrder());
            combined.put(data).putLong(address).putInt(payloadBytes).putInt(1).flip();
            staging=new NativeBuffer(device,combined.remaining(),VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            staging.write(combined);
            AccelerationStructureBuilder.dependency(commands,VK10.VK_PIPELINE_STAGE_HOST_BIT,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_ACCESS_HOST_WRITE_BIT,VK10.VK_ACCESS_TRANSFER_READ_BIT);
            VK10.vkCmdCopyBuffer(commands,staging.handle(),page.buffer().handle(),VkBufferCopy.calloc(1,stack)
                    .srcOffset(0).dstOffset(range.offset()).size(payloadBytes));
            VK10.vkCmdCopyBuffer(commands,staging.handle(),directory.handle(),VkBufferCopy.calloc(1,stack)
                    .srcOffset(payloadBytes).dstOffset(slot.offset()).size(16));
            AccelerationStructureBuilder.dependency(commands,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR|VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK10.VK_ACCESS_TRANSFER_WRITE_BIT,VK10.VK_ACCESS_SHADER_READ_BIT);
            live.put(slot.offset(),new Payload(page,range,slot));used+=range.bytes();
            return new Upload(slot,staging);
        } catch(Throwable failure) {
            if(staging!=null) staging.close();
            if(page!=null && range!=null) page.ranges().release(range);
            slots.release(slot);throw failure;
        }
    }
    public NativeBuffer buffer() {ensureOpen();return directory;}
    public boolean compactVertices() {return compactVertices;}
    /** BLAS reads the same payload bytes as the ray decoder, without duplicate vertex uploads. */
    public long meshAddress(RayAttributeRanges.Allocation allocation) {
        ensureOpen();var payload=live.get(allocation.offset());
        if(payload==null || !payload.slot().equals(allocation)) throw new IllegalStateException("Stale attribute directory slot");
        return payload.page().buffer().deviceAddress()+payload.range().offset();
    }
    /** Requires completion of all GPU readers of this slot and payload. */
    public void release(RayAttributeRanges.Allocation allocation) {
        ensureOpen();var payload=live.get(allocation.offset());
        if(payload==null || !payload.slot().equals(allocation)) throw new IllegalStateException("Stale or already released attribute slot");
        live.remove(allocation.offset());payload.page().ranges().release(payload.range());slots.release(payload.slot());used-=payload.range().bytes();
    }
    public long usedBytes() {return used;}
    public RayPayloadBudget.Snapshot budget() {return pageBudget.snapshot();}
    public int allocations() {return live.size();}
    public int pageCount() {return pages.size();}
    public long capacityBytes() {return (long)pages.size()*pageBytes;}
    private void ensureOpen() {if(closed) throw new IllegalStateException("Ray attribute arena closed");}
    /** Requires completion of all readers before destruction. */
    @Override public void close() {
        if(!closed) {
            closed=true;
            pages.forEach(page -> {page.buffer().close();pageBudget.release(this,pageBytes);});
            pages.clear();live.clear();used=0;directory.close();
            if(sharedBudget) {
                synchronized(DEVICE_BUDGETS) {
                    pageBudget.unregister(this);
                    if(pageBudget.snapshot().arenas()==0)DEVICE_BUDGETS.remove(device.address(),pageBudget);
                }
            } else pageBudget.unregister(this);
        }
    }
}
