package com.sulkan.shaders.graph;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Expected motion comes from projecting known world directions, not inverse reconstruction. */
public final class SkyMotionMathContract {
    public static void main(String[] args) {
        int cases=0;
        for(int[] size:new int[][]{{854,480},{1707,960},{2560,1440}}) {
            var p=new Matrix4f().setPerspective((float)Math.toRadians(80),(float)size[0]/size[1],512,.05f,true);
            for(float yaw:new float[]{-.2f,0,.2f})for(float pitch:new float[]{-.12f,0,.12f}) {
                var view=new Matrix4f().rotationXYZ(pitch,yaw,0).setTranslation(11106,83,18911);
                var oldView=new Matrix4f().rotationXYZ(.04f,-.08f,0).setTranslation(-17000,400,22000);
                for(var direction:new Vector3f[]{new Vector3f(0,0,-1),new Vector3f(.3f,.2f,-1),new Vector3f(-.25f,-.15f,-1)}) {
                    var current=p.transform(view.transform(new Vector4f(direction,0)));
                    var previous=p.transform(oldView.transform(new Vector4f(direction,0)));
                    float expectedX=(previous.x/previous.w-current.x/current.w)*size[0]/2;
                    float expectedY=(previous.y/previous.w-current.y/current.w)*size[1]/2;
                    for(int phase=0;phase<32;phase++) {
                        var cj=TemporalMath.jitter(phase);var pj=TemporalMath.jitter((phase+31)%32);
                        var cp=TemporalMath.jitterProjection(p,cj.x,cj.y,size[0],size[1]);
                        var pp=TemporalMath.jitterProjection(p,pj.x,pj.y,size[0],size[1]);
                        var sample=cp.transform(view.transform(new Vector4f(direction,0)));
                        var motion=TemporalMath.skyCameraMotion(sample.x/sample.w*.5f+.5f,sample.y/sample.w*.5f+.5f,
                            new Matrix4f(cp).invert(),new Matrix4f(view).invert(),pp,oldView,size[0],size[1],cj,pj);
                        near(motion.x,expectedX);near(motion.y,expectedY);cases++;
                    }
                }
            }
            for(int phase=0;phase<32;phase++) {
                var cj=TemporalMath.jitter(phase);var pj=TemporalMath.jitter((phase+31)%32);
                var cp=TemporalMath.jitterProjection(p,cj.x,cj.y,size[0],size[1]);
                var pp=TemporalMath.jitterProjection(p,pj.x,pj.y,size[0],size[1]);
                var motion=TemporalMath.skyCameraMotion(.4f,.6f,new Matrix4f(cp).invert(),new Matrix4f().translation(10000,100,20000),
                    pp,new Matrix4f().translation(-5000,-100,-9000),size[0],size[1],cj,pj);
                near(motion.x,0);near(motion.y,0);cases++;
            }
            try {
                TemporalMath.skyCameraMotion(.5f,.5f,new Matrix4f(p).invert(),new Matrix4f(),p,new Matrix4f().rotationY((float)Math.PI),
                    size[0],size[1],new Vector2f(),new Vector2f());
                throw new AssertionError("Behind-camera direction accepted");
            }catch(IllegalArgumentException expected){}
        }
        System.out.println("SULKAN_SKY_MOTION_MATH_PASS cases="+cases+" phases=32 extents=3 rotation=true translationIgnored=true jitterRemoved=true behindRejected=true scope=CPU_ONLY");
    }
    private static void near(float value,float expected) {
        if(!Float.isFinite(value) || Math.abs(value-expected)>.002f)throw new AssertionError(value+" != "+expected);
    }
}
