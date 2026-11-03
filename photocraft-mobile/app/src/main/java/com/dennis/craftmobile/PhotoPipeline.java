package com.dennis.craftmobile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Build;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * v0.6.21 processing order (preview and export run exactly the same code):
 *
 *  1. Auto — the unchanged 64×64 spatial Auto, with its own stored values and
 *     the hue-neutral white balance (Auto WB or the neutral picker).
 *  2. Photographer's sliders on top — exposure and Warmth/Tint as linear-light
 *     gains (blacks stay black, no colour cast in the shadows), highlights and
 *     shadows as luminance ratios (hue kept, blacks not lifted), then contrast
 *     and saturation. All at zero = skipped, so Auto alone renders exactly as
 *     it did in 0.6.20.
 *  3. Geometry in ONE resampling pass: rotate 90°, straighten/perspective with
 *     its coverage zoom, and the free crop. Output is allocated at crop size.
 *     Ultra HDR gain maps get the same geometry so HDR survives export.
 *  4. Detail (noise, sharpening, texture, clarity, dehaze) on the final frame.
 */
final class PhotoPipeline {
    private PhotoPipeline() {}

    // sRGB <-> linear lookup tables with linear interpolation.
    private static final int DEC_N = 1024, ENC_N = 4096;
    private static final float[] DEC = new float[DEC_N + 1], ENC = new float[ENC_N + 1];
    static {
        for (int i = 0; i <= DEC_N; i++) {
            double v = i / (double) DEC_N;
            DEC[i] = (float) (v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4));
        }
        for (int i = 0; i <= ENC_N; i++) {
            double v = i / (double) ENC_N;
            ENC[i] = (float) (v <= .0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - .055);
        }
    }
    static float toLinear(float v) {
        if (v <= 0f) return 0f;
        if (v >= 1f) return 1f;
        float p = v * DEC_N; int i = (int) p; float f = p - i;
        return DEC[i] + (DEC[i + 1] - DEC[i]) * f;
    }
    static float toGamma(float v) {
        if (v <= 0f) return 0f;
        if (v >= 1f) return 1f;
        float p = v * ENC_N; int i = (int) p; float f = p - i;
        return ENC[i] + (ENC[i + 1] - ENC[i]) * f;
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static int quant(float v) { return (int) (255 * clamp(v, 0, 1) + .5f); }

    /** The photographer's own tone sliders, prepared once per render. */
    static final class Manual {
        final boolean active, linearStage, ratioStage;
        final float gain, gainR, gainG, gainB, contrast, saturation, shadow, high;
        Manual(MainActivity.Edit e) {
            gain = (float) Math.pow(2, clamp(e.exposure, -4f, 4f));
            // Warmth/Tint as white-balance gains in linear light, normalised so
            // the overall brightness does not change. Multiplicative: black
            // stays black and nothing is added to the shadows.
            float warm = clamp(e.warmth, -100f, 100f) / 100f;
            float tnt = clamp(e.tint, -100f, 100f) / 100f;
            float r = (float) Math.exp(warm * .26f + tnt * .09f);
            float g = (float) Math.exp(-tnt * .18f);
            float b = (float) Math.exp(-warm * .26f + tnt * .09f);
            float k = .2126f * r + .7152f * g + .0722f * b;
            gainR = r / k; gainG = g / k; gainB = b / k;
            contrast = 1f + e.contrast / 100f * .85f;
            saturation = 1f + e.saturation / 100f;
            shadow = e.shadows / 100f * .30f;
            high = e.highlights / 100f * .30f;
            linearStage = e.exposure != 0f || warm != 0f || tnt != 0f;
            ratioStage = shadow != 0f || high != 0f;
            active = linearStage || ratioStage || e.contrast != 0f || e.saturation != 0f;
        }
    }

    /** Tone and colour. Writes into [output] (may be [src]; each row is read before written). */
    static void tone(Bitmap src, Bitmap output, MainActivity.Edit e, BooleanSupplier cancelled) {
        int w = src.getWidth(), h = src.getHeight();
        int[] pixels = new int[w];
        Manual m = new Manual(e);
        boolean spatial = e.autoOn();
        boolean wb = e.wbConfidence > 0f;
        int[] zoneX = null;
        float[] fractionX = null, rowZones = null;
        float strength = clamp(e.autoStrength / 100f, 0f, 1f);
        float autoGain = ((float) Math.pow(2, e.autoExposure) - 1f) * strength;
        float autoShadow = e.autoShadows / 100f * .24f * strength;
        float autoHighlights = e.autoHighlights / 100f * .13f * strength;
        float autoContrast = e.autoContrast / 100f * .22f * strength;
        float wbStrength = e.wbPicked || !spatial ? 1f : strength;
        // v0.6.22: Auto Rescue first (only when Auto found the photo needs it).
        AutoRescue.Params rescue = e.rescue();
        boolean rescued = spatial && rescue.active();
        float[][] rescueTable = rescued ? AutoRescue.tables(rescue, strength) : null;
        float vibrance = rescue.vibrance * strength;
        float[] v3 = new float[3];
        if (spatial) {
            zoneX = SpatialAuto64.xIndices(w);
            fractionX = SpatialAuto64.xFractions(w);
            rowZones = new float[SpatialAuto64.SIZE];
        }
        for (int y = 0; y < h; y++) {
            if ((y & 7) == 0 && cancelled.getAsBoolean()) throw new CancellationException();
            if (spatial) SpatialAuto64.interpolateRow(e.autoZones, y, h, rowZones);
            src.getPixels(pixels, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int pixel = pixels[x];
                float r0, g0, b0;
                if (rescued) {
                    v3[0] = rescueTable[0][Color.red(pixel)];
                    v3[1] = rescueTable[1][Color.green(pixel)];
                    v3[2] = rescueTable[2][Color.blue(pixel)];
                    if (vibrance > 0f) AutoRescue.vibrance(v3, vibrance);
                    r0 = v3[0]; g0 = v3[1]; b0 = v3[2];
                } else {
                    r0 = Color.red(pixel) / 255f;
                    g0 = Color.green(pixel) / 255f;
                    b0 = Color.blue(pixel) / 255f;
                }
                float maxChannel = Math.max(r0, Math.max(g0, b0));
                float r = r0, g = g0, b = b0;
                if (spatial) {
                    // --- 0.6.20 spatial Auto, unchanged ---
                    float lum = .2126f * r0 + .7152f * g0 + .0722f * b0;
                    float xf = fractionX[x];
                    int zi = zoneX[x];
                    float zone = rowZones[zi] * (1f - xf) +
                            rowZones[Math.min(SpatialAuto64.SIZE - 1, zi + 1)] * xf;
                    float protection = 1f - SpatialAuto64.smoothstep(.64f, .94f, maxChannel);
                    float darkness = 1f - SpatialAuto64.smoothstep(.12f, .68f, lum);
                    float highlights = SpatialAuto64.smoothstep(.61f, .94f, lum);
                    float requested = lum * (1f + autoGain * protection)
                            + autoShadow * zone * darkness
                            + autoHighlights * highlights
                            + autoContrast * (lum - .5f) * (1f - highlights);
                    requested = clamp(requested, 0f, 1f);
                    float scale = lum > .012f ? requested / lum : 1f;
                    if (scale > 1f && maxChannel > 0f) scale = Math.min(scale, .997f / maxChannel);
                    r = r0 * scale; g = g0 * scale; b = b0 * scale;
                }
                if (wb) {
                    // Hue-neutral WB (Auto WB or the neutral picker): preserve local
                    // luminance, protect saturated subjects and near-white pixels.
                    float chroma = (maxChannel - Math.min(r0, Math.min(g0, b0))) /
                            Math.max(.01f, maxChannel);
                    float colourGate = (1f - .35f * SpatialAuto64.smoothstep(.20f, .65f, chroma)) *
                            (1f - .78f * SpatialAuto64.smoothstep(.84f, .99f, maxChannel));
                    float dr = r * (e.wbRed - 1f) * wbStrength * colourGate;
                    float dg = g * (e.wbGreen - 1f) * wbStrength * colourGate;
                    float db = b * (e.wbBlue - 1f) * wbStrength * colourGate;
                    float deltaLum = .2126f * dr + .7152f * dg + .0722f * db;
                    r += dr - deltaLum; g += dg - deltaLum; b += db - deltaLum;
                    float upper = Math.max(r, Math.max(g, b));
                    if (upper > .997f) { float keep = .997f / upper; r *= keep; g *= keep; b *= keep; }
                }
                if (m.active) {
                    if (m.linearStage) {
                        float lr = toLinear(r) * m.gain * m.gainR;
                        float lg = toLinear(g) * m.gain * m.gainG;
                        float lb = toLinear(b) * m.gain * m.gainB;
                        if (m.gain > 1f) { lr = shoulder(lr); lg = shoulder(lg); lb = shoulder(lb); }
                        r = toGamma(lr); g = toGamma(lg); b = toGamma(lb);
                    }
                    if (m.ratioStage) {
                        float l = clamp(.2126f * r + .7152f * g + .0722f * b, 0f, 1f);
                        if (l > .0005f) {
                            float inv = 1f - l;
                            // Shadows peak in the lower mids and vanish at black;
                            // highlights act on the bright end. Ratio = hue kept.
                            float target = l + m.shadow * 3.375f * l * inv * inv + m.high * l * l;
                            float k = Math.max(0f, target) / l;
                            float top = Math.max(r, Math.max(g, b));
                            if (k > 1f && top * k > .997f) k = Math.max(1f, .997f / Math.max(top, 1e-4f));
                            r *= k; g *= k; b *= k;
                        }
                    }
                    if (m.contrast != 1f) {
                        r = (r - .5f) * m.contrast + .5f;
                        g = (g - .5f) * m.contrast + .5f;
                        b = (b - .5f) * m.contrast + .5f;
                    }
                    if (m.saturation != 1f) {
                        float gray = .2126f * r + .7152f * g + .0722f * b;
                        r = gray + (r - gray) * m.saturation;
                        g = gray + (g - gray) * m.saturation;
                        b = gray + (b - gray) * m.saturation;
                    }
                }
                pixels[x] = Color.argb(Color.alpha(pixel), quant(r), quant(g), quant(b));
            }
            output.setPixels(pixels, 0, w, 0, y, w, 1);
        }
    }

    /** Soft roll-off above 0.85 linear so positive exposure does not clip hard. */
    private static float shoulder(float x) {
        if (x <= .85f) return x;
        return .85f + .15f * (1f - (float) Math.exp(-(x - .85f) / .15f));
    }

    static boolean toneActive(MainActivity.Edit e) {
        return e.autoOn() || e.wbConfidence > 0f || new Manual(e).active;
    }

    static boolean hasTransform(MainActivity.Edit e) {
        return Math.abs(e.straightenAngle) >= .015f || Math.abs(e.perspectiveVertical) >= .015f ||
                Math.abs(e.perspectiveHorizontal) >= .015f;
    }

    static boolean hasGeometry(MainActivity.Edit e) {
        return e.rotation % 360 != 0 || hasTransform(e) || e.cropped();
    }

    /** Frame size after the 90° rotation (straighten/perspective keep it). */
    static int frameWidth(int w, int h, int rotation) { return rotation % 180 == 0 ? w : h; }
    static int frameHeight(int w, int h, int rotation) { return rotation % 180 == 0 ? h : w; }

    /** Pixel crop rectangle {x, y, width, height} in a frame of fw × fh. */
    static int[] cropPixels(MainActivity.Edit e, int fw, int fh) {
        float l = clamp(Math.min(e.cropLeft, e.cropRight), 0f, 1f);
        float r = clamp(Math.max(e.cropLeft, e.cropRight), 0f, 1f);
        float t = clamp(Math.min(e.cropTop, e.cropBottom), 0f, 1f);
        float b = clamp(Math.max(e.cropTop, e.cropBottom), 0f, 1f);
        int x0 = Math.min(fw - 1, Math.round(l * fw)), y0 = Math.min(fh - 1, Math.round(t * fh));
        int x1 = Math.max(x0 + 1, Math.min(fw, Math.round(r * fw)));
        int y1 = Math.max(y0 + 1, Math.min(fh, Math.round(b * fh)));
        return new int[]{x0, y0, x1 - x0, y1 - y0};
    }

    /** Complete source → output matrix for one bitmap of w × h. */
    static Matrix geometryMatrix(MainActivity.Edit e, int w, int h, int[] crop, BooleanSupplier cancelled) {
        int rotation = ((e.rotation % 360) + 360) % 360;
        Matrix m = new Matrix();
        if (rotation == 90) { m.setRotate(90); m.postTranslate(h, 0); }
        else if (rotation == 180) { m.setRotate(180); m.postTranslate(w, h); }
        else if (rotation == 270) { m.setRotate(270); m.postTranslate(0, w); }
        int fw = frameWidth(w, h, rotation), fh = frameHeight(w, h, rotation);
        if (hasTransform(e)) {
            Matrix t = TransformEngine.matrix(fw, fh, e.straightenAngle,
                    e.perspectiveVertical, e.perspectiveHorizontal, cancelled);
            if (t != null) m.postConcat(t);
        }
        m.postTranslate(-crop[0], -crop[1]);
        return m;
    }

    /** One filtered draw from [src] into a new crop-sized bitmap. */
    static Bitmap geometry(Bitmap src, MainActivity.Edit e, BooleanSupplier cancelled) {
        int w = src.getWidth(), h = src.getHeight();
        int rotation = ((e.rotation % 360) + 360) % 360;
        int fw = frameWidth(w, h, rotation), fh = frameHeight(w, h, rotation);
        int[] crop = cropPixels(e, fw, fh);
        Matrix m = geometryMatrix(e, w, h, crop, cancelled);
        if (cancelled != null && cancelled.getAsBoolean()) throw new CancellationException();
        Bitmap.Config config = src.getConfig() == null ? Bitmap.Config.ARGB_8888 : src.getConfig();
        Bitmap out = Bitmap.createBitmap(crop[2], crop[3], config);
        try {
            Canvas canvas = new Canvas(out);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(src, m, paint);
        } catch (RuntimeException | OutOfMemoryError error) {
            out.recycle();
            throw error;
        }
        if (Build.VERSION.SDK_INT >= 34) copyGainmap(src, out, e, cancelled);
        return out;
    }

    /** Ultra HDR: the gain map gets exactly the same rotation, straighten and crop. */
    private static void copyGainmap(Bitmap src, Bitmap out, MainActivity.Edit e, BooleanSupplier cancelled) {
        try {
            if (!src.hasGainmap()) return;
            android.graphics.Gainmap gm = src.getGainmap();
            if (gm == null) return;
            Bitmap contents = gm.getGainmapContents();
            if (contents == null || contents.getWidth() < 2 || contents.getHeight() < 2) return;
            Bitmap moved = geometryNoGainmap(contents, e, cancelled);
            android.graphics.Gainmap copy = new android.graphics.Gainmap(moved);
            float[] v = gm.getRatioMin(); copy.setRatioMin(v[0], v[1], v[2]);
            v = gm.getRatioMax(); copy.setRatioMax(v[0], v[1], v[2]);
            v = gm.getGamma(); copy.setGamma(v[0], v[1], v[2]);
            v = gm.getEpsilonSdr(); copy.setEpsilonSdr(v[0], v[1], v[2]);
            v = gm.getEpsilonHdr(); copy.setEpsilonHdr(v[0], v[1], v[2]);
            copy.setMinDisplayRatioForHdrTransition(gm.getMinDisplayRatioForHdrTransition());
            copy.setDisplayRatioForFullHdr(gm.getDisplayRatioForFullHdr());
            out.setGainmap(copy);
        } catch (CancellationException c) {
            throw c;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            // HDR is optional: the SDR photo is still correct without a gain map.
        }
    }

    private static Bitmap geometryNoGainmap(Bitmap src, MainActivity.Edit e, BooleanSupplier cancelled) {
        int w = src.getWidth(), h = src.getHeight();
        int rotation = ((e.rotation % 360) + 360) % 360;
        int[] crop = cropPixels(e, frameWidth(w, h, rotation), frameHeight(w, h, rotation));
        Matrix m = geometryMatrix(e, w, h, crop, cancelled);
        Bitmap.Config config = src.getConfig() == null ? Bitmap.Config.ARGB_8888 : src.getConfig();
        Bitmap out = Bitmap.createBitmap(crop[2], crop[3], config);
        new Canvas(out).drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /**
     * Whole edit. With [reuse] the tone pass writes into [src] (export: no second
     * full-size bitmap). Returns [src] itself only when there is no geometry and
     * reuse is on; otherwise a new bitmap, and [src] is left for the caller.
     */
    static Bitmap process(Bitmap src, MainActivity.Edit e, BooleanSupplier cancelled, boolean reuse) {
        Bitmap toned = src;
        boolean ownToned = false;
        if (toneActive(e)) {
            toned = reuse ? src : Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
            ownToned = toned != src;
            try {
                tone(src, toned, e, cancelled);
            } catch (RuntimeException | OutOfMemoryError ex) {
                if (ownToned) toned.recycle();
                throw ex;
            }
        }
        Bitmap result = toned;
        if (hasGeometry(e)) {
            try {
                result = geometry(toned, e, cancelled);
            } catch (RuntimeException | OutOfMemoryError ex) {
                if (ownToned) toned.recycle();
                throw ex;
            }
            if (ownToned) toned.recycle();
        } else if (!reuse && toned == src) {
            result = src.copy(Bitmap.Config.ARGB_8888, true);
        }
        try {
            DetailEngine.applyInPlace(result, detailSettings(e), cancelled);
        } catch (RuntimeException | OutOfMemoryError ex) {
            if (result != src) result.recycle();
            throw ex;
        }
        return result;
    }

    static DetailEngine.Settings detailSettings(MainActivity.Edit e) {
        DetailEngine.Settings d = new DetailEngine.Settings();
        d.luminanceNoise = e.luminanceNR;
        d.colorNoise = e.colorNR;
        d.sharpening = e.sharpen;
        d.clarity = e.clarity;
        d.texture = e.texture;
        d.dehaze = e.dehaze;
        return d;
    }
}
