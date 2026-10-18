package com.sulkan.shaders.diagnostics;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

/** Source-PNG nearest sampling for the guarded bounce fixture, independent of GPU atlas readback. */
final class CpuAtlasReference {
    record Sample(double[] rgb,boolean boundary) {}
    private record Sprite(double u0,double v0,double u1,double v1,int width,int height,int[] pixels) {}
    private final ArrayList<Sprite> sprites=new ArrayList<>();
    private final int atlasWidth,atlasHeight;
    CpuAtlasReference(Minecraft client) throws java.io.IOException {
        var atlas=(TextureAtlas)client.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        atlasWidth=atlas.getTexture().getWidth(0);atlasHeight=atlas.getTexture().getHeight(0);
        var resources=client.getResourceManager().listResources("textures/block",id -> id.getPath().endsWith(".png"));
        for(var entry:resources.entrySet()) {
            var file=entry.getKey();String path=file.getPath();
            var name=Identifier.fromNamespaceAndPath(file.getNamespace(),path.substring("textures/".length(),path.length()-4));
            var sprite=atlas.getSprite(name);
            if(!sprite.contents().name().equals(name) || sprite.isAnimated()) continue;
            try(var stream=entry.getValue().open();var image=NativeImage.read(stream)) {
                if(image.getWidth()!=sprite.contents().width() || image.getHeight()!=sprite.contents().height())
                    throw new IllegalStateException("Unexpected source sprite extent: "+name);
                int[] pixels=new int[image.getWidth()*image.getHeight()];
                for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) pixels[y*image.getWidth()+x]=image.getPixel(x,y);
                sprites.add(new Sprite(sprite.getU0(),sprite.getV0(),sprite.getU1(),sprite.getV1(),image.getWidth(),image.getHeight(),pixels));
            }
        }
    }
    Sample sample(double u,double v,double[] tint) {
        for(var sprite:sprites) if(u>=sprite.u0 && u<sprite.u1 && v>=sprite.v0 && v<sprite.v1) {
            int x=(int)Math.floor((u-sprite.u0)/(sprite.u1-sprite.u0)*sprite.width);
            int y=(int)Math.floor((v-sprite.v0)/(sprite.v1-sprite.v0)*sprite.height);
            int argb=sprite.pixels[y*sprite.width+x];double[] rgb=new double[3];
            for(int c=0;c<3;c++) {
                double value=Math.clamp((argb>>>(16-c*8)&255)/255.0*tint[c],0,1);
                rgb[c]=value<=.04045 ? value/12.92 : Math.pow((value+.055)/1.055,2.4);
            }
            return new Sample(rgb,edge(u*atlasWidth)<1.0/64 || edge(v*atlasHeight)<1.0/64);
        }
        throw new IllegalStateException("Bounce oracle hit unsupported source sprite at "+u+","+v);
    }
    Object diagnosticSource() {
        return java.util.Map.of("atlasWidth",atlasWidth,"atlasHeight",atlasHeight,"sprites",sprites,
            "scope","Original nonanimated block PNG texels and atlas placements used by this CPU oracle; no GPU texture readback.");
    }
    private static double edge(double value) {double f=value-Math.floor(value);return Math.min(f,1-f);}
}
