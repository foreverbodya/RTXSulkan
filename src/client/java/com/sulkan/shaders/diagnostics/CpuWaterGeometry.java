package com.sulkan.shaders.diagnostics;

import com.sulkan.shaders.render.raytracing.TerrainGeometryCache;
import com.sulkan.shaders.render.raytracing.TerrainMesh;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;
import org.joml.Vector4d;
import org.joml.Matrix4d;

/** Source water triangles; independent of the raster G-buffer and RT hit data. */
final class CpuWaterGeometry {
    record Hit(double distance,Vector3d position,Vector3d normal,boolean boundary) {}
    record PlaneWitness(int triangleIndex,Vector3d position,Vector3d normal,double positionError,double normalError,double screenEdgeDistance,double[] triangle) {}
    private record Triangle(Vector3d a,Vector3d e1,Vector3d e2,Vector3d normal) {}
    private final List<Triangle> triangles=new ArrayList<>();
    CpuWaterGeometry(List<double[]> data) {
        for(double[] v:data) {
            if(v.length!=9)throw new IllegalArgumentException("Source triangle extent");
            for(double value:v)if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite source triangle");
            var a=new Vector3d(v[0],v[1],v[2]);var e1=new Vector3d(v[3],v[4],v[5]);var e2=new Vector3d(v[6],v[7],v[8]);
            var n=e1.cross(e2,new Vector3d());if(n.lengthSquared()>1e-16)triangles.add(new Triangle(a,e1,e2,n.normalize()));
        }
    }
    CpuWaterGeometry() {
        for(var section:TerrainGeometryCache.snapshot())for(var mesh:section.meshes()) {
            if(mesh.opacity()!=TerrainMesh.Opacity.TRANSLUCENT)continue;
            var positions=mesh.positions();var indices=mesh.indices();
            for(int p=0;p<mesh.triangleCount()*3;p+=3) {
                int ia=indices.get(p),ib=indices.get(p+1),ic=indices.get(p+2);
                if((mesh.packedMaterial(ia)&65535)!=1)continue;
                var a=vertex(positions,ia,section.x(),section.y(),section.z());
                var e1=vertex(positions,ib,section.x(),section.y(),section.z()).sub(a);
                var e2=vertex(positions,ic,section.x(),section.y(),section.z()).sub(a);
                var n=e1.cross(e2,new Vector3d());if(n.lengthSquared()<1e-16)continue;
                triangles.add(new Triangle(a,e1,e2,n.normalize()));
            }
        }
    }
    Hit trace(Vector3d origin,Vector3d direction) {
        double closest=Double.POSITIVE_INFINITY;Triangle selected=null;boolean boundary=false;
        for(var triangle:triangles) {
            var h=direction.cross(triangle.e2,new Vector3d());double determinant=triangle.e1.dot(h);
            if(determinant<=1e-12)continue; // Match outward-wound primary raster culling.
            var s=new Vector3d(origin).sub(triangle.a);double u=s.dot(h)/determinant;if(u<0 || u>1)continue;
            var q=s.cross(triangle.e1,new Vector3d());double v=direction.dot(q)/determinant;if(v<0 || u+v>1)continue;
            double distance=triangle.e2.dot(q)/determinant;if(distance<=0 || distance>=closest)continue;
            closest=distance;selected=triangle;boundary=Math.min(u,Math.min(v,1-u-v))<1e-5;
        }
        return selected==null?null:new Hit(closest,new Vector3d(origin).fma(closest,direction),new Vector3d(selected.normal),boundary);
    }
    PlaneWitness coverageWitness(Vector3d origin,Vector3d direction,Matrix4d projection,Matrix4d view,int x,int y,int width,int height,int bits,
        Vector3d rasterPosition,Vector3d rasterNormal) {
        if(!origin.isFinite() || !direction.isFinite() || !projection.isFinite() || !view.isFinite()
            || !rasterPosition.isFinite() || !rasterNormal.isFinite())return null;
        PlaneWitness best=null;
        for(int index=0;index<triangles.size();index++) {
            var triangle=triangles.get(index);
            double normalError=triangle.normal.distance(rasterNormal);if(normalError>.001)continue;
            double denominator=triangle.normal.dot(direction);if(denominator>=-1e-12)continue;
            double distance=triangle.normal.dot(new Vector3d(triangle.a).sub(origin))/denominator;if(distance<=0)continue;
            var point=new Vector3d(origin).fma(distance,direction);double positionError=point.distance(rasterPosition);
            if(positionError>.003)continue; // Same position budget as strict source hits.
            double[][] screen=new double[3][2];boolean clipped=false;
            for(int vertex=0;vertex<3;vertex++) {
                var p=new Vector3d(triangle.a);if(vertex==1)p.add(triangle.e1);if(vertex==2)p.add(triangle.e2);
                var clip=projection.transform(view.transform(new Vector4d(p.sub(origin),1)));
                if(!clip.isFinite() || clip.w<=1e-8 || clip.z<0 || clip.z>clip.w){clipped=true;break;}
                screen[vertex][0]=(clip.x/clip.w*.5+.5)*width;screen[vertex][1]=(clip.y/clip.w*.5+.5)*height;
            }
            if(clipped || RasterCoverageReference.classify(screen,x,y,bits)!=RasterCoverageReference.Coverage.PARTIAL)continue;
            double edge=RasterCoverageReference.edgeDistance(screen,x+.5,y+.5);
            if(best==null || edge<best.screenEdgeDistance)best=new PlaneWitness(index,point,new Vector3d(triangle.normal),positionError,normalError,edge,
                new double[]{triangle.a.x,triangle.a.y,triangle.a.z,triangle.e1.x,triangle.e1.y,triangle.e1.z,triangle.e2.x,triangle.e2.y,triangle.e2.z});
        }
        return best;
    }
    java.util.Map<String,Object> diagnosticMiss(Vector3d origin,Vector3d direction) {
        double best=Double.NEGATIVE_INFINITY;Object closest="none";
        var vertices=new ArrayList<double[]>();
        for(var triangle:triangles) {
            vertices.add(new double[]{triangle.a.x,triangle.a.y,triangle.a.z,triangle.e1.x,triangle.e1.y,triangle.e1.z,
                triangle.e2.x,triangle.e2.y,triangle.e2.z});
            var h=direction.cross(triangle.e2,new Vector3d());double det=triangle.e1.dot(h);
            if(det<=1e-12)continue;
            var s=new Vector3d(origin).sub(triangle.a);double u=s.dot(h)/det;
            var q=s.cross(triangle.e1,new Vector3d());double v=direction.dot(q)/det,t=triangle.e2.dot(q)/det;
            if(t<=0)continue;
            double margin=Math.min(u,Math.min(v,1-u-v));
            if(margin>best) {
                best=margin;
                closest=java.util.Map.of("u",u,"v",v,"distance",t,"minimumBarycentric",margin,
                    "triangleIndex",vertices.size()-1,"determinant",det);
            }
        }
        return java.util.Map.of("triangleCount",triangles.size(),"sourceTrianglesOriginEdges",vertices,
            "closestBarycentricCandidate",closest,"scope","Diagnostic source planes only; outside barycentrics are not accepted as hits");
    }
    private static Vector3d vertex(java.nio.FloatBuffer p,int i,int x,int y,int z){return new Vector3d((double)p.get(i*3)+x,(double)p.get(i*3+1)+y,(double)p.get(i*3+2)+z);}
}
