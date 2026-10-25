package com.dennis.craftmobile;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.Arrays;

/**
 * Optional user-guided JPEG/HEIC white balance from a known neutral patch.
 * This deliberately does not change the approved conservative AutoWB algorithm.
 * Sample from the ORIGINAL preview, not from an already rendered edit.
 */
final class NeutralPointWB {
    private static float clamp(float v, float low, float high) {
        return Math.max(low, Math.min(high, v));
    }

    static AutoWhiteBalance.Estimate measure(Bitmap source, float imageX, float imageY) {
        if (source == null || source.isRecycled())
            throw new IllegalArgumentException("Open a photo first");
        int w=source.getWidth(), h=source.getHeight();
        int cx=(int)Math.floor(imageX), cy=(int)Math.floor(imageY);
        if (cx<0 || cy<0 || cx>=w || cy>=h)
            throw new IllegalArgumentException("Tap inside the photograph");

        int radius=Math.max(3,Math.min(9,Math.min(w,h)/160));
        int x0=Math.max(0,cx-radius), x1=Math.min(w-1,cx+radius);
        int y0=Math.max(0,cy-radius), y1=Math.min(h-1,cy+radius);
        int length=(x1-x0+1)*(y1-y0+1);
        int[] reds=new int[length],greens=new int[length],blues=new int[length];
        int i=0;
        for(int y=y0;y<=y1;y++)for(int x=x0;x<=x1;x++) {
            int c=source.getPixel(x,y);
            reds[i]=Color.red(c);
            greens[i]=Color.green(c);
            blues[i]=Color.blue(c);
            i++;
        }
        Arrays.sort(reds); Arrays.sort(greens); Arrays.sort(blues);
        float r=reds[length/2]/255f, g=greens[length/2]/255f,
                b=blues[length/2]/255f;
        float hi=Math.max(r,Math.max(g,b)),lo=Math.min(r,Math.min(g,b));
        float luma=.2126f*r+.7152f*g+.0722f*b;
        if(luma<.15f || lo<.075f)
            throw new IllegalArgumentException("Reference is too dark. Tap a brighter grey or white area.");
        if(hi>.96f)
            throw new IllegalArgumentException("Reference is too bright or clipped. Tap a midtone grey area.");
        if((hi-lo)/Math.max(.01f,hi)>.50f)
            throw new IllegalArgumentException("Reference appears strongly coloured. Tap a grey or white surface.");

        // A chosen neutral has an identifiable target. Correct a larger cast
        // than automatic WB, while retaining a little JPEG colour character.
        final float correction=.88f;
        float rGain=clamp((float)Math.exp(-Math.log(Math.max(.015f,r)/Math.max(.015f,g))*
                correction),.80f,1.22f);
        float bGain=clamp((float)Math.exp(-Math.log(Math.max(.015f,b)/Math.max(.015f,g))*
                correction),.80f,1.22f);
        float luminance=.2126f*rGain+.7152f+.0722f*bGain;
        float gGain=clamp(1f/luminance,.93f,1.07f);
        rGain=clamp(rGain*gGain,.80f,1.22f);
        bGain=clamp(bGain*gGain,.80f,1.22f);

        if(Math.abs(rGain-1f)<.003f && Math.abs(gGain-1f)<.003f &&
                Math.abs(bGain-1f)<.003f)
            return AutoWhiteBalance.Estimate.unchanged(length,1);
        return new AutoWhiteBalance.Estimate(rGain,gGain,bGain,1f,length,1);
    }

    private NeutralPointWB() {}
}
