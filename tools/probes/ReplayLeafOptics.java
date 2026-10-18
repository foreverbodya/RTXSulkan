package com.sulkan.shaders.diagnostics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

public final class ReplayLeafOptics {
    public static void main(String[] args)throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("capture-directory capture-name expected-mode new-report.json");
        Path dir=Path.of(args[0]),out=Path.of(args[3]);int mode=Integer.parseInt(args[2]);
        if(Files.exists(out))throw new IllegalArgumentException("New report required");
        var gson=new com.google.gson.GsonBuilder().setPrettyPrinting().create();
        try {
            var metadata=com.google.gson.JsonParser.parseString(Files.readString(dir.resolve("lighting-audit-"+args[1]+"-inputs.json"))).getAsJsonObject();
            if(metadata.getAsJsonObject("activeOptions").get("FOLIAGE_OPTICS").getAsDouble()!=mode)
                throw new IllegalStateException("Captured optics option differs from requested replay");
            boolean declared=false;for(var name:metadata.getAsJsonArray("resources"))if(name.getAsString().equals("foliage.optics"))declared=true;
            if(!declared)throw new IllegalStateException("No declared optics resource");
            byte[] raw=Files.readAllBytes(dir.resolve("lighting-audit-"+args[1]+"-foliage.optics.bin"));
            var report=new java.util.LinkedHashMap<String,Object>(LeafOpticsReference.compare(ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN),mode));
            report.put("passed",true);report.put("capture",args[1]);
            report.put("rawSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw)));
            Files.writeString(out,gson.toJson(report));System.out.println("LEAF_OPTICS_REPLAY_PASS "+report);
        }catch(Exception failure){Files.writeString(out,gson.toJson(java.util.Map.of("passed",false,"failure",failure.toString())));throw failure;}
    }
}
