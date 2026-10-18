package com.sulkan.shaders.diagnostics;

import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.NativePackRuntime;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.joml.Vector3d;

/** Independent double arithmetic over the actual filter inputs, not a convergence oracle. */
final class IndirectSpatialConformance {
    static void verify(Minecraft client,Path evidence,Runnable success,Consumer<Throwable> failure) {
        try {
            var frame=NativePackRuntime.diagnosticFrame();
            var names=new ArrayList<>(List.of("indirect.accum","indirect.spatial.1","indirect.spatial.2","indirect.spatial.4","indirect.position","indirect.normal"));
            if(Boolean.getBoolean("sulkan.qaLeafIndirect"))names.addAll(List.of("leaf.indirect.accum","leaf.indirect.spatial.1","leaf.indirect.spatial.2","leaf.indirect.spatial.4"));
            var reads=names.stream().map(NativePackRuntime::diagnosticReadback).toList();
            CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).whenComplete((unused,error) -> client.execute(() -> {
                if(error!=null){failure.accept(error);return;}
                try {
                    var reports=new ArrayList<Object>();
                    for(int pass=0;pass<3;pass++) reports.add(compare(frame,reads.get(pass).join(),reads.get(pass+1).join(),reads.get(4).join(),reads.get(5).join(),1<<pass));
                    var report=new LinkedHashMap<String,Object>();report.put("passes",reports);
                    if(Boolean.getBoolean("sulkan.qaLeafIndirect")) {
                        var backReports=new ArrayList<Object>();
                        for(int pass=0;pass<3;pass++)backReports.add(compare(frame,reads.get(6+pass).join(),reads.get(7+pass).join(),reads.get(4).join(),reads.get(5).join(),1<<pass));
                        report.put("transmissionPasses",backReports);
                    }
                    report.put("scope","Independent double filter arithmetic using actual demodulated input and surface guides; not high-sample transport convergence, unbiased reconstruction or cinematic acceptance.");
                    Files.writeString(evidence.resolve("indirect-spatial.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
                    LogUtils.getLogger().info("SULKAN_QA_INDIRECT_SPATIAL_PASS {}",report);success.run();
                }catch(Throwable problem){failure.accept(problem);}
            }));
        }catch(Throwable problem){failure.accept(problem);}
    }
    private static Object compare(ByteBuffer frame,ByteBuffer input,ByteBuffer output,ByteBuffer position,ByteBuffer normal,int step) {
        int width=(int)frame.getFloat(416),height=(int)frame.getFloat(420),pixels=width*height;
        if(input.remaining()!=pixels*8 || output.remaining()!=pixels*8 || position.remaining()!=pixels*16 || normal.remaining()!=pixels*8)
            throw new IllegalStateException("Wrong spatial filter image extent");
        var camera=new Vector3d(frame.getFloat(1104),frame.getFloat(1108),frame.getFloat(1112));
        int[] kernel={1,4,6,4,1};
        int samples=0,filtered=0,invalid=0,normalRejected=0,materialRejected=0,planeRejected=0,distanceRejected=0;
        double maximumError=0,maximumChange=0;
        for(int y=0;y<height;y+=Math.max(1,height/83)) for(int x=0;x<width;x+=Math.max(1,width/101)) {
            int center=y*width+x;double count=half(input,center,3);
            if(count<1 || half(normal,center,3)<.5) {
                invalid++;
                for(int c=0;c<4;c++) if(half(input,center,c)!=half(output,center,c)) throw new IllegalStateException("Filter changed invalid center");
                continue;
            }
            var p=vec(position,center,16,false);var n=vec(normal,center,8,true).normalize();
            var relative=new Vector3d(p).sub(camera);
            double footprint=2*relative.length()/(Math.max(Math.abs(frame.getFloat(20)),.01)*height);
            double limit=Math.min(2,.01+4*footprint*step/Math.max(Math.abs(n.dot(new Vector3d(relative).negate().normalize())),.1));
            double[] sum=new double[3];double weights=0;int accepted=0;
            for(int dy=-2;dy<=2;dy++) for(int dx=-2;dx<=2;dx++) {
                int qx=x+dx*step,qy=y+dy*step;if(qx<0 || qy<0 || qx>=width || qy>=height) continue;
                int q=qy*width+qx;
                if(half(input,q,3)<1 || half(normal,q,3)<.5) continue;
                if(position.getFloat(q*16+12)!=position.getFloat(center*16+12)){materialRejected++;continue;}
                var delta=vec(position,q,16,false).sub(p);
                double agreement=n.dot(vec(normal,q,8,true).normalize()),plane=Math.abs(delta.dot(n));
                if(agreement<=.98){normalRejected++;continue;}
                if(plane>.02){planeRejected++;continue;}
                if(delta.length()>limit){distanceRejected++;continue;}
                double weight=kernel[dx+2]*kernel[dy+2]*Math.pow(Math.max(agreement,0),32)*Math.exp(-plane/.01);
                for(int c=0;c<3;c++) sum[c]+=half(input,q,c)*weight;
                weights+=weight;accepted++;
            }
            if(weights<=0) throw new IllegalStateException("Spatial center has no weight");
            samples++;if(accepted>1)filtered++;
            if(half(output,center,3)!=count) throw new IllegalStateException("Filter changed temporal count");
            for(int c=0;c<3;c++) {
                double expected=sum[c]/weights,actual=half(output,center,c),error=Math.abs(expected-actual);
                maximumError=Math.max(maximumError,error);maximumChange=Math.max(maximumChange,Math.abs(actual-half(input,center,c)));
                if(!Double.isFinite(actual) || actual<0 || error>.001+.0015*Math.abs(expected))
                    throw new IllegalStateException("Spatial mismatch step="+step+" pixel="+center+" expected="+expected+" actual="+actual+" error="+error);
            }
        }
        if(samples<100 || filtered<10) throw new IllegalStateException("Insufficient spatial filter coverage: "+samples+" / "+filtered);
        var report=new LinkedHashMap<String,Object>();report.put("step",step);report.put("samples",samples);report.put("filtered",filtered);report.put("invalidCentersPreserved",invalid);
        report.put("normalRejectedTaps",normalRejected);report.put("materialRejectedTaps",materialRejected);report.put("planeRejectedTaps",planeRejected);report.put("distanceRejectedTaps",distanceRejected);
        report.put("maxError",maximumError);report.put("maxChange",maximumChange);return report;
    }
    private static double half(ByteBuffer b,int pixel,int channel){return Float.float16ToFloat(b.getShort(pixel*8+channel*2));}
    private static Vector3d vec(ByteBuffer b,int pixel,int stride,boolean half){return half?new Vector3d(half(b,pixel,0),half(b,pixel,1),half(b,pixel,2)):new Vector3d(b.getFloat(pixel*stride),b.getFloat(pixel*stride+4),b.getFloat(pixel*stride+8));}
}
