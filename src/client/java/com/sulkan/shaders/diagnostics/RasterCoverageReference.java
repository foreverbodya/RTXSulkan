/*
 * Coverage interval approach adapted from VK-GL-CTS, tcuRasterizationVerifier.cpp,
 * bee83722547054754049485ae6ae8344496c02a6, calculateTriangleCoverage.
 * Copyright 2014 The Android Open Source Project. Apache License, Version 2.0.
 * See META-INF/licenses/VK-GL-CTS-Apache-2.0.txt.
 * Local changes: double screen coordinates, bounded long arithmetic, single
 * sample only, and an explicit small-triangle/uncertainty-box overlap case.
 */
package com.sulkan.shaders.diagnostics;

/** Coverage possibilities, not an emulator of a particular GPU's rounding. */
final class RasterCoverageReference {
    enum Coverage { NONE, PARTIAL, FULL }
    static Coverage classify(double[][] screen,int pixelX,int pixelY,int bits) {
        if(bits<1 || bits>16)throw new IllegalArgumentException("Unsupported raster precision: "+bits);
        long scale=1L<<bits,cx=(long)pixelX*scale+scale/2,cy=(long)pixelY*scale+scale/2;
        if(Math.abs(cx)>(1L<<28) || Math.abs(cy)>(1L<<28))throw new IllegalArgumentException("Unsupported pixel coordinate range");
        // CTS single-sample central square: +/- three subpixels. Vertex
        // coordinates independently range from floor to ceil on that grid.
        long x0=cx-3,x1=cx+3,y0=cy-3,y1=cy+3;
        long[][] lo=new long[3][2],hi=new long[3][2];
        for(int i=0;i<3;i++)for(int c=0;c<2;c++) {
            double v=screen[i][c]*scale;
            if(!Double.isFinite(v) || Math.abs(v)>(1L<<28))throw new IllegalArgumentException("Unsupported raster coordinate range");
            lo[i][c]=(long)Math.floor(v);hi[i][c]=(long)Math.ceil(v);
        }
        double area=(screen[1][0]-screen[0][0])*(screen[2][1]-screen[0][1])
            -(screen[1][1]-screen[0][1])*(screen[2][0]-screen[0][0]);
        if(area==0)return Coverage.NONE;
        if(area<0){long[] t=lo[1];lo[1]=lo[2];lo[2]=t;t=hi[1];hi[1]=hi[2];hi[2]=t;}
        long[][] corners={{x0,y0},{x1,y0},{x1,y1},{x0,y1}};
        for(int edge=0;edge<3;edge++)for(int a=0;a<4;a++)for(int b=0;b<4;b++) {
            int next=(edge+1)%3;
            long ax=(a&1)==0?lo[edge][0]:hi[edge][0],ay=(a&2)==0?lo[edge][1]:hi[edge][1];
            long bx=(b&1)==0?lo[next][0]:hi[next][0],by=(b&2)==0?lo[next][1]:hi[next][1];
            if(ax>=x0 && ax<=x1 && ay>=y0 && ay<=y1)return Coverage.PARTIAL;
            for(int c=0;c<4;c++)if(intersects(ax,ay,bx,by,corners[c][0],corners[c][1],corners[(c+1)%4][0],corners[(c+1)%4][1]))return Coverage.PARTIAL;
        }
        if(cross(lo[0][0],lo[0][1],lo[1][0],lo[1][1],lo[2][0],lo[2][1])<=0)return Coverage.NONE;
        for(int edge=0;edge<3;edge++)if(cross(lo[edge][0],lo[edge][1],lo[(edge+1)%3][0],lo[(edge+1)%3][1],x0,y0)<0)return Coverage.NONE;
        return Coverage.FULL;
    }
    static double edgeDistance(double[][] screen,double x,double y) {
        double closest=Double.POSITIVE_INFINITY;
        for(int a=0;a<3;a++) {
            int b=(a+1)%3;double dx=screen[b][0]-screen[a][0],dy=screen[b][1]-screen[a][1];
            double n=dx*dx+dy*dy,t=n==0?0:Math.clamp(((x-screen[a][0])*dx+(y-screen[a][1])*dy)/n,0,1);
            closest=Math.min(closest,Math.hypot(x-screen[a][0]-t*dx,y-screen[a][1]-t*dy));
        }
        return closest;
    }
    private static long cross(long ax,long ay,long bx,long by,long x,long y){return (bx-ax)*(y-ay)-(by-ay)*(x-ax);}
    private static boolean intersects(long ax,long ay,long bx,long by,long cx,long cy,long dx,long dy) {
        if(Math.max(ax,bx)<Math.min(cx,dx) || Math.max(cx,dx)<Math.min(ax,bx)
            || Math.max(ay,by)<Math.min(cy,dy) || Math.max(cy,dy)<Math.min(ay,by))return false;
        long a=cross(ax,ay,bx,by,cx,cy),b=cross(ax,ay,bx,by,dx,dy);
        long c=cross(cx,cy,dx,dy,ax,ay),d=cross(cx,cy,dx,dy,bx,by);
        return (a==0 || b==0 || (a<0)!=(b<0)) && (c==0 || d==0 || (c<0)!=(d<0));
    }
}
