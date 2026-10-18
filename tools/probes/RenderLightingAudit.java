package com.sulkan.shaders.diagnostics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Fixed-exposure diagnostic rendering of captured pre-water, pre-SR HDR. */
public final class RenderLightingAudit {
    public static void main(String[] args)throws Exception {
        if(args.length!=4 && args.length!=5)throw new IllegalArgumentException("capture-dir capture-name exposureEV new-output.png [display-mode]");
        Path dir=Path.of(args[0]),out=Path.of(args[3]);double exposure=Double.parseDouble(args[2]);
        int mode=args.length==5?Integer.parseInt(args[4]):2;
        if(Files.exists(out))throw new IllegalArgumentException("New image required");
        var meta=com.google.gson.JsonParser.parseString(Files.readString(dir.resolve("lighting-audit-"+args[1]+"-inputs.json"))).getAsJsonObject();
        int w=meta.get("width").getAsInt(),h=meta.get("height").getAsInt();
        byte[] source=Files.readAllBytes(dir.resolve("lighting-audit-"+args[1]+"-ray.direct.hdr.bin"));
        if(source.length!=w*h*8 || !Double.isFinite(exposure))throw new IllegalArgumentException("Invalid HDR image/exposure");
        var pixels=ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            double[] rgb=new double[3];for(int c=0;c<3;c++)rgb[c]=Float.float16ToFloat(pixels.getShort(((y*w+x)*4+c)*2))*Math.pow(2,exposure);
            var display=DisplayMappingReference.map(rgb,mode);int packed=0;
            for(int c=0;c<3;c++)packed=(packed<<8)|(int)Math.round(Math.clamp(display[c],0,1)*255);
            image.setRGB(x,h-1-y,packed);
        }
        if(!ImageIO.write(image,"png",out.toFile()))throw new IllegalStateException("PNG writer unavailable");
        Files.writeString(Path.of(out+".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(java.util.Map.of("capture",args[1],"sourceHdrSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source)),"width",w,"height",h,"fixedExposureEV",exposure,"displayMode",mode,"scope","Independent CPU display transform of actual pre-water/pre-SR HDR at a common phase exposure. Diagnostic image, not a game screenshot, native DLSS output or optical ground truth.")));
        System.out.println("LIGHTING_AUDIT_FIXED_EXPOSURE_IMAGE "+out);
    }
}
