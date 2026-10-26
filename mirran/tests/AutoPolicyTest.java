package com.mirran.photo;
import android.graphics.Bitmap;
public final class AutoPolicyTest {
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    static Bitmap solid(int w,int h,int c){Bitmap b=new Bitmap(w,h);for(int y=0;y<h;y++)for(int x=0;x<w;x++)b.setPixel(x,y,c);return b;}
    public static void main(String[] args) {
        // Fire: 94% true-black background, 6% luminous orange flame.
        Bitmap fire=solid(100,100,0xff000000);
        for(int y=0;y<6;y++)for(int x=0;x<100;x++)fire.setPixel(x,y,0xffff6600);
        EditRecipe night=AutoAnalyzer.suggest(AutoAnalyzer.measure(fire));
        check(night.exposure==0f,"Fire exposure should be zero");
        check(night.shadows==0f,"Fire should retain deep shadows");
        check(night.vibrance==0f,"Fire should not be oversaturated");
        check(night.contrast<=.04f,"Fire contrast should stay conservative");
        // Midtone-rich low contrast scene: benefit from punch without flattening.
        Bitmap water=solid(100,100,0xff817f79);
        for(int y=0;y<20;y++)for(int x=0;x<100;x++)water.setPixel(x,y,0xff4b5155);
        for(int y=80;y<100;y++)for(int x=0;x<100;x++)water.setPixel(x,y,0xffada99c);
        EditRecipe landscape=AutoAnalyzer.suggest(AutoAnalyzer.measure(water));
        check(landscape.contrast>=.12f && landscape.contrast<=.23f,
            "Muted midtones should gain noticeable but bounded positive contrast");
        check(landscape.shadows<=.16f,"Shadow safety cap regressed");
        check(landscape.vibrance>0f && landscape.vibrance<=.17f,
            "Vibrance not in expected safe range");
        // Saturation protection on flower-like bright orange/red regions.
        Bitmap flower=solid(100,100,0xffff4c10);
        EditRecipe saturated=AutoAnalyzer.suggest(AutoAnalyzer.measure(flower));
        check(saturated.vibrance<.10f,"Saturated subject got excessive vibrance");
        // Pixel stage: a black frame must remain black even at maximum safe shadow lift.
        Bitmap deep=solid(20,20,0xff000000);
        EditRecipe manual=new EditRecipe();manual.shadows=.16f;manual.contrast=.18f;
        ToneEngine.applyInPlace(deep,manual);
        check(deep.getPixel(10,10)==0xff000000,"Deep black pixel changed");
        // The new contrast transfer must be visibly stronger than v0.1.2,
        // without blowing out highlights or lifting dark pixels.
        Bitmap mid=solid(10,10,0xff404040);
        EditRecipe punch=new EditRecipe();punch.contrast=.18f;
        ToneEngine.applyInPlace(mid,punch);
        int darkResult=mid.getPixel(5,5)&255;
        check(darkResult<=58 && darkResult>=43,
            "Midtone contrast needs to be perceptible but bounded: "+darkResult);
        Bitmap bright=solid(10,10,0xffc0c0c0);
        ToneEngine.applyInPlace(bright,punch);
        int brightResult=bright.getPixel(5,5)&255;
        check(brightResult>=197 && brightResult<=213,
            "Upper midtone contrast should not clip: "+brightResult);
        Bitmap nearWhite=solid(10,10,0xfff8f8f8);
        ToneEngine.applyInPlace(nearWhite,punch);
        int high=nearWhite.getPixel(5,5)&255;
        check(high>=246 && high<=252,"White highlight needs protection: "+high);
        // A dark backdrop must not fool local Auto into brightening a
        // separately illuminated centre subject (dark mulch, pale petals).
        Bitmap darkFlower=solid(100,100,0xff17151b);
        for(int y=25;y<75;y++)for(int x=25;x<75;x++)
            darkFlower.setPixel(x,y,0xffe5d9a8);
        AutoAnalyzer.Metrics darkMetrics=AutoAnalyzer.measure(darkFlower);
        EditRecipe subject=AutoAnalyzer.suggest(darkMetrics);
        check(subject.exposure==0f,"Bright subject must not be overexposed");
        check(subject.shadows<=.0451f,"Dark flower background must be preserved");
        check(subject.clarity<=.0251f,"Noise/subject protection lost");
        // Large blue areas are not a white-balance reference.
        Bitmap sky=solid(100,100,0xff0875f8);
        EditRecipe blue=AutoAnalyzer.suggest(AutoAnalyzer.measure(sky));
        check(blue.warmth==0f,"Sky must not dictate white balance");
        check(blue.vibrance<=.0251f,"Blue saturation limit missing");
        // Channel clipping may exist even when the luminance histogram
        // looks normal (electric-blue skies and brightly coloured clothing).
        Bitmap blueClip=solid(100,100,0xff1984ff);
        EditRecipe clipped=AutoAnalyzer.suggest(AutoAnalyzer.measure(blueClip));
        check(clipped.vibrance<=.0201f,"Blue channel clipping not protected");
        // Alternating fine texture should not be amplified by Auto Clarity.
        Bitmap noisy=solid(100,100,0xff707070);
        for(int y=0;y<100;y++)for(int x=0;x<100;x++)
            noisy.setPixel(x,y,(x+y)%2==0?0xff686868:0xff787878);
        AutoAnalyzer.Metrics noiseM=AutoAnalyzer.measure(noisy);
        check(noiseM.noiseIndex>.0135f,"Noise proxy missed fine granular pattern");
        EditRecipe grained=AutoAnalyzer.suggest(noiseM);
        check(grained.clarity==0f,"Auto must not sharpen obvious grain");
        System.out.println("MIRRAN tonal policy: all 11 tests passed.");
    }
}
