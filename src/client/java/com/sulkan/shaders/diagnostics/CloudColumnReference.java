package com.sulkan.shaders.diagnostics;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Source-density checks at actual GPU positions, then independent double Beer/moment integration. */
public final class CloudColumnReference {
    public static final List<String> RESOURCES=List.of("cloud.column.reference","cloud.column.origin","cloud.column.direction","cloud.column.samples");
    private CloudColumnReference() {}
    public static Map<String,Object> compare(ByteBuffer frame,List<ByteBuffer>b,byte[] png,byte[] noise,boolean detail)throws Exception {
        if(b.size()!=4)throw new IllegalArgumentException("Cloud column resource count");
        for(int i=0;i<4;i++)if(b.get(i).remaining()!=(i==3?32*512:512)*16)throw new IllegalStateException("Cloud column extent "+i);
        var image=ImageIO.read(new java.io.ByteArrayInputStream(png));
        var field=new CloudFieldReference(image.getWidth(),image.getHeight(),image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth()),
            0,frame.getFloat(564),new CloudNoiseReference(noise),detail);
        double[] lengths={0,(double)(float).001,.125,.5,1,2,6,31.25};
        double[][] directions={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1},{1,1,1},{-1,1,-1}};
        int sourceSamples=0,nonempty=0,empty=0;double maxDensity=0,maxPosition=0,maxOptical=0,maxOpacity=0,maxCentroid=0;
        for(int id=0;id<512;id++) {
            int x=id%32,y=id/32,offset=id*16;
            for(int resource=0;resource<3;resource++)for(int c=0;c<4;c++)if(!Float.isFinite(b.get(resource).getFloat(offset+c*4)))throw new IllegalStateException("Nonfinite column "+id);
            double length=b.get(1).getFloat(offset+12);if(length!=lengths[x%8])throw new IllegalStateException("Column declared length mismatch "+id);
            int count=Math.max(1,(int)Math.ceil(length));double substep=length/count;
            if(b.get(2).getFloat(offset+12)!=count || Math.abs(b.get(0).getFloat(offset+12)-substep)>1e-6+1e-6*substep)
                throw new IllegalStateException("Column substep mismatch "+id);
            double[] origin=new double[3],direction=new double[3];
            double[] expectedOrigin={(x*4+.37)*37,field.bottom-8+(y*4+.23)*(field.thickness+16)/64,-(x*4*.618+y*4*.3+.17)*71};
            double norm=Math.sqrt(java.util.Arrays.stream(directions[y%8]).map(v->v*v).sum());
            for(int c=0;c<3;c++) {
                origin[c]=b.get(1).getFloat(offset+c*4);direction[c]=b.get(2).getFloat(offset+c*4);
                if(Math.abs(origin[c]-expectedOrigin[c])>.002 || Math.abs(direction[c]-directions[y%8][c]/norm)>2e-6)
                    throw new IllegalStateException("Column input grid mismatch "+id);
            }
            double transmission=1,optical=0,moment=0,opacity=0;
            for(int i=0;i<32;i++) {
                int address=(id*32+i)*16;
                if(i>=count){for(int c=0;c<4;c++)if(b.get(3).getInt(address+c*4)!=0)throw new IllegalStateException("Stale unused column sample");continue;}
                double[] p=new double[3];double distance=(i+.5)*substep;
                for(int c=0;c<3;c++) {
                    p[c]=b.get(3).getFloat(address+c*4);double error=Math.abs(p[c]-(origin[c]+direction[c]*distance));maxPosition=Math.max(maxPosition,error);
                    if(!Double.isFinite(p[c]) || error>.002)throw new IllegalStateException("Column sample position mismatch "+id+"/"+i);
                }
                double density=b.get(3).getFloat(address+12),expected=field.density(p[0],p[1],p[2]),error=Math.abs(density-expected);
                maxDensity=Math.max(maxDensity,error);
                if(!Double.isFinite(density) || density<0 || error>2e-6)throw new IllegalStateException("Column source density mismatch "+id+"/"+i+" error="+error);
                double tau=density*substep,weight=transmission*(-Math.expm1(-tau));
                moment+=weight*distance;opacity+=weight;optical+=tau;transmission*=Math.exp(-tau);sourceSamples++;
            }
            double centroid=opacity>0?moment/opacity:length*.5;
            double opticalError=Math.abs(b.get(0).getFloat(offset)-optical),opacityError=Math.abs(b.get(0).getFloat(offset+8)-opacity);
            double centroidError=Math.abs(b.get(0).getFloat(offset+4)-centroid);
            maxOptical=Math.max(maxOptical,opticalError);maxOpacity=Math.max(maxOpacity,opacityError);maxCentroid=Math.max(maxCentroid,centroidError);
            if(opticalError>2e-5+2e-5*optical || opacityError>2e-5 || centroidError>.001)
                throw new IllegalStateException("Column integration mismatch id="+id+" optical="+opticalError+" opacity="+opacityError+" centroid="+centroidError);
            if(opacity>0)nonempty++;else empty++;
        }
        if(nonempty<16 || empty<64 || sourceSamples<2000)throw new IllegalStateException("Insufficient cloud column coverage "+nonempty+"/"+empty);
        var report=new LinkedHashMap<String,Object>();report.put("columns",512);report.put("sourceSamples",sourceSamples);report.put("nonempty",nonempty);report.put("empty",empty);
        report.put("densityModel",field.densityModel());report.put("maxSourceDensityError",maxDensity);report.put("maxSamplePositionErrorMeters",maxPosition);
        report.put("maxOpticalColumnError",maxOptical);report.put("maxOpacityError",maxOpacity);report.put("maxLocalCentroidErrorMeters",maxCentroid);
        report.put("scope","Actual GPU sample positions/source PNG density, declared 0..31.25m segment grid, double Beer optical column/opacity/local first moment, including empty/tiny intervals. Fixed implementation budgets; not whole-ray convergence, solar/ambient radiance fidelity or cinematic acceptance.");
        return report;
    }
}
