package com.sulkan.shaders.render.raytracing;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Render-thread-owned cache. Publications refer only to geometry accepted by Sodium's upload path. */
public final class TerrainGeometryCache {
    public record Section(long revision, int x, int y, int z, List<TerrainMesh> meshes) {
        public Section { meshes = List.copyOf(meshes); }
        public long ownedBytes() { return meshes.stream().mapToLong(TerrainMesh::ownedBytes).sum(); }
    }
    public enum Kind { REPLACE, REMOVE, CLEAR }
    public record Change(Kind kind, Object owner, Section section) {}
    public record Publication(Object owner, int x, int y, int z, List<TerrainMesh> meshes) {
        public Publication { meshes = List.copyOf(meshes); }
    }
    public record Statistics(int sections, long triangles, long ownedBytes, long revision, String failure,
                             long expandedBytes, long payloadBudgetBytes, TerrainMesh.DecodeStatistics decoded) {}
    public record MemoryStatistics(int sections,long ownedBytes,long payloadBudgetBytes,long revision,TerrainMesh.DecodeStatistics decoded) {}
    private static final Map<Object, Section> SECTIONS = new IdentityHashMap<>();
    private static final Map<Object, Change> CHANGES = new IdentityHashMap<>();
    private static boolean cleared;
    private static final long MAX_BYTES = TerrainMesh.PAYLOAD_BUDGET_BYTES;
    private static long revision;
    private static long bytes;
    private static String failure;

    private TerrainGeometryCache() {}

    public static boolean available() { return failure == null; }

    public static void publish(Object owner, int x, int y, int z, List<TerrainMesh> meshes) {
        if (!available()) return;
        Section previous = SECTIONS.get(owner);
        Section next = new Section(++revision, x, y, z, meshes);
        long total = bytes - (previous == null ? 0 : previous.ownedBytes()) + next.ownedBytes();
        if (total > MAX_BYTES) {
            fail("Terrain geometry exceeds CPU cache budget of " + MAX_BYTES + " bytes");
            return;
        }
        bytes = total;
        if (previous != null) previous.meshes().forEach(TerrainMesh::evictDecoded);
        SECTIONS.put(owner, next);
        CHANGES.put(owner, new Change(Kind.REPLACE, owner, next));
    }

    public static void remove(Object owner) {
        Section removed = SECTIONS.remove(owner);
        if (removed != null) {
            removed.meshes().forEach(TerrainMesh::evictDecoded);
            bytes -= removed.ownedBytes();
            revision++;
            CHANGES.put(owner, new Change(Kind.REMOVE, owner, null));
        }
    }

    public static void clear() {
        TerrainMesh.clearDecodedCache();
        SECTIONS.clear();
        CHANGES.clear();
        bytes = 0;
        failure = null;
        revision++;
        cleared = true;
    }

    public static void fail(String reason) {
        if (failure == null) {
            failure = reason;
            TerrainMesh.clearDecodedCache();
            SECTIONS.clear();
            CHANGES.clear();
            bytes = 0;
            revision++;
            cleared = true;
            LogUtils.getLogger().error("SULKAN_RT_GEOMETRY_UNAVAILABLE {}", reason);
        }
    }

    public static List<Section> snapshot() { return List.copyOf(SECTIONS.values()); }
    /** Seeds a new renderer after pack reload without losing section ownership. */
    public static List<Change> replay() {
        List<Change> result = new ArrayList<>(SECTIONS.size() + 1);
        result.add(new Change(Kind.CLEAR, null, null));
        SECTIONS.forEach((owner, section) -> result.add(new Change(Kind.REPLACE, owner, section)));
        return List.copyOf(result);
    }
    public static List<Change> drainChanges() {
        List<Change> pending = new ArrayList<>(CHANGES.size() + 1);
        if (cleared) pending.add(new Change(Kind.CLEAR, null, null));
        pending.addAll(CHANGES.values());
        CHANGES.clear();
        cleared = false;
        return List.copyOf(pending);
    }
    /** Constant-time QA memory snapshot; avoid scanning every mesh in each measured frame. */
    public static MemoryStatistics memoryStatistics() {
        return new MemoryStatistics(SECTIONS.size(),bytes,MAX_BYTES,revision,TerrainMesh.decodeStatistics());
    }
    public static Statistics statistics() {
        long triangles = SECTIONS.values().stream().flatMap(section -> section.meshes().stream()).mapToLong(TerrainMesh::triangleCount).sum();
        long expanded = SECTIONS.values().stream().flatMap(section -> section.meshes().stream()).mapToLong(TerrainMesh::expandedBytes).sum();
        return new Statistics(SECTIONS.size(), triangles, bytes, revision, failure, expanded, MAX_BYTES, TerrainMesh.decodeStatistics());
    }
}
