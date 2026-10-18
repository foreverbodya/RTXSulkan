package com.sulkan.shaders.diagnostics;

import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector4d;

/** Double reference: subtract two finite unprojected points, unlike the shader's homogeneous cross difference. */
final class CameraRayReference {
    record Ray(Vector3d origin,Vector3d direction) {}
    static Ray at(Matrix4d inverseProjection,Matrix4d inverseView,double u,double v) {
        var near=inverseView.transform(inverseProjection.transform(new Vector4d(u*2-1,v*2-1,1,1)));
        var middle=inverseView.transform(inverseProjection.transform(new Vector4d(u*2-1,v*2-1,.5,1)));
        var centre=inverseView.transform(inverseProjection.transform(new Vector4d(0,0,1,0)));
        if(!near.isFinite() || !middle.isFinite() || !centre.isFinite()
            || Math.abs(near.w)<1e-12 || Math.abs(middle.w)<1e-12 || Math.abs(centre.w)<1e-12)
            throw new IllegalStateException("Invalid perspective camera ray");
        var first=new Vector3d(near.x/near.w,near.y/near.w,near.z/near.w);
        var second=new Vector3d(middle.x/middle.w,middle.y/middle.w,middle.z/middle.w);
        return new Ray(new Vector3d(centre.x/centre.w,centre.y/centre.w,centre.z/centre.w),second.sub(first).normalize());
    }
}
