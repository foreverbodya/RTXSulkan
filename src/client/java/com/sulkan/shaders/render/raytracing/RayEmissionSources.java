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
    private record Light(int index,float x,float y,float z,float power,float radius) {}
    private record Node(float x,float y,float z,float power,float radius,int left,int right,int light) {}
    private static int tree(java.util.List<Node> nodes,Light[] lights,int begin,int end) {
        int index=nodes.size();nodes.add(null);
        if(end-begin==1){var l=lights[begin];nodes.set(index,new Node(l.x,l.y,l.z,l.power,l.radius,-1,-1,l.index));return index;}
        float[] lo={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY},hi={Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        for(int i=begin;i<end;i++){var l=lights[i];float[] p={l.x,l.y,l.z};for(int a=0;a<3;a++){lo[a]=Math.min(lo[a],p[a]);hi[a]=Math.max(hi[a],p[a]);}}
        int axis=hi[1]-lo[1]>hi[0]-lo[0]?1:0;if(hi[2]-lo[2]>hi[axis]-lo[axis])axis=2;
        final int sortAxis=axis;
        java.util.Arrays.sort(lights,begin,end,java.util.Comparator.comparingDouble(l->sortAxis==0?l.x:sortAxis==1?l.y:l.z));
        int middle=(begin+end)>>>1,left=tree(nodes,lights,begin,middle),right=tree(nodes,lights,middle,end);
        var a=nodes.get(left);var b=nodes.get(right);float power=a.power+b.power;
        float x=(a.x*a.power+b.x*b.power)/power,y=(a.y*a.power+b.y*b.power)/power,z=(a.z*a.power+b.z*b.power)/power;
        float ra=(float)Math.sqrt((a.x-x)*(a.x-x)+(a.y-y)*(a.y-y)+(a.z-z)*(a.z-z))+a.radius;
        float rb=(float)Math.sqrt((b.x-x)*(b.x-x)+(b.y-y)*(b.y-y)+(b.z-z)*(b.z-z))+b.radius;
        nodes.set(index,new Node(x,y,z,power,Math.max(ra,rb),left,right,-1));return index;
    }
    Snapshot create(VkDevice device,Map<Integer,TerrainRayScene.Geometry> geometry,boolean weighted) {
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
        long nodeCount=weighted?2*count-1:0;
        var bytes=ByteBuffer.allocate(Math.toIntExact((count+nodeCount)*STRIDE)).order(ByteOrder.nativeOrder());
        var lights=weighted?new Light[(int)count]:null;int lightIndex=0;
        for(var g:geometry.values())for(int primitive:candidates.get(g.mesh())) {
            bytes.putInt(g.section().x()).putInt(g.section().y()).putInt(g.section().z()).putInt(g.id());
            bytes.putInt(primitive).putInt(0).putInt(0).putInt(0);
            if(weighted){
                var source=g.mesh().readView();var positions=source.positions();int q=(primitive/2)*4;
                int a=q+((primitive&1)==0?0:2),b=q+((primitive&1)==0?1:3),c=q+((primitive&1)==0?2:0);
                float ax=positions.get(a*3),ay=positions.get(a*3+1),az=positions.get(a*3+2);
                float bx=positions.get(b*3),by=positions.get(b*3+1),bz=positions.get(b*3+2);
                float cx=positions.get(c*3),cy=positions.get(c*3+1),cz=positions.get(c*3+2);
                float nx=(by-ay)*(cz-az)-(bz-az)*(cy-ay),ny=(bz-az)*(cx-ax)-(bx-ax)*(cz-az),nz=(bx-ax)*(cy-ay)-(by-ay)*(cx-ax);
                float area=.5f*(float)Math.sqrt(nx*nx+ny*ny+nz*nz),x=(ax+bx+cx)/3,y=(ay+by+cy)/3,z=(az+bz+cz)/3;
                int material=source.packedMaterial(q)&65535;float emission=material==9?4:material==10?1.5f:material==11?2:8;
                float radius=0;for(int vertex:new int[]{a,b,c}){float dx=positions.get(vertex*3)-x,dy=positions.get(vertex*3+1)-y,dz=positions.get(vertex*3+2)-z;radius=Math.max(radius,(float)Math.sqrt(dx*dx+dy*dy+dz*dz));}
                // Proposal power uses material emission and area. Live texture
                // radiance is evaluated after selection; the PDF stays exact.
                lights[lightIndex]=new Light(lightIndex,g.section().x()+x,g.section().y()+y,g.section().z()+z,Math.max(1e-12f,area*emission),radius);
            }
            lightIndex++;
        }
        if(weighted){
            var nodes=new java.util.ArrayList<Node>((int)nodeCount);tree(nodes,lights,0,lights.length);
            for(var n:nodes)bytes.putFloat(n.x).putFloat(n.y).putFloat(n.z).putFloat(n.power)
                .putInt(n.left).putInt(n.right).putInt(n.light).putFloat(n.radius);
        }
        bytes.flip();
        var buffer=new NativeBuffer(device,bytes.remaining(),VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
            VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        try {buffer.write(bytes);return new Snapshot(buffer,(int)count,false);}
        catch(Throwable failure){buffer.close();throw failure;}
    }
}
