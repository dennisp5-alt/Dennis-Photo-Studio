package com.mirran.photo;

import android.graphics.Bitmap;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions;
import java.nio.FloatBuffer;

/** Google ML Kit semantic subject detection. Its model is downloaded once
 * through Google Play services. On-device inference needs no OpenAI account.
 * If unavailable, callers must not treat a synthetic region as a subject mask.
 */
public final class SubjectVision implements AutoCloseable {
    public interface Callback {
        void success(SubjectMask mask);
        void unavailable(String reason);
    }
    private final SubjectSegmenter segmenter;
    public SubjectVision(){
        segmenter=SubjectSegmentation.getClient(
            new SubjectSegmenterOptions.Builder()
                .enableForegroundConfidenceMask()
                .build());
    }
    public void analyze(Bitmap bitmap, Callback callback){
        if(bitmap==null){callback.unavailable("No image");return;}
        int w=bitmap.getWidth(),h=bitmap.getHeight();
        int max=Math.max(w,h);
        float scale=Math.min(1f,960f/Math.max(1,max));
        Bitmap small=scale<1f?Bitmap.createScaledBitmap(bitmap,
            Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)),true):bitmap;
        int mw=small.getWidth(),mh=small.getHeight();
        try {
            segmenter.process(InputImage.fromBitmap(small,0))
                .addOnSuccessListener(result->{
                    if(small!=bitmap)small.recycle();
                    FloatBuffer source=result.getForegroundConfidenceMask();
                    if(source==null||source.remaining()<mw*mh){
                        callback.unavailable("No subject mask returned");
                        return;
                    }
                    float[] probabilities=new float[mw*mh];
                    source.get(probabilities);
                    SubjectMask mask=new SubjectMask(mw,mh,probabilities);
                    if(mask.coverage()<.008f||mask.coverage()>.92f){
                        callback.unavailable("No reliable subject isolated");
                    }else callback.success(mask);
                }).addOnFailureListener(e->{
                    if(small!=bitmap)small.recycle();
                    callback.unavailable("Model unavailable; try again after ML Kit download");
                });
        }catch(Exception e){
            if(small!=bitmap)small.recycle();
            callback.unavailable("Subject segmentation not ready");
        }
    }
    @Override public void close(){segmenter.close();}
}
