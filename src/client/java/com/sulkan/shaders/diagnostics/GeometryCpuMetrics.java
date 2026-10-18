package com.sulkan.shaders.diagnostics;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/** QA-only CPU attribution. Nested scopes are inclusive and must not be added together. */
public final class GeometryCpuMetrics {
    public static final boolean ENABLED = Boolean.getBoolean("sulkan.qaPerformanceProfile");

    public enum Stage {
        CAPTURE("capture.total", "vertices"),
        CAPTURE_LANES("capture.byteLanes", "vertices"),
        CAPTURE_DEFLATE("capture.deflate", "inputBytes"),
        DECODE_HIT("decode.cacheHit", "lookups", false),
        DECODE_MISS("decode.miss", "vertices"),
        DECODE_INFLATE("decode.inflate", "outputBytes"),
        DECODE_RECONSTRUCT("decode.reconstruct", "vertices"),
        DECODE_RETAIN("decode.cacheRetention", "decodedBytes"),
        ATTRIBUTE_COMPACT("attributes.packCompact", "vertices"),
        ATTRIBUTE_LEGACY("attributes.packLegacy", "vertices"),
        BLAS_PREPARE("blas.prepareAndRecord", "vertices"),
        TLAS_PREPARE("tlas.prepareAndRecord", "instances"),
        BLAS_BUILD_RECORD("blas.buildRecord", "triangles"),
        TLAS_BUILD_RECORD("tlas.buildRecord", "instances"),
        SCENE_SYNCHRONIZE("scene.synchronize", "calls"),
        SCENE_STATISTICS("diagnostics.sceneStatistics", "meshes");

        final String name, unit;
        final boolean timed;
        Stage(String name, String unit) { this(name, unit, true); }
        Stage(String name, String unit, boolean timed) { this.name = name; this.unit = unit; this.timed = timed; }
    }

    private static final java.lang.management.ThreadMXBean THREAD = ENABLED ? ManagementFactory.getThreadMXBean() : null;
    private static final com.sun.management.ThreadMXBean ALLOCATION = THREAD instanceof com.sun.management.ThreadMXBean bean ? bean : null;
    private static final boolean CPU_AVAILABLE = enableCpuTime();
    private static final boolean ALLOCATION_AVAILABLE = enableAllocatedBytes();
    private static final Totals[] TOTALS = new Totals[Stage.values().length];
    static { for (int i = 0; i < TOTALS.length; i++) TOTALS[i] = new Totals(); }

    private static final class Totals {
        long calls, workUnits, wallNanoseconds, activeThreadCpuNanoseconds, javaAllocatedBytes;
    }

    private static boolean enableCpuTime() {
        if (!ENABLED || !THREAD.isCurrentThreadCpuTimeSupported()) return false;
        try {
            if (!THREAD.isThreadCpuTimeEnabled()) THREAD.setThreadCpuTimeEnabled(true);
            return THREAD.isThreadCpuTimeEnabled();
        } catch (UnsupportedOperationException | SecurityException unavailable) { return false; }
    }

    private static boolean enableAllocatedBytes() {
        if (!ENABLED || ALLOCATION == null || !ALLOCATION.isThreadAllocatedMemorySupported()) return false;
        try {
            if (!ALLOCATION.isThreadAllocatedMemoryEnabled()) ALLOCATION.setThreadAllocatedMemoryEnabled(true);
            return ALLOCATION.isThreadAllocatedMemoryEnabled();
        } catch (UnsupportedOperationException | SecurityException unavailable) { return false; }
    }

    /** Returns null when disabled; try-with-resources accepts null without a close call. */
    public static Scope begin(Stage stage, long requestedWorkUnits) {
        return ENABLED ? new Scope(stage, requestedWorkUnits) : null;
    }

    /** Cache-hit counting deliberately avoids management/clock calls on the hit path. */
    public static void countCacheHit() {
        if (ENABLED) synchronized (TOTALS) {
            var total = TOTALS[Stage.DECODE_HIT.ordinal()]; total.calls++; total.workUnits++;
        }
    }

    public static final class Scope implements AutoCloseable {
        private final Stage stage;
        private final long units, wallStart, cpuStart, allocatedStart;
        private boolean closed;
        private Scope(Stage stage, long units) {
            this.stage = stage; this.units = units;
            // Counters describe the current calling thread, never whole-process CPU/heap.
            allocatedStart = ALLOCATION_AVAILABLE ? ALLOCATION.getCurrentThreadAllocatedBytes() : -1;
            cpuStart = CPU_AVAILABLE ? THREAD.getCurrentThreadCpuTime() : -1;
            wallStart = System.nanoTime();
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            long elapsed = System.nanoTime() - wallStart;
            long cpu = CPU_AVAILABLE ? THREAD.getCurrentThreadCpuTime() : -1;
            long allocated = ALLOCATION_AVAILABLE ? ALLOCATION.getCurrentThreadAllocatedBytes() : -1;
            synchronized (TOTALS) {
                var total = TOTALS[stage.ordinal()]; total.calls++; total.workUnits += units;
                total.wallNanoseconds += elapsed;
                if (cpuStart >= 0 && cpu >= cpuStart) total.activeThreadCpuNanoseconds += cpu - cpuStart;
                if (allocatedStart >= 0 && allocated >= allocatedStart) total.javaAllocatedBytes += allocated - allocatedStart;
            }
        }
    }

    /** Immutable values suitable for Gson and subtraction between phase-boundary snapshots. */
    public static Map<String, Object> snapshot() {
        var stages = new LinkedHashMap<String, Object>();
        synchronized (TOTALS) {
            for (var stage : Stage.values()) {
                var total = TOTALS[stage.ordinal()];
                var row = new LinkedHashMap<String, Object>();
                row.put("calls", total.calls); row.put("requestedWorkUnits", total.workUnits); row.put("workUnit", stage.unit);
                row.put("measurement", stage.timed ? "inclusive scope" : "count only; no duration measured");
                row.put("wallNanoseconds", stage.timed ? total.wallNanoseconds : null);
                row.put("activeThreadCpuNanoseconds", stage.timed && CPU_AVAILABLE ? total.activeThreadCpuNanoseconds : null);
                row.put("javaAllocatedBytes", stage.timed && ALLOCATION_AVAILABLE ? total.javaAllocatedBytes : null);
                stages.put(stage.name, java.util.Collections.unmodifiableMap(row));
            }
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("enabled", ENABLED); result.put("activeThreadCpuAvailable", CPU_AVAILABLE);
        result.put("javaAllocatedBytesAvailable", ALLOCATION_AVAILABLE);
        result.put("stages", java.util.Collections.unmodifiableMap(stages));
        result.put("scope", "QA-only cumulative calling-thread measurements. Nested scopes are inclusive; do not sum them. Work units are requested sizes, including failed calls. Java allocated bytes exclude native/direct/Vulkan/driver allocations. Instrumentation has overhead; no GPU waits or disk writes are added.");
        return java.util.Collections.unmodifiableMap(result);
    }

    private GeometryCpuMetrics() {}
}
