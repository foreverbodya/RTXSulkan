package com.sulkan.shaders.render.raytracing;

import java.util.IdentityHashMap;
import java.util.Objects;

/** Shared ownership of actual attribute pages, including pages awaiting GPU retirement.
 * This is an attribute-page budget, not a limit on all Vulkan/driver allocations.
 */
public final class RayPayloadBudget {
    public static final long PAGE_BYTES = 64L * 1024 * 1024;
    public static final long MAX_BYTES = 3L * 1024 * 1024 * 1024;
    public enum Result { RESERVED, WAIT_FOR_PEER, HARD_LIMIT }
    public record Snapshot(long reservedBytes, long limitBytes, int arenas) {}
    private final long limit;
    private final IdentityHashMap<Object, Long> owners = new IdentityHashMap<>();
    private long reserved;

    public RayPayloadBudget(long limit) {
        if (limit <= 0 || limit > MAX_BYTES) throw new IllegalArgumentException("Invalid attribute page budget");
        this.limit = limit;
    }
    /** Reserve at least three quarters of the largest local heap for other users,
     * with a separate absolute 3 GiB ceiling. Round down to complete pages.
     */
    public static long defaultBytes(long localHeapBytes) {
        long result = Math.min(MAX_BYTES, localHeapBytes / 4) / PAGE_BYTES * PAGE_BYTES;
        if (result < PAGE_BYTES) throw new IllegalArgumentException("Device local heap is too small for bounded ray attributes");
        return result;
    }
    public synchronized void register(Object owner) {
        Objects.requireNonNull(owner);
        if (owners.putIfAbsent(owner, 0L) != null) throw new IllegalStateException("Attribute arena already registered");
    }
    public synchronized Result reserve(Object owner, long bytes) {
        long owned = ownedBytes(owner);
        if (bytes <= 0) throw new IllegalArgumentException("Empty page reservation");
        if (bytes > limit - owned) return Result.HARD_LIMIT;
        if (bytes > limit - reserved)
            return reserved > owned ? Result.WAIT_FOR_PEER : Result.HARD_LIMIT;
        owners.put(owner, owned + bytes); reserved += bytes;
        return Result.RESERVED;
    }
    public synchronized void release(Object owner, long bytes) {
        long owned = ownedBytes(owner);
        if (bytes <= 0 || bytes > owned) throw new IllegalStateException("Invalid attribute page release");
        owners.put(owner, owned - bytes); reserved -= bytes;
    }
    public synchronized void unregister(Object owner) {
        if (ownedBytes(owner) != 0) throw new IllegalStateException("Attribute pages still owned at arena close");
        owners.remove(owner);
    }
    public synchronized long ownedBytes(Object owner) {
        Long result = owners.get(owner);
        if (result == null) throw new IllegalStateException("Unknown attribute arena");
        return result;
    }
    public synchronized Snapshot snapshot() { return new Snapshot(reserved, limit, owners.size()); }
}
