package com.dennis.craftmobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Offline on-device Restore Lab experiment. Actual pretrained neural models:
 *  - Raspberry Pi AI Denoise NAFNet RGB Small (BSD-2-Clause).
 *  - ESRGAN TensorFlow Lite fp16 (Apache-2.0 tutorial).
 *
 * Sharpen is explicitly classical detail-aware sharpening; NOT a neural model.
 * Neural image sizes are deliberately capped for memory safety on Android.
 * Separate from Dennis Craft's approved four-grid Auto/editor renderer.
 */
final class RestoreEngine {
    interface Progress { void step(String what); }

    private static final int MAX_DENOISE_EDGE = 1536;
    private static final int MAX_UPSCALE_EDGE = 512;

    private static void update(Progress callback, String message) {
        if(callback != null)callback.step(message);
    }

    static Bitmap run(Context context, Bitmap original, boolean denoise,
                      boolean sharpen, boolean upscale, Progress callback) throws Exception {
        if(original == null)throw new IllegalArgumentException("No image selected");
        if(!denoise && !sharpen && !upscale)throw new IllegalArgumentException("Select a Restore tool");
        Bitmap working=original;
        try {
            int allowed=upscale ? MAX_UPSCALE_EDGE : MAX_DENOISE_EDGE;
            if(Math.max(working.getWidth(), working.getHeight()) > allowed) {
                update(callback,"Restore Lab · preparing safe working resolution…");
                Bitmap resized=resizeLongest(working,allowed);
                working=resized;
            }
            if(denoise) {
                update(callback,"Restore Lab · neural denoise…");
                Bitmap processed=neuralTiles(context,working,"nafnet_rgb_small.tflite",
                        false,callback);
                if(working!=original)working.recycle();
                working=processed;
            }
            if(sharpen) {
                update(callback,"Restore Lab · edge-aware sharpening…");
                Bitmap processed=working.copy(Bitmap.Config.ARGB_8888,true);
                DetailEngine.Settings detail=new DetailEngine.Settings();
                detail.sharpening=34f;
                // Respect texture and smooth-region safeguards in existing
                // professional detail engine, with no extra NR or dehaze.
                DetailEngine.applyInPlace(processed,detail);
                if(working!=original)working.recycle();
                working=processed;
            }
            if(upscale) {
                update(callback,"Restore Lab · neural 2× upscale…");
                Bitmap processed=neuralTiles(context,working,"esrgan_fp16.tflite",
                        true,callback);
                if(working!=original)working.recycle();
                working=processed;
            }
            return working;
        } catch(Exception | OutOfMemoryError problem) {
            if(working!=original && !working.isRecycled())working.recycle();
            throw problem;
        }
    }

    private static Bitmap resizeLongest(Bitmap source,int maxLongest) {
        int w=source.getWidth(),h=source.getHeight();
        if(Math.max(w,h)<=maxLongest)return source;
        float ratio=maxLongest/(float)Math.max(w,h);
        int nw=Math.max(1,Math.round(w*ratio));
        int nh=Math.max(1,Math.round(h*ratio));
        return Bitmap.createScaledBitmap(source,nw,nh,true);
    }

    private static Interpreter create(Context context,String asset) throws Exception {
        ByteArrayOutputStream memory=new ByteArrayOutputStream();
        try(InputStream data=context.getAssets().open(asset)) {
            byte[] chunk=new byte[65536];
            int n;
            while((n=data.read(chunk))!=-1)memory.write(chunk,0,n);
        }
        byte[] bytes=memory.toByteArray();
        if(bytes.length<1024)throw new IllegalStateException("Missing neural model: "+asset);
        ByteBuffer model=ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
        model.put(bytes);model.rewind();
        Interpreter.Options options=new Interpreter.Options();
        options.setNumThreads(4);
        return new Interpreter(model,options);
    }

    private static int bound(int value,int hi) {
        return Math.max(0,Math.min(hi-1,value));
    }

    private static int quant(float value) {
        return Math.max(0,Math.min(255,Math.round(value*255f)));
    }

    private static Bitmap neuralTiles(Context context,Bitmap source,
                                       String modelName,boolean superResolution,
                                       Progress progress) throws Exception {
        int width=source.getWidth(),height=source.getHeight();
        try(Interpreter model=create(context,modelName)) {
            int[] inputShape=model.getInputTensor(0).shape();
            int[] outputShape=model.getOutputTensor(0).shape();
            if(inputShape.length!=4 || inputShape[0]!=1 ||
                    inputShape[1]!=inputShape[2] || inputShape[3]!=3 ||
                    outputShape.length!=4 || outputShape[0]!=1 ||
                    outputShape[3]!=3 ||
                    model.getInputTensor(0).dataType()!=DataType.FLOAT32 ||
                    model.getOutputTensor(0).dataType()!=DataType.FLOAT32) {
                throw new IllegalStateException("Unsupported neural tensor layout: "+modelName);
            }
            final int patch=inputShape[1], outputPatch=outputShape[1];
            final int factor=outputPatch/patch;
            if((superResolution && (factor!=4 || outputShape[2]!=outputPatch)) ||
                    (!superResolution && (factor!=1 ||
                            outputShape[1]!=patch || outputShape[2]!=patch)) ||
                    patch<32 || patch>512) {
                throw new IllegalStateException("Unexpected neural model dimensions: "+modelName);
            }
            // A 4x neural model is deliberately downsampled to requested 2x.
            final int targetFactor=superResolution?2:1;
            final int overlap=superResolution?8:32;
            final int border=overlap/2;
            final int stride=patch-overlap;
            int targetW=width*targetFactor,targetH=height*targetFactor;
            if((long)targetW*targetH>3_000_000L)
                throw new IllegalArgumentException("Restore output exceeds safe pixel budget");
            int[] pixels=new int[width*height];
            source.getPixels(pixels,0,width,0,0,width,height);
            int[] output=new int[targetW*targetH];
            float[][][][] input=new float[1][patch][patch][3];
            float[][][][] result=new float[1][outputPatch][outputPatch][3];
            int totalTiles=((width+stride-1)/stride)*((height+stride-1)/stride);
            int completed=0;
            for(int y=0;y<height;y+=stride) {
                for(int x=0;x<width;x+=stride) {
                    if(Thread.currentThread().isInterrupted())
                        throw new InterruptedException("Restore cancelled");
                    for(int py=0;py<patch;py++){
                        int srcY=bound(y+py-border,height);
                        for(int px=0;px<patch;px++){
                            int srcX=bound(x+px-border,width);
                            int colour=pixels[srcY*width+srcX];
                            float normalizer=superResolution?1f:1f/255f;
                            input[0][py][px][0]=Color.red(colour)*normalizer;
                            input[0][py][px][1]=Color.green(colour)*normalizer;
                            input[0][py][px][2]=Color.blue(colour)*normalizer;
                        }
                    }
                    model.run(input,result);
                    int regionW=Math.min(stride,width-x);
                    int regionH=Math.min(stride,height-y);
                    for(int yy=0;yy<regionH*targetFactor;yy++){
                        int inputY=border+yy/targetFactor;
                        int netY=superResolution?inputY*4+(yy%2)*2+1:inputY;
                        for(int xx=0;xx<regionW*targetFactor;xx++){
                            int inputX=border+xx/targetFactor;
                            int netX=superResolution?inputX*4+(xx%2)*2+1:inputX;
                            float[] rgb=result[0][netY][netX];
                            float unit=superResolution?1f/255f:1f;
                            int sourcePixel=pixels[
                                bound(y+yy/targetFactor,height)*width+
                                bound(x+xx/targetFactor,width)];
                            int out=Color.argb(Color.alpha(sourcePixel),
                                quant(rgb[0]*unit),quant(rgb[1]*unit),quant(rgb[2]*unit));
                            output[(y*targetFactor+yy)*targetW+x*targetFactor+xx]=out;
                        }
                    }
                    completed++;
                    if(completed%Math.max(1,totalTiles/8)==0)
                        update(progress,(superResolution?"AI Upscale":"AI Denoise")+
                                " · "+Math.round(100f*completed/totalTiles)+"%");
                }
            }
            return Bitmap.createBitmap(output,targetW,targetH,Bitmap.Config.ARGB_8888);
        }
    }
    private RestoreEngine(){}
}
