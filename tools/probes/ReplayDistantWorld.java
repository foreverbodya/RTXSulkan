package com.sulkan.shaders.diagnostics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ReplayDistantWorld {
    record Capture(ByteBuffer frame,List<ByteBuffer> data,int mode,boolean clouds,int cloudTarget) {}
    static Capture load(Path root,String phase)throws Exception {
        String stem="distant-world-"+phase;
        var meta=com.google.gson.JsonParser.parseString(Files.readString(root.resolve(stem+"-inputs.json"))).getAsJsonObject();
        String model=meta.get("model").getAsString();boolean volume=model.equals("loaded_world_boundary_v3"),separated=volume || model.equals("loaded_world_boundary_v2");
        if(!separated && !model.equals("loaded_world_boundary_v1"))throw new IllegalStateException("Distant-world model changed");
        var roles=new ArrayList<String>();meta.getAsJsonArray("roles").forEach(v->roles.add(v.getAsString()));
        if(!roles.equals(volume?DistantWorldReference.ROLES_V3:separated?DistantWorldReference.ROLES_V2:DistantWorldReference.ROLES))throw new IllegalStateException("Distant-world roles differ");
        var options=meta.getAsJsonObject("activeOptions");double value=meta.get("mode").getAsDouble();
        if((value!=0 && value!=1) || options.get("DISTANT_WORLD_BLEND").getAsDouble()!=value)throw new IllegalStateException("Distant-world mode mismatch");
        boolean clouds=meta.get("clouds").getAsBoolean();
        int cloudTarget=volume?meta.get("cloudTarget").getAsInt():0;
        if(volume && (!clouds || (cloudTarget!=0 && cloudTarget!=1) || cloudTarget!=(options.has("DISTANT_CLOUD_TARGET_SCREEN")?options.get("DISTANT_CLOUD_TARGET_SCREEN").getAsInt():1)))throw new IllegalStateException("Distant-world cloud target mismatch");
        if(clouds!=(options.has("CLOUD_PREVIEW_STILL") && options.get("CLOUD_PREVIEW_STILL").getAsDouble()==1))throw new IllegalStateException("Distant-world sky mode mismatch");
        boolean water=options.has("WATER_HDR") && options.get("WATER_HDR").getAsDouble()==1;
        boolean reflection=options.has("SMOOTH_METAL_REFLECTIONS") && options.get("SMOOTH_METAL_REFLECTIONS").getAsDouble()==1;
        String base=clouds?"cloud.composite.hdr":water?"water.composite.hdr":reflection?"ray.composite.hdr":"ray.direct.hdr";
        var expected=new ArrayList<>(List.of(base,"distant.world.source","distant.world.guide","distant.world.target",clouds?"cloud.environment":"atmosphere.sky","atmosphere.lighting","distant.world.hdr"));
        if(separated)expected.add("distant.world.linear");
        if(volume)expected.addAll(List.of("cloud.compose.coefficients","atmosphere.sky"));
        var names=new ArrayList<String>();meta.getAsJsonArray("resources").forEach(v->names.add(v.getAsString()));
        if(!names.equals(expected))throw new IllegalStateException("Distant-world input resource names differ");
        var data=new ArrayList<ByteBuffer>();for(int i=0;i<expected.size();i++)data.add(read(root.resolve(stem+"-buffer-"+i+".bin")));
        return new Capture(read(root.resolve(stem+"-frame.bin")),data,(int)value,clouds,cloudTarget);
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]),out=Path.of(args[2]);if(Files.exists(out))throw new IllegalArgumentException("New report required");
        var gson=new com.google.gson.GsonBuilder().setPrettyPrinting().create();
        try {
            var capture=load(root,args[1]);var report=new java.util.LinkedHashMap<String,Object>(DistantWorldReference.compare(capture.frame,capture.data,capture.mode,capture.clouds,capture.cloudTarget));
            report.put("passed",true);report.put("phase",args[1]);Files.writeString(out,gson.toJson(report));System.out.println("DISTANT_WORLD_REPLAY_PASS "+report);
        }catch(Exception failure){Files.writeString(out,gson.toJson(java.util.Map.of("passed",false,"failure",failure.toString())));throw failure;}
    }
    private static ByteBuffer read(Path path)throws Exception{return ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN);}
}
