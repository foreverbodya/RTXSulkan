package com.sulkan.shaders.graph;

import com.google.gson.JsonElement;
import com.mojang.renderpearl.api.GpuFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pack-owned HDR reconstruction boundary; display mapping is applied after reconstruction. */
public record Reconstruction(String color,String depth,String motion,String exposure,String display,Map<String,String> displayReads) {
    public List<String> inputs() { return List.of(color,depth,motion,exposure); }
    public List<String> graphInputs() {
        var all=new java.util.ArrayList<>(inputs());all.addAll(displayReads.values());return List.copyOf(all);
    }
    static Reconstruction parse(JsonElement value,Map<String,PackGraph.Resource> resources,String present) {
        if(value==null) return null;
        if(!value.isJsonObject()) throw new IllegalArgumentException("Reconstruction must be an object");
        var json=value.getAsJsonObject();
        var keys=Set.of("type","color","depth","motion","exposure","display");
        if(!json.keySet().containsAll(keys) || json.keySet().stream().anyMatch(key->!keys.contains(key) && !key.equals("displayReads")) || !json.get("type").getAsString().equals("dlss_quality"))
            throw new IllegalArgumentException("Reconstruction requires dlss_quality and all image/display bindings");
        var displayReads=new java.util.TreeMap<String,String>();
        if(json.has("displayReads")) {
            if(!json.get("displayReads").isJsonObject())throw new IllegalArgumentException("displayReads must map sampler names to current resources");
            for(var entry:json.getAsJsonObject("displayReads").entrySet()) {
                if(!entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || entry.getKey().equals("Source") || !entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("Invalid display sampler binding "+entry.getKey());
                String name=entry.getValue().getAsString();var resource=resources.get(name);
                if(resource==null || resource.depth()!=1 || !Set.of(GpuFormat.R32_FLOAT,GpuFormat.RGBA16_FLOAT,GpuFormat.RGBA32_FLOAT).contains(resource.format()))
                    throw new IllegalArgumentException("Display inputs require current float 2D resources: "+name);
                displayReads.put(entry.getKey(),name);
            }
        }
        var result=new Reconstruction(json.get("color").getAsString(),json.get("depth").getAsString(),
                json.get("motion").getAsString(),json.get("exposure").getAsString(),json.get("display").getAsString(),java.util.Collections.unmodifiableMap(displayReads));
        if(!result.color.equals(present) || !PackFiles.safe(result.display) || Set.copyOf(result.inputs()).size()!=4)
            throw new IllegalArgumentException("Reconstruction color must be the distinct HDR present resource with a safe display shader");
        GpuFormat[] formats={GpuFormat.RGBA16_FLOAT,GpuFormat.R32_FLOAT,GpuFormat.RG16_FLOAT,GpuFormat.R32_FLOAT};
        for(int i=0;i<4;i++) {
            var resource=resources.get(result.inputs().get(i));
            if(resource==null || resource.format()!=formats[i] || resource.depth()!=1 || resource.mipmaps())
                throw new IllegalArgumentException("Invalid reconstruction image "+result.inputs().get(i));
            if(i==3 ? resource.fixedWidth()!=1 || resource.fixedHeight()!=1 : resource.fixedWidth()!=0 || resource.fixedHeight()!=0 || resource.scale()!=1)
                throw new IllegalArgumentException("Reconstruction needs world-size inputs and 1x1 exposure");
        }
        return result;
    }
}
