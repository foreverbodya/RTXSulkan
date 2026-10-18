package com.sulkan.shaders.diagnostics;

import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.NativePackRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.joml.Vector3d;

/** GPU endpoint stress checks against ideal double 24-bit midpoint sampling. */
final class IndirectSamplingConformance {
    static void verify(Minecraft client,Path evidence,Runnable success,Consumer<Throwable> failure) {
        var current=NativePackRuntime.diagnosticReadback("indirect.sampling.contract");
        var previous=NativePackRuntime.diagnosticReadback("indirect.sampling.legacy");
        java.util.concurrent.CompletableFuture.allOf(current,previous).whenComplete((unused,error)->client.execute(()->{
            if(error!=null){failure.accept(error);return;}
            try {
                var pixels=current.join();var legacy=previous.join();
                if(pixels.remaining()!=4096*4*16 || legacy.remaining()!=pixels.remaining())throw new IllegalStateException("Wrong sampler contract extent");
                double maxDirection=0,maxPdf=0,maxLegacyDirection=0;int legacyRejected=0;
                for(int row=0;row<4;row++) {
                    var n=switch(row) {
                        case 0 -> new Vector3d(1,0,0);
                        case 1 -> new Vector3d(1,(float)-.000005424,(float)-.00004059).normalize();
                        case 2 -> new Vector3d(0,0,1);
                        default -> new Vector3d((float).3,(float).4,(float).866025403784).normalize();
                    };
                    var helper=Math.abs(n.z)<.999?new Vector3d(0,0,1):new Vector3d(1,0,0);
                    var tangent=helper.cross(n,new Vector3d()).normalize();
                    var bitangent=n.cross(tangent,new Vector3d());
                    for(int x=0;x<4096;x++) {
                        int radial=x<2048?0xffffff-x:x-2048;
                        double u=(radial+.5)/16777216.0,v=(0x5abcde+.5)/16777216.0;
                        var expected=new Vector3d(tangent).mul(Math.sqrt(u)*Math.cos(2*Math.PI*v))
                            .fma(Math.sqrt(u)*Math.sin(2*Math.PI*v),bitangent).fma(Math.sqrt(1-u),n).normalize();
                        int p=(row*4096+x)*16;
                        double legacyDirectionError=0;
                        for(int c=0;c<3;c++) {
                            double actual=pixels.getFloat(p+c*4),e=Math.abs(actual-expected.get(c));
                            if(!Double.isFinite(actual) || e>1e-5)throw new IllegalStateException("Sampler endpoint direction mismatch row="+row+" bin="+x+" error="+e);
                            maxDirection=Math.max(maxDirection,e);
                            double old=legacy.getFloat(p+c*4);
                            if(!Double.isFinite(old))throw new IllegalStateException("Nonfinite legacy sampler counterexample");
                            legacyDirectionError=Math.max(legacyDirectionError,Math.abs(old-expected.get(c)));
                        }
                        if(legacyDirectionError>1e-5)legacyRejected++;
                        maxLegacyDirection=Math.max(maxLegacyDirection,legacyDirectionError);
                        double actual=pixels.getFloat(p+12),e=Math.abs(actual-Math.max(n.dot(expected),0)/Math.PI);
                        if(!Double.isFinite(actual) || e>2e-6)throw new IllegalStateException("Sampler endpoint PDF mismatch row="+row+" bin="+x+" error="+e);
                        maxPdf=Math.max(maxPdf,e);
                    }
                }
                if(legacyRejected==0)throw new IllegalStateException("Sampler stress cases did not detect old subtraction error");
                var report=Map.of("directions",16384,"radialBinsAtEachEndpoint",2048,"normalBases",4,
                    "maxDirectionError",maxDirection,"maxPdfError",maxPdf,"scope",
                    "Actual GPU cosine directions/PDF for endpoint bins against double ideal 24-bit midpoint sampling; old subtraction is a measured GPU counterexample. No tolerance relaxation. Not transport convergence.",
                    "legacyDirectionsOutsideTolerance",legacyRejected,"maxLegacyDirectionError",maxLegacyDirection);
                Files.writeString(evidence.resolve("indirect-sampling-contract.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
                LogUtils.getLogger().info("SULKAN_QA_INDIRECT_SAMPLING_PASS {}",report);success.run();
            }catch(Throwable problem){failure.accept(problem);}
        }));
    }
}
