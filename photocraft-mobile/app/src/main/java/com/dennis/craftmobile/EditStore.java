package com.dennis.craftmobile;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * v0.6.21: edits are kept per photo in the app's private storage, so closing the
 * app, swiping it away or Android killing it in the background no longer loses
 * work. Nothing is written next to the original photo. The key is the photo's
 * file name and size, so the same picture opened from the album browser, the
 * picker or "Open with" finds its edits again.
 */
final class EditStore {
    private static final int FORMAT = 1, KEEP = 400;
    private EditStore() {}

    static String key(Context context, Uri uri) {
        String name = null; long size = -1;
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int s = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0) name = c.getString(n);
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s);
            }
        } catch (RuntimeException ignored) {}
        String identity = name != null && size > 0 ? name + "|" + size : uri.toString();
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(identity.hashCode());
        }
    }

    private static File dir(Context context) {
        File d = new File(context.getFilesDir(), "edits");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static MainActivity.Edit load(Context context, String key) {
        if (key == null) return null;
        File f = new File(dir(context), key + ".json");
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) Math.min(f.length(), 4_000_000)];
            int read = 0, got;
            while (read < data.length && (got = in.read(data, read, data.length - read)) > 0) read += got;
            return fromJson(new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Log.w("PhotoStudioEdits", "Could not read saved edits", e);
            return null;
        }
    }

    static void save(Context context, String key, MainActivity.Edit edit) {
        if (key == null || edit == null) return;
        File d = dir(context);
        File f = new File(d, key + ".json");
        if (MainActivity.hasNoEdits(edit)) { f.delete(); return; }
        File tmp = new File(d, key + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(toJson(edit).toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception e) {
            Log.w("PhotoStudioEdits", "Could not save edits", e);
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
        prune(d);
    }

    private static void prune(File d) {
        File[] all = d.listFiles((x, n) -> n.endsWith(".json"));
        if (all == null || all.length <= KEEP) return;
        Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < all.length - KEEP; i++) all[i].delete();
    }

    static JSONObject toJson(MainActivity.Edit e) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("format", FORMAT);
        o.put("exposure", e.exposure); o.put("contrast", e.contrast);
        o.put("highlights", e.highlights); o.put("shadows", e.shadows);
        o.put("saturation", e.saturation); o.put("warmth", e.warmth); o.put("tint", e.tint);
        o.put("ratio", e.ratio); o.put("rotation", e.rotation);
        o.put("straighten", e.straightenAngle);
        o.put("perspectiveV", e.perspectiveVertical); o.put("perspectiveH", e.perspectiveHorizontal);
        o.put("look", e.look);
        if (e.autoZones != null) {
            JSONArray z = new JSONArray();
            for (float v : e.autoZones) z.put((double) v);
            o.put("autoZones", z);
        }
        o.put("autoStrength", e.autoStrength);
        o.put("autoLowKey", e.autoLowKey);
        o.put("autoAtmosphere", e.autoAtmosphere == null ? "" : e.autoAtmosphere);
        o.put("autoMacro", e.autoMacroProtected);
        o.put("autoImportant", e.autoImportantRegion);
        o.put("autoBlack", e.autoBlackProtected);
        o.put("autoExposure", e.autoExposure); o.put("autoContrast", e.autoContrast);
        o.put("autoHighlights", e.autoHighlights); o.put("autoShadows", e.autoShadows);
        o.put("lumNR", e.luminanceNR); o.put("colorNR", e.colorNR); o.put("sharpen", e.sharpen);
        o.put("clarity", e.clarity); o.put("texture", e.texture); o.put("dehaze", e.dehaze);
        o.put("wbRed", e.wbRed); o.put("wbGreen", e.wbGreen); o.put("wbBlue", e.wbBlue);
        o.put("wbConfidence", e.wbConfidence); o.put("wbPicked", e.wbPicked);
        o.put("cropL", e.cropLeft); o.put("cropT", e.cropTop);
        o.put("cropR", e.cropRight); o.put("cropB", e.cropBottom);
        o.put("rEv", e.rescueEv); o.put("rGamma", e.rescueGamma);
        o.put("rBlack", e.rescueBlack); o.put("rWhite", e.rescueWhite);
        o.put("rCastR", e.rescueCastR); o.put("rCastG", e.rescueCastG); o.put("rCastB", e.rescueCastB);
        o.put("rVib", e.rescueVibrance);
        return o;
    }

    private static float f(JSONObject o, String k, float d) { return (float) o.optDouble(k, d); }

    static MainActivity.Edit fromJson(JSONObject o) {
        MainActivity.Edit e = new MainActivity.Edit();
        e.exposure = f(o, "exposure", 0); e.contrast = f(o, "contrast", 0);
        e.highlights = f(o, "highlights", 0); e.shadows = f(o, "shadows", 0);
        e.saturation = f(o, "saturation", 0); e.warmth = f(o, "warmth", 0); e.tint = f(o, "tint", 0);
        e.ratio = f(o, "ratio", 0); e.rotation = o.optInt("rotation", 0);
        e.straightenAngle = f(o, "straighten", 0);
        e.perspectiveVertical = f(o, "perspectiveV", 0); e.perspectiveHorizontal = f(o, "perspectiveH", 0);
        e.look = o.optString("look", "None");
        JSONArray z = o.optJSONArray("autoZones");
        if (z != null && z.length() == SpatialAuto64.SIZE * SpatialAuto64.SIZE) {
            e.autoZones = new float[z.length()];
            for (int i = 0; i < z.length(); i++) e.autoZones[i] = (float) z.optDouble(i, 0);
        }
        e.autoStrength = f(o, "autoStrength", 100);
        e.autoLowKey = o.optBoolean("autoLowKey");
        e.autoAtmosphere = o.optString("autoAtmosphere", "");
        e.autoMacroProtected = o.optBoolean("autoMacro");
        e.autoImportantRegion = o.optBoolean("autoImportant");
        e.autoBlackProtected = o.optBoolean("autoBlack");
        e.autoExposure = f(o, "autoExposure", 0); e.autoContrast = f(o, "autoContrast", 0);
        e.autoHighlights = f(o, "autoHighlights", 0); e.autoShadows = f(o, "autoShadows", 0);
        e.luminanceNR = f(o, "lumNR", 0); e.colorNR = f(o, "colorNR", 0); e.sharpen = f(o, "sharpen", 0);
        e.clarity = f(o, "clarity", 0); e.texture = f(o, "texture", 0); e.dehaze = f(o, "dehaze", 0);
        e.wbRed = f(o, "wbRed", 1); e.wbGreen = f(o, "wbGreen", 1); e.wbBlue = f(o, "wbBlue", 1);
        e.wbConfidence = f(o, "wbConfidence", 0); e.wbPicked = o.optBoolean("wbPicked");
        e.cropLeft = f(o, "cropL", 0); e.cropTop = f(o, "cropT", 0);
        e.cropRight = f(o, "cropR", 1); e.cropBottom = f(o, "cropB", 1);
        e.rescueEv = f(o, "rEv", 0); e.rescueGamma = f(o, "rGamma", 1);
        e.rescueBlack = f(o, "rBlack", 0); e.rescueWhite = f(o, "rWhite", 1);
        e.rescueCastR = f(o, "rCastR", 1); e.rescueCastG = f(o, "rCastG", 1); e.rescueCastB = f(o, "rCastB", 1);
        e.rescueVibrance = f(o, "rVib", 0);
        return e;
    }
}
