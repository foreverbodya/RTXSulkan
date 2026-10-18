package com.sulkan.shaders.diagnostics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

public final class CheckDistantWorldMutations {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]),out=Path.of(args[2]);if(Files.exists(out))throw new IllegalArgumentException("New report required");
        var capture=ReplayDistantWorld.load(root,args[1]);DistantWorldReference.compare(capture.frame(),capture.data(),capture.mode(),capture.clouds(),capture.cloudTarget());
        int copied=-1,changed=-1;
        var guide=capture.data().get(2);for(int p=0;p<guide.remaining()/16;p++) {
            if(copied<0 && guide.getFloat(p*16)==0)copied=p;
            if(changed<0 && guide.getFloat(p*16)>0 && capture.data().get(3).getFloat(p*16)<2)changed=p;
        }
        if(copied<0 || changed<0)throw new IllegalStateException("Mutation capture lacks changed and unchanged pixels");
        final int copyPixel=copied,activePixel=changed;var results=new LinkedHashMap<String,String>();
        mutate(capture,6,"unchanged pixel",b->b.putShort(copyPixel*8,(short)(b.getShort(copyPixel*8)^1)),results);
        mutate(capture,2,"classification",b->b.putFloat(activePixel*16+8,0),results);
        mutate(capture,2,"blend weight",b->b.putFloat(copyPixel*16,.01f),results);
        mutate(capture,3,"sky target",b->b.putFloat(activePixel*16,b.getFloat(activePixel*16)+.02f),results);
        mutate(capture,1,"source depth",b->b.putInt(activePixel*16+8,Float.floatToIntBits(Float.NaN)),results);
        mutate(capture,6,"changed pixel",b->b.putShort(activePixel*8,Float.floatToFloat16(8)),results);
        if(capture.data().size()>=8)mutate(capture,7,"prequantized arithmetic",b->b.putFloat(activePixel*16,b.getFloat(activePixel*16)+.02f),results);
        if(capture.cloudTarget()==1) {
            mutate(capture,8,"foreground cloud radiance",b->b.putFloat(activePixel*16,b.getFloat(activePixel*16)+.02f),results);
            mutate(capture,8,"foreground cloud transmission",b->b.putFloat(activePixel*16+12,Float.NaN),results);
        }
        DistantWorldReference.compare(capture.frame(),capture.data(),capture.mode(),capture.clouds(),capture.cloudTarget());
        Files.writeString(out,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(java.util.Map.of("passed",true,"mutantsRejected",results,"originalStillPasses",true,"scope","In-memory single-resource corruption of an actual capture; originals never written. No physical sky, gameplay or cinematic acceptance.")));
        System.out.println("DISTANT_WORLD_MUTATIONS_PASS rejected="+results.size());
    }
    private static void mutate(ReplayDistantWorld.Capture source,int index,String name,Consumer<ByteBuffer> mutation,LinkedHashMap<String,String> result) {
        var data=new ArrayList<ByteBuffer>(source.data());var original=data.get(index).duplicate();var changed=ByteBuffer.allocate(original.remaining()).order(ByteOrder.LITTLE_ENDIAN);changed.put(original).flip();mutation.accept(changed);data.set(index,changed);
        try{DistantWorldReference.compare(source.frame(),data,source.mode(),source.clouds(),source.cloudTarget());throw new AssertionError("Mutation accepted: "+name);}
        catch(IllegalStateException expected){result.put(name,expected.toString());}
    }
}
