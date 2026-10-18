package com.sulkan.shaders.diagnostics;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4d;

/** Independent footprint/support arithmetic and a density-bound counterexample check.
 * Full-ray RGB is NOT independently integrated by this check. */
final class CloudDepthCompositionReference {
    final int width,height,cw,ch;
    final boolean[] complete;
    final ByteBuffer coefficients,segment,view,geometry,proxy;
    final Matrix4d ip,iv;
    final double cameraY,bottom;
    CloudDepthCompositionReference(ByteBuffer frame,List<ByteBuffer> b) {
        width=(int)frame.getFloat(416);height=(int)frame.getFloat(420);cw=(width+1)/2;ch=(height+1)/2;
        coefficients=b.get(1);segment=b.get(2);view=b.get(4);geometry=b.get(5);proxy=b.get(8);
        if(proxy.remaining()!=width*height*16)throw new IllegalStateException("Cloud composition proxy extent");
        ip=matrix(frame,128);iv=matrix(frame,192);
        cameraY=(double)frame.getFloat(484)+frame.getFloat(500);bottom=frame.getFloat(564);
        complete=new boolean[cw*ch];
        for(int y=0;y<ch;y++)for(int x=0;x<cw;x++) {
            int p=y*cw+x;var ray=CameraRayReference.at(ip,iv,(x+.5)/cw,(y+.5)/ch);
            double end=wholeEnd(cameraY+ray.origin().y,ray.direction().y);
            complete[p]=end<0 || geometry.getFloat(p*16+12)>=end;
        }
    }
    private double wholeEnd(double origin,double direction) {
        if(Math.abs(direction)<1e-6)return origin>bottom && origin<bottom+128?8000:-1;
        double a=(bottom-origin)/direction,b=(bottom+128-origin)/direction;
        double begin=Math.max(Math.min(a,b),0),end=Math.min(Math.max(a,b),8000);
        return end>begin?end:-1;
    }
    private int[] cells(int x,int y) {
        int tx=(2*x+1)*cw-width,ty=(2*y+1)*ch-height;
        int a=Math.floorDiv(tx,2*width),c=Math.floorDiv(ty,2*height);
        int x0=Math.clamp(a,0,cw-1),x1=Math.clamp(a+1,0,cw-1),y0=Math.clamp(c,0,ch-1),y1=Math.clamp(c+1,0,ch-1);
        return new int[]{y0*cw+x0,y0*cw+x1,y1*cw+x0,y1*cw+x1};
    }
    private double[] weights(int x,int y) {
        int tx=(2*x+1)*cw-width,ty=(2*y+1)*ch-height;
        double fx=Math.floorMod(tx,2*width)/(2.0*width),fy=Math.floorMod(ty,2*height)/(2.0*height);
        return new double[]{(1-fx)*(1-fy),fx*(1-fy),(1-fx)*fy,fx*fy};
    }
    double[] expected(int x,int y) {
        int p=y*width+x,mode=(int)proxy.getFloat(p*16+4);
        if(mode==4)return new double[]{0,0,0,1};
        if(mode==1 || mode==3) {
            double[] out=new double[4];for(int c=0;c<4;c++)out[c]=coefficients.getFloat(p*16+c*4);
            return out; // Only composition + analytic opacity bound, not a full-ray RGB oracle.
        }
        int[] cells=cells(x,y);double[] w=weights(x,y),out=new double[4];double mass=0;
        for(int i=0;i<4;i++)if(complete[cells[i]])mass+=w[i];
        for(int i=0;i<4;i++)if(complete[cells[i]])for(int c=0;c<4;c++)
            out[c]+=w[i]/mass*Float.float16ToFloat(view.getShort(cells[i]*8+c*2));
        return out;
    }
    Map<String,Object> compare() {
        int[] modes=new int[5];int boundChecks=0,legacyViolations=0,changed=0;
        double maxCoefficient=0,maxProxy=0,maxDeficit=0,maxLegacyDeficit=0;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            int p=y*width+x,o=p*16;int mode=(int)proxy.getFloat(o+4);
            for(int c=0;c<4;c++)if(!Float.isFinite(proxy.getFloat(o+c*4)) || !Float.isFinite(coefficients.getFloat(o+c*4)))
                throw new IllegalStateException("Nonfinite cloud depth composition "+p);
            if(mode<1 || mode>4 || mode!=proxy.getFloat(o+4))throw new IllegalStateException("Invalid cloud composition mode "+p);
            modes[mode]++;
            int[] cells=cells(x,y);double[] weights=weights(x,y);int mask=0;double mass=0,sum=0,opacityMass=0;
            for(int i=0;i<4;i++)if(complete[cells[i]]) {
                mask|=1<<i;mass+=weights[i];double w=weights[i]*geometry.getFloat(cells[i]*16+4);
                sum+=w*geometry.getFloat(cells[i]*16);opacityMass+=w;
            }
            boolean intersects=segment.getFloat(o+8)>=0;
            var ray=CameraRayReference.at(ip,iv,(x+.5)/width,(y+.5)/height);
            double end=wholeEnd(cameraY+ray.origin().y,ray.direction().y),maximum=segment.getFloat(o+4);
            int expectedMode=!intersects?4:maximum<end?1:mass>0?2:3;
            if(mode!=expectedMode)throw new IllegalStateException("Cloud support classification mismatch "+p+" mode="+mode+" expected="+expectedMode);
            if(mode==2 || mode==3) {
                if(proxy.getFloat(o+12)!=mask || Math.abs(proxy.getFloat(o+8)-mass)>2e-6)
                    throw new IllegalStateException("Cloud support mask/mass mismatch "+p);
            }
            double distance=proxy.getFloat(o),t=coefficients.getFloat(o+12);
            if(t<0 || t>1 || distance<0 || distance>8000 || ((mode==1 || mode==3) && distance>0
                && (!intersects || distance<segment.getFloat(o+8)-.002 || distance>segment.getFloat(o+12)+.002)))
                throw new IllegalStateException("Invalid cloud finite prefix/proxy "+p);
            if(mode==2) {
                double expectedProxy=opacityMass/mass>1e-7?sum/opacityMass:0;
                double error=Math.abs(distance-expectedProxy);maxProxy=Math.max(maxProxy,error);
                if(error>.002+2e-6*expectedProxy)throw new IllegalStateException("Cloud supported proxy mismatch "+p);
            }
            double[] expected=expected(x,y);
            for(int c=0;c<4;c++) {
                double actual=coefficients.getFloat(o+c*4),error=Math.abs(actual-expected[c]);maxCoefficient=Math.max(maxCoefficient,error);
                if(actual<0 || error>.0002+.0002*Math.abs(expected[c]))throw new IllegalStateException("Cloud supported coefficient mismatch "+p);
            }
            double legacyT=0;
            if(intersects)for(int i=0;i<4;i++)legacyT+=weights[i]*Float.float16ToFloat(view.getShort(cells[i]*8+6));
            else legacyT=1;
            if(Math.abs(t-legacyT)>.01)changed++;
            if(intersects && maximum<=128 && segment.getFloat(o)>1e-7) {
                double length=segment.getFloat(o+12)-segment.getFloat(o+8),minimum=Math.exp(-(double)(float).045*length);
                double deficit=minimum-t;maxDeficit=Math.max(maxDeficit,deficit);boundChecks++;
                if(deficit>.001)throw new IllegalStateException("Cloud opacity exceeds density/segment bound "+p+" deficit="+deficit);
                double old=minimum-legacyT;if(old>.001)legacyViolations++;
                maxLegacyDeficit=Math.max(maxLegacyDeficit,old);
            }
        }
        var report=new LinkedHashMap<String,Object>();report.put("allPixels",width*height);report.put("modes",modes);
        report.put("shortSurfaceBoundChecks",boundChecks);report.put("sameFrameLegacyBoundViolationPixels",legacyViolations);
        report.put("maxNewBoundDeficit",maxDeficit);report.put("maxLegacyBoundDeficit",maxLegacyDeficit);
        report.put("changedTransmissionAbove01Pixels",changed);report.put("maxSupportedCoefficientError",maxCoefficient);report.put("maxSupportedProxyErrorMeters",maxProxy);
        report.put("scope","Independent full/half ray support classification, exact footprint and opacity-weighted proxy arithmetic, finite clipped guides and density upper-bound checks on short surface rays. Legacy counterexamples use the same captured inputs. Full integrated RGB and true multilayer motion are not independently solved here; no visual/gameplay acceptance claim.");
        return report;
    }
    private static Matrix4d matrix(ByteBuffer f,int offset) {
        double[] a=new double[16];for(int i=0;i<16;i++)a[i]=f.getFloat(offset+i*4);return new Matrix4d(a);
    }
}
