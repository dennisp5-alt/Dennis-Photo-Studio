package com.dennis.craftmobile;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.Arrays;

/**
 * Deterministic architectural line finder: local Sobel orientation followed by
 * a bounded Hough accumulator, spatial line span and regional consensus.
 * It recognises structure even when no perspective correction is warranted.
 * No OpenCV, network or neural models.
 */
final class ArchitecturalLines {
    private static final int BINS = 61;      // angles -15° .. +15° by 0.5°
    private static final int RHO_STEP = 3;
    private static final int RHO_ZERO = 260;

    static final class Estimate {
        final float angle;
        final boolean confident;
        final int lines;
        final float strength;
        Estimate(float angle,boolean confident,int lines,float strength) {
            this.angle=angle;
            this.confident=confident;
            this.lines=lines;
            this.strength=strength;
        }
    }
    static final class Scene {
        final Estimate vAll,vLeft,vRight,hAll,hTop,hBottom;
        final boolean structureFound;
        Scene(Estimate[] vertical,Estimate[] horizontal) {
            vAll=vertical[0];vLeft=vertical[1];vRight=vertical[2];
            hAll=horizontal[0];hTop=horizontal[1];hBottom=horizontal[2];
            structureFound=vAll.confident||hAll.confident||
                    (vLeft.confident&&vRight.confident)||
                    (hTop.confident&&hBottom.confident);
        }
    }
    private static Estimate none(){return new Estimate(0f,false,0,0f);}

    static Scene scan(Bitmap input) {
        if(input==null)return new Scene(
                new Estimate[]{none(),none(),none()},
                new Estimate[]{none(),none(),none()});
        Bitmap small=input;
        int length=Math.max(input.getWidth(),input.getHeight());
        if(length>740) {
            float factor=740f/length;
            small=Bitmap.createScaledBitmap(input,
                    Math.max(80,Math.round(input.getWidth()*factor)),
                    Math.max(80,Math.round(input.getHeight()*factor)),true);
        }
        try {
            int width=small.getWidth(),height=small.getHeight();
            if(width<100||height<100)return new Scene(
                    new Estimate[]{none(),none(),none()},
                    new Estimate[]{none(),none(),none()});
            int[] rgb=new int[width*height];
            small.getPixels(rgb,0,width,0,0,width,height);
            float[] gray=new float[rgb.length];
            for(int i=0;i<rgb.length;i++) {
                int c=rgb[i];
                gray[i]=.2126f*Color.red(c)+.7152f*Color.green(c)+
                        .0722f*Color.blue(c);
            }
            int distanceBuckets=(width+height+550)/RHO_STEP+5;
            float[][][] strength=new float[2][BINS][distanceBuckets];
            short[][][] spanMin=new short[2][BINS][distanceBuckets];
            short[][][] spanMax=new short[2][BINS][distanceBuckets];
            for(int axis=0;axis<2;axis++)
                for(int bin=0;bin<BINS;bin++)
                    Arrays.fill(spanMin[axis][bin],Short.MAX_VALUE);

            double[] slopes=new double[BINS];
            for(int bin=0;bin<BINS;bin++)
                slopes[bin]=Math.tan(Math.toRadians(bin*.5f-15f));
            for(int y=2;y<height-2;y+=2) {
                if(Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException();
                for(int x=2;x<width-2;x+=2) {
                    int i=y*width+x;
                    float gx=(gray[i-width+1]+2f*gray[i+1]+gray[i+width+1])-
                            (gray[i-width-1]+2f*gray[i-1]+gray[i+width-1]);
                    float gy=(gray[i+width-1]+2f*gray[i+width]+gray[i+width+1])-
                            (gray[i-width-1]+2f*gray[i-width]+gray[i-width+1]);
                    float energy=(float)Math.hypot(gx,gy);
                    if(energy<95f)continue;
                    int axis;
                    float angle;
                    if(Math.abs(gx)>Math.abs(gy)*1.3f) {
                        axis=0; // vertical tangent: dx/dy
                        angle=(float)Math.toDegrees(Math.atan(-gy/gx));
                    } else if(Math.abs(gy)>Math.abs(gx)*1.3f) {
                        axis=1; // horizontal tangent: dy/dx
                        angle=(float)Math.toDegrees(Math.atan(-gx/gy));
                    } else continue;
                    if(Math.abs(angle)>14.8f)continue;
                    int centre=Math.round((angle+15f)*2f);
                    float weighted=Math.min(400f,energy);
                    for(int bin=Math.max(0,centre-3);bin<=Math.min(60,centre+3);bin++) {
                        float delta=angle-(bin*.5f-15f);
                        if(Math.abs(delta)>1.5f)continue;
                        float vote=weighted*Math.max(.10f,1f-Math.abs(delta)/1.6f);
                        double rho=axis==0?x-y*slopes[bin]:y-x*slopes[bin];
                        int column=(int)Math.round((rho+RHO_ZERO)/RHO_STEP);
                        if(column<0||column>=distanceBuckets)continue;
                        strength[axis][bin][column]+=vote;
                        short pos=(short)(axis==0?y:x);
                        if(pos<spanMin[axis][bin][column])
                            spanMin[axis][bin][column]=pos;
                        if(pos>spanMax[axis][bin][column])
                            spanMax[axis][bin][column]=pos;
                    }
                }
            }
            Estimate[][] answer=new Estimate[2][3];
            for(int axis=0;axis<2;axis++) {
                int extent=axis==0?height:width;
                float[][] scores=new float[3][BINS];
                int[][] counts=new int[3][BINS];
                for(int a=1;a<BINS-1;a++) {
                    // Select independent line intercepts, not thousands of
                    // pixels belonging to the same pole, roof or fence wire.
                    for(int rho=0;rho<distanceBuckets;rho++) {
                        float raw=strength[axis][a][rho];
                        int span=spanMax[axis][a][rho]-spanMin[axis][a][rho];
                        if(span<Math.max(32,(int)(extent*.075f))||raw<1400f)continue;
                        // Only local maxima in rho; avoid duplicates from a
                        // thick edge occupying three adjacent accumulator bins.
                        boolean localMax=true;
                        for(int offset=-2;offset<=2;offset++) {
                            int neighbour=rho+offset;
                            if(neighbour<0||neighbour>=distanceBuckets||
                                    neighbour==rho)continue;
                            if(strength[axis][a][neighbour]>raw) {
                                localMax=false;break;
                            }
                        }
                        if(!localMax)continue;
                        float contribution=raw*Math.min(1f,span/120f);
                        double intercept=rho*RHO_STEP-RHO_ZERO;
                        float middle=(spanMin[axis][a][rho]+spanMax[axis][a][rho])*.5f;
                        float location=(float)(intercept+middle*slopes[a]);
                        float transverse=axis==0?width:height;
                        if(location<0||location>transverse)continue;
                        scores[0][a]+=contribution;
                        counts[0][a]++;
                        if(location<transverse*.46f) {
                            scores[1][a]+=contribution;
                            counts[1][a]++;
                        } else if(location>transverse*.54f) {
                            scores[2][a]+=contribution;
                            counts[2][a]++;
                        }
                    }
                }
                for(int region=0;region<3;region++)
                    answer[axis][region]=summarise(scores[region],
                            counts[region],region==0);
            }
            return new Scene(answer[0],answer[1]);
        } finally {
            if(small!=input&&!small.isRecycled())small.recycle();
        }
    }

    private static Estimate summarise(float[] votes,int[] counts,boolean all) {
        float sum=0f,first=0f,second=0f;
        int best=-1;
        for(int b=1;b<BINS-1;b++) {
            sum+=votes[b];
            // A slight 3-bin moving average prevents one-bin jitter.
            float score=votes[b-1]*.3f+votes[b]+votes[b+1]*.3f;
            if(score>first){first=score;best=b;}
        }
        if(best<0||sum<2100f)return none();
        for(int b=1;b<BINS-1;b++) {
            if(Math.abs(b-best)<=4)continue;
            second=Math.max(second,votes[b-1]*.3f+votes[b]+votes[b+1]*.3f);
        }
        int independent=counts[best];
        float prominence=first/Math.max(600f,second);
        boolean reliable=first>(all?3900f:2400f) &&
                prominence>(all?1.10f:1.08f) &&
                independent>=1;
        // Reject a second incompatible peak supported about as strongly;
        // this often means two incompatible vanishing-point directions.
        float confidence=Math.min(1f,Math.max(0f,
                .40f*(prominence-1f)+.13f*Math.min(3,independent)));
        float angle=best*.5f-15f;
        return new Estimate(angle,reliable,independent,confidence);
    }

    private ArchitecturalLines(){}
}
