package com.sulkan.shaders.diagnostics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Corrupt copies of retained moving-frame inputs; originals stay unchanged. */
public final class CheckCloudReconstructionMutations {
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("capture-directory phase new-report.json");
        Path root=Path.of(args[0]),report=Path.of(args[2]);if(Files.exists(report))throw new IllegalArgumentException("Report must be new");
        String stem="cloud-reconstruction-"+args[1];var frame=read(root.resolve(stem+"-frame.bin"));
        var buffers=new ArrayList<ByteBuffer>();for(int i=0;i<10;i++)buffers.add(read(root.resolve(stem+"-buffer-"+i+".bin")));
        CloudReconstructionReference.compare(frame,buffers,true);
        int width=(int)frame.getFloat(416),height=(int)frame.getFloat(420),selected=-1,fallback=-1;
        for(int y=3;y<height;y+=7)for(int x=3;x<width;x+=7) {
            int p=y*width+x;
            if(buffers.get(9).getFloat(p*16+12)==1 && selected<0)selected=p;
            if(buffers.get(9).getFloat(p*16+12)==0 && fallback<0)fallback=p;
        }
        if(selected<0 || fallback<0)throw new IllegalStateException("No selected/fallback mutation witnesses");
        var failures=new LinkedHashMap<String,String>();
        var changed=copy(buffers,6);changed.get(6).putFloat(selected*4,.9f);reject("wrong_depth",frame,changed,failures);
        changed=copy(buffers,7);changed.get(7).putShort(selected*4,Float.floatToFloat16(100));reject("wrong_motion",frame,changed,failures);
        changed=copy(buffers,8);changed.get(8).putInt(selected*4,0);reject("wrong_validity",frame,changed,failures);
        changed=copy(buffers,9);changed.get(9).putFloat(selected*16+12,0);reject("wrong_selection",frame,changed,failures);
        changed=copy(buffers,9);changed.get(9).putFloat(selected*16,changed.get(9).getFloat(selected*16)+10);reject("wrong_proxy",frame,changed,failures);
        changed=copy(buffers,6);changed.get(6).putInt(fallback*4,changed.get(6).getInt(fallback*4)^1);reject("one_bit_fallback_change",frame,changed,failures);
        CloudReconstructionReference.compare(frame,buffers,true);
        Files.writeString(report,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(java.util.Map.of(
            "rejected",failures,"selectedWitness",selected,"fallbackWitness",fallback,"originalStillPasses",true,
            "scope","Checker rejection of six corrupted copies; not physical cloud motion or temporal image quality")));
        System.out.println("CLOUD_RECONSTRUCTION_MUTATIONS_REJECTED "+failures.size());
    }
    private static void reject(String name,ByteBuffer f,List<ByteBuffer>b,LinkedHashMap<String,String> failures) {
        try{CloudReconstructionReference.compare(f,b,true);}catch(IllegalStateException expected){failures.put(name,expected.getMessage());return;}
        throw new AssertionError("Mutation accepted: "+name);
    }
    private static ArrayList<ByteBuffer> copy(List<ByteBuffer> original,int resource) {
        var result=new ArrayList<>(original);var source=original.get(resource);
        result.set(resource,ByteBuffer.allocate(source.remaining()).order(ByteOrder.LITTLE_ENDIAN).put(source.duplicate()).flip());return result;
    }
    private static ByteBuffer read(Path p)throws Exception{return ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN);}
}
