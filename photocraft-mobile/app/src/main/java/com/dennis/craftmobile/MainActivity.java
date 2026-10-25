package com.dennis.craftmobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.content.ContentValues;
import android.content.ClipData;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
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
    private static final int ALBUM_PERMISSION = 702;
    private static final int BG = Color.rgb(8, 12, 19);
    private static final int PANEL = Color.rgb(13, 21, 32);
    private static final int ACCENT = Color.rgb(36, 136, 255);

    private enum Mode { LOOKS, ADJUST, CROP }
    static final class Edit implements java.io.Serializable {
        private static final long serialVersionUID=1L;
        float exposure, contrast, highlights, shadows, saturation, warmth, tint;
        float ratio = 0f;
        int rotation = 0;
        float straightenAngle = 0f;
        float perspectiveVertical, perspectiveHorizontal;
        String look = "Original";
        float[] autoZones;  // Immutable zone map measured on original preview.
        float autoStrength = 100f;
        boolean autoLowKey; // 16x16 scene judgement, no semantic portrait mask.
        String autoAtmosphere = ""; // 8x8 conservative atmosphere decision.
        boolean autoMacroProtected; // Additional 4x4 gate, when confident.
        boolean autoImportantRegion; // Conservative structural importance.
        boolean autoBlackProtected; // Intentional near-black regions.
        float luminanceNR, colorNR, sharpen, clarity, texture, dehaze;
        // Colour gains estimated only when spatial neutral evidence is convincing.
        float wbRed = 1f, wbGreen = 1f, wbBlue = 1f, wbConfidence;
        boolean wbPicked;
        Edit copy() {
            Edit d = new Edit();
            d.exposure = exposure; d.contrast = contrast; d.highlights = highlights;
            d.shadows = shadows; d.saturation = saturation; d.warmth = warmth;
            d.tint = tint; d.ratio = ratio; d.rotation = rotation;
            d.straightenAngle = straightenAngle;
            d.perspectiveVertical=perspectiveVertical;
            d.perspectiveHorizontal=perspectiveHorizontal;
            d.look = look;
            d.autoZones = autoZones; d.autoStrength = autoStrength;
            d.autoLowKey = autoLowKey;
            d.autoAtmosphere = autoAtmosphere;
            d.autoMacroProtected = autoMacroProtected;
            d.autoImportantRegion = autoImportantRegion;
            d.autoBlackProtected = autoBlackProtected;
            d.luminanceNR = luminanceNR; d.colorNR = colorNR; d.sharpen = sharpen;
            d.clarity = clarity; d.texture = texture; d.dehaze = dehaze;
            d.wbRed = wbRed; d.wbGreen = wbGreen; d.wbBlue = wbBlue;
            d.wbConfidence = wbConfidence;
            d.wbPicked=wbPicked;
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
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private Runnable pendingSliderRender;
    private LinearLayout controls, tabs;
    private FrameLayout canvas;
    private ZoomPhotoView photo;
    private View guideOverlay;
    private boolean guideMode;
    private boolean neutralPickMode;
    private boolean gridVisible;
    private int cropSection=1; // 0 Crop, 1 Straighten, 2 Transform
    private int guideStep;
    private float guideX,guideY,guideDownX,guideDownY;
    private TextView openPlaceholder, status, photoCounter;
    // Only user-granted picker URIs. Edits are session-local, original files untouched.
    private final ArrayList<Uri> photoSelection = new ArrayList<>();
    private final HashMap<String,Edit> editsByPhoto = new HashMap<>();
    private int photoIndex = -1;
    private String activeAlbumName = "";
    private AlbumBrowser albumBrowser;
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
    private boolean exportReceiverRegistered;
    private boolean editingFromAnotherApp;
    private final BroadcastReceiver exportReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent event) {
            if(!PhotoExportService.ACTION_FINISHED.equals(event.getAction())) return;
            Uri source=Build.VERSION.SDK_INT>=33 ?
                    event.getParcelableExtra(PhotoExportService.EXTRA_SOURCE,Uri.class) :
                    event.getParcelableExtra(PhotoExportService.EXTRA_SOURCE);
            Uri saved=Build.VERSION.SDK_INT>=33 ?
                    event.getParcelableExtra(PhotoExportService.EXTRA_RESULT,Uri.class) :
                    event.getParcelableExtra(PhotoExportService.EXTRA_RESULT);
            String error=event.getStringExtra(PhotoExportService.EXTRA_ERROR);
            boolean share=event.getBooleanExtra(PhotoExportService.EXTRA_SHARE,false);
            long seconds=event.getLongExtra(PhotoExportService.EXTRA_SECONDS,0);
            if(error!=null) {
                if(source!=null && source.equals(input)) status.setText("Export failed: "+error);
                Toast.makeText(MainActivity.this,"Save failed: "+error,Toast.LENGTH_LONG).show();
            } else if(saved!=null) {
                if(source!=null && source.equals(input)) {
                    status.setText("Saved JPEG to Pictures/Photo Studio · "+seconds+"s");
                    if(editingFromAnotherApp) {
                        Intent data=new Intent().setDataAndType(saved,"image/jpeg")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        MainActivity.this.setResult(RESULT_OK,data);
                    }
                }
                Toast.makeText(MainActivity.this,"JPEG saved in Gallery",Toast.LENGTH_LONG).show();
                if(share) {
                    Intent send=new Intent(Intent.ACTION_SEND);
                    send.setType("image/jpeg");
                    send.putExtra(Intent.EXTRA_STREAM,saved);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    send.setClipData(ClipData.newUri(getContentResolver(),"Saved image",saved));
                    startActivity(Intent.createChooser(send,"Share edited photo"));
                }
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Window w = getWindow();
        w.setStatusBarColor(Color.BLACK);
        w.setNavigationBarColor(Color.BLACK);
        w.getDecorView().setSystemUiVisibility(0);
        buildLayout();
        handleIncomingImage(getIntent());
    }

    @Override protected void onStart() {
        super.onStart();
        if(!exportReceiverRegistered) {
            IntentFilter filter=new IntentFilter(PhotoExportService.ACTION_FINISHED);
            if(Build.VERSION.SDK_INT>=33)
                registerReceiver(exportReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(exportReceiver,filter);
            exportReceiverRegistered=true;
        }
    }

    @Override protected void onStop() {
        if(exportReceiverRegistered) {
            unregisterReceiver(exportReceiver);
            exportReceiverRegistered=false;
        }
        super.onStop();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingImage(intent);
    }

    // Android Gallery / Files / Share-sheet integration: only accepted image data.
    private void handleIncomingImage(Intent intent) {
        if(intent==null)return;
        String action=intent.getAction();
        if(!Intent.ACTION_EDIT.equals(action)&&!Intent.ACTION_SEND.equals(action))return;
        if(intent.getType()!=null&&!intent.getType().startsWith("image/"))return;
        Uri uri=Intent.ACTION_EDIT.equals(action)?intent.getData():
                (Build.VERSION.SDK_INT>=33 ?
                        intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri.class) :
                        intent.getParcelableExtra(Intent.EXTRA_STREAM));
        if(uri==null && intent.getClipData()!=null &&
                intent.getClipData().getItemCount()>0)
            uri=intent.getClipData().getItemAt(0).getUri();
        if(uri==null || !"content".equals(uri.getScheme())) {
            Toast.makeText(this,"Unsupported source image",Toast.LENGTH_LONG).show();
            return;
        }
        editingFromAnotherApp=true;
        photoSelection.clear();
        photoSelection.add(uri);
        activeAlbumName="";
        editsByPhoto.clear();
        photoIndex=0;
        loadChosenPhoto(uri);
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
        toolbar(bar, "＋", this::choosePhotoSource);
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView brandTop = label("CRAFT", 15, Color.WHITE);
        brandTop.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        brandTop.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        TextView brandSub = label("PHOTO STUDIO", 9, Color.rgb(112, 163, 222));
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
        photo = new ZoomPhotoView(this, this::changePhoto, this::onEditorPhotoTap,
                () -> guideMode || neutralPickMode, () -> {
                    if (guideOverlay != null) guideOverlay.invalidate();
                });
        canvas.addView(photo, new FrameLayout.LayoutParams(-1, -1));
        openPlaceholder = label("◉\n\nPHOTO STUDIO\n\nProfessional photo editing, offline.\n\n＋  TAP TO OPEN PHOTO", 18, Color.rgb(212, 229, 245));
        openPlaceholder.setOnClickListener(v -> choosePhotoSource());
        canvas.addView(openPlaceholder, new FrameLayout.LayoutParams(-1, -1));
        guideOverlay=new View(this) {
            @Override protected void onDraw(android.graphics.Canvas c) {
                super.onDraw(c);
                if(gridVisible && photo!=null && photo.getDrawable()!=null) {
                    int iw=photo.getDrawable().getIntrinsicWidth();
                    int ih=photo.getDrawable().getIntrinsicHeight();
                    if(iw>0&&ih>0&&photo.getWidth()>0&&photo.getHeight()>0) {
                        // Match the live zoom/pan image matrix so the 3x3 grid
                        // follows the photograph while inspecting perspective.
                        float[] corners={0f,0f,(float)iw,(float)ih};
                        photo.getImageMatrix().mapPoints(corners);
                        float x0=corners[0],y0=corners[1];
                        float fw=corners[2]-x0,fh=corners[3]-y0;
                        android.graphics.Paint p=new android.graphics.Paint(
                                android.graphics.Paint.ANTI_ALIAS_FLAG);
                        p.setColor(Color.argb(180,255,255,255));
                        p.setStrokeWidth(dp(1));
                        for(int line=1;line<3;line++) {
                            float gx=x0+fw*line/3f;
                            float gy=y0+fh*line/3f;
                            c.drawLine(gx,y0,gx,y0+fh,p);
                            c.drawLine(x0,gy,x0+fw,gy,p);
                        }
                    }
                }
                if(!guideMode||guideStep==0)return;
                android.graphics.Paint pen=new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG);
                pen.setColor(Color.WHITE);
                pen.setStyle(android.graphics.Paint.Style.STROKE);
                pen.setStrokeWidth(dp(5));
                c.drawCircle(guideX,guideY,dp(12),pen);
                pen.setColor(ACCENT);
                pen.setStrokeWidth(dp(3));
                c.drawCircle(guideX,guideY,dp(12),pen);
                c.drawLine(guideX-dp(17),guideY,guideX+dp(17),guideY,pen);
                c.drawLine(guideX,guideY-dp(17),guideX,guideY+dp(17),pen);
            }
        };
        guideOverlay.setClickable(false);
        canvas.addView(guideOverlay,new FrameLayout.LayoutParams(-1,-1));
        photoCounter = label("",11,Color.WHITE);
        photoCounter.setPadding(dp(12),dp(6),dp(12),dp(6));
        photoCounter.setMaxWidth(dp(260));
        photoCounter.setSingleLine(true);
        photoCounter.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
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
        if(newMode!=Mode.CROP) {
            endGuide();
            gridVisible=false;
            if(guideOverlay!=null)guideOverlay.invalidate();
        }
        if(newMode!=Mode.ADJUST)neutralPickMode=false;
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


    // Preview-only sampling; never stretch the original photo into a forced 4:3 frame.
    private static Bitmap aspectSafeLookSample(Bitmap src) {
        float scale = 144f / Math.max(src.getWidth(), src.getHeight());
        return Bitmap.createScaledBitmap(src,
                Math.max(1, Math.round(src.getWidth() * scale)),
                Math.max(1, Math.round(src.getHeight() * scale)), true);
    }

    private void addHorizontalChoices(String[] names, java.util.function.Consumer<String> consumer) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setPadding(dp(11), dp(2), dp(11), 0);
        strip.setGravity(Gravity.TOP);
        scroll.addView(strip);
        // Keep each preset preview at the image's original aspect ratio.
        Bitmap tiny = (previewSource != null && mode == Mode.LOOKS)
                ? aspectSafeLookSample(previewSource) : null;
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
                swatch.setScaleType(ImageView.ScaleType.FIT_CENTER);
                swatch.setBackgroundColor(Color.rgb(15, 24, 36));
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
        if (input == null) { choosePhotoSource(); return; }
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
                dp(mode == Mode.ADJUST ? 224 : mode == Mode.CROP ? 260 : 189)));
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
            panelTitle("CROP & TRANSFORM", "Non-destructive · no AI");
            drawCropSections();
        }
    }


    private void drawCropSections() {
        LinearLayout strip=new LinearLayout(this);
        strip.setPadding(dp(12),dp(2),dp(12),0);
        String[] tabs={"CROP","STRAIGHTEN","TRANSFORM"};
        for(int n=0;n<tabs.length;n++) {
            final int section=n;
            TextView option=label(tabs[n],12,n==cropSection?Color.WHITE:Color.LTGRAY);
            option.setGravity(Gravity.CENTER);
            option.setBackground(background(n==cropSection?Color.rgb(30,76,121):
                    Color.rgb(22,34,49),8));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(33),1f);
            lp.setMargins(dp(3),0,dp(3),0);
            strip.addView(option,lp);
            option.setOnClickListener(v->{
                endGuide();
                cropSection=section;
                gridVisible=cropSection==2;
                guideOverlay.invalidate();
                drawControls();
            });
        }
        controls.addView(strip,new LinearLayout.LayoutParams(-1,dp(39)));
        if(cropSection==0)drawCropRatios();
        else if(cropSection==1)drawCropAndStraighten();
        else drawTransformControls();
    }

    private void drawCropRatios() {
        HorizontalScrollView scroll=new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout buttons=new LinearLayout(this);
        buttons.setPadding(dp(12),dp(8),dp(12),dp(8));
        String[] ratios={"Original","1:1","4:5","16:9","Rotate 90°"};
        for(String item:ratios) {
            TextView label=label(item,12,Color.WHITE);
            label.setPadding(dp(14),dp(9),dp(14),dp(9));
            label.setBackground(background(Color.rgb(29,49,69),8));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(42));
            lp.setMargins(dp(3),0,dp(3),0);
            buttons.addView(label,lp);
            label.setOnClickListener(v->{
                if(previewSource==null){openImage();return;}
                checkpoint();
                switch(item) {
                    case "1:1":edit.ratio=1f;break;
                    case "4:5":edit.ratio=.8f;break;
                    case "16:9":edit.ratio=16f/9f;break;
                    case "Rotate 90°":edit.rotation=(edit.rotation+90)%360;break;
                    default:edit.ratio=0f;
                }
                render();drawControls();
            });
        }
        scroll.addView(buttons);
        controls.addView(scroll,new LinearLayout.LayoutParams(-1,dp(60)));
        TextView hint=label("Rotation, straighten and perspective remain editable. "+
                "Full-resolution exports use the same geometry.",12,Color.LTGRAY);
        hint.setPadding(dp(16),dp(12),dp(16),0);
        controls.addView(hint,new LinearLayout.LayoutParams(-1,dp(57)));
    }

    private void drawTransformControls() {
        HorizontalScrollView scroll=new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout strip=new LinearLayout(this);
        strip.setPadding(dp(9),dp(5),dp(9),dp(2));
        String[] actions={"FULL AUTO","BALANCED AUTO","RESET","GRID"};
        for(String action:actions){
            TextView v=label(action,11,Color.WHITE);
            v.setPadding(dp(12),dp(8),dp(12),dp(8));
            v.setBackground(background(action.contains("AUTO")?ACCENT:
                    Color.rgb(30,48,67),8));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(38));
            lp.setMargins(dp(4),0,dp(4),0);
            strip.addView(v,lp);
            v.setOnClickListener(a->{
                if(previewSource==null){openImage();return;}
                if("FULL AUTO".equals(action)){proposeAutoTransform(false);return;}
                if("BALANCED AUTO".equals(action)){proposeAutoTransform(true);return;}
                if("GRID".equals(action)){
                    gridVisible=!gridVisible;guideOverlay.invalidate();return;
                }
                if(Math.abs(edit.perspectiveVertical)<.01f &&
                        Math.abs(edit.perspectiveHorizontal)<.01f)return;
                checkpoint();
                edit.perspectiveVertical=0f;
                edit.perspectiveHorizontal=0f;
                render();drawControls();
            });
        }
        scroll.addView(strip);
        controls.addView(scroll,new LinearLayout.LayoutParams(-1,dp(45)));
        drawTransformSlider("Vertical Skew",true);
        drawTransformSlider("Horizontal Skew",false);
    }

    private void drawTransformSlider(String title,boolean vertical) {
        LinearLayout group=new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(15),0,dp(15),0);
        float starting=vertical?edit.perspectiveVertical:edit.perspectiveHorizontal;
        TextView value=label(String.format(Locale.US,"%s: %+.1f",title,starting),
                12,Color.WHITE);
        group.addView(value,new LinearLayout.LayoutParams(-1,dp(20)));
        SeekBar slider=new SeekBar(this);
        slider.setMax(500);
        slider.setProgress(Math.round(starting*10f)+250);
        Edit before=edit.copy();
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar seek,int position,boolean byUser){
                if(!byUser || previewSource==null)return;
                float amount=(position-250)/10f;
                if(vertical)edit.perspectiveVertical=amount;
                else edit.perspectiveHorizontal=amount;
                value.setText(String.format(Locale.US,"%s: %+.1f",title,amount));
                scheduleSliderPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar seek){}
            @Override public void onStopTrackingTouch(SeekBar seek){
                if(pendingSliderRender!=null){
                    ui.removeCallbacks(pendingSliderRender);pendingSliderRender=null;
                }
                if(previewSource!=null) {
                    float after=vertical?edit.perspectiveVertical:edit.perspectiveHorizontal;
                    if(Math.abs(after-starting)>.04f){
                        undo.addLast(before);
                        if(undo.size()>35)undo.removeFirst();
                        redo.clear();
                    }
                    render();
                }
            }
        });
        group.addView(slider,new LinearLayout.LayoutParams(-1,dp(34)));
        controls.addView(group,new LinearLayout.LayoutParams(-1,dp(56)));
    }

    private void proposeAutoTransform(boolean balanced) {
        final Bitmap original=previewSource;
        if(original==null){openImage();return;}
        endGuide();
        int serial=generation.incrementAndGet();
        previewWork.getQueue().clear();
        status.setText((balanced?"Balanced":"Full")+
                " Auto Transform · analysing architectural lines…");
        previewWork.execute(()->{
            try{
                TransformEngine.Suggestion decision=TransformEngine.analyse(
                        original,balanced);
                ui.post(()->{
                    if(serial!=generation.get()||previewSource!=original)return;
                    if(!decision.hasAny()){
                        if(decision.structureFound) {
                            status.setText("Architectural lines found · no trustworthy adjustment");
                            Toast.makeText(this,"Straight structures detected, but no "+
                                    "reliable tilt or perspective correction is needed "+
                                    "or supported. Adjust manually if desired.",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            status.setText("Auto Transform · no reliable structural reference");
                            Toast.makeText(this,"No dependable architectural lines "+
                                    "found · use manual skew or Guided Level.",
                                    Toast.LENGTH_LONG).show();
                        }
                        return;
                    }
                    final float angle=decision.hasAngle?decision.angle:
                            edit.straightenAngle;
                    final float vert=decision.hasVertical?decision.vertical:
                            edit.perspectiveVertical;
                    final float horiz=decision.hasHorizontal?decision.horizontal:
                            edit.perspectiveHorizontal;
                    String description=String.format(Locale.US,
                            "Proposed straighten: %+.1f°%s\n"+
                            "Vertical Skew: %+.1f%s\n"+
                            "Horizontal Skew: %+.1f%s\n\n"+
                            "This is a conservative geometric estimate. "+
                            "Perspective lines can be misleading. Inspect the "+
                            "preview and use Undo if it looks unnatural. "+
                            "Automatic cropping keeps black corners out.",
                            angle,decision.hasAngle?"":" (preserved)",
                            vert,decision.hasVertical?"":" (preserved)",
                            horiz,decision.hasHorizontal?"":" (preserved)");
                    new AlertDialog.Builder(this)
                            .setTitle(balanced?"Balanced Auto Transform":"Full Auto Transform")
                            .setMessage(description)
                            .setNegativeButton("Keep current",null)
                            .setPositiveButton("Apply & review",(dialog,which)->{
                                if(serial!=generation.get() || previewSource!=original)return;
                                checkpoint();
                                edit.straightenAngle=angle;
                                edit.perspectiveVertical=vert;
                                edit.perspectiveHorizontal=horiz;
                                render();drawControls();
                            }).show();
                });
            }catch(RuntimeException|OutOfMemoryError ex){
                showError("Auto Transform failed: "+ex.getMessage());
            }
        });
    }

    private void drawCropAndStraighten() {
        HorizontalScrollView scroll=new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout buttons=new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(dp(10),0,dp(10),0);
        String[] choices={"AUTO STRAIGHTEN","GUIDED LEVEL","Reset Angle",
                "Original Ratio","1:1","4:5","16:9","Rotate 90°"};
        for(String choice:choices) {
            TextView button=label(choice,11,Color.WHITE);
            button.setPadding(dp(12),dp(8),dp(12),dp(8));
            boolean prominent="AUTO STRAIGHTEN".equals(choice);
            button.setBackground(background(prominent?ACCENT:
                    Color.rgb(30,49,69),8));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(36));
            lp.setMargins(dp(3),0,dp(3),0);
            buttons.addView(button,lp);
            button.setOnClickListener(v->{
                if(previewSource==null){openImage();return;}
                if("AUTO STRAIGHTEN".equals(choice)){
                    endGuide();autoStraighten();return;
                }
                if("GUIDED LEVEL".equals(choice)){startGuide();return;}
                endGuide();
                if("Reset Angle".equals(choice)) {
                    if(Math.abs(edit.straightenAngle)<.015f)return;
                    checkpoint();edit.straightenAngle=0f;
                } else {
                    checkpoint();
                    switch(choice) {
                        case "1:1":edit.ratio=1f;break;
                        case "4:5":edit.ratio=.8f;break;
                        case "16:9":edit.ratio=16f/9f;break;
                        case "Rotate 90°":edit.rotation=(edit.rotation+90)%360;break;
                        default:edit.ratio=0f;break;
                    }
                }
                render();drawControls();
            });
        }
        scroll.addView(buttons);
        controls.addView(scroll,new LinearLayout.LayoutParams(-1,dp(47)));

        LinearLayout anglePanel=new LinearLayout(this);
        anglePanel.setOrientation(LinearLayout.VERTICAL);
        anglePanel.setPadding(dp(15),0,dp(15),0);
        TextView value=label("",12,Color.WHITE);
        value.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);
        float originalAngle=edit.straightenAngle;
        value.setText(String.format(Locale.US,"Manual straighten: %+.1f°",originalAngle));
        anglePanel.addView(value,new LinearLayout.LayoutParams(-1,dp(27)));
        SeekBar slider=new SeekBar(this);
        slider.setMax(300);
        slider.setProgress(Math.round(originalAngle*10f)+150);
        Edit starting=edit.copy();
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser) {
                if(!fromUser||previewSource==null)return;
                float angle=(progress-150)/10f;
                edit.straightenAngle=angle;
                value.setText(String.format(Locale.US,"Manual straighten: %+.1f°",angle));
                scheduleSliderPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if(pendingSliderRender!=null) {
                    ui.removeCallbacks(pendingSliderRender);
                    pendingSliderRender=null;
                }
                if(previewSource!=null) {
                    if(Math.abs(edit.straightenAngle-originalAngle)>.04f) {
                        undo.addLast(starting);
                        if(undo.size()>35)undo.removeFirst();
                        redo.clear();
                    }
                    render();
                }
            }
        });
        anglePanel.addView(slider,new LinearLayout.LayoutParams(-1,dp(52)));
        controls.addView(anglePanel,new LinearLayout.LayoutParams(-1,dp(79)));
    }


    private void toggleNeutralPick() {
        if(previewSource==null) {
            Toast.makeText(this,"Open a photograph first",Toast.LENGTH_SHORT).show();return;
        }
        if(neutralPickMode) {
            neutralPickMode=false;
            status.setText("Neutral WB cancelled");
        } else {
            endGuide();
            photo.resetZoom();
            neutralPickMode=true;
            status.setText("WB: tap a known grey or white object");
            Toast.makeText(this,"Tap a grey or white surface away from glare.",
                    Toast.LENGTH_LONG).show();
        }
        drawControls();
    }
    private void onEditorPhotoTap(float x,float y) {
        if(neutralPickMode)pickNeutralPoint(x,y);
        else if(guideMode)guidedTap(x,y);
    }
    private void pickNeutralPoint(float x,float y) {
        if(!neutralPickMode||previewSource==null)return;
        try {
            android.graphics.drawable.Drawable drawable=photo.getDrawable();
            if(!(drawable instanceof android.graphics.drawable.BitmapDrawable))
                throw new IllegalArgumentException("Photo not ready");
            Bitmap shown=((android.graphics.drawable.BitmapDrawable)drawable).getBitmap();
            Matrix inverse=new Matrix();
            if(!photo.getImageMatrix().invert(inverse))
                throw new IllegalArgumentException("Reference point not available");
            float[] p={x,y};
            inverse.mapPoints(p);
            AutoWhiteBalance.Estimate wb=NeutralPointWB.measure(shown,p[0],p[1]);
            neutralPickMode=false;
            if(wb.confidence==0f) {
                status.setText("Reference already appears neutral");
                Toast.makeText(this,"Reference already neutral; WB unchanged",
                        Toast.LENGTH_LONG).show();
                drawControls();
                return;
            }
            checkpoint();
            edit.wbRed=clamp(edit.wbRed*wb.red,.78f,1.24f);
            edit.wbGreen=clamp(edit.wbGreen*wb.green,.92f,1.08f);
            edit.wbBlue=clamp(edit.wbBlue*wb.blue,.78f,1.24f);
            edit.wbConfidence=1f;
            edit.wbPicked=true;
            status.setText("WB adjusted using selected neutral");
            render();drawControls();
            Toast.makeText(this,"Neutral WB applied — use Undo to compare.",
                    Toast.LENGTH_LONG).show();
        } catch(IllegalArgumentException ex) {
            Toast.makeText(this,ex.getMessage(),Toast.LENGTH_LONG).show();
        }
    }

    /** Two user-picked points provide a reference even under perspective. */
    private void startGuide() {
        if(previewSource==null){openImage();return;}
        if(guideMode){endGuide();status.setText("Guided Level cancelled");return;}
        neutralPickMode=false;
        // Guided reference points always use the fit-to-screen photo coordinates.
        photo.resetZoom();
        guideMode=true;
        guideStep=0;
        guideOverlay.invalidate();
        status.setText("Guided Level · tap FIRST point on a straight edge");
        Toast.makeText(this,"Tap two distant points on the SAME horizontal "+
                "or vertical edge. Tap Guided Level again to cancel.",
                Toast.LENGTH_LONG).show();
    }
    private void endGuide() {
        guideMode=false;guideStep=0;
        if(guideOverlay!=null)guideOverlay.invalidate();
    }
    private boolean withinPhoto(float x,float y) {
        if(photo.getDrawable()==null)return false;
        int iw=photo.getDrawable().getIntrinsicWidth();
        int ih=photo.getDrawable().getIntrinsicHeight();
        if(iw<=0||ih<=0||photo.getWidth()<=0||photo.getHeight()<=0)return false;
        float factor=Math.min(photo.getWidth()/(float)iw,
                photo.getHeight()/(float)ih);
        float left=(photo.getWidth()-iw*factor)*.5f;
        float top=(photo.getHeight()-ih*factor)*.5f;
        return x>=left&&x<=left+iw*factor&&y>=top&&y<=top+ih*factor;
    }
    private void guidedTap(float x,float y) {
        if(!guideMode||previewSource==null)return;
        if(!withinPhoto(x,y)) {
            Toast.makeText(this,"Tap inside the photograph",Toast.LENGTH_SHORT).show();
            return;
        }
        if(guideStep==0) {
            guideX=x;guideY=y;guideStep=1;guideOverlay.invalidate();
            status.setText("Guided Level · tap SECOND point on that SAME edge");
            return;
        }
        float dx=x-guideX,dy=y-guideY;
        if(Math.hypot(dx,dy)<dp(60)) {
            Toast.makeText(this,"Select two points farther apart",Toast.LENGTH_SHORT).show();
            return;
        }
        boolean horizontal=Math.abs(dx)>=1.35f*Math.abs(dy);
        boolean vertical=Math.abs(dy)>=1.35f*Math.abs(dx);
        if(!horizontal&&!vertical) {
            Toast.makeText(this,"Choose a nearly horizontal or vertical reference",
                    Toast.LENGTH_LONG).show();
            return;
        }
        float delta;
        if(horizontal) {
            if(dx<0){dx=-dx;dy=-dy;}
            delta=(float)-Math.toDegrees(Math.atan2(dy,dx));
        } else {
            if(dy<0){dx=-dx;dy=-dy;}
            delta=(float)Math.toDegrees(Math.atan2(dx,dy));
        }
        if(Math.abs(delta)>StraightenEngine.MANUAL_MAX) {
            Toast.makeText(this,"That reference is too sloped; select another edge",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final float angle=Math.max(-StraightenEngine.MANUAL_MAX,
                Math.min(StraightenEngine.MANUAL_MAX,
                        edit.straightenAngle+delta));
        endGuide();
        new AlertDialog.Builder(this)
                .setTitle(horizontal?"Level selected horizontal":"Level selected vertical")
                .setMessage(String.format(Locale.US,
                        "Additional correction: %+.1f°\nFinal angle: %+.1f°\n\n"+
                        "This levels YOUR chosen edge. Perspective may make "+
                        "other edges lean. Rotation will crop the outer corners.",
                        delta,angle))
                .setNegativeButton("Keep current",null)
                .setPositiveButton("Apply",(dialog,which)->{
                    if(previewSource==null)return;
                    if(Math.abs(edit.straightenAngle-angle)>.04f) {
                        checkpoint();edit.straightenAngle=angle;
                    }
                    render();drawControls();
                }).show();
    }

    private void autoStraighten() {
        final Bitmap original=previewSource;
        if(original==null){openImage();return;}
        int serial=generation.incrementAndGet();
        previewWork.getQueue().clear();
        status.setText("Scanning long horizontal and vertical line segments…");
        previewWork.execute(()->{
            try {
                StraightenEngine.Estimate estimate=StraightenEngine.analyse(original);
                ui.post(()->{
                    if(serial!=generation.get()||original!=previewSource)return;
                    if(estimate.requiresConfirmation) {
                        // Do not silently rotate by 4–10 degrees merely
                        // because rooflines, paths or perspective dominate.
                        // The user explicitly approves potential frame loss.
                        status.setText(String.format(Locale.US,
                                "Straighten suggestion %+.1f° · review before applying",
                                estimate.correction));
                        new AlertDialog.Builder(this)
                                .setTitle("Review suggested straightening")
                                .setMessage(String.format(Locale.US,
                                        "Suggested rotation: %+.1f°\n\n"+
                                        "This photo contains lines that may be affected by perspective. "+
                                        "Applying this angle will crop some edges. "+
                                        "Keep the original if it already looks level. "+
                                        "You can fine-tune the manual slider or Undo afterwards.",
                                        estimate.correction))
                                .setNegativeButton("Keep original",(dialog,which)->
                                        status.setText("Straighten suggestion skipped"))
                                .setPositiveButton("Apply suggested angle",(dialog,which)->{
                                    if(serial!=generation.get()||original!=previewSource)return;
                                    if(Math.abs(edit.straightenAngle-estimate.correction)>.04f) {
                                        checkpoint();
                                        edit.straightenAngle=estimate.correction;
                                    }
                                    render();drawControls();
                                })
                                .show();
                        return;
                    }
                    if(!estimate.reliable) {
                        status.setText("Auto straighten · perspective conflict · try Guided Level");
                        Toast.makeText(this,
                                "Perspective conflict · try Guided Level or manual slider",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    boolean changed=Math.abs(edit.straightenAngle-estimate.correction)>.04f;
                    if(changed) {
                        checkpoint();
                        edit.straightenAngle=estimate.correction;
                    }
                    if(estimate.alreadyLevel) {
                        if(changed)render();
                        drawControls();
                        status.setText("Auto straighten · already level (0.0°)");
                        Toast.makeText(this,"Photo already level · no rotation needed",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    render();drawControls();
                    Toast.makeText(this,String.format(Locale.US,
                            "Auto straightened %+.1f° · fine-tune manually",
                            estimate.correction),Toast.LENGTH_LONG).show();
                });
            } catch(RuntimeException | OutOfMemoryError ex) {
                showError("Auto straighten failed: "+ex.getMessage());
            }
        });
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
        if(adjustGroup==0) {
            TextView pick=label(neutralPickMode?"CANCEL WB PICK":"◎ PICK NEUTRAL WB",11,Color.WHITE);
            pick.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
            pick.setPadding(dp(13),0,dp(13),0);
            GradientDrawable shape=background(neutralPickMode ?
                    Color.rgb(32,97,147):Color.rgb(35,57,80),9);
            shape.setStroke(dp(1),Color.rgb(69,115,158));
            pick.setBackground(shape);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(33));
            lp.leftMargin=dp(9);
            bar.addView(pick,lp);
            pick.setOnClickListener(v->toggleNeutralPick());
        }
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
        v.rotation = edit.rotation; v.ratio = edit.ratio;
        v.straightenAngle=edit.straightenAngle;
        v.perspectiveVertical=edit.perspectiveVertical;
        v.perspectiveHorizontal=edit.perspectiveHorizontal;
        v.look = name;
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
                    edit.wbPicked=false;
                    edit.autoZones = scene.zones;
                    edit.autoLowKey = scene.protectedLowKey;
                    edit.autoAtmosphere = scene.atmosphereDescription;
                    edit.autoMacroProtected = scene.protectedMacroAtmosphere;
                    edit.autoImportantRegion = scene.protectedImportantRegion;
                    edit.autoBlackProtected = scene.protectedNearBlack;
                    edit.autoStrength = 100f;
                    edit.look = "Auto";
                    // Auto tone and both detail groups are all scene dependent.
                    applyRecommendations(details,0);
                    adjustGroup = 0;
                    activeAdjustment = "Auto Strength";
                    render();
                    drawControls();
                    Toast.makeText(this, String.format(Locale.US,
                            "Scene Intelligent Auto: %+.2f EV · %s · WB %s",
                            scene.exposure,
                            scene.protectedImportantRegion ? "important region protected" :
                                    scene.protectedNearBlack ? "deep blacks preserved" :
                                    scene.protectedMacroAtmosphere ? "overall dark atmosphere protected" :
                                    scene.protectedAtmosphere ? scene.atmosphereDescription + " protected" :
                                    scene.protectedLowKey ? "low-key protected" : "scene analysed",
                            whiteBalance.confidence > 0f ? "corrected" : "preserved"),
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

    // Album mode asks for read-only library access. The existing photo picker is still available.
    private void choosePhotoSource() {
        // Keep the entry clean; the 24-photo selection picker remains available under More.
        final android.app.Dialog dialog = new android.app.Dialog(this);
        TextView entry = label("Browse album", 17, Color.WHITE);
        entry.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        entry.setGravity(Gravity.CENTER);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(Color.argb(239, 18, 30, 45));
        surface.setCornerRadius(dp(17));
        surface.setStroke(dp(1), Color.rgb(60, 88, 119));
        entry.setBackground(surface);
        entry.setContentDescription("Browse album");
        entry.setOnClickListener(v -> { dialog.dismiss(); browseAlbums(); });
        dialog.setContentView(entry);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams params = window.getAttributes();
            params.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(370));
            params.height = dp(70);
            params.dimAmount = 0.40f;
            window.setAttributes(params);
        }
    }

    private void browseAlbums() {
        String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            showAlbumBrowser();
            return;
        }
        if (Build.VERSION.SDK_INT >= 34 &&
                checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                        == PackageManager.PERMISSION_GRANTED) {
            explainPartialAlbumAccess();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Browse complete photo albums")
                .setMessage("To browse a whole album and swipe between all of its pictures, Photo Studio needs read-only access to your photos. Your originals are never changed. You can also keep using the individual photo picker without this permission.\n\nChoose Allow all photos when Android asks.")
                .setPositiveButton("Continue", (d, w) -> requestAlbumPermission())
                .setNegativeButton("Use photo picker", (d, w) -> openImage())
                .show();
    }

    private void requestAlbumPermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            }, ALBUM_PERMISSION);
        } else {
            requestPermissions(new String[]{
                    Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES
                            : Manifest.permission.READ_EXTERNAL_STORAGE
            }, ALBUM_PERMISSION);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != ALBUM_PERMISSION) return;
        String fullPermission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(fullPermission) == PackageManager.PERMISSION_GRANTED) {
            showAlbumBrowser();
        } else if (Build.VERSION.SDK_INT >= 34 &&
                checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                        == PackageManager.PERMISSION_GRANTED) {
            explainPartialAlbumAccess();
        } else {
            Toast.makeText(this, "Album access not granted. Individual photo picker is available.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void explainPartialAlbumAccess() {
        new AlertDialog.Builder(this).setTitle("Selected photos only")
                .setMessage("Android currently gives Photo Studio access to selected photos only. For a complete album, change Photos and videos permission to Allow all in App info. You can also browse the accessible photos now.")
                .setPositiveButton("Browse accessible", (d, w) -> showAlbumBrowser())
                .setNeutralButton("App settings", (d, w) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void showAlbumBrowser() {
        if (albumBrowser == null)
            albumBrowser = new AlbumBrowser(this, (photos, chosenIndex, name) -> {
                photoSelection.clear();
                photoSelection.addAll(photos);
                editsByPhoto.clear();
                activeAlbumName = name;
                photoIndex = chosenIndex;
                loadChosenPhoto(photoSelection.get(photoIndex));
            });
        albumBrowser.showAlbums();
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
            photoCounter.setText("‹  " + (activeAlbumName.isEmpty() ? "" : activeAlbumName + " · ") +
                    (photoIndex+1) + " / " + photoSelection.size() + "  ›");
            photoCounter.setContentDescription("Photo "+(photoIndex+1)+" of "+
                    photoSelection.size()+". Tap to show next.");
        } else photoCounter.setVisibility(View.GONE);
    }

    private void changePhoto(int direction) {
        if(photoSelection.size()<2 || photoIndex<0)return;
        endGuide();
        neutralPickMode=false;
        int next=photoIndex+direction;
        if(next<0 || next>=photoSelection.size()) {
            Toast.makeText(this,next<0?"First photo":(activeAlbumName.isEmpty() ? "Last selected photo" : "Last photo in album"),
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
        endGuide();
        neutralPickMode=false;
        int serial=generation.incrementAndGet();
        if(pendingSliderRender!=null) {
            ui.removeCallbacks(pendingSliderRender);
            pendingSliderRender=null;
        }
        previewWork.getQueue().clear();
        openingWork.getQueue().clear();
        Bitmap old=previewSource,oldFast=fastPreviewSource,oldRender=renderedPreview;
        input=null;previewSource=null;fastPreviewSource=null;renderedPreview=null;
        photo.resetZoom();
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
                    photo.resetZoom();
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
        activeAlbumName = "";
        editsByPhoto.clear();
        photoIndex=0;
        loadChosenPhoto(photoSelection.get(0));
    }

    private Bitmap decode(Uri uri, int dimension, long pixelLimit) throws Exception {
        return decodeBitmap(this,uri,dimension,pixelLimit,false);
    }

    static Bitmap decodeForExport(Context context,Uri uri,int dimension,long pixelLimit)
            throws Exception {
        return decodeBitmap(context,uri,dimension,pixelLimit,true);
    }

    private static Bitmap decodeBitmap(Context context,Uri uri,int dimension,long pixelLimit,
                                       boolean mutable) throws Exception {
        ImageDecoder.Source src = ImageDecoder.createSource(context.getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            if(mutable)decoder.setMutableRequired(true);
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
                                "AUTO 4-LAYER+  •  %+.2f EV%s  •  WB %s",
                                snapshot.exposure,
                                snapshot.autoImportantRegion ? " · Important region" :
                                        snapshot.autoBlackProtected ? " · Deep blacks protected" :
                                        snapshot.autoMacroProtected ? " · 4×4 atmosphere protected" :
                                        !snapshot.autoAtmosphere.isEmpty() ?
                                        " · " + snapshot.autoAtmosphere + " protected" :
                                        snapshot.autoLowKey ? " · Low-key protected" : "",
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
        return process(src,e,cancelled,false);
    }
    static Bitmap processForExport(Bitmap src,Edit edit) {
        // The global colour/tone pass reads a complete source row before
        // writing it. Reusing mutable decoded pixels is algorithmically
        // identical but avoids allocating a second 50MP ARGB bitmap.
        if(!src.isMutable())throw new IllegalArgumentException("Export bitmap must be mutable");
        return process(src,edit,()->Thread.currentThread().isInterrupted(),true);
    }
    private static Bitmap process(Bitmap src,Edit e,BooleanSupplier cancelled,boolean reuse) {
        int w = src.getWidth(), h = src.getHeight();
        Bitmap output = reuse ? src : Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
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
                        float wbStrength=e.wbPicked ? 1f : strength;
                        float dr = baseR*(e.wbRed-1f)*wbStrength*colourGate +
                                (warmth+tint)*colourGate;
                        float dg = baseG*(e.wbGreen-1f)*wbStrength*colourGate -
                                tint*colourGate;
                        float db = baseB*(e.wbBlue-1f)*wbStrength*colourGate +
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
        if(Math.abs(e.straightenAngle)>=.015f ||
                Math.abs(e.perspectiveVertical)>=.015f ||
                Math.abs(e.perspectiveHorizontal)>=.015f) {
            if(cancelled.getAsBoolean()) {
                rotated.recycle();
                throw new CancellationException();
            }
            Bitmap transformed=TransformEngine.render(rotated,
                    e.straightenAngle,e.perspectiveVertical,
                    e.perspectiveHorizontal,cancelled);
            if(transformed!=rotated)rotated.recycle();
            rotated=transformed;
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

    static boolean hasNoEdits(Edit e) { return noEdits(e); }
    private static boolean noEdits(Edit e) {
        return e.rotation == 0 && Math.abs(e.straightenAngle)<.015f &&
                Math.abs(e.perspectiveVertical)<.015f &&
                Math.abs(e.perspectiveHorizontal)<.015f &&
                e.ratio == 0f && e.autoZones == null &&
                e.exposure == 0f && e.contrast == 0f && e.highlights == 0f &&
                e.shadows == 0f && e.saturation == 0f && e.warmth == 0f &&
                e.tint == 0f && e.wbConfidence == 0f &&
                !detailSettings(e).enabled();
    }

    private void save(boolean share) {
        if(input==null) {choosePhotoSource();return;}
        if(PhotoExportService.isBusy()) {
            Toast.makeText(this,"A JPEG export is already processing",Toast.LENGTH_SHORT).show();
            return;
        }
        Uri source=input;
        Edit snapshot=edit.copy();
        boolean started=PhotoExportService.begin(this,source,snapshot,jpegQuality,share);
        if(!started) {
            Toast.makeText(this,"Could not start export; try again",Toast.LENGTH_LONG).show();
            return;
        }
        status.setText("Saving in background · full-resolution JPEG Q"+jpegQuality);
        Toast.makeText(this,"Export started · safe to switch apps or lock screen",
                Toast.LENGTH_SHORT).show();
    }

    private void more() {
        String[] options = {"Browse full photo album", "Modern photo picker", "Export / share",
                "JPEG quality", "Reset edits", "About / limitations"};
        new AlertDialog.Builder(this).setTitle("Photo Studio")
                .setItems(options, (dialog, which) -> {
                    if (which==0) browseAlbums();
                    if (which==1) openImage();
                    if (which==2) exportDialog();
                    if (which==3) chooseQuality();
                    if (which==4 && previewSource != null) {
                        checkpoint(); edit = new Edit(); render(); drawControls();
                    }
                    if (which==5) about();
                }).show();
    }

    private void about() {
        new AlertDialog.Builder(this).setTitle("Photo Studio · v0.6.20")
                .setMessage("Offline native Android editing prototype.\n\n" +
                        "Working: background JPEG exports and Edit/Share integration, neutral-point WB picker, pinch zoom, double-tap inspection and pan, whole-album browsing, modern Android photo picker, JPEG/HEIC, styled looks, " +
                        "tone/colour adjustments, four-level Auto + structural region and deep-black safeguards, Auto WB, " +
                        "luminance/chroma NR, sharpening, " +
                        "clarity, texture, contrast dehaze, centre crop, rotate 90°, " +
                        "conservative Auto Straighten, Guided Level using two tapped points, " +
                        "manual +/-15° angle, full and balanced perspective auto, " +
                        "vertical/horizontal skew and alignment grid, " +
                        "corner-safe fill crop, " +
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
        if (albumBrowser != null) albumBrowser.close();
        previewWork.shutdownNow(); openingWork.shutdownNow();
        super.onDestroy();
    }
}
