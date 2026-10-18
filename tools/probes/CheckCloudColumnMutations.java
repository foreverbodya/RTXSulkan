package com.sulkan.shaders.diagnostics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CheckCloudColumnMutations {
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("capture-directory phase new-report.json");
        Path root=Path.of(args[0]),out=Path.of(args[2]);if(Files.exists(out))throw new IllegalArgumentException("Report must be new");
        String stem="cloud-column-"+args[1];var f=read(root.resolve(stem+"-frame.bin"));
        byte[] png=Files.readAllBytes(root.resolve("cloud-source.png")),noise=Files.readAllBytes(root.resolve("cloud-noise-source.png"));
        var b=new ArrayList<ByteBuffer>();for(int i=0;i<4;i++)b.add(read(root.resolve(stem+"-buffer-"+i+".bin")));
        boolean detail=com.google.gson.JsonParser.parseString(Files.readString(root.resolve(stem+"-inputs.json"))).getAsJsonObject().get("detailProfile").getAsBoolean();
        CloudColumnReference.compare(f,b,png,noise,detail);
        int witness=-1,positive=-1;
        for(int id=0;id<512;id++)if(b.get(0).getFloat(id*16+8)>.001 && b.get(2).getFloat(id*16+12)>2){witness=id;break;}
        if(witness<0)throw new IllegalStateException("No nonempty multi-sample column witness");
        int count=(int)b.get(2).getFloat(witness*16+12);
        for(int i=0;i<count;i++)if(b.get(3).getFloat((witness*32+i)*16+12)>1e-5){positive=i;break;}
        if(positive<0)throw new IllegalStateException("No nonzero source sample");
        var rejected=new java.util.LinkedHashMap<String,String>();
        var changed=copy(b,0);changed.get(0).putFloat(witness*16,changed.get(0).getFloat(witness*16)+.1f);reject("wrong_optical_column",f,changed,png,noise,detail,rejected);
        changed=copy(b,0);changed.get(0).putFloat(witness*16+4,changed.get(0).getFloat(witness*16+4)+1);reject("wrong_centroid",f,changed,png,noise,detail,rejected);
        changed=copy(b,0);changed.get(0).putFloat(witness*16+8,changed.get(0).getFloat(witness*16+8)+.1f);reject("wrong_opacity",f,changed,png,noise,detail,rejected);
        int address=(witness*32+positive)*16;
        changed=copy(b,3);changed.get(3).putFloat(address,changed.get(3).getFloat(address)+1);reject("wrong_sample_position",f,changed,png,noise,detail,rejected);
        changed=copy(b,3);changed.get(3).putFloat(address+12,changed.get(3).getFloat(address+12)+.01f);reject("wrong_source_density",f,changed,png,noise,detail,rejected);
        changed=copy(b,3);changed.get(3).putFloat(31*16+12,1);reject("stale_unused_sample",f,changed,png,noise,detail,rejected);
        CloudColumnReference.compare(f,b,png,noise,detail);
        Files.writeString(out,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(java.util.Map.of("rejected",rejected,"columnWitness",witness,
            "sourceSampleWitness",positive,"originalStillPasses",true,"scope","Six in-memory corruptions rejected by source/math/unused-slot checks. Does not prove radiance or aesthetic fidelity.")));
        System.out.println("CLOUD_COLUMN_MUTATIONS_REJECTED "+rejected.size());
    }
    private static void reject(String name,ByteBuffer f,List<ByteBuffer>b,byte[] png,byte[] noise,boolean detail,java.util.Map<String,String> rejected)throws Exception {
        try{CloudColumnReference.compare(f,b,png,noise,detail);}catch(IllegalStateException expected){rejected.put(name,expected.getMessage());return;}
        throw new AssertionError("Mutation accepted: "+name);
    }
    private static ArrayList<ByteBuffer> copy(List<ByteBuffer>b,int resource){var result=new ArrayList<>(b);var source=b.get(resource);result.set(resource,ByteBuffer.allocate(source.remaining()).order(ByteOrder.LITTLE_ENDIAN).put(source.duplicate()).flip());return result;}
    private static ByteBuffer read(Path p)throws Exception{return ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN);}
}
