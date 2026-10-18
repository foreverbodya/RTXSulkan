package com.sulkan.shaders.graph;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanQueryPool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

/** Bounded, explicitly requested QA measurement. Queries are read only after the engine's fence. */
final class GpuPassTimings {
    private static final int SAMPLES=32;
    private final VulkanDevice device;
    private final String phase;
    private final Path output;
    private final Map<String,Object> metadata;
    private final Map<String,Series> passes=new LinkedHashMap<>();
    private final double period;
    private final int validBits;
    private final long mask;
    private Throwable failure;
    private boolean saved;

    private static final class Series {
        int recorded;
        final List<Double> milliseconds=new ArrayList<>();
    }

    GpuPassTimings(VulkanDevice device,String phase,Path directory,List<String> names,Map<String,Object> metadata) {
        this.device=device;this.phase=phase;this.output=directory.resolve("gpu-pass-timings-"+phase+".json");
        this.metadata=Map.copyOf(metadata);
        for(String name:names)if(passes.put(name,new Series())!=null)throw new IllegalArgumentException("Duplicate timed pass "+name);
        if(passes.isEmpty())throw new IllegalArgumentException("No compute passes to measure");
        try(var stack=MemoryStack.stackPush()) {
            var properties=VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(),properties);
            period=properties.limits().timestampPeriod();
            var count=stack.ints(0);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(device.vkDevice().getPhysicalDevice(),count,null);
            var families=VkQueueFamilyProperties.calloc(count.get(0),stack);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(device.vkDevice().getPhysicalDevice(),count,families);
            validBits=families.get(device.graphicsQueue().queueFamilyIndex()).timestampValidBits();
            if(validBits<=0 || validBits>64 || !(period>0))throw new IllegalStateException("Graphics queue has no usable GPU timestamps");
            mask=validBits==64?-1L:(1L<<validBits)-1;
        }
    }

    Measurement start(VulkanCommandEncoder encoder,String name) {
        if(failure!=null)throw new IllegalStateException("GPU timing failed",failure);
        var series=passes.get(name);
        if(series==null)throw new IllegalArgumentException("Unexpected timed pass "+name);
        if(series.recorded==SAMPLES)return null;
        var pool=(VulkanQueryPool)device.createTimestampQueryPool(2);
        encoder.writeTimestamp(pool,0);
        series.recorded++;
        return new Measurement(encoder,pool,series);
    }

    final class Measurement {
        private final VulkanCommandEncoder encoder;
        private final VulkanQueryPool pool;
        private final Series series;
        private boolean finished;
        private Measurement(VulkanCommandEncoder encoder,VulkanQueryPool pool,Series series) {
            this.encoder=encoder;this.pool=pool;this.series=series;
        }
        void finish(boolean submitted) {
            if(finished)throw new IllegalStateException("GPU timing finished twice");
            finished=true;
            encoder.writeTimestamp(pool,1);
            encoder.queueForDestroy(()-> {
                try {
                    if(!submitted)throw new IllegalStateException("Timed pass did not finish recording");
                    var values=pool.getValues(0,2);
                    if(values[0].isEmpty() || values[1].isEmpty())throw new IllegalStateException("Fence completed without timestamp availability");
                    long ticks=(values[1].getAsLong()-values[0].getAsLong())&mask;
                    double milliseconds=ticks*period*1e-6;
                    if(!Double.isFinite(milliseconds) || milliseconds<0)throw new IllegalStateException("Invalid timestamp interval");
                    series.milliseconds.add(milliseconds);
                    saveIfComplete();
                } catch(Throwable problem) {failure=problem;}
                finally {pool.destroy();}
            });
        }
    }

    boolean ready() {
        if(failure!=null)throw new IllegalStateException("GPU timing failed",failure);
        return saved;
    }

    private void saveIfComplete() throws java.io.IOException {
        if(saved || passes.values().stream().anyMatch(p->p.milliseconds.size()!=SAMPLES))return;
        var report=new LinkedHashMap<String,Object>(metadata);
        report.put("phase",phase);report.put("samplesPerPass",SAMPLES);
        report.put("timestampPeriodNanoseconds",period);report.put("timestampValidBits",validBits);
        report.put("scope","Graphics queue compute passes and native DLSS; ALL_COMMANDS timestamp intervals include barriers and dependency stalls. Excludes raster, terrain draw, acceleration-structure build and CPU work. Instrumented ranking, not uninstrumented FPS.");
        var results=new LinkedHashMap<String,Object>();
        for(var entry:passes.entrySet()) {
            var sorted=entry.getValue().milliseconds.stream().sorted().toList();
            results.put(entry.getKey(),Map.of("milliseconds",List.copyOf(entry.getValue().milliseconds),
                    "mean",sorted.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
                    "median",(sorted.get(15)+sorted.get(16))*.5,"p95",sorted.get(30),"min",sorted.getFirst(),"max",sorted.getLast()));
        }
        report.put("passes",results);
        Files.createDirectories(output.getParent());
        Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(report));
        saved=true;
        LogUtils.getLogger().info("SULKAN_GPU_PASS_TIMINGS_PASS phase={} passes={} samples={}",phase,passes.size(),SAMPLES);
    }
}
