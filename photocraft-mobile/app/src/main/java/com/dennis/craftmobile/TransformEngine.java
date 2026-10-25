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
        final boolean structureFound, alreadyAligned;
        Suggestion(float a,float v,float h,boolean aOK,boolean vOK,
                   boolean hOK,float confidence,boolean found,boolean aligned) {
            angle=a;vertical=v;horizontal=h;
            hasAngle=aOK;hasVertical=vOK;hasHorizontal=hOK;
            this.confidence=confidence;
            structureFound=found;alreadyAligned=aligned;
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
        /**
     * v0.6.15: match actual extended lines and repeated structural spans, not
     * isolated Sobel pixel orientations. Do not demand slopes have opposite
     * signs: camera roll can shift both toward the same sign.
     */
    static Suggestion analyse(Bitmap bitmap,boolean balanced) {
        if(bitmap==null)return new Suggestion(0,0,0,false,false,false,0,false,false);
        ArchitecturalLines.Scene scene=ArchitecturalLines.scan(bitmap);
        StraightenEngine.Estimate prior=StraightenEngine.analyse(bitmap);
        boolean found=scene.structureFound ||
                prior.reliable || prior.requiresConfirmation;
        ArchitecturalLines.Estimate hv=scene.vAll;
        ArchitecturalLines.Estimate hh=scene.hAll;
        float angle=0f;
        boolean angleOK=false;
        if(hv.confident && hh.confident) {
            // In genuine camera roll, horizontal and vertical architectural
            // families indicate roughly the same angle. Perspective can make
            // them disagree. Never use a dominant fence alone in conflict.
            if(Math.abs(hv.angle-hh.angle)<2.2f) {
                float geometricAngle=(hv.angle+hh.angle)*.5f;
                if(Math.abs(geometricAngle)>.8f &&
                        Math.abs(geometricAngle)<8f) {
                    angle=-geometricAngle;
                    angleOK=true;
                }
            }
        } else {
            ArchitecturalLines.Estimate observed=hv.confident?hv:hh;
            if(observed.confident && observed.strength>=.25f &&
                    Math.abs(observed.angle)>.9f &&
                    Math.abs(observed.angle)<6f) {
                angle=-observed.angle;
                angleOK=true;
            }
        }
        // Only use older line-angle results when the new detector finds no
        // stable architectural family, avoiding reintroduction of misleading
        // perspective correction on roof and sidewalk photographs.
        if(!scene.structureFound &&
                (prior.reliable||prior.requiresConfirmation) &&
                Math.abs(prior.correction)>.8f &&
                Math.abs(prior.correction)<5f) {
            angle=prior.correction;
            angleOK=true;
        }

        final float damp=balanced?.55f:.86f;
        final float cap=balanced?8f:17f;
        float vertical=0f,horizontal=0f;
        boolean vOK=false,hOK=false;
        ArchitecturalLines.Estimate l=scene.vLeft,r=scene.vRight;
        if(l.confident && r.confident) {
            float separation=r.angle-l.angle;
            if(Math.abs(separation)>=1.4f &&
                    Math.abs(separation)<=10.5f) {
                float gradient=(float)(Math.tan(Math.toRadians(r.angle))-
                        Math.tan(Math.toRadians(l.angle)));
                vertical=clamp(-gradient*bitmap.getHeight()/
                        (float)bitmap.getWidth()*100f*damp,-cap,cap);
                vOK=Math.abs(vertical)>.85f;
            }
        }
        ArchitecturalLines.Estimate t=scene.hTop,b=scene.hBottom;
        if(t.confident && b.confident) {
            float separation=b.angle-t.angle;
            if(Math.abs(separation)>=1.4f &&
                    Math.abs(separation)<=10.5f) {
                float gradient=(float)(Math.tan(Math.toRadians(b.angle))-
                        Math.tan(Math.toRadians(t.angle)));
                horizontal=clamp(-gradient*bitmap.getWidth()/
                        (float)bitmap.getHeight()*100f*damp,-cap,cap);
                hOK=Math.abs(horizontal)>.85f;
            }
        }
        // Rotation and keystone are independent; if no skew is supported,
        // we can still report that the photo contains well-aligned lines,
        // instead of falsely claiming "no architecture found."
        boolean aligned=found && !angleOK && !vOK && !hOK;
        float confidence=Math.max(prior.confidence,
                Math.max(hv.strength,hh.strength));
        return new Suggestion(angle,vertical,horizontal,
                angleOK,vOK,hOK,confidence,found,aligned);
    }

    private static float clamp(float v,float min,float max){
        return Math.max(min,Math.min(max,v));
    }
    private TransformEngine(){}
}
