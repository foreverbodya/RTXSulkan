package com.sulkan.shaders.graph;

import java.util.Map;

/** Render-thread QA counters. Engine/NGX-internal barriers and descriptor pools are excluded. */
public final class ProfileCounters {
    public static final boolean ENABLED=Boolean.getBoolean("sulkan.qaPerformanceProfile");
    private static long barriers,asBarriers,dispatches,descriptorPools,descriptorFrees;
    public static void barrier(){if(ENABLED)barriers++;}
    public static void asBarrier(){if(ENABLED)asBarriers++;}
    public static void dispatch(){if(ENABLED)dispatches++;}
    public static void descriptorAllocated(){if(ENABLED)descriptorPools++;}
    public static void descriptorFreed(){if(ENABLED)descriptorFrees++;}
    public static Map<String,Long> snapshot(){return Map.of("computeBarriers",barriers,"asBarriers",asBarriers,
        "dispatches",dispatches,"descriptorPoolAllocations",descriptorPools,"descriptorPoolFrees",descriptorFrees,
        "liveDescriptorPools",descriptorPools-descriptorFrees);}
    private ProfileCounters(){}
}
