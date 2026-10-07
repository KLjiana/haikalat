package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.subsystems.render3d.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Explicit 2D slice PNGs plus lossless RGBA float data, outside measured execution. */
final class VolumetricSliceExporter {
    private VolumetricSliceExporter() { }
    static void save(Path prefix,RenderPipeline pipeline,int layer) throws IOException {
        var d=pipeline.volumetricFogDiagnostics();if(!d.available())return;
        for(var field:VolumetricFogDiagnostics.Field.values()) {
            int slice=Math.max(0,Math.min(layer,d.depthSlices()-1));
            float[] rgba=pipeline.captureVolumetricSlice(field,slice);
            String suffix="-"+field.name().toLowerCase(java.util.Locale.ROOT)+"-z"+slice;
            Path raw=Path.of(prefix+suffix+".rgba.f32");Files.createDirectories(raw.toAbsolutePath().getParent());
            try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(raw))) {
                out.writeInt(d.columnsX());out.writeInt(d.columnsY());out.writeInt(slice);
                for(float value:rgba) { if(!Float.isFinite(value))throw new IllegalStateException("nonfinite volume slice "+field);out.writeFloat(value); }
            }
            BufferedImage image=new BufferedImage(d.columnsX(),d.columnsY(),BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<d.columnsY();y++)for(int x=0;x<d.columnsX();x++) {
                int p=(y*d.columnsX()+x)*4;int rgb;
                if(field==VolumetricFogDiagnostics.Field.MEDIUM)rgb=gray(rgba[p+3]*10);
                else if(field==VolumetricFogDiagnostics.Field.HISTORY_REJECTION||field==VolumetricFogDiagnostics.Field.REACTIVE_PREFIX)rgb=gray(rgba[p]);
                else {rgb=0xff000000;for(int c=0;c<3;c++)rgb|=mapped(rgba[p+c])<<(16-c*8);}
                image.setRGB(x,d.columnsY()-1-y,rgb);
            }
            ImageIO.write(image,"png",Path.of(prefix+suffix+".png").toFile());
            if(field==VolumetricFogDiagnostics.Field.SCATTERING_TRANSMISSION) {
                for(int y=0;y<d.columnsY();y++)for(int x=0;x<d.columnsX();x++)image.setRGB(x,d.columnsY()-1-y,gray(rgba[(y*d.columnsX()+x)*4+3]));
                ImageIO.write(image,"png",Path.of(prefix+"-transmittance-z"+slice+".png").toFile());
            }
        }
        float[] reactive=pipeline.captureVolumetricReactiveRgbaFloat();
        int width=pipeline.graph().width(),height=pipeline.graph().height();
        SceneCaptureIO.linear(Path.of(prefix+"-fog-reactive.h4f.gz"),reactive,width,height);
        BufferedImage mask=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)mask.setRGB(x,height-1-y,gray(reactive[(y*width+x)*4]));
        ImageIO.write(mask,"png",Path.of(prefix+"-fog-reactive.png").toFile());
    }
    private static int mapped(float linear){return value((float)Math.pow(Math.max(0,linear)/(1+Math.max(0,linear)),1/2.2));}
    private static int value(float f){return Math.round(Math.max(0,Math.min(1,f))*255);}
    private static int gray(float f){int v=value(f);return 0xff000000|v<<16|v<<8|v;}
}
