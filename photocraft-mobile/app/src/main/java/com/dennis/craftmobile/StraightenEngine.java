package com.dennis.craftmobile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.util.Arrays;

/**
 * Line-aware, non-generative, geometric auto straightening.
 *
 * The earlier v0.6.9/10 histogram rejected photographs where many unrelated
 * gradients competed with valid rooflines, walls or poles. This detector
 * gathers *collinear extended segments*, separately for near-horizontal and
 * near-vertical references, and judges confidence from angular peak
 * prominence. It never forces a rotation when genuine evidence is absent.
 */
final class StraightenEngine {
    static final float MANUAL_MAX = 15f;
    private static final float AUTO_MAX = 10f;
    private static final float BIN_STEP = .5f;
    private static final int BINS = 49; // -12 to +12 inclusive
    private static final int RHO_STEP = 3;
    private static final int RHO_OFFSET = 170;

    static final class Estimate {
        final float correction, confidence;
        final boolean reliable, alreadyLevel;
        final float observedAngle;
        Estimate(float correction,float confidence,boolean reliable,
                 boolean alreadyLevel,float observedAngle) {
            this.correction=correction;
            this.confidence=confidence;
            this.reliable=reliable;
            this.alreadyLevel=alreadyLevel;
            this.observedAngle=observedAngle;
        }
    }

    private static final class AxisScore {
        final int bestBin;
        final float best, baseline, quality;
        final boolean valid;
        AxisScore(int bin,float best,float baseline,boolean valid) {
            bestBin=bin;
            this.best=best;this.baseline=baseline;
            quality=best/Math.max(100f,baseline);
            this.valid=valid;
        }
    }

    static Estimate analyse(Bitmap source) {
        if(source==null)return new Estimate(0f,0f,false,false,0f);
        Bitmap small=source;
        int longest=Math.max(source.getWidth(),source.getHeight());
        if(longest>800) {
            float scale=800f/longest;
            small=Bitmap.createScaledBitmap(source,
                    Math.max(40,Math.round(scale*source.getWidth())),
                    Math.max(40,Math.round(scale*source.getHeight())),true);
        }
        try {
            int w=small.getWidth(),h=small.getHeight();
            if(w<96||h<96)return new Estimate(0f,0f,false,false,0f);
            int[] pixels=new int[w*h];
            small.getPixels(pixels,0,w,0,0,w,h);
            float[] grey=new float[pixels.length];
            for(int i=0;i<grey.length;i++) {
                int c=pixels[i];
                grey[i]=.2126f*Color.red(c)+.7152f*Color.green(c)+
                        .0722f*Color.blue(c);
            }

            // Two independent sets of line-intercept bins. Horizontal and
            // vertical structure can disagree in perspective photographs.
            int rhoBins=(Math.max(w,h)+400)/RHO_STEP+3;
            float[][][] votes=new float[2][BINS][rhoBins];
            short[][][] spanMin=new short[2][BINS][rhoBins];
            short[][][] spanMax=new short[2][BINS][rhoBins];
            for(int axis=0;axis<2;axis++)
                for(int angle=0;angle<BINS;angle++)
                    Arrays.fill(spanMin[axis][angle],Short.MAX_VALUE);

            double[] tan=new double[BINS];
            for(int a=0;a<BINS;a++)tan[a]=Math.tan(Math.toRadians(a*BIN_STEP-12f));
            int strongPixels=0;
            for(int y=2;y<h-2;y+=2) {
                if(Thread.currentThread().isInterrupted())
                    return new Estimate(0f,0f,false,false,0f);
                for(int x=2;x<w-2;x+=2) {
                    int i=y*w+x;
                    float gx=(grey[i-w+1]+2*grey[i+1]+grey[i+w+1])
                            -(grey[i-w-1]+2*grey[i-1]+grey[i+w-1]);
                    float gy=(grey[i+w-1]+2*grey[i+w]+grey[i+w+1])
                            -(grey[i-w-1]+2*grey[i-w]+grey[i-w+1]);
                    float magnitude=(float)Math.hypot(gx,gy);
                    if(magnitude<130f)continue;
                    int axis=Math.abs(gy)>=Math.abs(gx)?0:1;
                    float angle=(float)Math.toDegrees(Math.atan2(-gx,gy));
                    while(angle>45f)angle-=90f;
                    while(angle< -45f)angle+=90f;
                    if(Math.abs(angle)>12f)continue;

                    strongPixels++;
                    int center=Math.round((angle+12f)/BIN_STEP);
                    float weight=Math.min(magnitude,450f);
                    for(int k=Math.max(0,center-3);k<=Math.min(BINS-1,center+3);k++) {
                        float diff=(k*BIN_STEP-12f)-angle;
                        if(Math.abs(diff)>1.8f)continue;
                        float contribution=weight*(float)Math.exp(
                                -.5f*(diff/1.1f)*(diff/1.1f));
                        double rho=axis==0 ? y-x*tan[k] : x+y*tan[k];
                        int cell=(int)Math.round((rho+RHO_OFFSET)/RHO_STEP);
                        if(cell<0||cell>=rhoBins)continue;
                        votes[axis][k][cell]+=contribution;
                        short at=(short)(axis==0?x:y);
                        if(at<spanMin[axis][k][cell])spanMin[axis][k][cell]=at;
                        if(at>spanMax[axis][k][cell])spanMax[axis][k][cell]=at;
                    }
                }
            }
            if(strongPixels<50)return new Estimate(0f,0f,false,false,0f);

            AxisScore horizontal=scoreAxis(votes[0],spanMin[0],spanMax[0],w);
            AxisScore vertical=scoreAxis(votes[1],spanMin[1],spanMax[1],h);
            if(!horizontal.valid && !vertical.valid)
                return new Estimate(0f,0f,false,false,0f);

            AxisScore chosen;
            float angle;
            if(horizontal.valid && vertical.valid) {
                float hAngle=refinedAngle(votes[0],spanMin[0],spanMax[0],
                        w,horizontal.bestBin);
                float vAngle=refinedAngle(votes[1],spanMin[1],spanMax[1],
                        h,vertical.bestBin);
                if(Math.abs(hAngle-vAngle)<=1.6f) {
                    float hWeight=horizontal.quality,vWeight=vertical.quality;
                    angle=(hAngle*hWeight+vAngle*vWeight)/(hWeight+vWeight);
                    chosen=horizontal.quality>=vertical.quality?horizontal:vertical;
                } else if(horizontal.quality>vertical.quality*1.4f) {
                    // Strong roofs/landscape horizon despite perspective poles.
                    chosen=horizontal; angle=hAngle;
                } else if(vertical.quality>horizontal.quality*1.5f) {
                    chosen=vertical; angle=vAngle;
                } else {
                    // Different high-confidence architectural axes could mean
                    // perspective or converging lines, not camera roll.
                    return new Estimate(0f,0f,false,false,0f);
                }
            } else {
                chosen=horizontal.valid?horizontal:vertical;
                int axis=horizontal.valid?0:1;
                angle=refinedAngle(votes[axis],spanMin[axis],spanMax[axis],
                        axis==0?w:h,chosen.bestBin);
            }

            if(Math.abs(angle)>AUTO_MAX)
                return new Estimate(0f,0f,false,false,angle);
            // ±0.8° is usually visually already level, and tiny rotations
            // would only crop the image without meaningful improvement.
            boolean alreadyLevel=Math.abs(angle)<.8f;
            float confidence=Math.min(1f,Math.max(0f,
                    (chosen.quality-1.6f)/4f));
            return new Estimate(alreadyLevel?0f:-angle,confidence,
                    true,alreadyLevel,angle);
        } finally {
            if(small!=source && !small.isRecycled())small.recycle();
        }
    }

    private static AxisScore scoreAxis(float[][] votes,short[][] mins,
                                       short[][] maxs,int extent) {
        final float[] scores=new float[BINS];
        int rhoBins=votes[0].length;
        int minimumSpan=Math.max(36,(int)(extent*.08f));
        for(int a=1;a<BINS-1;a++) {
            int[] chosen=new int[4];
            Arrays.fill(chosen,-10000);
            for(int n=0;n<4;n++) {
                float best=0f;int bestRho=-1;
                for(int r=0;r<rhoBins;r++) {
                    int span=maxs[a][r]-mins[a][r];
                    if(span<minimumSpan)continue;
                    boolean near=false;
                    for(int previous:chosen)
                        if(Math.abs(r-previous)<=2)near=true;
                    if(near)continue;
                    float lengthWeight=(float)Math.pow(
                            Math.min(1f,span/140f),.6f);
                    float score=votes[a][r]*lengthWeight;
                    if(score>best){best=score;bestRho=r;}
                }
                if(bestRho<0)break;
                chosen[n]=bestRho;
                scores[a]+=best;
            }
        }
        int best=1;
        for(int a=2;a<BINS-1;a++)
            if(scores[a]>scores[best])best=a;
        float[] copy=scores.clone();
        Arrays.sort(copy);
        float baseline=copy[BINS/2];
        // A structured orientation must rise clearly above the otherwise
        // flat random-texture response. Long, coherent segments matter
        // more than thousands of weak individual diagonal pixel edges.
        float minScore=Math.max(4000f,12000f*
                Math.min(1f,extent/800f));
        boolean valid=scores[best]>minScore &&
                scores[best]>baseline*1.85f;
        return new AxisScore(best,scores[best],baseline,valid);
    }

    private static float refinedAngle(float[][] votes,short[][] mins,
                                      short[][] maxs,int extent,int best) {
        // One half-degree neighbourhood is adequate; a quadratic refinement
        // avoids clipping to fixed 0.5° increments for manual follow-up.
        float left=topStrength(votes[best-1],mins[best-1],maxs[best-1],extent);
        float centre=topStrength(votes[best],mins[best],maxs[best],extent);
        float right=topStrength(votes[best+1],mins[best+1],maxs[best+1],extent);
        float denom=left-2f*centre+right;
        float shift=Math.abs(denom)<.0001f ? 0f :
                .5f*(left-right)/denom;
        shift=Math.max(-.5f,Math.min(.5f,shift));
        return (best+shift)*BIN_STEP-12f;
    }

    private static float topStrength(float[] votes,short[] mins,
                                     short[] maxs,int extent) {
        float best=0f;
        int minimumSpan=Math.max(36,(int)(extent*.08f));
        for(int r=0;r<votes.length;r++) {
            int span=maxs[r]-mins[r];
            if(span<minimumSpan)continue;
            float strength=votes[r]*(float)Math.pow(
                    Math.min(1f,span/140f),.6f);
            best=Math.max(best,strength);
        }
        return best;
    }

    /**
     * Rotate into the SAME output frame with exactly enough centred zoom to
     * cover every output corner. This avoids black triangles, at the cost
     * of a modest crop that increases with angle / aspect ratio.
     */
    static Bitmap rotateAndFill(Bitmap source,float degrees) {
        if(source==null||Math.abs(degrees)<.015f)return source;
        float angle=Math.max(-MANUAL_MAX,Math.min(MANUAL_MAX,degrees));
        int w=source.getWidth(),h=source.getHeight();
        double rad=Math.toRadians(angle);
        float cosine=(float)Math.abs(Math.cos(rad));
        float sine=(float)Math.abs(Math.sin(rad));
        float zoom=Math.max(cosine+sine*(h/(float)w),
                cosine+sine*(w/(float)h));
        // Small safety margin against fractional antialias sampling at edges.
        zoom=Math.max(1f,zoom*1.001f);
        Bitmap result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(result);
        Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG|Paint.ANTI_ALIAS_FLAG);
        float cx=w*.5f,cy=h*.5f;
        canvas.translate(cx,cy);
        canvas.rotate(angle);
        canvas.scale(zoom,zoom);
        canvas.translate(-cx,-cy);
        canvas.drawBitmap(source,0f,0f,paint);
        return result;
    }
    private StraightenEngine() {}
}
