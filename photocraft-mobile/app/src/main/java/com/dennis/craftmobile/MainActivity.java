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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
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
    private static final int BG = Color.rgb(8, 12, 19);
    private static final int PANEL = Color.rgb(13, 21, 32);
    private static final int ACCENT = Color.rgb(36, 136, 255);

    private enum Mode { LOOKS, ADJUST, CROP }
    private static final class Edit {
        float exposure, contrast, highlights, shadows, saturation, warmth, tint;
        float ratio = 0f;
        int rotation = 0;
        String look = "Original";
        float[] autoZones;  // Immutable zone map measured on original preview.
        float autoStrength = 100f;
        Edit copy() {
            Edit d = new Edit();
            d.exposure = exposure; d.contrast = contrast; d.highlights = highlights;
            d.shadows = shadows; d.saturation = saturation; d.warmth = warmth;
            d.tint = tint; d.ratio = ratio; d.rotation = rotation; d.look = look;
            d.autoZones = autoZones; d.autoStrength = autoStrength;
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
    private int jpegQuality = 95;

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
        // Android 15/16 enforce edge-to-edge; keep toolbar and tabs inside safe insets.
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
        } else {
            root.setFitsSystemWindows(true);
        }
        setContentView(root);
        root.requestApplyInsets();

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(3), dp(8), dp(3));
        bar.setBackgroundColor(Color.rgb(13, 20, 30));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(62)));
        toolbar(bar, "＋", this::openImage);
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView brandTop = label("DENNIS CRAFT", 13, Color.WHITE);
        brandTop.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        brandTop.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        TextView brandSub = label("MOBILE PHOTO STUDIO", 9, Color.rgb(112, 163, 222));
        brandSub.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        brand.addView(brandTop, new LinearLayout.LayoutParams(-1, dp(22)));
        brand.addView(brandSub, new LinearLayout.LayoutParams(-1, dp(15)));
        bar.addView(brand, new LinearLayout.LayoutParams(0, -1, 1));
        TextView compare = label("◫", 24, Color.WHITE);
        compare.setContentDescription("Hold to compare with the original");
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
        toolbar(bar, "↶", this::undo);
        toolbar(bar, "↷", this::redo);
        toolbar(bar, "⇩", this::exportDialog);

        canvas = new FrameLayout(this);
        root.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1));
        photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photo.setBackgroundColor(BG);
        canvas.addView(photo, new FrameLayout.LayoutParams(-1, -1));
        openPlaceholder = label("◉\n\nDENNIS CRAFT MOBILE\n\nProfessional photo editing, offline.\n\n＋  TAP TO OPEN PHOTO", 18, Color.rgb(212, 229, 245));
        openPlaceholder.setOnClickListener(v -> openImage());
        canvas.addView(openPlaceholder, new FrameLayout.LayoutParams(-1, -1));

        status = label("OFFLINE  •  ORIGINAL PRESERVED  •  JPEG Q95", 10, Color.rgb(148, 173, 198));
        status.setBackgroundColor(Color.rgb(12, 21, 32));
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(24)));

        controls = new LinearLayout(this);
        controls.setBackgroundColor(PANEL);
        controls.setOrientation(LinearLayout.VERTICAL);
        root.addView(controls, new LinearLayout.LayoutParams(-1, dp(189)));

        tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setBackgroundColor(Color.rgb(9, 16, 25));
        root.addView(tabs, new LinearLayout.LayoutParams(-1, dp(72)));
        drawTabs(); drawControls();
    }

    private void toolbar(LinearLayout row, String title, Runnable callback) {
        TextView v = label(title, 24, Color.rgb(231, 242, 255));
        v.setContentDescription(title.equals("⇩") ? "Export image" :
                title.equals("＋") ? "Choose photo" : title);
        v.setOnClickListener(x -> callback.run());
        row.addView(v, new LinearLayout.LayoutParams(dp(41), dp(52)));
    }

    private void drawTabs() {
        tabs.removeAllViews();
        tab("Looks", "◉", mode == Mode.LOOKS, () -> selectMode(Mode.LOOKS));
        tab("Adjust", "☷", mode == Mode.ADJUST, () -> selectMode(Mode.ADJUST));
        tab("Auto", "✦", false, this::autoTune);
        tab("Crop", "⌗", mode == Mode.CROP, () -> selectMode(Mode.CROP));
        tab("More", "•••", false, this::more);
    }

    private void selectMode(Mode newMode) {
        mode = newMode;
        drawTabs();
        drawControls();
    }

    private void tab(String name, String glyph, boolean active, Runnable callback) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setGravity(Gravity.CENTER);
        int tint = active ? ACCENT : Color.rgb(179, 195, 213);
        TextView icon = label(glyph, 22, tint);
        TextView caption = label(name, 11, tint);
        group.addView(icon, new LinearLayout.LayoutParams(-1, dp(31)));
        group.addView(caption, new LinearLayout.LayoutParams(-1, dp(22)));
        group.setContentDescription(name);
        group.setOnClickListener(v -> callback.run());
        tabs.addView(group, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private void panelTitle(String heading, String description) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(16), dp(3), dp(14), dp(2));
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(heading, 13, Color.WHITE);
        title.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        row.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        TextView info = label(description, 11, Color.rgb(144, 164, 186));
        row.addView(info, new LinearLayout.LayoutParams(-2, -1));
        controls.addView(row, new LinearLayout.LayoutParams(-1, dp(40)));
    }

    private Edit presetFor(String name) {
        Edit v = new Edit();
        v.look = name;
        switch (name) {
            case "Natural": v.contrast = 9; v.shadows = 10; v.saturation = 5; break;
            case "Warm": v.contrast = 7; v.warmth = 24; v.saturation = 9; break;
            case "Cool": v.contrast = 8; v.warmth = -25; v.saturation = 4; break;
            case "Dramatic": v.contrast = 30; v.highlights = -24;
                v.shadows = -12; v.saturation = -8; break;
            case "B&W": v.contrast = 15; v.saturation = -100; break;
        }
        return v;
    }

    private void addHorizontalChoices(String[] names, java.util.function.Consumer<String> consumer) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setPadding(dp(11), dp(2), dp(11), 0);
        strip.setGravity(Gravity.TOP);
        scroll.addView(strip);
        Bitmap tiny = (previewSource != null && mode == Mode.LOOKS) ?
                Bitmap.createScaledBitmap(previewSource, 120, 90, true) : null;
        for (String name : names) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(3), dp(3), dp(3), dp(3));
            boolean active = name.equals(edit.look) && mode == Mode.LOOKS;
            GradientDrawable border = background(
                    active ? Color.rgb(19, 60, 105) : Color.rgb(26, 39, 56), 11);
            border.setStroke(dp(active ? 2 : 1),
                    active ? ACCENT : Color.rgb(44, 61, 82));
            cell.setBackground(border);
            if (tiny != null) {
                ImageView swatch = new ImageView(this);
                swatch.setImageBitmap(process(tiny, presetFor(name)));
                swatch.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cell.addView(swatch, new LinearLayout.LayoutParams(dp(88), dp(75)));
            } else {
                TextView glyph = label(mode == Mode.LOOKS ? "◉" : "⌗", 24,
                        Color.rgb(118, 167, 220));
                glyph.setBackground(background(Color.rgb(35, 56, 79), 8));
                cell.addView(glyph, new LinearLayout.LayoutParams(dp(88), dp(75)));
            }
            TextView caption = label(name, 11,
                    active ? Color.WHITE : Color.rgb(183, 200, 217));
            cell.addView(caption, new LinearLayout.LayoutParams(dp(88), dp(30)));
            cell.setOnClickListener(v -> consumer.accept(name));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(94), dp(114));
            lp.setMargins(dp(3), 0, dp(3), 0);
            strip.addView(cell, lp);
        }
        controls.addView(scroll, new LinearLayout.LayoutParams(-1, dp(139)));
    }

    private void addActionStrip() {
        LinearLayout strip = new LinearLayout(this);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(4), dp(3), dp(4), dp(3));
        action(strip, "Looks", () -> { mode = Mode.LOOKS; drawTabs(); drawControls(); }, false);
        action(strip, "AUTO", this::autoTune, true);
        action(strip, "Adjust", () -> { mode = Mode.ADJUST; drawTabs(); drawControls(); }, false);
        action(strip, "Crop", () -> { mode = Mode.CROP; drawTabs(); drawControls(); }, false);
        action(strip, "Q" + jpegQuality, this::chooseQuality, false);
        action(strip, "Export", () -> save(false), false);
        controls.addView(strip, new LinearLayout.LayoutParams(-1, dp(52)));
    }

    private void action(LinearLayout row, String name, Runnable onClick, boolean primary) {
        TextView button = label(name, 12, Color.WHITE);
        button.setBackground(background(primary ? ACCENT : Color.rgb(42, 46, 55), 8));
        button.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(43), 1);
        p.setMargins(dp(2), 0, dp(2), 0);
        row.addView(button, p);
    }

    private void exportDialog() {
        if (input == null) { openImage(); return; }
        String[] options = {"85 · Smaller file", "90 · High quality",
                "95 · Very high quality", "100 · Maximum JPEG quality"};
        int selected = jpegQuality == 85 ? 0 : jpegQuality == 90 ? 1 :
                jpegQuality == 100 ? 3 : 2;
        new AlertDialog.Builder(this)
                .setTitle("Export JPEG")
                .setSingleChoiceItems(options, selected, (dialog, position) -> {
                    jpegQuality = new int[]{85, 90, 95, 100}[position];
                    status.setText("READY TO EXPORT  •  JPEG Q" + jpegQuality);
                })
                .setPositiveButton("SAVE PHOTO", (dialog, which) -> save(false))
                .setNeutralButton("SHARE", (dialog, which) -> save(true))
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void chooseQuality() {
        String[] options = {"85 · Smaller file", "90 · High quality",
                "95 · Very high (default)", "100 · Maximum JPEG quality"};
        int selected = jpegQuality == 85 ? 0 : jpegQuality == 90 ? 1 : jpegQuality == 100 ? 3 : 2;
        new AlertDialog.Builder(this)
                .setTitle("JPEG export quality")
                .setSingleChoiceItems(options, selected, (dialog, which) -> {
                    jpegQuality = new int[]{85, 90, 95, 100}[which];
                    dialog.dismiss();
                    drawControls();
                    status.setText("Export: JPEG quality " + jpegQuality +
                            " · Maximum quality is not lossless");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void drawControls() {
        controls.removeAllViews();
        if (mode == Mode.LOOKS) {
            panelTitle("LOOKS", "Tap a style to apply");
            addHorizontalChoices(new String[]{"Original", "Natural", "Warm", "Cool", "Dramatic", "B&W"},
                    this::applyLook);
        } else if (mode == Mode.ADJUST) {
            panelTitle("ADJUSTMENTS", "Fine tune your photo");
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout options = new LinearLayout(this);
            String[] fields = "Auto".equals(edit.look) ?
                    new String[]{"Auto Strength", "Exposure", "Contrast", "Highlights",
                            "Shadows", "Saturation", "Warmth", "Tint"} :
                    new String[]{"Exposure", "Contrast", "Highlights", "Shadows",
                            "Saturation", "Warmth", "Tint"};
            for (String field : fields) {
                TextView v = label(field, 12, field.equals(activeAdjustment) ? ACCENT : Color.LTGRAY);
                v.setPadding(dp(12), dp(7), dp(12), dp(7));
                v.setOnClickListener(x -> { activeAdjustment = field; drawControls(); });
                options.addView(v);
            }
            scroll.addView(options);
            controls.addView(scroll, new LinearLayout.LayoutParams(-1, dp(43)));
            showSlider();
        } else {
            panelTitle("CROP & ROTATE", "Non-destructive preview");
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
        boolean strengthSlider = activeAdjustment.equals("Auto Strength");
        float range = activeAdjustment.equals("Exposure") ? 2f : 100f;
        float original = getField(activeAdjustment);
        slider.setProgress(strengthSlider ? Math.round(original * 10f) :
                Math.round((original / range + 1) * 500f));
        value.setText(strengthSlider ? "Auto Strength: " + Math.round(original) + "%" :
                activeAdjustment + ": " + String.format(Locale.US,
                        activeAdjustment.equals("Exposure") ? "%+.2f EV" : "%+.0f", original));
        Edit starting = edit.copy();
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || previewSource == null) return;
                float result = strengthSlider ? progress / 10f :
                        ((float) progress / 500 - 1) * range;
                setField(activeAdjustment, result);
                value.setText(strengthSlider ? "Auto Strength: " + Math.round(result) + "%" :
                        activeAdjustment + ": " + String.format(Locale.US,
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
            case "Auto Strength": return edit.autoStrength;
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
            case "Auto Strength": edit.autoStrength = clamp(value, 0f, 100f); break;
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
        Bitmap original = previewSource;
        if (original == null) {
            Toast.makeText(this, "Open a photo before using Auto", Toast.LENGTH_SHORT).show();
            openImage();
            return;
        }
        int serial = generation.incrementAndGet();
        status.setText("AUTO  •  Analysing 64×64 scene zones…");
        work.execute(() -> {
            try {
                SpatialAuto64.Scene scene = SpatialAuto64.analyse(original);
                ui.post(() -> {
                    if (serial != generation.get() || previewSource != original) return;
                    checkpoint();
                    edit.exposure = scene.exposure;
                    edit.contrast = scene.contrast;
                    edit.highlights = scene.highlights;
                    edit.shadows = scene.shadows;
                    edit.saturation = 0f;
                    edit.warmth = 0f;
                    edit.tint = 0f;
                    edit.autoZones = scene.zones;
                    edit.autoStrength = 100f;
                    edit.look = "Auto";
                    activeAdjustment = "Auto Strength";
                    render();
                    drawControls();
                    Toast.makeText(this, String.format(Locale.US,
                            "64×64 Auto: %+.2f EV · bright pixels protected",
                            scene.exposure), Toast.LENGTH_LONG).show();
                });
            } catch (RuntimeException | OutOfMemoryError failure) {
                showError("Auto analysis failed: " + failure.getMessage());
            }
        });
    }

    private static int percentile(int[] hist, int n, float f) {
        int count=0; for (int i=0;i<256;i++) { count+=hist[i]; if (count >= n*f) return i; }
        return 128;
    }

    private void openImage() {
        // Android 13+ has the system photo picker built in; no storage permissions.
        Intent pick;
        if (Build.VERSION.SDK_INT >= 33) {
            pick = new Intent(MediaStore.ACTION_PICK_IMAGES);
            pick.setType("image/*");
        } else {
            // Compatible fallback when the platform Photo Picker is unavailable.
            pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("image/*");
        }
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(pick, OPEN_IMAGE);
        } catch (android.content.ActivityNotFoundException ex) {
            Intent legacy = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            legacy.addCategory(Intent.CATEGORY_OPENABLE);
            legacy.setType("image/*");
            startActivityForResult(legacy, OPEN_IMAGE);
        }
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
                    if ("Auto".equals(snapshot.look) && snapshot.autoZones != null) {
                        status.setText(String.format(Locale.US,
                                "64×64 AUTO  •  %+.2f EV  •  Strength %.0f%%  •  Highlights safe",
                                snapshot.exposure, snapshot.autoStrength));
                    } else {
                        status.setText("Live preview · JPEG export Q" + jpegQuality);
                    }
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
        boolean spatial = "Auto".equals(e.look) && e.autoZones != null &&
                e.autoZones.length == SpatialAuto64.SIZE * SpatialAuto64.SIZE;
        int[] zoneX = null;
        float[] fractionX = null, rowZones = null;
        float strength = clamp(e.autoStrength / 100f, 0f, 1f);
        float autoGain = ((float)Math.pow(2, e.exposure) - 1f) * strength;
        float autoShadow = e.shadows / 100f * .24f * strength;
        float autoHighlights = e.highlights / 100f * .13f * strength;
        float autoContrast = e.contrast / 100f * .22f * strength;
        if (spatial) {
            zoneX = SpatialAuto64.xIndices(w);
            fractionX = SpatialAuto64.xFractions(w);
            rowZones = new float[SpatialAuto64.SIZE];
        }
        for (int y=0; y<h; y++) {
            if (spatial) SpatialAuto64.interpolateRow(e.autoZones, y, h, rowZones);
            src.getPixels(pixels, 0, w, 0, y, w, 1);
            for (int x=0; x<w; x++) {
                int pixel = pixels[x];
                if (spatial) {
                    float r0 = Color.red(pixel) / 255f;
                    float g0 = Color.green(pixel) / 255f;
                    float b0 = Color.blue(pixel) / 255f;
                    float lum = .2126f*r0 + .7152f*g0 + .0722f*b0;
                    float maxChannel = Math.max(r0, Math.max(g0, b0));
                    float xf = fractionX[x];
                    int zi = zoneX[x];
                    float zone = rowZones[zi]*(1f-xf) +
                            rowZones[Math.min(SpatialAuto64.SIZE-1,zi+1)]*xf;
                    float protection = 1f - SpatialAuto64.smoothstep(.64f,.94f,maxChannel);
                    float darkness = 1f - SpatialAuto64.smoothstep(.12f,.68f,lum);
                    float highlights = SpatialAuto64.smoothstep(.61f,.94f,lum);
                    float requested = lum*(1f + autoGain*protection)
                            + autoShadow*zone*darkness
                            + autoHighlights*highlights
                            + autoContrast*(lum-.5f)*(1f-highlights);
                    requested = clamp(requested,0f,1f);
                    float scale = lum > .012f ? requested/lum : 1f;
                    // Leave pristine pixels untouched when strength=0.
                    if (scale > 1f && maxChannel > 0f) {
                        scale = Math.min(scale, .997f/maxChannel);
                    }
                    pixels[x] = Color.argb(Color.alpha(pixel),
                            quant(r0*scale),quant(g0*scale),quant(b0*scale));
                    continue;
                }
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
        Uri toLoad = input; Edit snapshot = edit.copy(); final int quality = jpegQuality;
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
                    if (stream == null || !result.compress(Bitmap.CompressFormat.JPEG, quality, stream))
                        throw new IllegalStateException("JPEG export failed");
                }
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(saved, values, null, null);
                if (result != original) result.recycle();
                original.recycle();
                Uri finished = saved;
                ui.post(() -> {
                    status.setText("Saved JPEG Q" + quality + " to Pictures/Dennis Craft Mobile");
                    Toast.makeText(this, "Exported JPEG Q" + quality, Toast.LENGTH_LONG).show();
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
        String[] options = {"Modern photo picker", "Export / share", "JPEG quality", "Reset edits",
                "About / limitations"};
        new AlertDialog.Builder(this).setTitle("Dennis Craft Mobile")
                .setItems(options, (dialog, which) -> {
                    if (which==0) openImage();
                    if (which==1) exportDialog();
                    if (which==2) chooseQuality();
                    if (which==3 && previewSource != null) {
                        checkpoint(); edit = new Edit(); render(); drawControls();
                    }
                    if (which==4) about();
                }).show();
    }

    private void about() {
        new AlertDialog.Builder(this).setTitle("Dennis Craft Mobile · v0.4")
                .setMessage("Offline native Android editing prototype.\n\n" +
                        "Working: modern Android photo picker, JPEG/HEIC, styled looks, " +
                        "tone/colour adjustments, 64×64 Auto with clipped-highlights guard, centre crop, rotate, " +
                        "undo/redo, press-and-hold before view, export and share.\n\n" +
                        "Limitations: no healing, local masks, layers or RAW workflow yet. " +
                        "PhotoCraft's Rust engine is NOT integrated in this version. " +
                        "Export is selectable JPEG 85/90/95/100 (not lossless), capped at 8192px / 50MP and does not " +
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
