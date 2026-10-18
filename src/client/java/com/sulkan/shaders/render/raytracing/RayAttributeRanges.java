package com.sulkan.shaders.render.raytracing;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Render-thread allocator. A release is legal only after the corresponding GPU readers complete. */
public final class RayAttributeRanges {
    public record Allocation(int offset, int bytes, long serial) { public int headerWord() { return offset / 4; } }
    private final TreeMap<Integer,Integer> free = new TreeMap<>();
    private final Map<Integer,Allocation> live = new HashMap<>();
    private long serial;
    private int used;
    public RayAttributeRanges(int capacity) {
        if (capacity <= 0 || capacity % 16 != 0 || capacity > 64 * 1024 * 1024) throw new IllegalArgumentException("Invalid ray attribute capacity");
        free.put(0,capacity);
    }
    public Allocation allocate(int bytes) {
        var allocation=tryAllocate(bytes);
        if(allocation==null) throw new IllegalStateException("Ray attribute arena exhausted; requested="+bytes+" used="+used);
        return allocation;
    }
    /** Null means insufficient contiguous space; ownership is unchanged. */
    public Allocation tryAllocate(int bytes) {
        if (bytes <= 0) throw new IllegalArgumentException("Empty attribute allocation");
        int aligned = Math.multiplyExact(Math.addExact(bytes,15)/16,16);
        Integer offset = null, length = null;
        for (var entry : free.entrySet()) if (entry.getValue() >= aligned) { offset=entry.getKey(); length=entry.getValue(); break; }
        if (offset == null) return null;
        free.remove(offset);
        if (length > aligned) free.put(offset+aligned,length-aligned);
        var allocation = new Allocation(offset,aligned,++serial);
        live.put(offset,allocation);
        used += aligned;
        return allocation;
    }
    public void release(Allocation allocation) {
        if (!live.remove(allocation.offset(),allocation)) throw new IllegalStateException("Stale or already released ray allocation");
        used -= allocation.bytes();
        int offset=allocation.offset(),bytes=allocation.bytes();
        var before=free.lowerEntry(offset);
        if (before != null && before.getKey()+before.getValue() == offset) {
            offset=before.getKey(); bytes+=before.getValue(); free.remove(before.getKey());
        }
        var after=free.ceilingEntry(offset);
        if (after != null && offset+bytes == after.getKey()) { bytes+=after.getValue(); free.remove(after.getKey()); }
        free.put(offset,bytes);
    }
    public int usedBytes() { return used; }
    public int allocations() { return live.size(); }
}
