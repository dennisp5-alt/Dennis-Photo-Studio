package com.dennis.craftmobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.ClipData;
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
import android.view.GestureDetector;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

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
        float luminanceNR, colorNR, sharpen, clarity, texture, dehaze;
        // Colour gains estimated only when spatial neutral evidence is convincing.
        float wbRed = 1f, wbGreen = 1f, wbBlue = 1f, wbConfidence;
        Edit copy() {
            Edit d = new Edit();
            d.exposure = exposure; d.contrast = contrast; d.highlights = highlights;
            d.shadows = shadows; d.saturation = saturation; d.warmth = warmth;
            d.tint = tint; d.ratio = ratio; d.rotation = rotation; d.look = look;
            d.autoZones = autoZones; d.autoStrength = autoStrength;
            d.luminanceNR = luminanceNR; d.colorNR = colorNR; d.sharpen = sharpen;
            d.clarity = clarity; d.texture = texture; d.dehaze = dehaze;
            d.wbRed = wbRed; d.wbGreen = wbGreen; d.wbBlue = wbBlue;
            d.wbConfidence = wbConfidence;
            return d;
        }
    }

    private Uri input;
    private Bitmap previewSource, fastPreviewSource, renderedPreview;
    private Edit edit = new Edit();
    private final ArrayDeque<Edit> undo = new ArrayDeque<>();
    private final ArrayDeque<Edit> redo = new ArrayDeque<>();
    // Keep photo opening and saving independent of the preview renderer.
    // Repeated slider changes replace queued preview work rather than piling up.
    private final ThreadPoolExecutor previewWork = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    private final ThreadPoolExecutor openingWork = new ThreadPoolExecutor(
            1,1,0L,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>());
    private final ExecutorService savingWork = Executors.newSingleThreadExecutor();
    private final AtomicBoolean saving = new AtomicBoolean(false);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private Runnable pendingSliderRender;
    private LinearLayout controls, tabs;
    private FrameLayout canvas;
    private ImageView photo;
    private TextView openPlaceholder, status, photoCounter;
    // Only user-granted picker URIs. Edits are session-local, original files untouched.
    private final ArrayList<Uri> photoSelection = new ArrayList<>();
    private final HashMap<String,Edit> editsByPhoto = new HashMap<>();
    private int photoIndex = -1;
    private Mode mode = Mode.LOOKS;
    private String activeAdjustment = "Exposure";
    private int adjustGroup = 0;  // 0 Tone, 1 Noise+Sharpen, 2 Presence
    private static final String[] TONE_FIELDS =
            {"Exposure","Contrast","Highlights","Shadows","Saturation","Warmth","Tint"};
    private static final String[] DETAIL_FIELDS =
            {"Luminance NR","Color NR","Sharpen"};
    private static final String[] PRESENCE_FIELDS =
            {"Clarity","Texture","Dehaze"};
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
        GestureDetector swipeDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { return true; }
                    @Override public boolean onFling(MotionEvent start, MotionEvent end,
                            float velocityX, float velocityY) {
                        if (start==null || end==null || photoSelection.size()<2) return false;
                        float dx=end.getX()-start.getX(),dy=end.getY()-start.getY();
                        if(Math.abs(dx)<dp(80) || Math.abs(dx)<Math.abs(dy)*1.4f ||
                                Math.abs(velocityX)<190f) return false;
                        changePhoto(dx<0 ? 1 : -1);
                        return true;
                    }
                });
        photo.setOnTouchListener((v,event) -> {
            swipeDetector.onTouchEvent(event);
            return true;
        });
        photoCounter = label("",11,Color.WHITE);
        photoCounter.setPadding(dp(12),dp(6),dp(12),dp(6));
        photoCounter.setBackground(background(Color.rgb(25,40,59),10));
        FrameLayout.LayoutParams position=new FrameLayout.LayoutParams(
                -2,dp(34),Gravity.TOP|Gravity.RIGHT);
        position.setMargins(0,dp(10),dp(12),0);
        canvas.addView(photoCounter,position);
        photoCounter.setVisibility(View.GONE);
        photoCounter.setOnClickListener(v -> {
            if(photoSelection.size()>1) changePhoto(1);
        });

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
        controls.setLayoutParams(new LinearLayout.LayoutParams(-1,
                dp(mode == Mode.ADJUST ? 224 : 189)));
        if (mode == Mode.LOOKS) {
            panelTitle("LOOKS", "Tap a style to apply");
            addHorizontalChoices(new String[]{"Original", "Natural", "Warm", "Cool", "Dramatic", "B&W"},
                    this::applyLook);
        } else if (mode == Mode.ADJUST) {
            panelTitle("ADJUSTMENTS", "64×64 intelligent detail controls");
            addDetailGroupStrip();
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout options = new LinearLayout(this);
            String[] fields = adjustGroup == 1 ? DETAIL_FIELDS :
                    adjustGroup == 2 ? PRESENCE_FIELDS :
                    ("Auto".equals(edit.look) ? 
                            new String[]{"Auto Strength","Exposure","Contrast","Highlights",
                                    "Shadows","Saturation","Warmth","Tint"} : TONE_FIELDS);
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

    private void addDetailGroupStrip() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8),0,dp(8),0);
        scroller.addView(bar);
        detailTab(bar,"Tone",0);
        detailTab(bar,"Noise + Sharpen",1);
        detailTab(bar,"Clarity + Texture",2);
        TextView auto = label("✦ AUTO GROUP",11,Color.WHITE);
        auto.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
        auto.setPadding(dp(12),0,dp(12),0);
        auto.setBackground(background(ACCENT,9));
        LinearLayout.LayoutParams param = new LinearLayout.LayoutParams(-2,dp(33));
        param.leftMargin=dp(9);
        bar.addView(auto,param);
        auto.setOnClickListener(v -> autoForCurrentGroup());
        controls.addView(scroller,new LinearLayout.LayoutParams(-1,dp(42)));
    }
    private void detailTab(LinearLayout bar,String title,int group) {
        TextView tab=label(title,12,adjustGroup==group ? Color.WHITE :
                Color.rgb(166,183,201));
        tab.setPadding(dp(13),0,dp(13),0);
        if(adjustGroup==group) tab.setBackground(background(Color.rgb(34,58,88),9));
        bar.addView(tab,new LinearLayout.LayoutParams(-2,dp(34)));
        tab.setOnClickListener(v->{
            adjustGroup=group;
            activeAdjustment=group==0 ? ("Auto".equals(edit.look) ? "Auto Strength" :
                    "Exposure") : group==1 ? "Luminance NR" : "Clarity";
            drawControls();
        });
    }
    private void applyRecommendations(DetailEngine.Recommended r,int group) {
        if(group==1) {
            edit.luminanceNR=r.luminanceNoise;
            edit.colorNR=r.colorNoise;
            edit.sharpen=r.sharpening;
        } else if(group==2) {
            edit.clarity=r.clarity;
            edit.texture=r.texture;
            edit.dehaze=r.dehaze;
        } else {
            edit.luminanceNR=r.luminanceNoise;
            edit.colorNR=r.colorNoise;
            edit.sharpen=r.sharpening;
            edit.clarity=r.clarity;
            edit.texture=r.texture;
            edit.dehaze=r.dehaze;
        }
    }
    private void autoForCurrentGroup() {
        if(adjustGroup==0) {autoTune();return;}
        Bitmap original=previewSource;
        if(original==null) {openImage();return;}
        int group=adjustGroup;
        int serial=generation.incrementAndGet();
        status.setText("Analysing 64×64 detail zones…");
        previewWork.getQueue().clear();
        previewWork.execute(()->{
            try {
                DetailEngine.Recommended recommended=DetailEngine.analyse(original);
                ui.post(()->{
                    if(serial!=generation.get() || original!=previewSource)return;
                    checkpoint();
                    applyRecommendations(recommended,group);
                    render();drawControls();
                    Toast.makeText(this,group==1 ? 
                            "Auto Noise + Sharpen applied" : "Auto Clarity + Texture applied",
                            Toast.LENGTH_SHORT).show();
                });
            } catch(RuntimeException | OutOfMemoryError ex) {
                showError("Detail Auto failed: "+ex.getMessage());
            }
        });
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
        boolean positiveSlider = strengthSlider || adjustGroup != 0;
        float range = activeAdjustment.equals("Exposure") ? 2f : 100f;
        float original = getField(activeAdjustment);
        slider.setProgress(positiveSlider ? Math.round(original * 10f) :
                Math.round((original / range + 1) * 500f));
        value.setText(positiveSlider ? activeAdjustment + ": " + Math.round(original) + "%" :
                activeAdjustment + ": " + String.format(Locale.US,
                        activeAdjustment.equals("Exposure") ? "%+.2f EV" : "%+.0f", original));
        Edit starting = edit.copy();
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || previewSource == null) return;
                float result = positiveSlider ? progress / 10f :
                        ((float) progress / 500 - 1) * range;
                setField(activeAdjustment, result);
                value.setText(positiveSlider ? activeAdjustment + ": " + Math.round(result) + "%" :
                        activeAdjustment + ": " + String.format(Locale.US,
                                activeAdjustment.equals("Exposure") ? "%+.2f EV" : "%+.0f", result));
                scheduleSliderPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                // Always finish at the original preview resolution, not the
                // temporary fast slider preview.
                if (pendingSliderRender != null) {
                    ui.removeCallbacks(pendingSliderRender);
                    pendingSliderRender = null;
                }
                render();
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
            case "Luminance NR": return edit.luminanceNR;
            case "Color NR": return edit.colorNR;
            case "Sharpen": return edit.sharpen;
            case "Clarity": return edit.clarity;
            case "Texture": return edit.texture;
            case "Dehaze": return edit.dehaze;
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
            case "Luminance NR": edit.luminanceNR = clamp(value,0f,100f); break;
            case "Color NR": edit.colorNR = clamp(value,0f,100f); break;
            case "Sharpen": edit.sharpen = clamp(value,0f,100f); break;
            case "Clarity": edit.clarity = clamp(value,0f,100f); break;
            case "Texture": edit.texture = clamp(value,0f,100f); break;
            case "Dehaze": edit.dehaze = clamp(value,0f,100f); break;
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
        status.setText("AUTO  •  64×64 tone, detail and white balance…");
        previewWork.getQueue().clear();
        previewWork.execute(() -> {
            try {
                SpatialAuto64.Scene scene = SpatialAuto64.analyse(original);
                DetailEngine.Recommended details = DetailEngine.analyse(original);
                AutoWhiteBalance.Estimate whiteBalance = AutoWhiteBalance.analyse(original);
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
                    edit.wbRed = whiteBalance.red;
                    edit.wbGreen = whiteBalance.green;
                    edit.wbBlue = whiteBalance.blue;
                    edit.wbConfidence = whiteBalance.confidence;
                    edit.autoZones = scene.zones;
                    edit.autoStrength = 100f;
                    edit.look = "Auto";
                    // Auto tone and both detail groups are all scene dependent.
                    applyRecommendations(details,0);
                    adjustGroup = 0;
                    activeAdjustment = "Auto Strength";
                    render();
                    drawControls();
                    Toast.makeText(this, String.format(Locale.US,
                            "64×64 Auto: %+.2f EV · WB %s",
                            scene.exposure, whiteBalance.confidence > 0f ?
                                    "corrected" : "preserved"),
                            Toast.LENGTH_LONG).show();
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

    // Coalesce drag updates and use a lower-resolution view only while sliding.
    // Finger release always performs a full-resolution preview render.
    private void scheduleSliderPreview() {
        if (pendingSliderRender != null) ui.removeCallbacks(pendingSliderRender);
        pendingSliderRender = () -> {
            pendingSliderRender = null;
            render(true);
        };
        ui.postDelayed(pendingSliderRender, 90L);
    }

    private static Bitmap fastPreview(Bitmap full) {
        int w=full.getWidth(), h=full.getHeight();
        int longest=Math.max(w,h);
        if (longest <= 960) return full;
        float scale=960f/longest;
        return Bitmap.createScaledBitmap(full, Math.max(1,Math.round(w*scale)),
                Math.max(1,Math.round(h*scale)), true);
    }

    private void openImage() {
        Intent pick;
        if (Build.VERSION.SDK_INT >= 33) {
            pick = new Intent(MediaStore.ACTION_PICK_IMAGES);
            pick.setType("image/*");
            pick.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,
                    Math.min(24,MediaStore.getPickImagesMaxLimit()));
        } else {
            pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("image/*");
            pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        }
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(pick,OPEN_IMAGE);
        } catch(android.content.ActivityNotFoundException ex) {
            Intent legacy=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            legacy.addCategory(Intent.CATEGORY_OPENABLE);
            legacy.setType("image/*");
            legacy.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
            startActivityForResult(legacy,OPEN_IMAGE);
        }
    }

    private void updatePhotoCounter() {
        if(photoSelection.size()>1 && photoIndex>=0) {
            photoCounter.setVisibility(View.VISIBLE);
            photoCounter.setText("‹  "+(photoIndex+1)+" / "+photoSelection.size()+"  ›");
            photoCounter.setContentDescription("Photo "+(photoIndex+1)+" of "+
                    photoSelection.size()+". Tap to show next.");
        } else photoCounter.setVisibility(View.GONE);
    }

    private void changePhoto(int direction) {
        if(photoSelection.size()<2 || photoIndex<0)return;
        int next=photoIndex+direction;
        if(next<0 || next>=photoSelection.size()) {
            Toast.makeText(this,next<0?"First selected photo":"Last selected photo",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if(input!=null)editsByPhoto.put(input.toString(),edit.copy());
        photoIndex=next;
        loadChosenPhoto(photoSelection.get(photoIndex));
    }

    private void releaseOldBitmaps(Bitmap full,Bitmap fast,Bitmap rendered) {
        if(full==null && fast==null && rendered==null)return;
        // Cleanup occurs after the currently running preview task is finished.
        previewWork.execute(()->{
            if(rendered!=null && rendered!=full && rendered!=fast &&
                    !rendered.isRecycled()) rendered.recycle();
            if(fast!=null && fast!=full && !fast.isRecycled())fast.recycle();
            if(full!=null && !full.isRecycled())full.recycle();
        });
    }

    private void loadChosenPhoto(Uri uri) {
        int serial=generation.incrementAndGet();
        if(pendingSliderRender!=null) {
            ui.removeCallbacks(pendingSliderRender);
            pendingSliderRender=null;
        }
        previewWork.getQueue().clear();
        openingWork.getQueue().clear();
        Bitmap old=previewSource,oldFast=fastPreviewSource,oldRender=renderedPreview;
        input=null;previewSource=null;fastPreviewSource=null;renderedPreview=null;
        photo.setImageDrawable(null);
        releaseOldBitmaps(old,oldFast,oldRender);
        openPlaceholder.setText("OPENING PHOTO…\n\nPreparing preview");
        openPlaceholder.setVisibility(View.VISIBLE);
        updatePhotoCounter();
        status.setText("Opening photo "+(photoIndex+1)+" / "+photoSelection.size()+"…");
        openingWork.execute(()->{
            Bitmap full=null,small=null;
            try {
                full=decode(uri,1600,2_560_000);
                if(serial!=generation.get()) {
                    full.recycle();return;
                }
                small=fastPreview(full);
                final Bitmap decoded=full,thumbnail=small;
                ui.post(()->{
                    if(serial!=generation.get()) {
                        if(thumbnail!=decoded)thumbnail.recycle();
                        decoded.recycle();return;
                    }
                    input=uri;
                    previewSource=decoded;
                    fastPreviewSource=thumbnail;
                    Edit previous=editsByPhoto.get(uri.toString());
                    edit=previous==null?new Edit():previous.copy();
                    undo.clear();redo.clear();
                    if(adjustGroup==0 && !"Auto".equals(edit.look) &&
                            "Auto Strength".equals(activeAdjustment))
                        activeAdjustment="Exposure";
                    photo.setImageBitmap(decoded);
                    openPlaceholder.setVisibility(View.GONE);
                    status.setText("Photo ready · "+(photoIndex+1)+" / "+
                            photoSelection.size());
                    updatePhotoCounter();
                    render();
                    drawControls();
                });
            } catch(Exception | OutOfMemoryError ex) {
                if(small!=null && small!=full && !small.isRecycled())small.recycle();
                if(full!=null && !full.isRecycled())full.recycle();
                final String reason=ex.getMessage();
                ui.post(()->{
                    if(serial!=generation.get())return;
                    openPlaceholder.setText("COULD NOT OPEN PHOTO\n\nTap to try again");
                    status.setText("Cannot open photo: "+reason);
                    Toast.makeText(this,"Cannot open: "+reason,Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override protected void onActivityResult(int code,int result,Intent data) {
        super.onActivityResult(code,result,data);
        if(code!=OPEN_IMAGE || result!=RESULT_OK || data==null)return;
        ArrayList<Uri> selected=new ArrayList<>();
        ClipData clip=data.getClipData();
        if(clip!=null) {
            for(int i=0;i<clip.getItemCount() && selected.size()<24;i++) {
                Uri uri=clip.getItemAt(i).getUri();
                if(uri!=null && !selected.contains(uri))selected.add(uri);
            }
        }
        if(data.getData()!=null && !selected.contains(data.getData()))
            selected.add(0,data.getData());
        if(selected.isEmpty())return;
        for(Uri uri:selected) {
            try {getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);}
            catch(Exception ignored) {}
        }
        photoSelection.clear();
        photoSelection.addAll(selected);
        editsByPhoto.clear();
        photoIndex=0;
        loadChosenPhoto(photoSelection.get(0));
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

    private void render() { render(false); }

    private void render(boolean quick) {
        Bitmap original = previewSource;
        if (original == null) return;
        Bitmap source = quick && fastPreviewSource != null ? fastPreviewSource : original;
        Edit snapshot = edit.copy();
        int serial = generation.incrementAndGet();
        previewWork.getQueue().clear();
        if (noEdits(snapshot)) {
            Bitmap previous=renderedPreview;
            renderedPreview=null;
            photo.setImageBitmap(original);
            if(previous!=null && previous!=original && previous!=fastPreviewSource)
                releaseOldBitmaps(null,null,previous);
            status.setText("Original · ready to edit");
            return;
        }
        status.setText(quick ? "Previewing adjustment…" : "Rendering photo…");
        previewWork.execute(() -> {
            if (serial != generation.get() || Thread.currentThread().isInterrupted()) return;
            try {
                Bitmap output = process(source, snapshot, () ->
                        serial != generation.get() || Thread.currentThread().isInterrupted());
                ui.post(() -> {
                    if (serial != generation.get() || original != previewSource) {
                        output.recycle();
                        return;
                    }
                    Bitmap previous = renderedPreview;
                    renderedPreview = output;
                    photo.setImageBitmap(output);
                    if (previous != null && previous != previewSource &&
                            previous != fastPreviewSource && previous != output) {
                        previous.recycle();
                    }
                    if ("Auto".equals(snapshot.look) && snapshot.autoZones != null) {
                        status.setText(String.format(Locale.US,
                                "64×64 AUTO  •  %+.2f EV  •  %.0f%%  •  WB %s",
                                snapshot.exposure, snapshot.autoStrength,
                                snapshot.wbConfidence > 0f ? "corrected" : "preserved"));
                    } else {
                        status.setText("Live preview · JPEG export Q" + jpegQuality);
                    }
                });
            } catch (CancellationException ignored) {
                // Expected when newer adjustment supersedes a slow old render.
            } catch (OutOfMemoryError ex) {
                ui.post(() -> {
                    if (serial == generation.get()) showError("Not enough memory for preview");
                });
            } catch (RuntimeException ex) {
                ui.post(() -> {
                    if (serial == generation.get()) showError("Preview failed: " + ex.getMessage());
                });
            }
        });
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
    private static int quant(float value) {
        return (int)(255 * clamp(value,0,1) + .5f);
    }

    private static DetailEngine.Settings detailSettings(Edit e) {
        DetailEngine.Settings d = new DetailEngine.Settings();
        d.luminanceNoise=e.luminanceNR;
        d.colorNoise=e.colorNR;
        d.sharpening=e.sharpen;
        d.clarity=e.clarity;
        d.texture=e.texture;
        d.dehaze=e.dehaze;
        return d;
    }

    private static Bitmap process(Bitmap src, Edit e) {
        return process(src, e, () -> false);
    }

    private static Bitmap process(Bitmap src, Edit e, BooleanSupplier cancelled) {
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
            if ((y & 7) == 0 && cancelled.getAsBoolean()) {
                output.recycle();
                throw new CancellationException();
            }
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
                    float baseR = r0*scale, baseG = g0*scale, baseB = b0*scale;
                    if (e.wbConfidence > 0f || warmth != 0f || tint != 0f) {
                        // Hue-neutral WB: preserve local luminance and retain headroom.
                        // Saturated subject colours and nearly white pixels are
                        // protected; manual Warmth/Tint remain independently usable.
                        float chroma = (maxChannel-Math.min(r0,Math.min(g0,b0))) /
                                Math.max(.01f,maxChannel);
                        float colourGate=(1f-.35f*SpatialAuto64.smoothstep(
                                .20f,.65f,chroma)) *
                                (1f-.78f*SpatialAuto64.smoothstep(.84f,.99f,maxChannel));
                        float dr = baseR*(e.wbRed-1f)*strength*colourGate +
                                (warmth+tint)*colourGate;
                        float dg = baseG*(e.wbGreen-1f)*strength*colourGate -
                                tint*colourGate;
                        float db = baseB*(e.wbBlue-1f)*strength*colourGate +
                                (-warmth+tint)*colourGate;
                        float deltaLum = .2126f*dr+.7152f*dg+.0722f*db;
                        baseR += dr-deltaLum;
                        baseG += dg-deltaLum;
                        baseB += db-deltaLum;
                        float upper=Math.max(baseR,Math.max(baseG,baseB));
                        if (upper>.997f) {
                            float keep=.997f/upper;
                            baseR*=keep; baseG*=keep; baseB*=keep;
                        }
                    }
                    pixels[x] = Color.argb(Color.alpha(pixel),
                            quant(baseR),quant(baseG),quant(baseB));
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
        if (e.ratio <= 0) {
            try {
                DetailEngine.applyInPlace(rotated, detailSettings(e), cancelled);
            } catch (CancellationException ex) {
                rotated.recycle();
                throw ex;
            }
            return rotated;
        }
        int width = rotated.getWidth(), height = rotated.getHeight();
        int cw = width, ch = height;
        if (width / (float)height > e.ratio) cw = Math.max(1, Math.round(height*e.ratio));
        else ch = Math.max(1, Math.round(width/e.ratio));
        Bitmap cropped = Bitmap.createBitmap(rotated, (width-cw)/2, (height-ch)/2, cw, ch);
        if (cropped != rotated) rotated.recycle();
        try {
            DetailEngine.applyInPlace(cropped, detailSettings(e), cancelled);
        } catch (CancellationException ex) {
            cropped.recycle();
            throw ex;
        }
        return cropped;
    }

    private static boolean noEdits(Edit e) {
        return e.rotation == 0 && e.ratio == 0f && e.autoZones == null &&
                e.exposure == 0f && e.contrast == 0f && e.highlights == 0f &&
                e.shadows == 0f && e.saturation == 0f && e.warmth == 0f &&
                e.tint == 0f && e.wbConfidence == 0f &&
                !detailSettings(e).enabled();
    }

    private void exportStatus(Uri source, String message) {
        ui.post(() -> {
            // An export of the previous file should not overwrite the status
            // of a newly chosen photograph.
            if (source.equals(input)) status.setText(message);
        });
    }

    private void save(boolean share) {
        if (input == null) { openImage(); return; }
        if (!saving.compareAndSet(false, true)) {
            Toast.makeText(this, "A photo is already being saved", Toast.LENGTH_SHORT).show();
            return;
        }
        final Uri toLoad = input;
        final Edit snapshot = edit.copy();
        final int quality = jpegQuality;
        status.setText("Saving · reading original photo…");
        savingWork.execute(() -> {
            Uri saved = null;
            Bitmap original = null, result = null;
            try {
                // Full-resolution export is intentionally preserved. It runs
                // independently of preview work to avoid locking up photo opening.
                original = decode(toLoad, 8192, 50_000_000);
                exportStatus(toLoad, "Saving · processing full-resolution image…");
                result = noEdits(snapshot) ? original : process(original, snapshot);
                exportStatus(toLoad, "Saving · encoding JPEG Q" + quality + "…");
                String name = "DCS_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",
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
                    if (stream == null ||
                            !result.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                        throw new IllegalStateException("JPEG encoding failed");
                    }
                }
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(saved, values, null, null);
                Uri finished = saved;
                ui.post(() -> {
                    if (toLoad.equals(input)) {
                        status.setText("Saved JPEG Q" + quality +
                                " to Pictures/Dennis Craft Mobile");
                    }
                    Toast.makeText(this, "Exported JPEG Q" + quality,
                            Toast.LENGTH_LONG).show();
                    if (share && !isFinishing()) {
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("image/jpeg");
                        shareIntent.putExtra(Intent.EXTRA_STREAM, finished);
                        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(shareIntent, "Share photo"));
                    }
                });
            } catch (Exception | OutOfMemoryError ex) {
                if (saved != null) getContentResolver().delete(saved, null, null);
                final String reason = ex.getMessage();
                ui.post(() -> {
                    if (toLoad.equals(input)) status.setText("Export failed: " + reason);
                    Toast.makeText(this, "Export failed: " + reason,
                            Toast.LENGTH_LONG).show();
                });
            } finally {
                if (result != null && result != original && !result.isRecycled()) {
                    result.recycle();
                }
                if (original != null && !original.isRecycled()) original.recycle();
                saving.set(false);
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
        new AlertDialog.Builder(this).setTitle("Dennis Craft Mobile · v0.6.2")
                .setMessage("Offline native Android editing prototype.\n\n" +
                        "Working: modern Android photo picker, JPEG/HEIC, styled looks, " +
                        "tone/colour adjustments, 64×64 Auto WB, luminance/chroma NR, sharpening, " +
                        "clarity, texture, contrast dehaze, centre crop, rotate, " +
                        "undo/redo, press-and-hold before view, export and share.\n\n" +
                        "Auto WB uses cautious neutral samples, avoids colourful subjects and skips uncertain scenes. Auto analyses noise and detail from 64×64 local probes. Each detail group also has " +
                        "its own Auto button and six manual sliders. Dehaze is a contrast-based " +
                        "approximation, not a depth map. " +
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
        generation.incrementAndGet();
        if (pendingSliderRender != null) ui.removeCallbacks(pendingSliderRender);
        previewWork.shutdownNow(); openingWork.shutdownNow(); savingWork.shutdownNow();
        super.onDestroy();
    }
}
