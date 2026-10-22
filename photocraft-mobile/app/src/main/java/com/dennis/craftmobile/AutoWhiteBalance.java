package com.dennis.craftmobile;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.Arrays;

/**
 * Cautious auto-white-balance estimate from 64x64 scene samples.
 *
 * Only midtone, low-chroma candidate neutrals are eligible. Spatial coverage
 * must include both halves of the image, so blue skies, pink flowers, foliage
 * and warm sunsets alone cannot force the colour correction. No classification,
 * face recognition, illuminant temperature or RAW reconstruction is claimed.
 */
final class AutoWhiteBalance {
    static final int SIZE = 64, MAX_SAMPLES = SIZE * SIZE;

    static final class Estimate {
        final float red, green, blue, confidence;
        final int neutralSamples, regions;
        Estimate(float r,float g,float b,float confidence,int count,int coverage) {
            red=r;green=g;blue=b;this.confidence=confidence;
            neutralSamples=count;regions=coverage;
        }
        static Estimate unchanged(int count,int coverage) {
            return new Estimate(1f,1f,1f,0f,count,coverage);
        }
    }

    private static float clamp(float value,float min,float max) {
        return Math.min(max,Math.max(min,value));
    }

    static Estimate analyse(Bitmap source) {
        int width=source.getWidth(),height=source.getHeight();
        if(width<16 || height<16)return Estimate.unchanged(0,0);

        float[] redGreen=new float[MAX_SAMPLES],blueGreen=new float[MAX_SAMPLES];
        boolean[] regions=new boolean[8*8];
        int count=0,upper=0,lower=0;

        // 4096 spatial samples; five-pixel cross averages suppress single noisy
        // pixels and let grey objects provide stable neutral evidence.
        for(int gy=0;gy<SIZE;gy++) {
            int y=Math.min(height-2,Math.max(1,(int)((gy+.5f)*height/SIZE)));
            for(int gx=0;gx<SIZE;gx++) {
                int x=Math.min(width-2,Math.max(1,(int)((gx+.5f)*width/SIZE)));
                int center=source.getPixel(x,y);
                int left=source.getPixel(x-1,y),right=source.getPixel(x+1,y);
                int top=source.getPixel(x,y-1),bottom=source.getPixel(x,y+1);
                float r=(Color.red(center)*2+Color.red(left)+Color.red(right)+
                        Color.red(top)+Color.red(bottom))/6f/255f;
                float g=(Color.green(center)*2+Color.green(left)+Color.green(right)+
                        Color.green(top)+Color.green(bottom))/6f/255f;
                float b=(Color.blue(center)*2+Color.blue(left)+Color.blue(right)+
                        Color.blue(top)+Color.blue(bottom))/6f/255f;
                float lum=.2126f*r+.7152f*g+.0722f*b;
                float hi=Math.max(r,Math.max(g,b)),lo=Math.min(r,Math.min(g,b));
                float relativeChroma=(hi-lo)/Math.max(hi,.001f);
                if(lum<.20f||lum>.82f||hi>.94f||lo<.05f ||
                        relativeChroma>.20f)continue;
                redGreen[count]=(float)Math.log(Math.max(.01f,r)/Math.max(.01f,g));
                blueGreen[count]=(float)Math.log(Math.max(.01f,b)/Math.max(.01f,g));
                count++;
                regions[(gy/8)*8+gx/8]=true;
                if(gy<32)upper++; else lower++;
            }
        }
        int regionCount=0;
        for(boolean value:regions)if(value)regionCount++;

        // Neutrals must be spatially spread, not a single flower or coloured sky.
        if(count<85||regionCount<20||upper<count*.17f||lower<count*.17f)
            return Estimate.unchanged(count,regionCount);

        // Trim extremes to suppress isolated foliage, skin, colour text or
        // coloured clothing that barely passed the saturation gate.
        float estimatedRG=trimmedMean(redGreen,count);
        float estimatedBG=trimmedMean(blueGreen,count);
        float coverage=Math.min(1f,(count-70f)/350f) *
                Math.min(1f,(regionCount-15f)/30f);
        float spread=Math.min(1f,Math.min(upper,lower)/(count*.24f));
        float confidence=clamp(coverage*spread,0f,1f);
        if(confidence<.08f)return Estimate.unchanged(count,regionCount);

        // The corrective colour gain is deliberately bounded. Compensate
        // the gains back to neutral luminance so WB doesn't brighten exposure.
        float correction=.63f*confidence;
        float rGain=clamp((float)Math.exp(-estimatedRG*correction),.94f,1.06f);
        float bGain=clamp((float)Math.exp(-estimatedBG*correction),.94f,1.06f);
        float luminance=.2126f*rGain+.7152f+.0722f*bGain;
        float gGain=clamp(1f/luminance,.97f,1.03f);
        rGain=clamp(rGain*gGain,.94f,1.06f);
        bGain=clamp(bGain*gGain,.94f,1.06f);

        // Trivial <0.3% differences just cause needless colour churn.
        if(Math.abs(rGain-1f)<.003f &&
                Math.abs(gGain-1f)<.003f &&
                Math.abs(bGain-1f)<.003f)
            return Estimate.unchanged(count,regionCount);

        return new Estimate(rGain,gGain,bGain,confidence,count,regionCount);
    }
    private static float trimmedMean(float[] values,int count) {
        float[] ordered=Arrays.copyOf(values,count);
        Arrays.sort(ordered);
        int begin=count/5,end=count-begin;
        double total=0;
        for(int i=begin;i<end;i++)total+=ordered[i];
        return (float)(total/Math.max(1,end-begin));
    }

    private AutoWhiteBalance() {}
}
