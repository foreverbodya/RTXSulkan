package com.sulkan.shaders.render.raytracing;

import com.sulkan.shaders.platform.vulkan.NativeBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.Map;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;

/** Optional immutable proposal over accepted opaque/cutout emissive triangles.
 * Material IDs are the own ray-lighting pack's declared emission classes.
 * No texture/radiance approximation is stored: the shader samples live UVs.
 */
final class RayEmissionSources {
    static final int MAX_TRIANGLES=262144, STRIDE=32;
    record Snapshot(NativeBuffer buffer,int triangles,boolean overflow) {
        long address(){return buffer==null?0:buffer.deviceAddress();}
        void close(){if(buffer!=null)buffer.close();}
    }
    private final IdentityHashMap<TerrainMesh,int[]> candidates=new IdentityHashMap<>();
    private int[] extract(TerrainMesh mesh) {
        if(mesh.opacity()==TerrainMesh.Opacity.TRANSLUCENT)return new int[0];
        var source=mesh.readView();
        var result=new java.util.ArrayList<Integer>();
        var positions=source.positions();
        for(int q=0;q<mesh.vertexCount();q+=4) {
            int material=source.packedMaterial(q)&65535;
            if(material!=9 && material!=10 && material!=11 && material!=15)continue;
            for(int half=0;half<2;half++) {
                int a=q+(half==0?0:2),b=q+(half==0?1:3),c=q+(half==0?2:0);
                double ax=positions.get(b*3)-positions.get(a*3),ay=positions.get(b*3+1)-positions.get(a*3+1),az=positions.get(b*3+2)-positions.get(a*3+2);
                double bx=positions.get(c*3)-positions.get(a*3),by=positions.get(c*3+1)-positions.get(a*3+1),bz=positions.get(c*3+2)-positions.get(a*3+2);
                double x=ay*bz-az*by,y=az*bx-ax*bz,z=ax*by-ay*bx;
                if(x*x+y*y+z*z>1e-20)result.add(q/2+half);
            }
        }
        return result.stream().mapToInt(Integer::intValue).toArray();
    }
    Snapshot create(VkDevice device,Map<Integer,TerrainRayScene.Geometry> geometry) {
        var active=new IdentityHashMap<TerrainMesh,Boolean>();
        long count=0;
        for(var g:geometry.values()) {
            active.put(g.mesh(),true);
            count+=candidates.computeIfAbsent(g.mesh(),this::extract).length;
        }
        candidates.keySet().removeIf(mesh->!active.containsKey(mesh));
        // Complete fallback, never silently remove some emitters from the PDF.
        // The original BSDF emission estimator remains active on overflow.
        if(count==0 || count>MAX_TRIANGLES)return new Snapshot(null,0,count>MAX_TRIANGLES);
        var bytes=ByteBuffer.allocate(Math.toIntExact(count*STRIDE)).order(ByteOrder.nativeOrder());
        for(var g:geometry.values())for(int primitive:candidates.get(g.mesh())) {
            bytes.putInt(g.section().x()).putInt(g.section().y()).putInt(g.section().z()).putInt(g.id());
            bytes.putInt(primitive).putInt(0).putInt(0).putInt(0);
        }
        bytes.flip();
        var buffer=new NativeBuffer(device,bytes.remaining(),VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
            VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        try {buffer.write(bytes);return new Snapshot(buffer,(int)count,false);}
        catch(Throwable failure){buffer.close();throw failure;}
    }
}
