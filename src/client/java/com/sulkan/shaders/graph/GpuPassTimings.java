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
public final class GpuPassTimings {
    private final int SAMPLES=Integer.getInteger("sulkan.qaGpuTimingSamples",32);
    private int pending;
    private boolean samplingFrame;
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

    private static final class TimingPool extends VulkanQueryPool {
        TimingPool(VulkanDevice device){super(device,2);}
        long handle(){return vkQueryPool();}
    }
    private static final class Series {
        int recorded;
        boolean optional;
        final List<Double> milliseconds=new ArrayList<>();
    }

    GpuPassTimings(VulkanDevice device,String phase,Path directory,List<String> names,Map<String,Object> metadata) {
        this.device=device;this.phase=phase;this.output=directory.resolve("gpu-pass-timings-"+phase+".json");
        this.metadata=Map.copyOf(metadata);
        for(String name:names)if(passes.put(name,new Series())!=null)throw new IllegalArgumentException("Duplicate timed pass "+name);
        for(String name:List.of("geometry.upload","blas.build","tlas.build")){var series=new Series();series.optional=true;passes.put(name,series);}
        if(SAMPLES<1 || SAMPLES>256)throw new IllegalArgumentException("GPU sample count outside 1..256");
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

    public Measurement start(VulkanCommandEncoder encoder,String name) { return start(encoder,null,name); }
    public Measurement start(VulkanCommandEncoder encoder,org.lwjgl.vulkan.VkCommandBuffer commands,String name) {
        if(failure!=null)throw new IllegalStateException("GPU timing failed",failure);
        var series=passes.get(name);
        if(series==null)throw new IllegalArgumentException("Unexpected timed pass "+name);
        if(name.equals("world.total"))samplingFrame=series.recorded<SAMPLES;
        if(series.optional ? !samplingFrame : series.recorded==SAMPLES)return null;
        var pool=new TimingPool(device);
        if(commands==null)encoder.writeTimestamp(pool,0);
        else VK10.vkCmdWriteTimestamp(commands,VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,pool.handle(),0);
        pending++;
        series.recorded++;
        return new Measurement(encoder,commands,pool,series);
    }

    public final class Measurement {
        private final org.lwjgl.vulkan.VkCommandBuffer commands;
        private final VulkanCommandEncoder encoder;
        private final TimingPool pool;
        private final Series series;
        private boolean finished;
        private Measurement(VulkanCommandEncoder encoder,org.lwjgl.vulkan.VkCommandBuffer commands,TimingPool pool,Series series) {
            this.commands=commands;
            this.encoder=encoder;this.pool=pool;this.series=series;
        }
        public void finish(boolean submitted) {
            if(finished)throw new IllegalStateException("GPU timing finished twice");
            finished=true;
            if(commands==null)encoder.writeTimestamp(pool,1);
            else VK10.vkCmdWriteTimestamp(commands,VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,pool.handle(),1);
            encoder.queueForDestroy(()-> {
                try {
                    if(!submitted)throw new IllegalStateException("Timed pass did not finish recording");
                    var values=pool.getValues(0,2);
                    if(values[0].isEmpty() || values[1].isEmpty())throw new IllegalStateException("Fence completed without timestamp availability");
                    long ticks=(values[1].getAsLong()-values[0].getAsLong())&mask;
                    double milliseconds=ticks*period*1e-6;
                    if(!Double.isFinite(milliseconds) || milliseconds<0)throw new IllegalStateException("Invalid timestamp interval");
                    series.milliseconds.add(milliseconds);

                } catch(Throwable problem) {failure=problem;}
                finally {pool.destroy();pending--;try {saveIfComplete();}catch(Throwable problem){failure=problem;}}
            });
        }
    }

    boolean ready() {
        if(failure!=null)throw new IllegalStateException("GPU timing failed",failure);
        return saved;
    }

    private void saveIfComplete() throws java.io.IOException {
        if(saved || pending!=0 || passes.values().stream().anyMatch(p->!p.optional && p.milliseconds.size()!=SAMPLES))return;
        var report=new LinkedHashMap<String,Object>(metadata);
        report.put("phase",phase);report.put("samplesPerPass",SAMPLES);
        report.put("timestampPeriodNanoseconds",period);report.put("timestampValidBits",validBits);
        report.put("scope","Graphics queue ALL_COMMANDS intervals include dependency stalls. world.total spans Sulkan beginWorldFrame through post, native DLSS and present, including world raster but excluding UI/swapchain. Nested pass intervals must not be added to total. Optional geometry/BLAS/TLAS results are per-event, zero events reported explicitly. No query waits or added queue/device idle.");
        var results=new LinkedHashMap<String,Object>();
        for(var entry:passes.entrySet()) {
            var sorted=entry.getValue().milliseconds.stream().sorted().toList();
            if(sorted.isEmpty()){results.put(entry.getKey(),Map.of("events",0,"milliseconds",List.of(),"mean",0d));continue;}
            results.put(entry.getKey(),Map.of("events",sorted.size(),"milliseconds",List.copyOf(entry.getValue().milliseconds),
                    "mean",sorted.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
                    "median",(sorted.get((sorted.size()-1)/2)+sorted.get(sorted.size()/2))*.5,"p95",sorted.get((int)((sorted.size()-1)*.95)),"min",sorted.getFirst(),"max",sorted.getLast()));
        }
        report.put("passes",results);
        Files.createDirectories(output.getParent());
        Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(report));
        saved=true;
        LogUtils.getLogger().info("SULKAN_GPU_PASS_TIMINGS_PASS phase={} passes={} samples={}",phase,passes.size(),SAMPLES);
    }
}
