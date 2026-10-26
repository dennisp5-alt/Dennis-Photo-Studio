package com.mirran.photo;
import android.graphics.Bitmap;
public final class OfflineDetailTest {
    static void check(boolean condition,String reason){
        if(!condition)throw new AssertionError(reason);
    }
    static Bitmap solid(int width,int height,int colour){
        Bitmap b=new Bitmap(width,height);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)b.setPixel(x,y,colour);
        return b;
    }
    static int y(int colour){
        return (int)(.2126*((colour>>16)&255)+.7152*((colour>>8)&255)+.0722*(colour&255));
    }
    public static void main(String[] args){
        Bitmap unchanged=solid(20,20,0xff668899);
        int p=unchanged.getPixel(8,9);
        DetailEngine.denoiseInPlace(unchanged,0);
        DetailEngine.dehazeInPlace(unchanged,0);
        EditRecipe plan=new EditRecipe();plan.subject=.4f;
        DetailEngine.maskedExposureInPlace(unchanged,plan,null);
        check(unchanged.getPixel(8,9)==p,"Zero strength or missing mask must be identity");
        Bitmap noise=solid(32,32,0xff7a7a7a);
        for(int y=1;y<31;y++)for(int x=1;x<31;x++){
            if((x+y)%5==0)noise.setPixel(x,y,0xff818181);
        }
        int before=y(noise.getPixel(9,11));
        DetailEngine.denoiseInPlace(noise,.8f);
        int after=y(noise.getPixel(9,11));
        check(Math.abs(after-122)<=Math.abs(before-122),
            "Noise should not be increased");
        Bitmap sharp=solid(20,20,0xff333333);
        for(int py=0;py<20;py++)for(int px=10;px<20;px++)sharp.setPixel(px,py,0xffe4e4e4);
        DetailEngine.denoiseInPlace(sharp,1f);
        check(y(sharp.getPixel(8,8))<=52&&y(sharp.getPixel(11,8))>=220,
            "Strong edges must be protected");
        float[] floats=new float[]{1,1,0,0};
        SubjectMask foreground=new SubjectMask(2,2,floats);
        check(foreground.sample(5,5,20,20)>.96f,"Mask foreground sampling");
        check(foreground.sample(5,15,20,20)<.04f,"Mask background sampling");
        Bitmap split=solid(20,20,0xff777777);
        EditRecipe masked=new EditRecipe();
        masked.subject=.7f;masked.background=-.5f;
        DetailEngine.maskedExposureInPlace(split,masked,foreground);
        check(y(split.getPixel(5,5))>119,"Foreground should brighten");
        check(y(split.getPixel(5,15))<119,"Background should darken");
        Bitmap dark=solid(20,20,0xff000000);
        DetailEngine.dehazeInPlace(dark,1f);
        DetailEngine.maskedExposureInPlace(dark,masked,foreground);
        check(dark.getPixel(5,5)==0xff000000,
            "True blacks remain black");
        Bitmap mist=solid(24,24,0xffa5a9aa);
        for(int py=8;py<16;py++)for(int px=8;px<16;px++)
            mist.setPixel(px,py,0xff6f7979);
        int midBefore=y(mist.getPixel(12,12));
        DetailEngine.dehazeInPlace(mist,.6f);
        int midAfter=y(mist.getPixel(12,12));
        check(midAfter<=midBefore && midAfter>=midBefore-30,
            "Dehaze should add modest contrast without crushing");
        System.out.println("MIRRAN offline detail: all 7 tests passed.");
    }
}
