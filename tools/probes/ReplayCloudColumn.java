package com.sulkan.shaders.diagnostics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

public final class ReplayCloudColumn {
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("capture-directory phase new-report.json");
        Path root=Path.of(args[0]),out=Path.of(args[2]);if(Files.exists(out))throw new IllegalArgumentException("Report must be new");
        String stem="cloud-column-"+args[1];var gson=new com.google.gson.GsonBuilder().setPrettyPrinting().create();
        try {
            var meta=com.google.gson.JsonParser.parseString(Files.readString(root.resolve(stem+"-inputs.json"))).getAsJsonObject();
            var declared=new ArrayList<String>();meta.getAsJsonArray("resources").forEach(value->declared.add(value.getAsString()));
            if(!declared.equals(CloudColumnReference.RESOURCES) || meta.get("effectivePhase").getAsDouble()!=0
                || meta.getAsJsonObject("activeOptions").get("CLOUD_PREVIEW_STILL").getAsInt()!=1
                || !meta.get("model").getAsString().equals("one_meter_local_opacity_centroid_v1"))throw new IllegalStateException("Column capture contract changed");
            byte[] png=Files.readAllBytes(root.resolve("cloud-source.png")),noise=Files.readAllBytes(root.resolve("cloud-noise-source.png"));
            if(!hash(png).equals(meta.get("sourcePngSha256").getAsString()) || !hash(noise).equals(meta.get("noisePngSha256").getAsString()))throw new IllegalStateException("Column source hash differs");
            var buffers=new ArrayList<ByteBuffer>();for(int i=0;i<4;i++)buffers.add(read(root.resolve(stem+"-buffer-"+i+".bin")));
            var report=new java.util.LinkedHashMap<String,Object>(CloudColumnReference.compare(read(root.resolve(stem+"-frame.bin")),buffers,png,noise,meta.get("detailProfile").getAsBoolean()));
            report.put("passed",true);Files.writeString(out,gson.toJson(report));System.out.println("CLOUD_COLUMN_REPLAY_PASS "+report);
        }catch(Exception problem){Files.writeString(out,gson.toJson(java.util.Map.of("passed",false,"failure",problem.toString())));throw problem;}
    }
    private static ByteBuffer read(Path p)throws Exception{return ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN);}
    private static String hash(byte[] bytes)throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
}
