package com.sulkan.shaders.diagnostics;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import org.joml.Vector3d;

/** Double CPU closure with uniform 128-step view integration and Simpson solar quadrature. */
final class MultipleScatteringReference {
    record Cell(double[] source,double[] feedback) {}
    static Cell cell(int x,int y) {
        double height=.001+59.999*Math.pow(y/31.0,2),muSun=x/31.0*2-1;
        var sun=new Vector3d(Math.sqrt(Math.max(0,1-muSun*muSun)),muSun,0);
        double[] radiance=new double[3],feedback=new double[3];
        for(int azimuth=0;azimuth<8;azimuth++)for(int polar=0;polar<8;polar++) {
            double mu=1-2*(polar+.5)/8,phi=2*Math.PI*(azimuth+.5)/8;
            var d=new Vector3d(Math.cos(phi)*Math.sqrt(1-mu*mu),mu,Math.sin(phi)*Math.sqrt(1-mu*mu));
            double maximum=AtmosphereReference.top(height,mu),ground=AtmosphereReference.ground(height,mu);
            if(ground>=0)maximum=Math.min(maximum,ground);
            double[] trans={1,1,1};double step=maximum/128;
            for(int j=0;j<128;j++) {
                var position=new Vector3d(d).mul((j+.5)*step).add(0,AtmosphereReference.R+height,0);
                double altitude=position.length()-AtmosphereReference.R;
                var density=AtmosphereReference.density(altitude);
                var sunlight=AtmosphereReference.sun(altitude,position.dot(sun)/position.length());
                for(int c=0;c<3;c++) {
                    double extinction=AtmosphereReference.extinction(density,c),segment=Math.exp(-extinction*step);
                    double integral=trans[c]*(-Math.expm1(-extinction*step))/extinction*AtmosphereReference.scattering(density,c);
                    radiance[c]+=integral*sunlight[c]/(4*Math.PI*64);feedback[c]+=integral/64;
                    trans[c]*=segment;
                }
            }
            if(ground>=0) {
                var position=new Vector3d(d).mul(ground).add(0,AtmosphereReference.R+height,0);
                double localSun=position.dot(sun)/AtmosphereReference.R;var sunlight=AtmosphereReference.sun(.001,localSun);
                for(int c=0;c<3;c++)radiance[c]+=trans[c]*(.1/Math.PI)*Math.max(localSun,0)*sunlight[c]/64;
            }
        }
        for(int c=0;c<3;c++)radiance[c]/=1-feedback[c];
        return new Cell(radiance,feedback);
    }
    static Object verify(ByteBuffer gpu) {
        if(gpu.remaining()!=32*32*16)throw new IllegalStateException("Wrong multiple-scattering extent");
        for(int p=0;p<1024;p++)for(int c=0;c<4;c++) {
            float value=gpu.getFloat(p*16+c*4);
            if(!Float.isFinite(value) || value<0 || (c==3 && value!=1))throw new IllegalStateException("Invalid multiple-scattering LUT");
        }
        double maxError=0,maxFeedback=0;int cells=0;
        for(int x:new int[]{0,15,16,24,31})for(int y:new int[]{0,4,16,31}) {
            var reference=cell(x,y);
            for(int c=0;c<3;c++) {
                double expected=reference.source[c],error=Math.abs(gpu.getFloat((y*32+x)*16+c*4)-expected);
                if(error>.00002+.03*Math.abs(expected))throw new IllegalStateException("Multiple-scattering closure mismatch xy="+x+","+y+" channel="+c+" expected="+expected+" actual="+gpu.getFloat((y*32+x)*16+c*4));
                if(reference.feedback[c]<0 || reference.feedback[c]>=1)throw new IllegalStateException("Noncontractive atmospheric closure");
                maxError=Math.max(maxError,error);maxFeedback=Math.max(maxFeedback,reference.feedback[c]);
            }
            cells++;
        }
        var report=new LinkedHashMap<String,Object>();report.put("cells",cells);report.put("maxAbsoluteError",maxError);report.put("maxReferenceFeedback",maxFeedback);
        report.put("scope","Fixed 64-direction isotropic closure versus independent double 128-step view and Simpson solar quadrature. Not angular convergence, spectral calibration or full volumetric path tracing.");
        return report;
    }
}
