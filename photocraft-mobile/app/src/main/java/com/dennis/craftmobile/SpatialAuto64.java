package com.dennis.craftmobile;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * 64x64 spatial analysis with per-channel highlight headroom.
 * No semantic masks are claimed. Scene weights remain immutable for preview/export parity.
 */
final class SpatialAuto64 {
    static final int SIZE = 64;
    private static final int CELLS = SIZE * SIZE;

    static final class Scene {
        final float[] zones;
        final float[] fineZones;
        final float exposure, contrast, highlights, shadows;
        final float brightCoverage;
        final boolean protectedLowKey;
        final boolean genuinelyDark;
        final int exposedZones, darkZones;
        final boolean protectedAtmosphere;
        final String atmosphereDescription;
        final boolean protectedMacroAtmosphere;
        Scene(float[] zones, float[] fineZones, float exposure, float contrast,
              float highlights, float shadows, float brightCoverage,
              SceneJudgement16.Decision judgement, SceneAtmosphere8.Decision atmosphere,
              SceneIntent4.Decision intent) {
            this.zones = zones;
            this.fineZones = fineZones;
            this.exposure = exposure;
            this.contrast = contrast;
            this.highlights = highlights;
            this.shadows = shadows;
            this.brightCoverage = brightCoverage;
            this.protectedLowKey = judgement.protectedLowKey;
            this.genuinelyDark = judgement.trulyDark;
            this.exposedZones = judgement.exposedZones;
            this.darkZones = judgement.darkZones;
            this.protectedAtmosphere = atmosphere.protectedAtmosphere;
            this.atmosphereDescription = atmosphere.description;
            this.protectedMacroAtmosphere = intent.preserved;
        }
    }

    static Scene analyse(Bitmap source) {
        final int w = source.getWidth(), h = source.getHeight();
        if (w < 1 || h < 1) throw new IllegalArgumentException("Empty image");
        final int stride = Math.max(1, Math.max(w, h) / 640);
        final int[] row = new int[w], histogram = new int[256];
        final float[] sum = new float[CELLS];
        final int[] count = new int[CELLS], brightCount = new int[CELLS];
        final float[] fineSum=new float[SpatialFine128.CELLS];
        final int[] fineCount=new int[SpatialFine128.CELLS];
        final int[] fineHot=new int[SpatialFine128.CELLS];
        int bright = 0, warmGlow = 0, samples = 0;

        for (int y = 0; y < h; y += stride) {
            source.getPixels(row, 0, w, 0, y, w, 1);
            int gridY = Math.min(SIZE-1, (int)((long)y * SIZE/h));
            for (int x = 0; x < w; x += stride) {
                int pixel = row[x];
                int r = Color.red(pixel), g = Color.green(pixel), b = Color.blue(pixel);
                int lum = Math.min(255, (54*r + 183*g + 19*b + 128)>>8);
                int gridX = Math.min(SIZE-1, (int)((long)x * SIZE/w));
                int idx = gridY*SIZE + gridX;
                sum[idx] += lum/255f;
                count[idx]++;
                int fx=Math.min(127,(int)((long)x*128/w));
                int fy=Math.min(127,(int)((long)y*128/h));
                int fineIndex=fy*128+fx;
                fineSum[fineIndex]+=lum/255f;
                fineCount[fineIndex]++;
                histogram[lum]++;
                // High RGB channels matter even when bright pink petals have low luminance.
                if (Math.max(r,Math.max(g,b)) >= 237 && lum >= 120) {
                    brightCount[idx]++;
                    fineHot[fineIndex]++;
                    bright++;
                }
                // Strong orange/yellow light is distinct from ordinary warm
                // complexion or red fabric; this is not a fire detector.
                if (r >= 140 && g >= 55 && r >= g*1.28f &&
                        g >= b*1.50f && g-b >= 28 && lum >= 65) {
                    warmGlow++;
                }
                samples++;
            }
        }

        int p5 = percentile(histogram, samples, .05f);
        int median = percentile(histogram, samples, .50f);
        int p95 = percentile(histogram, samples, .95f);
        float highlight = p95/255f;
        float coverage = bright/(float)Math.max(1,samples);

        float maxEV = highlight > .84f || coverage > .10f ? .025f :
                      highlight > .71f || coverage > .025f ? .085f :
                      highlight > .62f ? .18f : .45f;
        // Decide WHETHER to lift exposure before deciding HOW MUCH. This is a
        // second, 16x16 scene-judgement layer built from the same measurements.
        SceneJudgement16.Decision judgement = SceneJudgement16.assess(
                sum, count, histogram, samples, coverage, maxEV);
        SceneAtmosphere8.Decision atmosphere = SceneAtmosphere8.assess(
                sum, count, histogram, samples, warmGlow, coverage,
                judgement.exposureCeiling, judgement.shadowScale);
        SceneIntent4.Decision intent = SceneIntent4.assess(
                sum, count, histogram, samples, atmosphere);
        float ideal = (float)(Math.log(.36f/Math.max(.09f, median/255f)) /
                Math.log(2f));
        float exposure = clamp(ideal, -.16f, intent.exposureCeiling);
        float shadows = (p5 < 40 ? 15f : p5 < 70 ? 11f : 6f) *
                intent.shadowScale;
        float highlights = highlight > .83f ? -13f :
                           highlight > .71f ? -8f : 0f;
        float contrast = clamp((.60f - (p95-p5)/255f)*11f, -4f, 4f);

        float[] raw = new float[CELLS];
        for (int i = 0; i < CELLS; i++) {
            float local = count[i] > 0 ? sum[i]/count[i] : median/255f;
            float hot = count[i] > 0 ? brightCount[i]/(float)count[i] : 0f;
            raw[i] = clamp((.49f-local)/.39f, 0f, 1f) *
                    (1f-clamp(hot*1.5f, 0f, 1f));
        }

        // 3x3 Gaussian-like smoothing suppresses abrupt zone boundaries.
        float[] smooth = new float[CELLS];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float weighted = 0f, weightTotal = 0f;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int sx = Math.max(0, Math.min(SIZE-1,x+dx));
                        int sy = Math.max(0, Math.min(SIZE-1,y+dy));
                        int weight = (dx == 0 ? 2 : 1) * (dy == 0 ? 2 : 1);
                        weighted += raw[sy*SIZE+sx]*weight;
                        weightTotal += weight;
                    }
                }
                smooth[y*SIZE+x] = weighted / weightTotal;
            }
        }
        float[] fine=SpatialFine128.build(fineSum,fineCount,fineHot,smooth,sum,count);
        return new Scene(smooth,fine,exposure,contrast,highlights,shadows,coverage,
                judgement,atmosphere,intent);
    }

    private static int percentile(int[] h, int total, float fraction) {
        int threshold = Math.max(1, (int)Math.ceil(total*fraction));
        int sum = 0;
        for (int i=0;i<h.length;i++) {
            sum += h[i];
            if (sum >= threshold) return i;
        }
        return 255;
    }

    static float smoothstep(float start, float end, float x) {
        float u = clamp((x-start)/(end-start), 0f, 1f);
        return u*u*(3f-2f*u);
    }

    static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi,v));
    }

    static int[] xIndices(int width) {
        int[] indexes = new int[width];
        for (int x = 0; x < width; x++) {
            float z = clamp((x+.5f)*SIZE/width-.5f, 0f, SIZE-1);
            indexes[x] = (int)z;
        }
        return indexes;
    }

    static float[] xFractions(int width) {
        float[] result = new float[width];
        for (int x = 0; x < width; x++) {
            float z = clamp((x+.5f)*SIZE/width-.5f, 0f, SIZE-1);
            result[x] = z-(int)z;
        }
        return result;
    }

    static void interpolateRow(float[] zones, int y, int height, float[] row) {
        float yf = clamp((y+.5f)*SIZE/height-.5f,0f,SIZE-1);
        int top = (int)yf, bottom = Math.min(SIZE-1,top+1);
        float f = yf-top;
        for (int x = 0; x < SIZE; x++) {
            row[x] = zones[top*SIZE+x]*(1f-f) + zones[bottom*SIZE+x]*f;
        }
    }

    private SpatialAuto64() {}
}
