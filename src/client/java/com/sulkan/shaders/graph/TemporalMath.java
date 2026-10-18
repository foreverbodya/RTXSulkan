package com.sulkan.shaders.graph;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/** Camera-only temporal math. Object deformation and motion require their own previous positions. */
public final class TemporalMath {
    private TemporalMath() {}
    public static Vector2f jitter(int frame) {
        if (frame < 0) throw new IllegalArgumentException("Negative frame index");
        int phase=frame%32+1;
        return new Vector2f(radicalInverse(phase,2)-.5f,radicalInverse(phase,3)-.5f);
    }
    private static float radicalInverse(int value,int base) {
        float scale=1f/base, result=0;
        while (value>0) { result+=(value%base)*scale; value/=base; scale/=base; }
        return result;
    }
    public static Matrix4f jitterProjection(Matrix4fc projection,float x,float y,int width,int height) {
        if (width<=0 || height<=0 || !Float.isFinite(x) || !Float.isFinite(y)) throw new IllegalArgumentException("Invalid jitter/extent");
        // Homogeneous clip translation: works for perspective and orthographic matrices.
        return new Matrix4f().translation(2*x/width,2*y/height,0).mul(projection);
    }
    public static Vector2f staticCameraMotion(float u,float v,float depth,Matrix4fc inverseProjection,
            Matrix4fc inverseView,Matrix4fc previousProjection,Matrix4fc previousView,
            Vector3fc cameraDelta,int width,int height) {
        if (width<=0 || height<=0 || !Float.isFinite(depth) || depth<=0) throw new IllegalArgumentException("Invalid surface/extent");
        Vector4f eye=inverseProjection.transform(new Vector4f(u*2-1,v*2-1,depth,1));
        if (!eye.isFinite() || Math.abs(eye.w)<1e-8f) throw new IllegalArgumentException("Invalid surface reconstruction");
        var relative=new org.joml.Vector3f(eye.x,eye.y,eye.z).div(eye.w).mulDirection(inverseView).add(cameraDelta);
        Vector4f previous=previousProjection.transform(previousView.transform(new Vector4f(relative,1)));
        if (!previous.isFinite() || previous.w<=1e-8f) throw new IllegalArgumentException("Surface was behind previous camera");
        return new Vector2f((previous.x/previous.w*.5f+.5f-u)*width,(previous.y/previous.w*.5f+.5f-v)*height);
    }
    /** Removes both sample offsets from reprojection built with jittered current/previous matrices. */
    public static Vector2f skyCameraMotion(float u,float v,Matrix4fc inverseProjection,Matrix4fc inverseView,
            Matrix4fc previousProjection,Matrix4fc previousView,int width,int height,
            org.joml.Vector2fc currentJitter,org.joml.Vector2fc previousJitter) {
        if(width<=0 || height<=0 || !Float.isFinite(u) || !Float.isFinite(v)
                || !currentJitter.isFinite() || !previousJitter.isFinite())throw new IllegalArgumentException("Invalid sky sample");
        var eye=inverseProjection.transform(new Vector4f(u*2-1,v*2-1,1,1));
        if(!eye.isFinite() || Math.abs(eye.w)<1e-8f)throw new IllegalArgumentException("Invalid sky reconstruction");
        var direction=new org.joml.Vector3f(eye.x,eye.y,eye.z).div(eye.w).mulDirection(inverseView).mulDirection(previousView);
        var previous=previousProjection.transform(new Vector4f(direction,0));
        if(!previous.isFinite() || previous.w<=1e-8f)throw new IllegalArgumentException("Sky direction behind previous camera");
        var motion=new Vector2f((previous.x/previous.w*.5f+.5f-u)*width,(previous.y/previous.w*.5f+.5f-v)*height)
            .add(currentJitter.x()-previousJitter.x(),currentJitter.y()-previousJitter.y());
        if(!motion.isFinite() || Math.max(Math.abs(motion.x),Math.abs(motion.y))>65504)throw new IllegalArgumentException("Sky motion exceeds finite RG16F range");
        return motion;
    }
    /** Removes both sample offsets from reprojection built with jittered current/previous matrices. */
    public static Vector2f staticCameraMotion(float u,float v,float depth,Matrix4fc inverseProjection,
            Matrix4fc inverseView,Matrix4fc previousProjection,Matrix4fc previousView,
            Vector3fc cameraDelta,int width,int height,org.joml.Vector2fc currentJitter,org.joml.Vector2fc previousJitter) {
        if(!currentJitter.isFinite() || !previousJitter.isFinite()) throw new IllegalArgumentException("Invalid jitter");
        return staticCameraMotion(u,v,depth,inverseProjection,inverseView,previousProjection,previousView,cameraDelta,width,height)
                .add(currentJitter.x()-previousJitter.x(),currentJitter.y()-previousJitter.y());
    }
}
