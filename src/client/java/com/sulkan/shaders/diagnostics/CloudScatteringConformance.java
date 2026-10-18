package com.sulkan.shaders.diagnostics;

import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.NativePackRuntime;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Shared GPU scattering weights versus a closed-form plane-slab integral. */
final class CloudScatteringConformance {
    static CompletableFuture<Map<String,Object>> capture(Path evidence, String phase) {
        var reads = List.of("cloud.scattering.reference", "cloud.scattering.parameters").stream()
            .map(NativePackRuntime::diagnosticReadback).toList();
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(unused -> {
            try {
                var result = reads.get(0).join();var parameters = reads.get(1).join();
                // Preserve both actual inputs and outputs before any assertion.
                write(evidence.resolve("cloud-scattering-"+phase+"-radiance.bin"), result);
                write(evidence.resolve("cloud-scattering-"+phase+"-parameters.bin"), parameters);
                if(result.remaining()!=32*64*16 || parameters.remaining()!=32*64*16)
                    throw new IllegalStateException("Cloud scattering diagnostic extent");
                int checked=0, empty=0, equalAngles=0;double maxError=0,maxTransmissionError=0,maxBias=0;
                for(int y=0;y<64;y++)for(int x=0;x<32;x++) {
                    int p=y*32+x;
                    double[] input=new double[4];for(int c=0;c<4;c++)input[c]=parameters.getFloat(p*16+c*4);
                    double column=new double[]{0,1.0/4096,1.0/1024,1.0/64,1.0/16,.25,1,4}[x%8];
                    double viewY=.25+.75*(x/8%4)/3, sunY=y%8==0?viewY:.2+.8*(y%8)/7, cosine=-1+2.0*(y/8)/7;
                    double[] expectedInput={column,viewY,sunY,cosine};
                    for(int c=0;c<4;c++)if(!Double.isFinite(input[c]) || Math.abs(input[c]-expectedInput[c])>1e-6)
                        throw new IllegalStateException("Cloud slab input mismatch pixel="+p+" channel="+c);
                    if(column==0)empty++;if(y%8==0)equalAngles++;
                    for(int order=0;order<3;order++) {
                        double expected=CloudScatteringReference.radiance(input[0],input[1],input[2],input[3],order);
                        double actual=result.getFloat(p*16+order*4),error=Math.abs(actual-expected);
                        double bias=Math.abs(CloudScatteringReference.midpointRelativeBias(input[0],input[1],input[2],order,256));
                        // Analytic quadrature bias plus a separate FP32 accumulation allowance.
                        double bound=bias*expected + 2e-6 + 3e-5*expected;
                        maxError=Math.max(maxError,error);maxBias=Math.max(maxBias,bias);checked++;
                        if(!Double.isFinite(actual) || actual<0 || error>bound)
                            throw new IllegalStateException("Cloud slab radiance mismatch pixel="+p+" order="+order+" error="+error+" bound="+bound);
                    }
                    double actual=result.getFloat(p*16+12),expected=Math.exp(-input[0]/input[1]);
                    double error=Math.abs(actual-expected);maxTransmissionError=Math.max(maxTransmissionError,error);
                    // 256 products of rounded exp() outputs: forward error allowance 256*FLT_EPSILON.
                    if(!Double.isFinite(actual) || actual<0 || actual>1 || error>256*Math.ulp(1.0f))
                        throw new IllegalStateException("Cloud slab transmission mismatch pixel="+p+" error="+error);
                }
                var report=new LinkedHashMap<String,Object>();report.put("phase",phase);report.put("model","three_orders_view_and_light_v1");
                report.put("slabs",2048);report.put("orderComparisons",checked);report.put("emptySlabs",empty);report.put("equalViewSunSlabs",equalAngles);
                report.put("maxRadianceError",maxError);report.put("maxTransmissionError",maxTransmissionError);report.put("maxAnalyticMidpointRelativeBias",maxBias);
                report.put("scope","Shared GPU phase/step weights against independent closed-form homogeneous slab for three approximate orders, zero density, equal-angle limit and analytic quadrature bias. Not heterogeneous cloud transport, convergence of the solar density quadrature, exact multiple scattering or cinematic acceptance.");
                Files.writeString(evidence.resolve("cloud-scattering-"+phase+".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
                LogUtils.getLogger().info("SULKAN_QA_CLOUD_SCATTERING_PASS phase={} {}",phase,report);return report;
            }catch(Exception problem){throw new java.util.concurrent.CompletionException(problem);}
        });
    }
    private static void write(Path path,ByteBuffer buffer) throws java.io.IOException {
        byte[] raw=new byte[buffer.remaining()];buffer.duplicate().get(raw);Files.write(path,raw);
    }
}
