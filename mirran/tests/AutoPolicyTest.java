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
        check(landscape.contrast>=.09f && landscape.contrast<=.18f,
            "Muted midtones should gain restrained positive contrast");
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
        System.out.println("MIRRAN tonal policy: all 4 tests passed.");
    }
}
