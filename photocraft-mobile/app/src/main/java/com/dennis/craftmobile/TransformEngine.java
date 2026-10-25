package com.dennis.craftmobile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import java.util.function.BooleanSupplier;

/**
 * Offline, deterministic perspective + roll correction. No ML/AI models.
 * In one Canvas sampling pass, perform both homography-based skew adjustment
 * and rotation. A binary-searched coverage zoom prevents black corners.
 */
final class TransformEngine {
    static final float MAX_SKEW = 25f;
    static final float MAX_ROLL = 15f;

    static final class Suggestion {
        final float angle,vertical,horizontal;
        final boolean hasAngle,hasVertical,hasHorizontal;
        final float confidence;
        Suggestion(float a,float v,float h,boolean aOK,boolean vOK,
                   boolean hOK,float confidence) {
            angle=a;vertical=v;horizontal=h;
            hasAngle=aOK;hasVertical=vOK;hasHorizontal=hOK;
            this.confidence=confidence;
        }
        boolean hasAny(){return hasAngle||hasVertical||hasHorizontal;}
    }

    /**
     * High-resolution-safe: only one output bitmap allocated for the geometry
     * correction, with at most one filtered draw from the source. Existing
     * photographic tone/colour/detail algorithms execute unchanged.
     */
    static Bitmap render(Bitmap source,float roll,float vertical,float horizontal,
                         BooleanSupplier cancelled) {
        if(source==null)throw new IllegalArgumentException("Photo required");
        if(Math.abs(roll)<.015f && Math.abs(vertical)<.015f &&
                Math.abs(horizontal)<.015f)return source;
        int w=source.getWidth(),h=source.getHeight();
        float cx=w*.5f,cy=h*.5f;
        // Perspective controls change trapezoid geometry, not simple shearing.
        // Vertical: top and bottom widths oppose; horizontal: left and right
        // heights oppose. Both axes are bounded to avoid extreme warping.
        float vx=clamp(vertical,-MAX_SKEW,MAX_SKEW)*w*.0025f;
        float hy=clamp(horizontal,-MAX_SKEW,MAX_SKEW)*h*.0025f;
        float[] corners={0f,0f,w,0f,0f,h,w,h};
        float[] dest={vx,hy,w-vx,-hy,-vx,h-hy,w+vx,h+hy};
        Matrix geometry=new Matrix();
        if(!geometry.setPolyToPoly(corners,0,dest,0,4))
            throw new IllegalArgumentException("Invalid perspective coordinates");
        geometry.postRotate(clamp(roll,-MAX_ROLL,MAX_ROLL),cx,cy);

        // Determine zoom by mapping *output* corners back into the source.
        // This prevents black triangular pixels after both transformations.
        float lower=1f,upper=3.5f;
        if(!covered(geometry,upper,w,h,cx,cy))
            throw new IllegalArgumentException("Transform would crop too much; reduce skew");
        for(int n=0;n<20;n++) {
            if(cancelled!=null && cancelled.getAsBoolean())
                throw new java.util.concurrent.CancellationException();
            float mid=(lower+upper)*.5f;
            if(covered(geometry,mid,w,h,cx,cy))upper=mid;
            else lower=mid;
        }
        Matrix resultMatrix=new Matrix(geometry);
        resultMatrix.postScale(upper*1.001f,upper*1.001f,cx,cy);
        if(cancelled!=null && cancelled.getAsBoolean())
            throw new java.util.concurrent.CancellationException();
        Bitmap result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas=new Canvas(result);
            Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG|Paint.DITHER_FLAG);
            canvas.drawBitmap(source,resultMatrix,paint);
            return result;
        } catch(RuntimeException | OutOfMemoryError error) {
            result.recycle();
            throw error;
        }
    }

    private static boolean covered(Matrix geometry,float zoom,int w,int h,
                                   float cx,float cy) {
        Matrix temp=new Matrix(geometry);
        temp.postScale(zoom,zoom,cx,cy);
        Matrix inverse=new Matrix();
        if(!temp.invert(inverse))return false;
        float[] points={0.7f,0.7f,w-.7f,.7f,.7f,h-.7f,w-.7f,h-.7f};
        inverse.mapPoints(points);
        for(int n=0;n<points.length;n+=2){
            if(!Float.isFinite(points[n])||!Float.isFinite(points[n+1])||
                    points[n]<.3f||points[n]>w-.3f||
                    points[n+1]<.3f||points[n+1]>h-.3f)return false;
        }
        return true;
    }

    /**
     * Two genuinely separate analyses:
     *  - Existing line-aware StraightenEngine estimates roll safely.
     *  - Regional vertical/horizontal edge slopes estimate convergence.
     * A transform is merely SUGGESTED, never silently applied.
     */
    static Suggestion analyse(Bitmap bitmap,boolean balanced) {
        if(bitmap==null)return new Suggestion(0,0,0,false,false,false,0);
        StraightenEngine.Estimate level=StraightenEngine.analyse(bitmap);
        boolean hasAngle=level.reliable||level.requiresConfirmation;
        float roll=hasAngle?clamp(level.correction,-8f,8f):0f;
        if(level.alreadyLevel)roll=0f;
        float maxTilt=balanced?8f:18f;
        float strength=balanced?.55f:.90f;

        Bitmap sample=bitmap;
        int longEdge=Math.max(bitmap.getWidth(),bitmap.getHeight());
        if(longEdge>700){
            float scale=700f/longEdge;
            sample=Bitmap.createScaledBitmap(bitmap,
                    Math.max(96,Math.round(bitmap.getWidth()*scale)),
                    Math.max(96,Math.round(bitmap.getHeight()*scale)),true);
        }
        try {
            int w=sample.getWidth(),h=sample.getHeight();
            int[] rgb=new int[w*h];
            sample.getPixels(rgb,0,w,0,0,w,h);
            float[] gray=new float[rgb.length];
            for(int k=0;k<rgb.length;k++){
                int c=rgb[k];
                gray[k]=.2126f*Color.red(c)+.7152f*Color.green(c)+
                        .0722f*Color.blue(c);
            }

            // Angle histograms for left/right verticals and upper/lower
            // horizontals. Absence of one side means perspective is unknown;
            // refuse to invent an automatic trapezoid correction.
            EdgeStats vLeft=new EdgeStats(),vRight=new EdgeStats();
            EdgeStats hTop=new EdgeStats(),hBottom=new EdgeStats();
            for(int y=2;y<h-2;y+=2){
                if(Thread.currentThread().isInterrupted())
                    return new Suggestion(0,0,0,false,false,false,0);
                for(int x=2;x<w-2;x+=2){
                    int i=y*w+x;
                    float gx=(gray[i-w+1]+2f*gray[i+1]+gray[i+w+1])-
                            (gray[i-w-1]+2f*gray[i-1]+gray[i+w-1]);
                    float gy=(gray[i+w-1]+2f*gray[i+w]+gray[i+w+1])-
                            (gray[i-w-1]+2f*gray[i-w]+gray[i-w+1]);
                    float energy=(float)Math.hypot(gx,gy);
                    if(energy<145f)continue;
                    float strengthPixel=Math.min(energy,440f);
                    if(Math.abs(gx)>Math.abs(gy)*2f){
                        float angle=(float)Math.toDegrees(Math.atan(-gy/gx));
                        if(Math.abs(angle)<16f) {
                            if(x<w*.44f)vLeft.add(angle,strengthPixel,y,h);
                            else if(x>w*.56f)vRight.add(angle,strengthPixel,y,h);
                        }
                    }
                    if(Math.abs(gy)>Math.abs(gx)*2f){
                        float angle=(float)Math.toDegrees(Math.atan(-gx/gy));
                        if(Math.abs(angle)<16f) {
                            if(y<h*.44f)hTop.add(angle,strengthPixel,x,w);
                            else if(y>h*.56f)hBottom.add(angle,strengthPixel,x,w);
                        }
                    }
                }
            }

            float vertical=0f,horizontal=0f;
            boolean vOK=false,hOK=false;
            if(vLeft.reliable() && vRight.reliable()){
                float a=vLeft.angle(),b=vRight.angle();
                float separation=b-a;
                // Opposite convergence is evidence for projective distortion;
                // uniform slope means camera roll, not vertical keystone.
                if(a*b<0f && Math.abs(separation)>1.6f &&
                        Math.abs(separation)<13f) {
                    float slopes=(float)(Math.tan(Math.toRadians(b))-
                            Math.tan(Math.toRadians(a)));
                    vertical=clamp(-slopes*(h/(float)w)*100f*strength,
                            -maxTilt,maxTilt);
                    vOK=Math.abs(vertical)>=.75f;
                }
            }
            if(hTop.reliable()&&hBottom.reliable()) {
                float a=hTop.angle(),b=hBottom.angle();
                float separation=b-a;
                if(a*b<0f && Math.abs(separation)>1.6f &&
                        Math.abs(separation)<13f) {
                    float slopes=(float)(Math.tan(Math.toRadians(b))-
                            Math.tan(Math.toRadians(a)));
                    horizontal=clamp(-slopes*(w/(float)h)*100f*strength,
                            -maxTilt,maxTilt);
                    hOK=Math.abs(horizontal)>=.75f;
                }
            }
            float confidence=(vOK||hOK)?.65f:
                    (hasAngle?level.confidence:0f);
            return new Suggestion(roll,vertical,horizontal,
                    hasAngle,vOK,hOK,confidence);
        } finally {
            if(sample!=bitmap&&!sample.isRecycled())sample.recycle();
        }
    }

    private static final class EdgeStats {
        static final int COUNT=33;
        final float[] votes=new float[COUNT];
        final int[] min=new int[COUNT],max=new int[COUNT];
        int totalEdges;
        float totalWeight;
        EdgeStats() {
            java.util.Arrays.fill(min,Integer.MAX_VALUE);
            java.util.Arrays.fill(max,Integer.MIN_VALUE);
        }
        void add(float angle,float weight,int pos,int extent) {
            int bin=Math.round(angle)+16;
            if(bin<1||bin>31)return;
            for(int offset=-1;offset<=1;offset++){
                int b=bin+offset;
                float contribution=weight*(offset==0?1f:.35f);
                votes[b]+=contribution;
                min[b]=Math.min(min[b],pos);
                max[b]=Math.max(max[b],pos);
                totalWeight+=contribution;
            }
            totalEdges++;
        }
        int peakBin(){
            int best=1;
            for(int i=2;i<COUNT-1;i++)
                if(votes[i]>votes[best])best=i;
            return best;
        }
        boolean reliable(){
            if(totalEdges<38||totalWeight<7000f)return false;
            int best=peakBin();
            float bestVote=votes[best];
            float second=0f;
            for(int b=1;b<COUNT-1;b++)
                if(Math.abs(b-best)>2)second=Math.max(second,votes[b]);
            return bestVote/totalWeight>.085f &&
                    bestVote>second*1.06f &&
                    max[best]-min[best]>=30;
        }
        float angle(){return peakBin()-16f;}
    }

    private static float clamp(float v,float min,float max){
        return Math.max(min,Math.min(max,v));
    }
    private TransformEngine(){}
}
