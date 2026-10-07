package com.kaleblangley.haikalat.demo.pbr;

import java.util.List;

/** Double reductions for temporal evidence; independent of renderer history and packing helpers. */
final class VolumetricTemporalMetrics {
    record Roi(int width,int height,int x,int y,int columns,int rows) {
        Roi {
            if(width<1||height<1||x<0||y<0||columns<1||rows<1
                    ||(long)x+columns>width||(long)y+rows>height) throw new IllegalArgumentException("invalid temporal ROI");
        }
        int pixels(){return Math.multiplyExact(columns,rows);}
        void require(float[] image) {
            if(image.length!=Math.multiplyExact(Math.multiplyExact(width,height),4))throw new IllegalArgumentException("temporal image extent differs");
        }
    }
    record Recovery(double beforeAbsoluteRgbEnergy,double residualAbsoluteRgbEnergy,Double residualRatio,
                    int nonfiniteValues,boolean passed) { }
    record Stability(int frames,int pixels,int brightPixels,int darkPixels,int nonfiniteValues,
                     double maximumRelativeStdDev,double maximumDarkAbsoluteStdDev,
                     double roiReferenceMean,double roiMeanRelativeStdDev,double roiMeanAbsoluteStdDev,
                     boolean perPixelDiagnosticPassed,boolean passed) { }

    static Recovery recovery(float[] before,float[] beforeBaseline,float[] after,float[] afterBaseline,
                             Roi roi,double minimumEnergy,double limit) {
        for(float[] image:new float[][]{before,beforeBaseline,after,afterBaseline})roi.require(image);
        double energy=0,residual=0;int invalid=0;
        for(int y=roi.y;y<roi.y+roi.rows;y++)for(int x=roi.x;x<roi.x+roi.columns;x++) {
            int p=4*(x+y*roi.width);
            for(int c=0;c<3;c++) {
                float a=before[p+c],b=beforeBaseline[p+c],d=after[p+c],e=afterBaseline[p+c];
                if(!Float.isFinite(a)||!Float.isFinite(b)||!Float.isFinite(d)||!Float.isFinite(e)){invalid++;continue;}
                energy+=Math.abs((double)a-b);residual+=Math.abs((double)d-e);
            }
        }
        Double ratio=energy>minimumEnergy?residual/energy:null;
        return new Recovery(energy,residual,ratio,invalid,invalid==0&&ratio!=null&&ratio<=limit);
    }

    static Stability stability(List<float[]> sequence,float[] reference,Roi roi,
                               double darkLuminance,double relativeLimit,double absoluteLimit) {
        if(sequence.size()<2)throw new IllegalArgumentException("temporal stability needs at least two frames");
        roi.require(reference);for(float[] image:sequence)roi.require(image);
        int bright=0,dark=0,invalid=0;double relative=0,absolute=0,referenceSum=0;
        double[] frameMeans=new double[sequence.size()];
        for(int y=roi.y;y<roi.y+roi.rows;y++)for(int x=roi.x;x<roi.x+roi.columns;x++) {
            int p=4*(x+y*roi.width);boolean valid=finiteRgb(reference,p);
            double mean=0,m2=0;int n=0;
            for(int frame=0;frame<sequence.size();frame++) {
                float[] image=sequence.get(frame);
                if(!finiteRgb(image,p)){invalid++;valid=false;continue;}
                double l=luminance(image,p);frameMeans[frame]+=l;
                double delta=l-mean;mean+=delta/++n;m2+=delta*(l-mean);
            }
            if(!finiteRgb(reference,p))invalid++;
            if(!valid)continue;
            double ref=luminance(reference,p),std=Math.sqrt(Math.max(0,m2/n));referenceSum+=ref;
            if(ref>darkLuminance){bright++;relative=Math.max(relative,std/ref);}
            else {dark++;absolute=Math.max(absolute,std);}
        }
        double mean=0,m2=0;int n=0;
        for(double value:frameMeans){double delta=value-mean;mean+=delta/++n;m2+=delta*(value-mean);}
        double std=Math.sqrt(Math.max(0,m2/n))/roi.pixels(),referenceMean=referenceSum/roi.pixels();
        double roiRelative=referenceMean>0?std/referenceMean:0;
        boolean planned=referenceMean>darkLuminance?roiRelative<=relativeLimit:std<=absoluteLimit;
        return new Stability(sequence.size(),roi.pixels(),bright,dark,invalid,relative,absolute,
                referenceMean,roiRelative,std,invalid==0&&relative<=relativeLimit&&absolute<=absoluteLimit,
                invalid==0&&planned);
    }
    private static double luminance(float[] image,int p){return .2126*image[p]+.7152*image[p+1]+.0722*image[p+2];}
    private static boolean finiteRgb(float[] image,int p){return Float.isFinite(image[p])&&Float.isFinite(image[p+1])&&Float.isFinite(image[p+2]);}
}
