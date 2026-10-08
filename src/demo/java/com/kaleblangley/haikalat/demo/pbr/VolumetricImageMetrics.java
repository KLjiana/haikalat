package com.kaleblangley.haikalat.demo.pbr;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;

/** Independent double-precision image reduction over a configuration-defined ROI. */
final class VolumetricImageMetrics {
    record Result(int pixels,double nrmse,double p95Normalized,double darkMaximumAbsolute,
                  int nonfiniteValues,double transmittanceMaximumAbsolute) {
        boolean passed(double rmse,double p95,double dark) {
            return nonfiniteValues==0&&nrmse<=rmse&&p95Normalized<=p95&&darkMaximumAbsolute<=dark;
        }
    }
    static Result measure(float[] candidate,float[] reference,int width,int x0,int y0,int roiWidth,int roiHeight,
                          double epsilon,double floor,double darkLuminance) {
        if(candidate.length!=reference.length)throw new IllegalArgumentException("image extents differ");
        double sumError=0,sumReference=0,dark=0,transmittance=0;int invalid=0,index=0;
        double[] pixels=new double[roiWidth*roiHeight];
        for(int y=y0;y<y0+roiHeight;y++)for(int x=x0;x<x0+roiWidth;x++) {
            int offset=4*(x+width*y);double e=0,r=0,max=0;
            for(int c=0;c<4;c++)if(!Float.isFinite(candidate[offset+c])||!Float.isFinite(reference[offset+c]))invalid++;
            for(int c=0;c<3;c++) {double value=reference[offset+c],delta=candidate[offset+c]-value;
                e+=delta*delta;r+=value*value;max=Math.max(max,Math.abs(delta));}
            sumError+=e;sumReference+=r;pixels[index++]=Math.sqrt(e/3)/Math.max(Math.sqrt(r/3),floor);
            double l=.2126*reference[offset]+.7152*reference[offset+1]+.0722*reference[offset+2];
            if(l<darkLuminance)dark=Math.max(dark,max);
            transmittance=Math.max(transmittance,Math.abs(candidate[offset+3]-reference[offset+3]));
        }
        Arrays.sort(pixels);return new Result(index,Math.sqrt(sumError/Math.max(sumReference,epsilon)),
                pixels[Math.min(index-1,(int)Math.ceil(index*.95)-1)],dark,invalid,transmittance);
    }
    static void errorImage(Path path,float[] candidate,float[] reference,int width,int height) throws IOException {
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            int p=4*(x+y*width);double error=0,energy=0;
            for(int c=0;c<3;c++){double delta=candidate[p+c]-reference[p+c];error+=delta*delta;energy+=reference[p+c]*reference[p+c];}
            double normalized=Math.sqrt(error/3)/Math.max(Math.sqrt(energy/3),.01);
            int red=(int)Math.round(255*Math.min(1,normalized/.1));
            image.setRGB(x,height-1-y,0xff000000|red<<16);
        }
        ImageIO.write(image,"png",path.toFile());
    }
    static void preview(Path path,float[] rgba,int width,int height) throws IOException {
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){int p=4*(x+y*width),rgb=0xff000000;
            for(int c=0;c<3;c++){double v=Math.max(0,rgba[p+c]);int encoded=(int)Math.round(255*Math.pow(v/(1+v),1/2.2));rgb|=encoded<<(16-8*c);}
            image.setRGB(x,height-1-y,rgb);}
        ImageIO.write(image,"png",path.toFile());
    }
}
