package com.sulkan.shaders.diagnostics;

import java.nio.file.Files;
import java.nio.file.Path;
import org.joml.Vector3d;

/** Properties of the independent reference, not a GPU-direction readback. */
public final class CheckSunDisk {
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]);if(Files.exists(out))throw new IllegalArgumentException("New report required");
        int directions=0,pairs=0;double maxUnit=0,maxCentroid=0,maxCone=0,maxMoment=0;
        for(int k=0;k<4096;k++) {
            double y=2*(k+.5)/4096-1,phi=k*Math.PI*(3-Math.sqrt(5));
            var centre=k==0?new Vector3d(0,0,1):k==1?new Vector3d(0,0,-1):
                new Vector3d(Math.sqrt(1-y*y)*Math.cos(phi),y,Math.sqrt(1-y*y)*Math.sin(phi)).normalize();
            if(!SunDiskReference.direction(centre,0,1).equals(centre))throw new AssertionError("Point fallback changed");
            var sum=new Vector3d();double moment=0;
            for(int i=0;i<8;i++) {
                var d=SunDiskReference.direction(centre,i,8);directions++;
                if(!d.equals(SunDiskReference.direction(centre,i,8)))throw new AssertionError("Nondeterministic sample");
                double unit=Math.abs(d.length()-1);maxUnit=Math.max(maxUnit,unit);
                double cosine=Math.clamp(d.dot(centre),-1,1),angle=Math.acos(cosine);maxCone=Math.max(maxCone,angle);
                if(unit>1e-14 || angle>.004675)throw new AssertionError("Invalid disk direction");
                moment+=(1/(cosine*cosine)-1)/Math.pow(Math.tan(.004675),2)/8;sum.add(d);
                if((i&1)==1) {
                    var pair=SunDiskReference.direction(centre,i-1,8).add(d);
                    double error=pair.cross(centre).length();if(error>1e-14)throw new AssertionError("Non-antipodal tangent pair");pairs++;
                }
            }
            double error=sum.normalize().distance(centre);maxCentroid=Math.max(maxCentroid,error);
            maxMoment=Math.max(maxMoment,Math.abs(moment-.5));
            if(error>1e-14 || Math.abs(moment-.5)>1e-9)throw new AssertionError("Disk quadrature moment differs");
        }
        var report=java.util.Map.of("passed",true,"directions",directions,"antipodalPairs",pairs,
            "maxUnitError",maxUnit,"maxNormalizedCentroidError",maxCentroid,"maxAngleRadians",maxCone,
            "maxTangentDiskSecondMomentError",maxMoment,
            "scope","CPU reference properties: cone, deterministic pairs, point fallback and tangent-disk second moment. Not an actual GPU direction readback, continuous area-light integral or cinematic acceptance.");
        Files.writeString(out,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));System.out.println(report);
    }
}
