package com.dennis.craftmobile;

/**
 * Third, coarse 8x8 judgement layer derived from the existing 64x64 sample
 * statistics. It does not rescan the bitmap, recognise subjects or change WB.
 *
 * The only supported intervention is to restrain global exposure and shadows
 * when dark surroundings + independently illuminated warm details suggest a
 * deliberately low-key scene. All other photos use v0.6.3 unchanged.
 */
final class SceneAtmosphere8 {
    static final int SIZE = 8;

    static final class Decision {
        final float exposureCeiling;
        final float shadowScale;
        final boolean protectedAtmosphere;
        final String description;
        final int darkBlocks;
        Decision(float exposureCeiling, float shadowScale,
                 boolean protectedAtmosphere, String description, int darkBlocks) {
            this.exposureCeiling = exposureCeiling;
            this.shadowScale = shadowScale;
            this.protectedAtmosphere = protectedAtmosphere;
            this.description = description;
            this.darkBlocks = darkBlocks;
        }
    }

    static Decision assess(float[] zoneSums, int[] zoneCounts, int[] histogram,
                           int total, int warmGlowSamples, float brightCoverage,
                           float exposureCeiling, float shadowScale) {
        if(zoneSums.length != 4096 || zoneCounts.length != 4096)
            throw new IllegalArgumentException("Expected 64x64 scene zones");
        if(total <= 0) return new Decision(exposureCeiling,shadowScale,
                false,"",0);

        int darkBlocks=0, lightBlocks=0;
        for(int y=0;y<SIZE;y++) {
            for(int x=0;x<SIZE;x++) {
                float blockSum=0f;
                int samples=0;
                // 8x8 weighted pooling: all 64 fine cells in each block.
                for(int cy=0;cy<8;cy++) {
                    for(int cx=0;cx<8;cx++) {
                        int i=(y*8+cy)*64+x*8+cx;
                        blockSum+=zoneSums[i];
                        samples+=zoneCounts[i];
                    }
                }
                float lum=samples>0 ? blockSum/samples : 0f;
                if(lum<.16f)darkBlocks++;
                if(lum>.37f)lightBlocks++;
            }
        }

        int p50=percentile(histogram,total,.50f);
        int p95=percentile(histogram,total,.95f);
        int p99=percentile(histogram,total,.99f);
        float warmCoverage=warmGlowSamples/(float)total;

        // Underlying warm light must be strong and saturated enough to avoid
        // confusing normal warm skin, red clothing or sunsets with firelight.
        // The frame must ALSO contain extensive darkness.
        boolean warmGlow = warmCoverage >= .023f && darkBlocks >= 24 &&
                p50 < 68 && p95 >= 44 && p99 >= 90;

        // Alternatively: a very dark frame with isolated intense highlights.
        // Do not change ordinary dim/underexposed pictures without those lights.
        boolean nightLights = !warmGlow && darkBlocks >= 53 && p50 < 19 &&
                p95 >= 42 && p99 >= 135 && brightCoverage >= .009f &&
                lightBlocks <= 5;

        if(!warmGlow && !nightLights) {
            return new Decision(exposureCeiling,shadowScale,false,"",darkBlocks);
        }

        // A protective gate can only reduce, never amplify, prior decisions.
        // In particular it cannot override v0.6.3 low-key protection with a
        // stronger exposure. Local tone and highlight algorithms are untouched.
        float cap=warmGlow ? .025f : .045f;
        float shadowCap=warmGlow ? .22f : .32f;
        return new Decision(Math.min(exposureCeiling,cap),
                Math.min(shadowScale,shadowCap),true,
                warmGlow ? "Warm atmosphere" : "Night lights",darkBlocks);
    }

    private static int percentile(int[] hist,int total,float fraction) {
        int target=Math.max(1,(int)Math.ceil(total*fraction)),sum=0;
        for(int i=0;i<hist.length;i++) {
            sum+=hist[i];
            if(sum>=target)return i;
        }
        return 255;
    }

    private SceneAtmosphere8() {}
}
