package com.sulkan.shaders.diagnostics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Replay the exact retained candidate inputs; optionally reject corrupted copies. */
public final class ReplayCloudDepthComposition {
    public static void main(String[] args)throws Exception {
        if(args.length<3 || args.length>4)throw new IllegalArgumentException("capture-directory phase NEW-report [mutations]");
        Path root=Path.of(args[0]),report=Path.of(args[2]);if(Files.exists(report))throw new IllegalArgumentException("Report exists");
        String stem="cloud-preview-"+args[1];var frame=read(root.resolve(stem+"-frame.bin"));
        var buffers=new ArrayList<ByteBuffer>();for(int i=0;i<9;i++)buffers.add(read(root.resolve(stem+"-buffer-"+i+".bin")));
        var gson=new com.google.gson.GsonBuilder().setPrettyPrinting().create();
        try {
            var result=new LinkedHashMap<String,Object>(new CloudDepthCompositionReference(frame,buffers).compare());
            if(args.length==4) {
                int filtered=-1,clipped=-1;
                for(int p=0;p<buffers.get(8).remaining()/16;p++) {
                    int mode=(int)buffers.get(8).getFloat(p*16+4);
                    if(mode==2 && buffers.get(8).getFloat(p*16)>1 && filtered<0)filtered=p;
                    if(mode==1 && buffers.get(2).getFloat(p*16+4)<32 && clipped<0)clipped=p;
                }
                if(filtered<0 || clipped<0)throw new IllegalStateException("Missing mutation witnesses");
                var rejected=new LinkedHashMap<String,String>();
                var changed=copy(buffers,8);changed.get(8).putFloat(filtered*16+12,((int)changed.get(8).getFloat(filtered*16+12))^1);
                reject("wrong_support_mask",frame,changed,rejected);
                changed=copy(buffers,8);changed.get(8).putFloat(filtered*16,changed.get(8).getFloat(filtered*16)+10);
                reject("wrong_supported_proxy",frame,changed,rejected);
                changed=copy(buffers,8);changed.get(8).putFloat(clipped*16+4,3);
                reject("clipped_ray_wrong_mode",frame,changed,rejected);
                changed=copy(buffers,1);changed.get(1).putFloat(clipped*16+12,0);
                reject("opaque_short_cloud_prefix",frame,changed,rejected);
                new CloudDepthCompositionReference(frame,buffers).compare();
                result.put("mutationsRejected",rejected);result.put("originalStillPasses",true);
            }
            result.put("passed",true);Files.writeString(report,gson.toJson(result));System.out.println("CLOUD_DEPTH_REPLAY_PASS "+result);
        }catch(RuntimeException error) {
            Files.writeString(report,gson.toJson(java.util.Map.of("passed",false,"failure",error.toString())));throw error;
        }
    }
    private static void reject(String name,ByteBuffer f,List<ByteBuffer>b,LinkedHashMap<String,String> rejected) {
        try{new CloudDepthCompositionReference(f,b).compare();}catch(IllegalStateException expected){rejected.put(name,expected.getMessage());return;}
        throw new AssertionError("Mutation accepted: "+name);
    }
    private static ArrayList<ByteBuffer> copy(List<ByteBuffer> original,int resource) {
        var result=new ArrayList<>(original);var source=original.get(resource);
        result.set(resource,ByteBuffer.allocate(source.remaining()).order(ByteOrder.LITTLE_ENDIAN).put(source.duplicate()).flip());return result;
    }
    private static ByteBuffer read(Path p)throws Exception{return ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN);}
}
