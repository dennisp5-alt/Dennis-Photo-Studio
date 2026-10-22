package com.dennis.craftmobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    private static final int OPEN_IMAGE = 701;
    private static final int BG = Color.rgb(11, 12, 15);
    private static final int PANEL = Color.rgb(20, 23, 29);
    private static final int ACCENT = Color.rgb(40, 132, 242);

    private enum Mode { LOOKS, ADJUST, CROP }
    private static final class Edit {
        float exposure, contrast, highlights, shadows, saturation, warmth, tint;
        float ratio = 0f;
        int rotation = 0;
        String look = "Original";
        Edit copy() {
            Edit d = new Edit();
            d.exposure = exposure; d.contrast = contrast; d.highlights = highlights;
            d.shadows = shadows; d.saturation = saturation; d.warmth = warmth;
            d.tint = tint; d.ratio = ratio; d.rotation = rotation; d.look = look;
            return d;
        }
    }

    private Uri input;
    private Bitmap previewSource, renderedPreview;
    private Edit edit = new Edit();
    private final ArrayDeque<Edit> undo = new ArrayDeque<>();
    private final ArrayDeque<Edit> redo = new ArrayDeque<>();
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private LinearLayout controls, tabs;
    private FrameLayout canvas;
    private ImageView photo;
    private TextView openPlaceholder, status;
    private Mode mode = Mode.LOOKS;
    private String activeAdjustment = "Exposure";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Window w = getWindow();
        w.setStatusBarColor(Color.BLACK);
        w.setNavigationBarColor(Color.BLACK);
        w.getDecorView().setSystemUiVisibility(0);
        buildLayout();
    }

    private int dp(float value) { return (int) (getResources().getDisplayMetrics().density * value + .5f); }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }

    private TextView label(String text, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(text); v.setTextSize(sp); v.setTextColor(color);
        v.setGravity(Gravity.CENTER); v.setIncludeFontPadding(false); return v;
    }

    private TextView touchButton(String text, Runnable action) {
        TextView v = label(text, 15, Color.WHITE);
        v.setBackground(background(Color.rgb(39, 43, 51), 10));
        v.setPadding(dp(13), dp(11), dp(13), dp(11));
        v.setOnClickListener(x -> action.run());
        return v;
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        setContentView(root);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(3), dp(6), dp(3));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(58)));
        toolbar(bar, "Open", this::openImage);
        toolbar(bar, "?", this::about);
        toolbar(bar, "Auto", this::autoTune);
        TextView compare = label("Hold", 12, Color.WHITE);
        compare.setOnTouchListener((v, event) -> {
            if (previewSource == null) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                photo.setImageBitmap(previewSource); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP ||
                    event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                if (renderedPreview != null) photo.setImageBitmap(renderedPreview);
                return true;
            }
            return true;
        });
        bar.addView(compare, new LinearLayout.LayoutParams(0, dp(48), 1));
        toolbar(bar, "Undo", this::undo);
        toolbar(bar, "Redo", this::redo);
        toolbar(bar, "Save", () -> save(false));

        canvas = new FrameLayout(this);
        root.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1));
        photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photo.setBackgroundColor(BG);
        canvas.addView(photo, new FrameLayout.LayoutParams(-1, -1));
        openPlaceholder = label("DENNIS CRAFT MOBILE\n\nOpen a photo to start editing\n\nTap here", 19, Color.LTGRAY);
        openPlaceholder.setOnClickListener(v -> openImage());
        canvas.addView(openPlaceholder, new FrameLayout.LayoutParams(-1, -1));

        status = label("Offline · Original preserved", 11, Color.LTGRAY);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(24)));

        controls = new LinearLayout(this);
        controls.setBackgroundColor(PANEL);
        controls.setOrientation(LinearLayout.VERTICAL);
        root.addView(controls, new LinearLayout.LayoutParams(-1, dp(156)));

        tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setBackgroundColor(Color.rgb(13, 16, 20));
        root.addView(tabs, new LinearLayout.LayoutParams(-1, dp(69)));
        drawTabs(); drawControls();
    }

    private void toolbar(LinearLayout row, String title, Runnable callback) {
        TextView v = label(title, 12, Color.WHITE);
        v.setOnClickListener(x -> callback.run());
        row.addView(v, new LinearLayout.LayoutParams(0, dp(48), 1));
    }

    private void drawTabs() {
        tabs.removeAllViews();
        tab("Looks", Mode.LOOKS);
        tab("Adjust", Mode.ADJUST);
        tab("Crop", Mode.CROP);
        TextView more = label("More", 14, Color.LTGRAY);
        more.setOnClickListener(x -> more());
        tabs.addView(more, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private void tab(String name, Mode selection) {
        TextView v = label(name, 14, mode == selection ? ACCENT : Color.LTGRAY);
        v.setOnClickListener(x -> { mode = selection; drawTabs(); drawControls(); });
        tabs.addView(v, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private void addHorizontalChoices(String[] names, java.util.function.Consumer<String> consumer) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(10), dp(8), dp(10), dp(8));
        scroll.addView(strip);
        for (String name : names) {
            TextView choice = touchButton(name, () -> consumer.accept(name));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(58));
            lp.setMargins(dp(4), 0, dp(4), 0);
            strip.addView(choice, lp);
        }
        controls.addView(scroll, new LinearLayout.LayoutParams(-1, dp(83)));
    }

    private void drawControls() {
        controls.removeAllViews();
        if (mode == Mode.LOOKS) {
            controls.addView(label("LOOKS  •  Original / Natural / Warm / Cool / Dramatic / B&W", 12,
                    Color.LTGRAY), new LinearLayout.LayoutParams(-1, dp(38)));
            addHorizontalChoices(new String[]{"Original", "Natural", "Warm", "Cool", "Dramatic", "B&W"},
                    this::applyLook);
        } else if (mode == Mode.ADJUST) {
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout options = new LinearLayout(this);
            for (String field : new String[]{"Exposure", "Contrast", "Highlights", "Shadows",
                    "Saturation", "Warmth", "Tint"}) {
                TextView v = label(field, 12, field.equals(activeAdjustment) ? ACCENT : Color.LTGRAY);
                v.setPadding(dp(12), dp(7), dp(12), dp(7));
                v.setOnClickListener(x -> { activeAdjustment = field; drawControls(); });
                options.addView(v);
            }
            scroll.addView(options);
            controls.addView(scroll, new LinearLayout.LayoutParams(-1, dp(46)));
            showSlider();
        } else {
            controls.addView(label("CROP  •  Centre crop for now", 13, Color.LTGRAY),
                    new LinearLayout.LayoutParams(-1, dp(38)));
            addHorizontalChoices(new String[]{"Original", "1:1", "4:5", "16:9", "Rotate 90°"},
                    choice -> {
                        if (previewSource == null) { openImage(); return; }
                        checkpoint();
                        switch (choice) {
                            case "1:1": edit.ratio = 1; break;
                            case "4:5": edit.ratio = .8f; break;
                            case "16:9": edit.ratio = 16f/9f; break;
                            case "Rotate 90°": edit.rotation = (edit.rotation + 90) % 360; break;
                            default: edit.ratio = 0;
                        }
                        render();
                    });
        }
    }

    private void showSlider() {
        LinearLayout line = new LinearLayout(this);
        line.setPadding(dp(14), dp(2), dp(14), 0);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setOrientation(LinearLayout.VERTICAL);
        TextView value = label("", 12, Color.WHITE);
        line.addView(value, new LinearLayout.LayoutParams(-1, dp(24)));
        SeekBar slider = new SeekBar(this);
        slider.setMax(1000);
        float range = activeAdjustment.equals("Exposure") ? 2f : 100f;
        float original = getField(activeAdjustment);
        slider.setProgress(Math.round((original / range + 1) * 500f));
        value.setText(activeAdjustment + ": " + String.format(Locale.US,
                activeAdjustment.equals("Exposure") ? "%+.2f EV" : "%+.0f", original));
        Edit starting = edit.copy();
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || previewSource == null) return;
                float result = ((float) progress / 500 - 1) * range;
                setField(activeAdjustment, result);
                value.setText(activeAdjustment + ": " + String.format(Locale.US,
                        activeAdjustment.equals("Exposure") ? "%+.2f EV" : "%+.0f", result));
                render();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (previewSource != null && getField(activeAdjustment) != original) {
                    undo.addLast(starting);
                    if (undo.size() > 35) undo.removeFirst();
                    redo.clear();
                }
            }
        });
        line.addView(slider, new LinearLayout.LayoutParams(-1, dp(52)));
        controls.addView(line);
    }

    private float getField(String which) {
        switch (which) {
            case "Exposure": return edit.exposure;
            case "Contrast": return edit.contrast;
            case "Highlights": return edit.highlights;
            case "Shadows": return edit.shadows;
            case "Saturation": return edit.saturation;
            case "Warmth": return edit.warmth;
            default: return edit.tint;
        }
    }
    private void setField(String which, float value) {
        switch (which) {
            case "Exposure": edit.exposure = value; break;
            case "Contrast": edit.contrast = value; break;
            case "Highlights": edit.highlights = value; break;
            case "Shadows": edit.shadows = value; break;
            case "Saturation": edit.saturation = value; break;
            case "Warmth": edit.warmth = value; break;
            default: edit.tint = value;
        }
    }

    private void applyLook(String name) {
        if (previewSource == null) { openImage(); return; }
        checkpoint();
        Edit v = new Edit();
        v.rotation = edit.rotation; v.ratio = edit.ratio; v.look = name;
        switch (name) {
            case "Natural": v.contrast = 9; v.shadows = 10; v.saturation = 5; break;
            case "Warm": v.contrast = 7; v.warmth = 24; v.saturation = 9; break;
            case "Cool": v.contrast = 8; v.warmth = -25; v.saturation = 4; break;
            case "Dramatic": v.contrast = 30; v.highlights = -24; v.shadows = -12;
                v.saturation = -8; break;
            case "B&W": v.contrast = 15; v.saturation = -100; break;
        }
        edit = v; render();
    }

    private void checkpoint() {
        undo.addLast(edit.copy());
        if (undo.size() > 35) undo.removeFirst();
        redo.clear();
    }
    private void undo() {
        if (undo.isEmpty()) return;
        redo.addLast(edit.copy()); edit = undo.removeLast(); render(); drawControls();
    }
    private void redo() {
        if (redo.isEmpty()) return;
        undo.addLast(edit.copy()); edit = redo.removeLast(); render(); drawControls();
    }

    private void autoTune() {
        if (previewSource == null) { openImage(); return; }
        checkpoint();
        int[] hist = new int[256];
        int width = previewSource.getWidth(), height = previewSource.getHeight();
        int stride = Math.max(1, (int)Math.sqrt((double)width * height / 18000));
        int sampled = 0;
        for (int y=0; y<height; y+=stride) for (int x=0; x<width; x+=stride) {
            int pixel = previewSource.getPixel(x, y);
            int l = Math.round(.2126f*Color.red(pixel)+.7152f*Color.green(pixel)+
                    .0722f*Color.blue(pixel));
            hist[Math.min(255, Math.max(0, l))]++; sampled++;
        }
        int low = percentile(hist, sampled, .05f);
        int middle = percentile(hist, sampled, .50f);
        int high = percentile(hist, sampled, .95f);
        // Deliberately conservative Auto: preserves bright clouds better than fixed auto-levels.
        edit.exposure = clamp((float)(Math.log(.44 / Math.max(.08, middle/255.0)) /
                Math.log(2)), -.85f, .85f);
        edit.contrast = clamp((170-(high-low)) * .13f, -14, 19);
        edit.shadows = low < 38 ? 20 : (low < 65 ? 10 : 0);
        edit.highlights = high > 230 ? -22 : (high > 210 ? -9 : 0);
        edit.look = "Auto";
        render(); drawControls();
    }
    private static int percentile(int[] hist, int n, float f) {
        int count=0; for (int i=0;i<256;i++) { count+=hist[i]; if (count >= n*f) return i; }
        return 128;
    }

    private void openImage() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(pick, OPEN_IMAGE);
    }
    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != OPEN_IMAGE || result != RESULT_OK || data == null ||
                data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        status.setText("Opening…");
        int serial = generation.incrementAndGet();
        work.execute(() -> {
            try {
                Bitmap resultBitmap = decode(uri, 2000, 4_000_000);
                ui.post(() -> {
                    if (serial != generation.get()) return;
                    input = uri; previewSource = resultBitmap;
                    edit = new Edit(); undo.clear(); redo.clear();
                    openPlaceholder.setVisibility(View.GONE);
                    render(); drawControls();
                });
            } catch (Exception | OutOfMemoryError ex) { showError("Cannot open this image: " + ex.getMessage()); }
        });
    }

    private Bitmap decode(Uri uri, int dimension, long pixelLimit) throws Exception {
        ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            double s = Math.min(1.0, Math.min((double)dimension / Math.max(w,h),
                    Math.sqrt((double)pixelLimit / ((double)w*h))));
            if (s < 1) decoder.setTargetSize(Math.max(1, (int)Math.round(w*s)),
                    Math.max(1, (int)Math.round(h*s)));
        });
    }

    private void render() {
        Bitmap source = previewSource;
        if (source == null) return;
        Edit snapshot = edit.copy();
        int serial = generation.incrementAndGet();
        status.setText("Rendering…");
        work.execute(() -> {
            if (serial != generation.get()) return;
            try {
                Bitmap output = process(source, snapshot);
                ui.post(() -> {
                    if (serial != generation.get()) return;
                    renderedPreview = output; photo.setImageBitmap(output);
                    status.setText("Live preview · Full-quality JPEG export");
                });
            } catch (OutOfMemoryError ex) { showError("Not enough memory for preview"); }
        });
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
    private static int quant(float value) {
        return (int)(255 * clamp(value,0,1) + .5f);
    }

    private static Bitmap process(Bitmap src, Edit e) {
        int w = src.getWidth(), h = src.getHeight();
        Bitmap output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[w];
        float multiplier = (float)Math.pow(2, e.exposure);
        float contrast = 1f + e.contrast / 100f * .85f;
        float saturation = 1f + e.saturation / 100f;
        float warmth = e.warmth / 100f * .11f;
        float tint = e.tint / 100f * .07f;
        float high = e.highlights / 100f * .30f;
        float shadow = e.shadows / 100f * .30f;
        for (int y=0; y<h; y++) {
            src.getPixels(pixels, 0, w, 0, y, w, 1);
            for (int x=0; x<w; x++) {
                int pixel = pixels[x];
                float r = Color.red(pixel)/255f * multiplier;
                float g = Color.green(pixel)/255f * multiplier;
                float b = Color.blue(pixel)/255f * multiplier;
                float l = .2126f*r + .7152f*g + .0722f*b;
                float s = clamp(l, 0f, 1f);
                float correction = shadow*(1f-s)*(1f-s) + high*s*s;
                r = (r-.5f)*contrast + .5f + correction + warmth + tint;
                g = (g-.5f)*contrast + .5f + correction - tint;
                b = (b-.5f)*contrast + .5f + correction - warmth + tint;
                float gray = .2126f*r + .7152f*g + .0722f*b;
                r = gray + (r-gray)*saturation;
                g = gray + (g-gray)*saturation;
                b = gray + (b-gray)*saturation;
                pixels[x] = Color.argb(Color.alpha(pixel), quant(r), quant(g), quant(b));
            }
            output.setPixels(pixels, 0, w, 0, y, w, 1);
        }
        Bitmap rotated = output;
        if (e.rotation != 0) {
            Matrix m = new Matrix(); m.postRotate(e.rotation);
            rotated = Bitmap.createBitmap(output, 0, 0, w, h, m, true);
            if (rotated != output) output.recycle();
        }
        if (e.ratio <= 0) return rotated;
        int width = rotated.getWidth(), height = rotated.getHeight();
        int cw = width, ch = height;
        if (width / (float)height > e.ratio) cw = Math.max(1, Math.round(height*e.ratio));
        else ch = Math.max(1, Math.round(width/e.ratio));
        Bitmap cropped = Bitmap.createBitmap(rotated, (width-cw)/2, (height-ch)/2, cw, ch);
        if (cropped != rotated) rotated.recycle();
        return cropped;
    }

    private void save(boolean share) {
        if (input == null) { openImage(); return; }
        Uri toLoad = input; Edit snapshot = edit.copy();
        status.setText("Exporting image…");
        work.execute(() -> {
            Uri saved = null;
            try {
                // 8192 pixels / 50 MP guard limits memory spikes on very large camera files.
                Bitmap original = decode(toLoad, 8192, 50_000_000);
                Bitmap result = process(original, snapshot);
                String name = "DCS_" + new SimpleDateFormat("yyyyMMdd_HHmmss",
                        Locale.US).format(new Date()) + ".jpg";
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Dennis Craft Mobile");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                saved = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (saved == null) throw new IllegalStateException("MediaStore create failed");
                try (OutputStream stream = getContentResolver().openOutputStream(saved)) {
                    if (stream == null || !result.compress(Bitmap.CompressFormat.JPEG, 95, stream))
                        throw new IllegalStateException("JPEG export failed");
                }
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(saved, values, null, null);
                if (result != original) result.recycle();
                original.recycle();
                Uri finished = saved;
                ui.post(() -> {
                    status.setText("Saved to Pictures/Dennis Craft Mobile");
                    Toast.makeText(this, "Saved to Pictures", Toast.LENGTH_LONG).show();
                    if (share) {
                        Intent intent = new Intent(Intent.ACTION_SEND);
                        intent.setType("image/jpeg");
                        intent.putExtra(Intent.EXTRA_STREAM, finished);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(intent, "Share photo"));
                    }
                });
            } catch (Exception | OutOfMemoryError ex) {
                if (saved != null) getContentResolver().delete(saved, null, null);
                showError("Export failed: " + ex.getMessage());
            }
        });
    }

    private void more() {
        String[] options = {"Open another photo", "Share edited photo", "Reset edits",
                "About / limitations"};
        new AlertDialog.Builder(this).setTitle("Dennis Craft Mobile")
                .setItems(options, (dialog, which) -> {
                    if (which==0) openImage();
                    if (which==1) save(true);
                    if (which==2 && previewSource != null) {
                        checkpoint(); edit = new Edit(); render(); drawControls();
                    }
                    if (which==3) about();
                }).show();
    }

    private void about() {
        new AlertDialog.Builder(this).setTitle("Dennis Craft Mobile · v0.1")
                .setMessage("Offline native Android editing prototype.\n\n" +
                        "Working: open JPEG/HEIC and other supported images, looks, " +
                        "tone/colour adjustments, simple Auto, centre crop, rotate, " +
                        "undo/redo, press-and-hold before view, export and share.\n\n" +
                        "Limitations: no healing, local masks, layers or RAW workflow yet. " +
                        "PhotoCraft's Rust engine is NOT integrated in this version. " +
                        "Export is JPEG 95, up to 8192px / 50MP and currently does not " +
                        "preserve original metadata. Editing never overwrites the source.")
                .setPositiveButton("Close", null).show();
    }

    private void showError(String message) {
        ui.post(() -> { status.setText(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show(); });
    }
    @Override protected void onDestroy() {
        generation.incrementAndGet(); work.shutdownNow(); super.onDestroy();
    }
}
