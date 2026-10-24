package com.dennis.craftmobile;

/**
 * Conservative fourth Auto layer: 4x4 whole-scene intent.
 * Reuses the existing 64x64 sampled sums/counts; no bitmap rescan.
 *
 * This layer only reinforces an already-protected 8x8 night/warm atmosphere
 * when very dark large regions coexist with concentrated illuminated regions.
 * It cannot make a scene brighter or change local tone, colour, skin, or WB.
 */
final class SceneIntent4 {
    static final int SIZE=4;

    static final class Decision {
        final float exposureCeiling, shadowScale;
        final boolean preserved;
        final int darkBlocks;
        Decision(float exposureCeiling,float shadowScale,boolean preserved,int darkBlocks) {
            this.exposureCeiling=exposureCeiling;
            this.shadowScale=shadowScale;
            this.preserved=preserved;
            this.darkBlocks=darkBlocks;
        }
    }

    static Decision assess(float[] sums64,int[] counts64,
                           int[] histogram,int samples,
                           SceneAtmosphere8.Decision previous) {
        if(sums64.length!=4096 || counts64.length!=4096) {
            throw new IllegalArgumentException("Expected existing 64x64 measurements");
        }
        if(samples<=0 || !previous.protectedAtmosphere) {
            return new Decision(previous.exposureCeiling,previous.shadowScale,false,0);
        }
        int dark=0, illuminated=0, almostBlack=0;
        float brightest=0f;
        for(int my=0;my<SIZE;my++) {
            for(int mx=0;mx<SIZE;mx++) {
                float total=0f;
                int n=0;
                // One 4x4 zone covers 16x16=256 existing fine cells.
                for(int yy=0;yy<16;yy++) {
                    for(int xx=0;xx<16;xx++) {
                        int idx=(my*16+yy)*64+mx*16+xx;
                        total+=sums64[idx];
                        n+=counts64[idx];
                    }
                }
                float luminance=n>0 ? total/n : 0f;
                if(luminance<.145f)dark++;
                if(luminance<.075f)almostBlack++;
                if(luminance>.225f)illuminated++;
                brightest=Math.max(brightest,luminance);
            }
        }
        int p92=percentile(histogram,samples,.92f);
        int p99=percentile(histogram,samples,.99f);
        // Require obvious regions of darkness and separately lit detail.
        // An uniformly dim underexposed photo fails the lit-region test.
        // A normally bright/warm portrait fails the dark-block test.
        boolean intentional=dark>=6 && almostBlack>=2 &&
                illuminated>=1 && illuminated<=8 &&
                brightest>=.29f && p92>=73 && p99>=151;
        if(!intentional) {
            return new Decision(previous.exposureCeiling,previous.shadowScale,false,dark);
        }
        // The extra level is a *minor* fine-tune on an already protected scene.
        // Keep the approved 8x8 decision when uncertain.
        float cap=Math.min(previous.exposureCeiling,.015f);
        float shadows=Math.min(previous.shadowScale,.14f);
        return new Decision(cap,shadows,true,dark);
    }

    private static int percentile(int[] histogram,int total,float q) {
        int target=Math.max(1,(int)Math.ceil(total*q)),count=0;
        for(int i=0;i<histogram.length;i++){
            count+=histogram[i];
            if(count>=target)return i;
        }
        return 255;
    }

    private SceneIntent4(){}
}
