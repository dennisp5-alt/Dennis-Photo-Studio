package com.dennis.craftmobile;

/**
 * Multi-scale tonal-judgement layer derived entirely from the existing 64x64
 * analysis; no second bitmap scan and no portrait/skin/face inference.
 *
 * A low-key scene can contain many dark zones AND already well-exposed regions.
 * Such an image should not receive the same global exposure increase as a
 * uniformly underexposed frame. All thresholds work on image luminance only.
 */
final class SceneJudgement16 {
    static final int SIZE = 16;
    static final int COUNT = SIZE * SIZE;

    static final class Decision {
        final float exposureCeiling, shadowScale;
        final boolean protectedLowKey, trulyDark;
        final int exposedZones, darkZones;
        final float upperExposure;
        Decision(float exposureCeiling, float shadowScale,
                 boolean protectedLowKey, boolean trulyDark,
                 int exposedZones, int darkZones, float upperExposure) {
            this.exposureCeiling = exposureCeiling;
            this.shadowScale = shadowScale;
            this.protectedLowKey = protectedLowKey;
            this.trulyDark = trulyDark;
            this.exposedZones = exposedZones;
            this.darkZones = darkZones;
            this.upperExposure = upperExposure;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static Decision assess(float[] sums64, int[] counts64,
                           int[] histogram, int total, float brightPixelCoverage,
                           float originalMaxExposure) {
        if (sums64.length != 4096 || counts64.length != 4096)
            throw new IllegalArgumentException("Expected 64x64 input");

        // Exactly 16 fine cells are pooled into each coarse cell. The pooling
        // is weighted by the original sample count, not a mean of means.
        float[] coarse = new float[COUNT];
        int exposedZones = 0, darkZones = 0, midtoneZones = 0;
        int wellExposedIslands = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float sum=0f;
                int n=0;
                for (int yy=0; yy<4; yy++) {
                    for (int xx=0; xx<4; xx++) {
                        int i=(y*4+yy)*64+x*4+xx;
                        sum += sums64[i];
                        n += counts64[i];
                    }
                }
                float l=n>0 ? sum/n : 0f;
                coarse[y*SIZE+x]=l;
                if (l>=.32f) midtoneZones++;
                if (l>=.40f) exposedZones++;
                if (l<.17f) darkZones++;
            }
        }

        // A cluster of brighter zones is stronger evidence than a handful of
        // isolated spots, reflections or light sources.
        for (int y=1; y<SIZE-1; y++) {
            for (int x=1; x<SIZE-1; x++) {
                int i=y*SIZE+x;
                if (coarse[i]<.34f) continue;
                int neighbours=0;
                if (coarse[i-1]>=.30f) neighbours++;
                if (coarse[i+1]>=.30f) neighbours++;
                if (coarse[i-SIZE]>=.30f) neighbours++;
                if (coarse[i+SIZE]>=.30f) neighbours++;
                if (neighbours>=2) wellExposedIslands++;
            }
        }

        float p10=percentile(histogram,total,.10f)/255f;
        float p50=percentile(histogram,total,.50f)/255f;
        float p85=percentile(histogram,total,.85f)/255f;
        float p95=percentile(histogram,total,.95f)/255f;

        // Highlights and midtones already present in a dark scene are not
        // evidence that its shadows should be raised wholesale.
        boolean hasSubjectExposure = wellExposedIslands>=12 &&
                midtoneZones>=20 && p85>=.39f && p95>=.51f;
        boolean deliberateLowKey = hasSubjectExposure &&
                darkZones>=55 && p10<.17f && p50<.29f &&
                brightPixelCoverage<.12f;
        boolean genuinelyDark = p85<.35f && p95<.49f &&
                exposedZones<20 && midtoneZones<45;

        float ceiling=originalMaxExposure;
        float shadowScale=1f;
        if (deliberateLowKey) {
            // Reference portrait previously hit +.45 EV; preserve atmosphere
            // without preventing a small useful tonal adjustment.
            ceiling=Math.min(ceiling,.07f);
            shadowScale=.55f;
        } else if (hasSubjectExposure && p50<.30f) {
            // Less dramatic scenes with a dark background still need restraint.
            ceiling=Math.min(ceiling,.14f);
            shadowScale=.78f;
        } else if (genuinelyDark) {
            // No well-exposed subject/region was found. Retain the original
            // ability to correct actual underexposure (up to the old ceiling).
            ceiling=originalMaxExposure;
            shadowScale=1f;
        } else if (p85>=.45f && p50<.30f && darkZones>=35) {
            // A weaker fallback for mixed scenes where the spatial cluster
            // couldn't be confidently established.
            ceiling=Math.min(ceiling,.20f);
            shadowScale=.85f;
        }
        return new Decision(clamp(ceiling,0f,.45f),shadowScale,
                deliberateLowKey,genuinelyDark,exposedZones,darkZones,p85);
    }

    private static int percentile(int[] hist,int total,float quantile) {
        int target=Math.max(1,(int)Math.ceil(total*quantile)),count=0;
        for(int i=0;i<hist.length;i++) {
            count+=hist[i];
            if(count>=target)return i;
        }
        return 255;
    }
    private SceneJudgement16() {}
}
