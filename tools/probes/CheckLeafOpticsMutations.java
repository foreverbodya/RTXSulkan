package com.sulkan.shaders.diagnostics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

public final class CheckLeafOpticsMutations {
    public static void main(String[] args)throws Exception {
        Path input=Path.of(args[0]),out=Path.of(args[2]);int mode=Integer.parseInt(args[1]);
        if(Files.exists(out))throw new IllegalArgumentException("New report required");
        byte[] original=Files.readAllBytes(input);var results=new LinkedHashMap<String,String>();
        LeafOpticsReference.compare(wrap(original),mode);
        mutate(original,mode,"reflection coefficient",b->b.putFloat(100*16,b.getFloat(100*16)+.01f),results);
        mutate(original,mode,"transmission coefficient",b->b.putFloat((4096+100)*16+4,b.getFloat((4096+100)*16+4)+.01f),results);
        mutate(original,mode,"option label",b->b.putFloat(12,mode==0?1:0),results);
        mutate(original,mode,"nonfinite coefficient",b->b.putFloat(100*16+8,Float.NaN),results);
        var shortBuffer=wrap(original).limit(original.length-16);
        try{LeafOpticsReference.compare(shortBuffer,mode);throw new AssertionError("Truncated extent accepted");}
        catch(IllegalStateException expected){results.put("truncated extent",expected.toString());}
        LeafOpticsReference.compare(wrap(original),mode);
        if(!java.util.Arrays.equals(original,Files.readAllBytes(input)))throw new IllegalStateException("Original capture changed");
        Files.writeString(out,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(java.util.Map.of("passed",true,"mutantsRejected",results,"originalStillPasses",true,"scope","In-memory corruption of an actual GPU coefficient capture only; originals preserved. Not a physical material reference.")));
        System.out.println("LEAF_OPTICS_MUTATIONS_PASS rejected="+results.size());
    }
    private static void mutate(byte[] bytes,int mode,String name,Consumer<ByteBuffer> mutation,LinkedHashMap<String,String> result) {
        var changed=wrap(bytes.clone());mutation.accept(changed);
        try{LeafOpticsReference.compare(changed,mode);throw new AssertionError("Mutant accepted: "+name);}
        catch(IllegalStateException expected){result.put(name,expected.toString());}
    }
    private static ByteBuffer wrap(byte[] bytes){return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);}
}
