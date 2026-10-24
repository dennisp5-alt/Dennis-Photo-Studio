package com.dennis.craftmobile;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.PointF;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Conservative optional RESTORE LAB algorithms, independent of normal Auto.
 *
 * Deblur is bounded conventional high-pass edge restoration; not motion-blur
 * deconvolution. JPEG Repair attenuates ringing, chroma speckles and subtle
 * 8x8 discontinuities without blurring strong edges. Face Detail is an offline
 * ML Kit face-location + cautious NON-GENERATIVE skin noise cleanup: landmarks
 * protect eyes, eyebrows, nose and mouth. It NEVER reconstructs facial identity.
 */
final class AdvancedRestoreEngine {
    interface Progress { void status(String description); }

    static final class Options {
        int deblur;       // 0..50, default 22
        int jpegRepair;   // 0..65, default 35
        int face;         // 0..45, default 18
        boolean enabled() { return deblur>0 || jpegRepair>0 || face>0; }
    }

    static final class Result {
        final Bitmap bitmap;
        final int facesLocated, facesEnhanced;
        Result(Bitmap bitmap,int located,int enhanced) {
            this.bitmap=bitmap; facesLocated=located;facesEnhanced=enhanced;
        }
    }

    static Result process(Bitmap original,Options settings,Progress progress) throws Exception {
        if(original==null)throw new IllegalArgumentException("No photograph loaded");
        if(settings==null || !settings.enabled())throw new IllegalArgumentException("Select a restoration tool");
        Bitmap current=original.copy(Bitmap.Config.ARGB_8888,true);
        int found=0,enhanced=0;
        try {
            if(settings.jpegRepair>0) {
                announce(progress,"JPEG Repair · cleaning compression artefacts…");
                jpegRepair(current,Math.min(65,settings.jpegRepair));
            }
            if(settings.deblur>0) {
                announce(progress,"Deblur · protecting fine edges…");
                deblur(current,Math.min(50,settings.deblur));
            }
            if(settings.face>0) {
                announce(progress,"Face Detail · locating faces offline…");
                int[] stats=faceDetail(current,Math.min(45,settings.face),progress);
                found=stats[0];enhanced=stats[1];
            }
            return new Result(current,found,enhanced);
        } catch(Exception|OutOfMemoryError ex) {
            if(!current.isRecycled())current.recycle();
            throw ex;
        }
    }

    private static void announce(Progress callback,String msg) {
        if(callback!=null)callback.status(msg);
    }
    private static int within(int value,int max) {
        return Math.max(0,Math.min(max-1,value));
    }
    private static int eight(float value) {
        return Math.max(0,Math.min(255,Math.round(value)));
    }
    private static int pack(int alpha,float r,float g,float b) {
        return Color.argb(alpha,eight(r),eight(g),eight(b));
    }
    private static float lum(int rgb) {
        return (54f*Color.red(rgb)+183f*Color.green(rgb)+19f*Color.blue(rgb))/256f;
    }
    private static int[] read(Bitmap bitmap) {
        int[] values=new int[bitmap.getWidth()*bitmap.getHeight()];
        bitmap.getPixels(values,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight());
        return values;
    }
    private static void commit(Bitmap b,int[] pixels) {
        b.setPixels(pixels,0,b.getWidth(),0,0,b.getWidth(),b.getHeight());
    }

    private static void jpegRepair(Bitmap bitmap,int strength) throws InterruptedException {
        int w=bitmap.getWidth(),h=bitmap.getHeight();
        int[] pixels=read(bitmap);
        int[] out=pixels.clone();
        float mix=strength/65f;
        for(int y=1;y<h-1;y++) {
            if((y&31)==0 && Thread.currentThread().isInterrupted())
                throw new InterruptedException("JPEG Repair cancelled");
            for(int x=1;x<w-1;x++) {
                int idx=y*w+x,c=pixels[idx];
                float l=lum(c);
                float gradient=Math.max(Math.abs(l-lum(pixels[idx-1])),
                        Math.abs(l-lum(pixels[idx+1])));
                gradient=Math.max(gradient,Math.abs(l-lum(pixels[idx-w])));
                gradient=Math.max(gradient,Math.abs(l-lum(pixels[idx+w])));
                // High-contrast real edges, lettering, hair and eyes should
                // not be softened in a compression cleanup operation.
                if(gradient>=31f)continue;

                float sr=Color.red(c)*2f,sg=Color.green(c)*2f,
                        sb=Color.blue(c)*2f,weight=2f;
                for(int dy=-1;dy<=1;dy++) {
                    for(int dx=-1;dx<=1;dx++) {
                        if(dx==0&&dy==0)continue;
                        int p=pixels[idx+dy*w+dx];
                        float delta=Math.abs(l-lum(p));
                        if(delta>=22f)continue;
                        float cw=delta<=7f?1f:.5f;
                        sr+=Color.red(p)*cw;sg+=Color.green(p)*cw;
                        sb+=Color.blue(p)*cw;weight+=cw;
                    }
                }
                // 8x8 block edges get a little more cleanup if surrounding
                // pixels are sufficiently flat. Other flat areas benefit from
                // weaker chroma/mosquito-noise cleanup.
                boolean seam=(x%8==0 || x%8==7 || y%8==0 || y%8==7);
                float blend=mix*(seam?.55f:.28f);
                float edgeSafety=1f-Math.min(1f,gradient/31f);
                blend*=edgeSafety;
                if(blend<=0f)continue;
                out[idx]=pack(Color.alpha(c),
                        Color.red(c)*(1f-blend)+(sr/weight)*blend,
                        Color.green(c)*(1f-blend)+(sg/weight)*blend,
                        Color.blue(c)*(1f-blend)+(sb/weight)*blend);
            }
        }
        commit(bitmap,out);
    }

    private static void deblur(Bitmap bitmap,int strength) throws InterruptedException {
        int w=bitmap.getWidth(),h=bitmap.getHeight();
        if(w<5||h<5)return;
        int[] pixels=read(bitmap),out=pixels.clone();
        float[] l=new float[pixels.length];
        for(int i=0;i<pixels.length;i++)l[i]=lum(pixels[i]);
        float intensity=strength/50f;
        for(int y=2;y<h-2;y++) {
            if((y&31)==0 && Thread.currentThread().isInterrupted())
                throw new InterruptedException("Deblur cancelled");
            for(int x=2;x<w-2;x++) {
                int idx=y*w+x;
                float c=l[idx];
                float dx=Math.abs(l[idx+1]-l[idx-1]);
                float dy=Math.abs(l[idx+w]-l[idx-w]);
                float edge=(dx+dy)*.5f;
                // Never intensify smooth regions containing noise or
                // potentially synthetic facial microtexture.
                if(edge<5f)continue;
                float gaussian=(4*c+
                        2*(l[idx-1]+l[idx+1]+l[idx-w]+l[idx+w])+
                        l[idx-w-1]+l[idx-w+1]+l[idx+w-1]+l[idx+w+1])/16f;
                float detail=c-gaussian;
                // Avoid halos on extreme highlights / black-white borders.
                if(Math.abs(detail)>27f || edge>88f)continue;
                float confidence=Math.min(1f,(edge-5f)/18f);
                float delta=detail*intensity*.78f*confidence;
                delta=Math.max(-11f,Math.min(11f,delta));
                float scale=(c+delta)/Math.max(15f,c);
                scale=Math.max(.85f,Math.min(1.16f,scale));
                int src=pixels[idx];
                out[idx]=pack(Color.alpha(src),
                        Color.red(src)*scale,Color.green(src)*scale,
                        Color.blue(src)*scale);
            }
        }
        commit(bitmap,out);
    }

    private static int[] faceDetail(Bitmap bitmap,int strength,Progress progress)
            throws Exception {
        FaceDetectorOptions opts=new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .build();
        FaceDetector detector=FaceDetection.getClient(opts);
        List<Face> faces;
        try {
            // Called from the existing dedicated background export executor.
            // This variant bundles detection models directly into the APK.
            faces=Tasks.await(detector.process(InputImage.fromBitmap(bitmap,0)),
                    20,TimeUnit.SECONDS);
        } finally {
            detector.close();
        }
        if(faces==null||faces.isEmpty()) {
            announce(progress,"Face Detail · no face found; image unchanged");
            return new int[]{0,0};
        }
        int w=bitmap.getWidth(),h=bitmap.getHeight();
        int[] pixels=read(bitmap),out=pixels.clone();
        int improved=0;
        for(Face face:faces) {
            if(Thread.currentThread().isInterrupted())
                throw new InterruptedException("Face Restore cancelled");
            Rect box=face.getBoundingBox();
            int x0=Math.max(1,box.left),x1=Math.min(w-1,box.right);
            int y0=Math.max(1,box.top),y1=Math.min(h-1,box.bottom);
            if(x1-x0<100||y1-y0<100)continue;
            float cx=(x0+x1)*.5f,cy=(y0+y1)*.5f;
            float rx=Math.max(1f,(x1-x0)*.53f),ry=Math.max(1f,(y1-y0)*.52f);
            float amount=strength/45f*.24f; // 24% max restoration
            for(int y=y0;y<y1;y++) {
                for(int x=x0;x<x1;x++) {
                    float ell=(x-cx)*(x-cx)/(rx*rx)+(y-cy)*(y-cy)/(ry*ry);
                    if(ell>=.90f)continue;
                    if(importantFeature(face,x,y,box.width()))continue;
                    float fade=Math.min(1f,(.90f-ell)*5f);
                    int idx=y*w+x,c=pixels[idx];
                    float center=lum(c);
                    float r=Color.red(c)*3f,g=Color.green(c)*3f,
                            b=Color.blue(c)*3f,weights=3f;
                    for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
                        if(dx==0&&dy==0)continue;
                        int p=pixels[idx+dy*w+dx];
                        float difference=Math.abs(center-lum(p));
                        if(difference>=14f)continue;
                        float weight=difference<=6f?1f:.4f;
                        weights+=weight;
                        r+=Color.red(p)*weight;
                        g+=Color.green(p)*weight;
                        b+=Color.blue(p)*weight;
                    }
                    float blend=amount*fade;
                    out[idx]=pack(Color.alpha(c),
                            (1f-blend)*Color.red(c)+blend*r/weights,
                            (1f-blend)*Color.green(c)+blend*g/weights,
                            (1f-blend)*Color.blue(c)+blend*b/weights);
                }
            }
            improved++;
        }
        commit(bitmap,out);
        announce(progress,"Face Detail · "+improved+" face region(s) refined conservatively");
        return new int[]{faces.size(),improved};
    }

    private static boolean importantFeature(Face face,int x,int y,int width) {
        final int[] ids={FaceLandmark.LEFT_EYE,FaceLandmark.RIGHT_EYE,
                FaceLandmark.NOSE_BASE,FaceLandmark.MOUTH_BOTTOM,
                FaceLandmark.MOUTH_LEFT,FaceLandmark.MOUTH_RIGHT,
                FaceLandmark.LEFT_CHEEK,FaceLandmark.RIGHT_CHEEK};
        for(int id:ids) {
            if(id==FaceLandmark.LEFT_CHEEK||id==FaceLandmark.RIGHT_CHEEK)continue;
            FaceLandmark point=face.getLandmark(id);
            if(point==null)continue;
            PointF center=point.getPosition();
            float distanceX=x-center.x,distanceY=y-center.y;
            float radius=(id==FaceLandmark.LEFT_EYE||id==FaceLandmark.RIGHT_EYE)
                    ?.18f*width:.15f*width;
            if(distanceX*distanceX+distanceY*distanceY<radius*radius)return true;
        }
        return false;
    }
    private AdvancedRestoreEngine(){}
}
