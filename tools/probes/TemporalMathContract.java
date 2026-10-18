package com.sulkan.shaders.graph;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

public final class TemporalMathContract {
    public static void main(String[] args) {
        var seen=new java.util.HashSet<String>();
        for(int frame=0;frame<32;frame++) {
            Vector2f j=TemporalMath.jitter(frame);
            if(!j.isFinite() || Math.abs(j.x)>.5 || Math.abs(j.y)>.5 || !seen.add(j.toString())) throw new AssertionError("Invalid jitter phase");
            if(!j.equals(TemporalMath.jitter(frame+32))) throw new AssertionError("Jitter period differs");
        }
        for(int[] size:new int[][]{{854,480},{1707,960},{2560,1440}}) {
            Matrix4f projection=new Matrix4f().setPerspective((float)Math.toRadians(80),(float)size[0]/size[1],512,.05f,true);
            Matrix4f copy=new Matrix4f(projection);
            Vector2f jitter=TemporalMath.jitter(7);
            Matrix4f shifted=TemporalMath.jitterProjection(projection,jitter.x,jitter.y,size[0],size[1]);
            if(!projection.equals(copy)) throw new AssertionError("Jitter mutated its input matrix");
            for(Vector4f point:new Vector4f[]{new Vector4f(2,3,-20,1),new Vector4f(-4,1,-50,1)}) {
                Vector4f a=projection.transform(new Vector4f(point)),b=shifted.transform(new Vector4f(point));
                near((b.x/b.w-a.x/a.w)*size[0]/2,jitter.x,.0005f);
                near((b.y/b.w-a.y/a.w)*size[1]/2,jitter.y,.0005f);
            }
            for(float dx:new float[]{-1,0,1}) for(float yaw:new float[]{-.15f,0,.2f}) {
                Vector3f camera=new Vector3f(dx,3,4),previousCamera=new Vector3f(0,3,4),world=new Vector3f(8,6,-14);
                Matrix4f view=new Matrix4f().rotationY(yaw),previousView=new Matrix4f().rotationY(-.08f);
                Vector4f current=projection.transform(view.transform(new Vector4f(new Vector3f(world).sub(camera),1)));
                Vector4f previous=projection.transform(previousView.transform(new Vector4f(new Vector3f(world).sub(previousCamera),1)));
                float u=current.x/current.w*.5f+.5f,v=current.y/current.w*.5f+.5f,depth=current.z/current.w;
                Vector2f motion=TemporalMath.staticCameraMotion(u,v,depth,new Matrix4f(projection).invert(),new Matrix4f(view).invert(),projection,previousView,
                        new Vector3f(camera).sub(previousCamera),size[0],size[1]);
                near(motion.x,(previous.x/previous.w-current.x/current.w)*size[0]/2,.002f);
                near(motion.y,(previous.y/previous.w-current.y/current.w)*size[1]/2,.002f);
                for(int phase=0;phase<32;phase++) {
                    Vector2f cj=TemporalMath.jitter(phase),pj=TemporalMath.jitter((phase+31)%32);
                    Matrix4f cp=TemporalMath.jitterProjection(projection,cj.x,cj.y,size[0],size[1]);
                    Matrix4f pp=TemporalMath.jitterProjection(projection,pj.x,pj.y,size[0],size[1]);
                    Vector4f sample=cp.transform(view.transform(new Vector4f(new Vector3f(world).sub(camera),1)));
                    Vector2f corrected=TemporalMath.staticCameraMotion(sample.x/sample.w*.5f+.5f,sample.y/sample.w*.5f+.5f,
                            sample.z/sample.w,new Matrix4f(cp).invert(),new Matrix4f(view).invert(),pp,previousView,
                            new Vector3f(camera).sub(previousCamera),size[0],size[1],cj,pj);
                    near(corrected.x,(previous.x/previous.w-current.x/current.w)*size[0]/2,.002f);
                    near(corrected.y,(previous.y/previous.w-current.y/current.w)*size[1]/2,.002f);
                }
            }
        }
        try { TemporalMath.jitter(-1); throw new AssertionError("Negative phase accepted"); } catch(IllegalArgumentException expected) {}
        System.out.println("SULKAN_TEMPORAL_MATH_CONTRACT_PASS phases=32 extents=3 translationRotationCases=27 jitteredCases=864 scope=CPU_ONLY");
    }
    private static void near(float value,float expected,float tolerance) {
        if(!Float.isFinite(value)||Math.abs(value-expected)>tolerance) throw new AssertionError(value+" != "+expected);
    }
}
