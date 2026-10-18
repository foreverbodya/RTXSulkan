package com.sulkan.shaders.diagnostics;

import com.sulkan.shaders.render.raytracing.TerrainGeometryCache;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Content identity and post-timing measurements for the disposable natural scene. */
final class NaturalSceneFingerprint {
    static String capture() throws Exception { return capture(null); }
    static String capture(java.nio.file.Path reportPath) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        var details=new java.util.ArrayList<Object>();
        var sections=TerrainGeometryCache.snapshot().stream().sorted(Comparator
            .comparingInt(TerrainGeometryCache.Section::x).thenComparingInt(TerrainGeometryCache.Section::y)
            .thenComparingInt(TerrainGeometryCache.Section::z)).toList();
        for(var section:sections) {
            digest.update((section.x()+","+section.y()+","+section.z()+":"+section.meshes().size()+"\n").getBytes(StandardCharsets.UTF_8));
            var meshHashes=new java.util.ArrayList<String>();
            var meshDetails=new java.util.ArrayList<Object>();
            for(var mesh:section.meshes()) {
                var md=MessageDigest.getInstance("SHA-256");
                md.update((mesh.opacity()+":"+mesh.strideBytes()+":"+mesh.vertexCount()+":"+mesh.triangleCount()+"\n").getBytes(StandardCharsets.UTF_8));
                append(md,mesh.packedVertices());append(md,mesh.indices());
                meshHashes.add(HexFormat.of().formatHex(md.digest()));
                if(reportPath!=null) {
                    var columns=new java.util.ArrayList<String>();var words=mesh.packedVertices();int stride=mesh.strideBytes()/4;
                    byte[] bytes=new byte[mesh.vertexCount()*4];var column=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                    for(int word=0;word<stride;word++) {
                        column.clear();for(int v=0;v<mesh.vertexCount();v++)column.put(words.get(v*stride+word));
                        columns.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                    }
                    meshDetails.add(Map.of("opacity",mesh.opacity(),"vertices",mesh.vertexCount(),"triangles",mesh.triangleCount(),
                        "packedWordSha256",columns,"meshSha256",meshHashes.getLast()));
                }
            }
            meshHashes.stream().sorted().forEach(s -> digest.update(s.getBytes(StandardCharsets.US_ASCII)));
            if(reportPath!=null)details.add(Map.of("position",java.util.List.of(section.x(),section.y(),section.z()),"meshes",meshDetails));
        }
        String result=HexFormat.of().formatHex(digest.digest());
        if(reportPath!=null)java.nio.file.Files.writeString(reportPath,new com.google.gson.GsonBuilder().setPrettyPrinting().create()
            .toJson(Map.of("sha256",result,"sections",details,"scope","Exact ordered source vertex words and shared indices within each mesh; mesh list order canonicalized. Per-word hashes aid diagnosis; no ignored fields.")));
        return result;
    }

    private static void append(MessageDigest digest,IntBuffer source) {
        int[] chunk=new int[16384];byte[] bytes=new byte[chunk.length*4];
        var view=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        while(source.hasRemaining()) {
            int n=Math.min(chunk.length,source.remaining());source.get(chunk,0,n);
            view.clear();view.put(chunk,0,n);digest.update(bytes,0,n*4);
        }
    }

    static Object hdrStatistics(ByteBuffer pixels,int width,int height) {
        if(pixels.remaining()!=width*height*8)throw new IllegalStateException("Wrong natural HDR statistics extent");
        var report=new LinkedHashMap<String,Object>();
        report.put("width",width);report.put("height",height);
        report.put("whole",region(pixels,width,0,0,width,height));
        report.put("centralShadowRoi",region(pixels,width,(int)(width*.32),height/2,(int)(width*.57),(int)(height*.75)));
        report.put("scope","One source-HDR frame before exposure and SR, fixed screen regions; no material correspondence, convergence or equal sample phase claim");
        return report;
    }

    private static Object region(ByteBuffer pixels,int width,int x0,int y0,int x1,int y1) {
        double[] l=new double[(x1-x0)*(y1-y0)];int i=0,black=0;double sum=0;
        for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++) {
            int offset=(y*width+x)*8;
            double value=.2126*Float.float16ToFloat(pixels.getShort(offset))+
                .7152*Float.float16ToFloat(pixels.getShort(offset+2))+.0722*Float.float16ToFloat(pixels.getShort(offset+4));
            if(!Double.isFinite(value) || value<0)throw new IllegalStateException("Invalid source HDR luminance");
            l[i++]=value;sum+=value;if(value<.001)black++;
        }
        Arrays.sort(l);
        return Map.of("pixelRect",java.util.List.of(x0,y0,x1,y1),"pixels",l.length,"meanLuminance",sum/l.length,
            "medianLuminance",l[l.length/2],"p10Luminance",l[(int)((l.length-1)*.1)],
            "p90Luminance",l[(int)((l.length-1)*.9)],"fractionBelow0001",(double)black/l.length);
    }
}
