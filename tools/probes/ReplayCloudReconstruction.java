package com.sulkan.shaders.diagnostics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/** Usage: capture directory, phase, NEW report path. Original evidence is never overwritten. */
public final class ReplayCloudReconstruction {
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("directory phase new-report.json");
        Path root=Path.of(args[0]),report=Path.of(args[2]);
        if(Files.exists(report))throw new IllegalArgumentException("Replay report must be new");
        String stem="cloud-reconstruction-"+args[1];
        var inputs=com.google.gson.JsonParser.parseString(Files.readString(root.resolve(stem+"-inputs.json"))).getAsJsonObject();
        var buffers=new ArrayList<ByteBuffer>();
        int count=inputs.getAsJsonArray("resources").size();
        for(int i=0;i<count;i++)buffers.add(read(root.resolve(stem+"-buffer-"+i+".bin")));
        var gson=new com.google.gson.GsonBuilder().setPrettyPrinting().create();
        try {
            var declared=new ArrayList<String>();inputs.getAsJsonArray("resources").forEach(value->declared.add(value.getAsString()));
            if(!declared.equals(CloudReconstructionReference.RESOURCES) && !declared.equals(CloudReconstructionReference.RESOURCES_V2))throw new IllegalStateException("Replay resource order differs");
            var result=new java.util.LinkedHashMap<String,Object>(CloudReconstructionReference.compare(read(root.resolve(stem+"-frame.bin")),buffers,inputs.get("enabled").getAsBoolean()));
            result.put("passed",true);Files.writeString(report,gson.toJson(result));
            System.out.println("CLOUD_RECONSTRUCTION_REPLAY_PASS "+result);
        }catch(RuntimeException problem) {
            Files.writeString(report,gson.toJson(java.util.Map.of("passed",false,"failure",problem.toString(),"capture",root.toString(),"phase",args[1])));
            throw problem;
        }
    }
    private static ByteBuffer read(Path path)throws Exception{return ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN);}
}
