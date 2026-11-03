package com.dennis.craftmobile;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * v0.6.22 Auto Rescue — runs BEFORE the familiar 64×64 Scene Auto, and only when
 * the photograph clearly needs it. A well-exposed, well-coloured photo gets an
 * exact identity here, so Auto on good photos is unchanged.
 *
 *  • Under-exposure: no real highlights anywhere (99th percentile well below
 *    white) means the camera got it wrong, not a moody low-key shot, which keeps
 *    its lamps, windows or sky. Lifted in linear light, up to +1.6 EV, with a
 *    soft shoulder.
 *  • Over-exposure / washed bright: a midtone gamma pulls the middle down while
 *    white stays white (no grey skies), like Levels' middle slider.
 *  • Flat / foggy: black and white points stretched part of the way (Photoshop
 *    Auto Contrast style, same for all channels so neutrals stay neutral).
 *  • Colour cast: measured on low-colour midtones only (walls, gravel, clothing,
 *    cloud), so a yellow flower or a red car cannot be "corrected". Golden-light
 *    scenes keep most of their warmth.
 *  • Dull colour: gentle vibrance on muted pixels, half strength on skin tones.
 *
 * Everything per channel is folded into three 256-entry lookup tables, so the
 * cost per pixel is tiny. Values scale with Auto Strength.
 */
final class AutoRescue {
    static final class Params {
        float ev, midGamma = 1f, black, white = 1f, castR = 1f, castG = 1f, castB = 1f, vibrance;
        boolean active() {
            return Math.abs(ev) > .005f || Math.abs(midGamma - 1f) > .003f || black > .002f ||
                    white < .998f || Math.abs(castR - 1f) > .003f || Math.abs(castG - 1f) > .003f ||
                    Math.abs(castB - 1f) > .003f || vibrance > .005f;
        }
        String describe() {
            StringBuilder b = new StringBuilder();
            if (ev > .05f) b.append(String.format(java.util.Locale.US, "lifted %+.1f EV", ev));
            if (midGamma > 1.02f) append(b, "bright mids eased");
            if (black > .01f || white < .97f) append(b, "contrast restored");
            if (Math.abs(castR - castB) > .02f) append(b, "colour cast removed");
            if (vibrance > .02f) append(b, "colour livened");
            return b.toString();
        }
        private static void append(StringBuilder b, String s) { if (b.length() > 0) b.append(" · "); b.append(s); }
    }

    private AutoRescue() {}

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float smooth(float a, float b, float x) {
        float t = clamp((x - a) / (b - a), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
    private static float lum(float r, float g, float b) { return .2126f * r + .7152f * g + .0722f * b; }

    /** Pixels sampled once, about 300k, from the original preview. */
    private static float[][] sample(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int stride = Math.max(1, (int) Math.sqrt(w * (double) h / 300_000.0));
        int nx = (w + stride - 1) / stride, ny = (h + stride - 1) / stride;
        float[][] px = new float[3][nx * ny];
        int[] row = new int[w];
        int n = 0;
        for (int y = 0; y < h; y += stride) {
            src.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x += stride) {
                int c = row[x];
                px[0][n] = Color.red(c) / 255f; px[1][n] = Color.green(c) / 255f; px[2][n] = Color.blue(c) / 255f;
                n++;
            }
        }
        if (n < px[0].length) for (int k = 0; k < 3; k++) px[k] = java.util.Arrays.copyOf(px[k], n);
        return px;
    }

    private static float percentile(float[] sorted, float q) {
        if (sorted.length == 0) return 0f;
        return sorted[Math.min(sorted.length - 1, Math.max(0, (int) (q * (sorted.length - 1))))];
    }

    private static float[] lumas(float[][] px) {
        float[] l = new float[px[0].length];
        for (int i = 0; i < l.length; i++) l[i] = lum(px[0][i], px[1][i], px[2][i]);
        java.util.Arrays.sort(l);
        return l;
    }

    static Params analyse(Bitmap src) {
        Params p = new Params();
        float[][] px = sample(src);
        int n = px[0].length;
        if (n < 64) return p;
        float[] l = lumas(px);
        float p05 = percentile(l, .005f), p5 = percentile(l, .05f), median = percentile(l, .5f);
        float p99 = percentile(l, .99f), p995 = percentile(l, .995f);
        int hot = 0;
        for (int i = 0; i < n; i++)
            if (Math.max(px[0][i], Math.max(px[1][i], px[2][i])) >= .93f) hot++;
        float hotFraction = hot / (float) n;

        // 1. Under-exposure: the brightest 1% is still well short of white and
        //    almost nothing is near clipping. Lamps, windows or sky => low-key, left alone.
        float under = smooth(.86f, .66f, p99) * (1f - smooth(.001f, .006f, hotFraction));
        if (under > 0f && median < .40f) {
            float lin99 = PhotoPipeline.toLinear(Math.max(.02f, p99));
            float linMed = PhotoPipeline.toLinear(Math.max(.01f, median));
            double toHighlights = Math.log(PhotoPipeline.toLinear(.92f) / lin99) / Math.log(2);
            double toMidtones = Math.log(PhotoPipeline.toLinear(.42f) / linMed) / Math.log(2);
            p.ev = clamp((float) Math.min(toHighlights, toMidtones), 0f, 1.6f) * under;
        }

        // 2. Washed-out bright photo: middle and shadows all high. Midtone gamma
        //    keeps white at white. High-key snow/beach scenes with real darks are left.
        float over = smooth(.48f, .62f, median) * smooth(.12f, .30f, p5);
        if (over > 0f) {
            float g = (float) (Math.log(.46f) / Math.log(Math.max(.47f, median)));
            p.midGamma = 1f + (clamp(g, 1f, 1.7f) - 1f) * over;
        }

        // 3. Strong colour cast (the existing Auto WB handles mild ones, ±6%).
        float[] cast = castGains(src);
        p.castR = cast[0]; p.castG = cast[1]; p.castB = cast[2];

        // 4. Flat / foggy: stretch black and white points, measured after 1–3.
        float[][] after = applyToSamples(px, p, false);
        float[] la = lumas(after);
        float lo = percentile(la, .003f), hi = percentile(la, .997f);
        float range = hi - lo;
        float flat = smooth(.82f, .58f, range) * .8f;
        if (flat > 0f) {
            p.black = clamp(lo * .9f, 0f, .14f) * flat;
            p.white = 1f - clamp((1f - hi) * .9f, 0f, .22f) * flat;
        }

        // 5. Dull colour: mean colourfulness after 1–4.
        after = applyToSamples(px, p, false);
        double chroma = 0;
        for (int i = 0; i < n; i++) {
            float r = after[0][i], g = after[1][i], b = after[2][i];
            float mx = Math.max(r, Math.max(g, b));
            chroma += mx - Math.min(r, Math.min(g, b));
        }
        float meanChroma = (float) (chroma / n);
        // Muted (overcast, flat light) but not black-and-white or toned.
        p.vibrance = .35f * smooth(.15f, .07f, meanChroma) * smooth(.012f, .035f, meanChroma);
        return p;
    }

    /**
     * Strong cast estimate. Starts with a wide colour tolerance and narrows around
     * the estimate, so neutral surfaces that the cast has coloured are found again.
     * The neutrals must be spread across the frame (top and bottom), agree between
     * darker and lighter midtones, and pale sky is never used. Returns gains
     * normalised to keep brightness, or 1,1,1 when there is no clear evidence.
     */
    static float[] castGains(Bitmap src) {
        final int grid = 96;
        int w = src.getWidth(), h = src.getHeight();
        float[] none = {1f, 1f, 1f};
        if (w < 32 || h < 32) return none;
        int n = grid * grid;
        float[] lr = new float[n], lb = new float[n], ly = new float[n];
        int[] cell = new int[n];
        boolean[] top = new boolean[n];
        int m = 0, warmGlow = 0;
        for (int gy = 0; gy < grid; gy++) {
            int y = Math.min(h - 2, Math.max(1, (int) ((gy + .5f) * h / grid)));
            for (int gx = 0; gx < grid; gx++) {
                int x = Math.min(w - 2, Math.max(1, (int) ((gx + .5f) * w / grid)));
                float r = 0, g = 0, b = 0;
                int[] taps = {src.getPixel(x, y), src.getPixel(x, y), src.getPixel(x - 1, y),
                        src.getPixel(x + 1, y), src.getPixel(x, y - 1), src.getPixel(x, y + 1)};
                for (int c : taps) { r += Color.red(c); g += Color.green(c); b += Color.blue(c); }
                r /= 6 * 255f; g /= 6 * 255f; b /= 6 * 255f;
                if (r > .55f && r > g * 1.25f && g > b * 1.4f) warmGlow++;
                float y0 = lum(r, g, b);
                float hi = Math.max(r, Math.max(g, b)), lo = Math.min(r, Math.min(g, b));
                if (y0 < .16f || y0 > .86f || hi > .96f || lo < .03f) continue;
                lr[m] = (float) Math.log(PhotoPipeline.toLinear(r) / PhotoPipeline.toLinear(g));
                lb[m] = (float) Math.log(PhotoPipeline.toLinear(b) / PhotoPipeline.toLinear(g));
                ly[m] = y0;
                cell[m] = (gy / 12) * 8 + gx / 12;
                top[m] = gy < grid / 2;
                m++;
            }
        }
        if (m < 200) return none;
        float cr = 0f, cb = 0f;
        float[] tolerance = {.55f, .40f, .28f, .20f};
        int used = 0, upper = 0, regions = 0;
        float darkR = 0, darkB = 0, lightR = 0, lightB = 0;
        for (float tol : tolerance) {
            float[] sr = new float[m], sb = new float[m];
            boolean[] seen = new boolean[64];
            int k = 0, up = 0, nd = 0, nl = 0;
            float dR = 0, dB = 0, lR = 0, lB = 0;
            for (int i = 0; i < m; i++) {
                float dr = lr[i] - cr, db = lb[i] - cb;
                // Distance from neutral once the current estimate is removed.
                if (Math.sqrt(dr * dr + db * db) > tol) continue;
                // Pale blue sky is not a neutral surface.
                if (top[i] && ly[i] > .50f && db > .10f && db > dr + .12f) continue;
                sr[k] = lr[i]; sb[k] = lb[i]; k++;
                seen[cell[i]] = true;
                if (top[i]) up++;
                if (ly[i] < .45f) { dR += lr[i]; dB += lb[i]; nd++; } else { lR += lr[i]; lB += lb[i]; nl++; }
            }
            if (k < 120) return none;
            cr = trimmed(sr, k); cb = trimmed(sb, k);
            used = k; upper = up; regions = 0;
            for (boolean v : seen) if (v) regions++;
            darkR = nd > 30 ? dR / nd : cr; darkB = nd > 30 ? dB / nd : cb;
            lightR = nl > 30 ? lR / nl : cr; lightB = nl > 30 ? lB / nl : cb;
        }
        // How tightly the neutrals cluster around the estimate: grey surfaces under
        // one light do (about 0.09); washed-out foliage and flowers do not (0.13+).
        double rms = 0; int rn = 0;
        for (int i = 0; i < m; i++) {
            float dr = lr[i] - cr, db = lb[i] - cb;
            double d = Math.sqrt(dr * dr + db * db);
            if (d <= .20f) { rms += d * d; rn++; }
        }
        float tight = rn > 0 ? (float) Math.sqrt(rms / rn) : 1f;
        float share = used / (float) m;
        float spread = Math.min(upper, used - upper) / (float) used;
        float magnitude = (float) Math.sqrt(cr * cr + cb * cb);
        // Darker and lighter midtones must point the same way.
        float dot = darkR * lightR + darkB * lightB;
        float agree = dot / (float) Math.max(1e-4, Math.sqrt((darkR * darkR + darkB * darkB) * (lightR * lightR + lightB * lightB)));
        float confidence = smooth(.10f, .22f, share) * smooth(.12f, .25f, spread) *
                smooth(18f, 30f, regions) * smooth(.3f, .7f, agree) *
                smooth(.09f, .16f, magnitude) * (1f - smooth(.70f, .95f, magnitude)) *
                smooth(.122f, .104f, tight) *
                // Nearly everything one tint = a sepia/toned picture, not a cast.
                (1f - smooth(.75f, .90f, share));
        if (cr > cb) confidence *= 1f - .6f * smooth(.03f, .10f, warmGlow / (float) n);
        // Green–magenta is corrected less: foliage-heavy scenes look "green" for real.
        float k = .70f * confidence;
        if (k < .02f) return none;
        float gr = (float) Math.exp(-cr * k), gb = (float) Math.exp(-cb * k);
        float gg = 1f;
        float norm = lum(gr, gg, gb);
        return new float[]{clamp(gr / norm, .75f, 1.33f), clamp(gg / norm, .9f, 1.1f), clamp(gb / norm, .75f, 1.33f)};
    }

    private static float trimmed(float[] v, int k) {
        float[] o = java.util.Arrays.copyOf(v, k);
        java.util.Arrays.sort(o);
        int a = k / 5, b = k - a;
        double t = 0;
        for (int i = a; i < b; i++) t += o[i];
        return (float) (t / Math.max(1, b - a));
    }

    /** Per-channel part of the rescue as lookup tables over 8-bit input. */
    static float[][] tables(Params p, float strength) {
        float s = clamp(strength, 0f, 1f);
        float gain = (float) Math.pow(2, p.ev * s);
        float gamma = 1f + (p.midGamma - 1f) * s;
        float black = p.black * s, white = 1f - (1f - p.white) * s;
        float[] cast = {(float) Math.pow(p.castR, s), (float) Math.pow(p.castG, s), (float) Math.pow(p.castB, s)};
        float[][] t = new float[3][256];
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < 256; i++) {
                float lin = PhotoPipeline.toLinear(i / 255f) * gain * cast[c];
                if (gain > 1f && lin > .80f) lin = .80f + .20f * (1f - (float) Math.exp(-(lin - .80f) / .20f));
                float v = PhotoPipeline.toGamma(Math.min(1f, lin));
                v = clamp((v - black) / Math.max(.2f, white - black), 0f, 1f);
                if (gamma != 1f) v = (float) Math.pow(v, gamma);
                t[c][i] = v;
            }
        }
        return t;
    }

    /** Vibrance on one pixel (values 0..1); skin tones get half. */
    static void vibrance(float[] rgb, float amount) {
        float r = rgb[0], g = rgb[1], b = rgb[2];
        float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        if (mx <= .02f) return;
        float sat = (mx - mn) / mx;
        float boost = amount * (1f - sat) * (1f - sat);
        if (r > g && g > b && sat > .15f && sat < .6f) boost *= .5f;
        float y = lum(r, g, b), k = 1f + boost;
        r = y + (r - y) * k; g = y + (g - y) * k; b = y + (b - y) * k;
        float top = Math.max(r, Math.max(g, b));
        if (top > 1f) { float q = (1f - y) / Math.max(1e-4f, top - y); r = y + (r - y) * q; g = y + (g - y) * q; b = y + (b - y) * q; }
        rgb[0] = Math.max(0f, r); rgb[1] = Math.max(0f, g); rgb[2] = Math.max(0f, b);
    }

    private static float[][] applyToSamples(float[][] px, Params p, boolean withVibrance) {
        float[][] t = tables(p, 1f);
        int n = px[0].length;
        float[][] o = new float[3][n];
        float[] v = new float[3];
        for (int i = 0; i < n; i++) {
            for (int c = 0; c < 3; c++) o[c][i] = t[c][Math.round(px[c][i] * 255f)];
            if (withVibrance && p.vibrance > 0f) {
                v[0] = o[0][i]; v[1] = o[1][i]; v[2] = o[2][i];
                vibrance(v, p.vibrance);
                o[0][i] = v[0]; o[1][i] = v[1]; o[2][i] = v[2];
            }
        }
        return o;
    }

    /** The rescued picture that the Scene Auto analyses (preview size). */
    static Bitmap apply(Bitmap src, Params p) {
        int w = src.getWidth(), h = src.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        float[][] t = tables(p, 1f);
        int[] row = new int[w];
        float[] v = new float[3];
        for (int y = 0; y < h; y++) {
            src.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int c = row[x];
                v[0] = t[0][Color.red(c)]; v[1] = t[1][Color.green(c)]; v[2] = t[2][Color.blue(c)];
                if (p.vibrance > 0f) vibrance(v, p.vibrance);
                row[x] = Color.argb(Color.alpha(c), q(v[0]), q(v[1]), q(v[2]));
            }
            out.setPixels(row, 0, w, 0, y, w, 1);
        }
        return out;
    }
    private static int q(float v) { return (int) (255 * clamp(v, 0f, 1f) + .5f); }
}
