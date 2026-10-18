package com.sulkan.shaders.platform.vulkan;

import java.util.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/** Driver estimate, separate from NativeBuffer ownership and sampled device-wide NVIDIA VRAM. */
public final class GpuMemorySnapshot {
    public static final com.mojang.renderpearl.backend.vulkan.init.FeatureSet OPTIONAL_FEATURE = new com.mojang.renderpearl.backend.vulkan.init.FeatureSet("Sulkan profiling memory budget",Set.of("VK_EXT_memory_budget"),Set.of());
    public static Map<String,Object> capture(VkDevice device) {
        if(!device.getCapabilities().VK_EXT_memory_budget)
            return Map.of("available",false,"reason","VK_EXT_memory_budget is not enabled; no unsupported query issued");
        try(var stack=MemoryStack.stackPush()) {
            var budget=VkPhysicalDeviceMemoryBudgetPropertiesEXT.calloc(stack).sType$Default();
            var properties=VkPhysicalDeviceMemoryProperties2.calloc(stack).sType$Default().pNext(budget.address());
            VK11.vkGetPhysicalDeviceMemoryProperties2(device.getPhysicalDevice(),properties);
            var heaps=new ArrayList<Object>();long localUsage=0,localBudget=0;
            for(int i=0;i<properties.memoryProperties().memoryHeapCount();i++) {
                var heap=properties.memoryProperties().memoryHeaps(i);boolean local=(heap.flags()&VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT)!=0;
                heaps.add(Map.of("index",i,"size",heap.size(),"usage",budget.heapUsage(i),"budget",budget.heapBudget(i),"deviceLocal",local));
                if(local){localUsage+=budget.heapUsage(i);localBudget+=budget.heapBudget(i);}
            }
            return Map.of("available",true,"heaps",heaps,"deviceLocalEstimatedUsage",localUsage,"deviceLocalBudget",localBudget,
                "scope","VK_EXT_memory_budget driver estimate of current-process heap allocations, not an exact residency/instantaneous peak guarantee");
        }
    }
    private GpuMemorySnapshot(){}
}
