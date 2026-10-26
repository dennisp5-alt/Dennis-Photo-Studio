package com.mirran.photo;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int BLUE=Color.rgb(8,84,196), NAVY=Color.rgb(10,31,66);
    private static final int PALE=Color.rgb(246,249,253), MUTED=Color.rgb(93,111,134);
    private static final String[] LIGHT={"Exposure","Highlights","Shadows","Contrast"};
    private static final String[] COLOUR={"Vibrance","Warmth"};
    private static final String[] PRESENCE={"Clarity","Denoise","Dehaze"};
    private static final String[] MASKING={"Subject","Background"};
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private Uri chosen;
    private Bitmap originalPreview,editedPreview;
    private EditRecipe recipe=new EditRecipe();
    private AutoAnalyzer.Metrics metrics;
    private ImageView photograph;
    private TextView stageTag,summary,chip,actionText;
    private LinearLayout controls,tabRow;
    private View emptyState;
    private Button mainButton,saveButton;
    private TextView albumStatus,albumButton;
    private TextView maskStatus;
    private SubjectVision subjectVision;
    private volatile SubjectMask currentMask;
    private final LinkedHashMap<String,SubjectMask> recentMasks =
        new LinkedHashMap<String,SubjectMask>(8,.75f,true){
            @Override protected boolean removeEldestEntry(Map.Entry<String,SubjectMask> e){
                return size()>4;
            }
        };
    // Album access is scoped to in-session permissions; no automatic exports.
    private final ArrayList<Uri> albumPhotos=new ArrayList<>();
    private final LinkedHashMap<String,EditRecipe> sessionEdits=new LinkedHashMap<>();
    private String albumName="";
    private int albumIndex=-1,loadSerial=0;
    private float touchX,touchY;
    private boolean showingCompare=false;
    private final Runnable showCompare=this::displayOriginalOnHold;
    private String activeTab="Light";
    private int renderGeneration=0;
    private boolean busy=false;
    private final Runnable delayedRender=this::renderPreview;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().getDecorView().setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars());
            if(v.getPaddingTop()!=safe.top||v.getPaddingBottom()!=safe.bottom)
                v.setPadding(0,safe.top,0,safe.bottom);
            return insets;
        });
        makeUI();
        // The model is optional. Its first use may require a Google Play
        // services download; manual editing always remains usable.
        try{subjectVision=new SubjectVision();}
        catch(Throwable problem){subjectVision=null;}
    }
    private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    private GradientDrawable bg(int color,int radius) {
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private GradientDrawable stroke(int color,int border,int radius) {
        GradientDrawable d=bg(color,radius);d.setStroke(dp(1),border);return d;
    }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView t=new TextView(this);t.setText(value);t.setTextColor(color);t.setTextSize(size);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        return t;
    }
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout horizontal(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void add(LinearLayout parent,View view,int w,int h){parent.addView(view,new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h)));}
    private void makeUI() {
        LinearLayout root=vertical();root.setBackgroundColor(PALE);setContentView(root);
        LinearLayout header=horizontal();header.setPadding(dp(18),dp(10),dp(17),dp(10));
        MarkView mark=new MarkView(this);add(header,mark,42,42);
        LinearLayout brand=vertical();brand.setPadding(dp(12),0,0,0);
        TextView name=text("M I R R A N",18,NAVY,true);add(brand,name,-2,24);
        TextView sub=text("INTELLIGENT PHOTO STUDIO",9,MUTED,false);add(brand,sub,-2,16);
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        albumButton=text("ALBUMS",11,BLUE,true);
        albumButton.setPadding(dp(6),dp(12),dp(6),dp(12));
        albumButton.setContentDescription("Browse photo albums with swipe navigation");
        albumButton.setOnClickListener(v->openAlbums());
        header.addView(albumButton);
        TextView cfg=text("SETTINGS ⋯",11,BLUE,true);cfg.setPadding(dp(10),dp(12),0,dp(12));
        cfg.setOnClickListener(v->showServerSettings());
        header.addView(cfg);root.addView(header);

        FrameLayout stage=new FrameLayout(this);stage.setBackground(bg(Color.rgb(229,236,247),18));
        LinearLayout.LayoutParams stageLp=new LinearLayout.LayoutParams(-1,0,1);
        stageLp.setMargins(dp(13),0,dp(13),dp(10));
        root.addView(stage,stageLp);
        photograph=new ImageView(this);photograph.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stage.addView(photograph,new FrameLayout.LayoutParams(-1,-1));
        stageTag=text("ORIGINAL",10,Color.WHITE,true);
        stageTag.setBackground(bg(Color.argb(195,9,28,59),10));
        stageTag.setPadding(dp(9),dp(4),dp(9),dp(4));
        FrameLayout.LayoutParams lbl=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START);
        lbl.setMargins(dp(13),dp(13),0,0);stage.addView(stageTag,lbl);stageTag.setVisibility(View.GONE);
        LinearLayout empty=vertical();empty.setGravity(Gravity.CENTER);empty.setPadding(dp(22),0,dp(22),0);
        TextView placeholder=text("PHOTOGRAPHY, UNDERSTOOD.",12,NAVY,true);
        placeholder.setGravity(Gravity.CENTER);
        TextView guidance=text("Open a photograph to begin a thoughtful, natural edit.",15,MUTED,false);
        guidance.setGravity(Gravity.CENTER);guidance.setPadding(0,dp(16),0,dp(10));
        TextView open=text("+  OPEN A PHOTO",13,BLUE,true);open.setGravity(Gravity.CENTER);
        open.setBackground(stroke(Color.WHITE,Color.rgb(182,207,239),16));open.setPadding(dp(22),dp(13),dp(22),dp(13));
        open.setOnClickListener(v->openPhoto());
        empty.addView(placeholder);empty.addView(guidance);
        LinearLayout.LayoutParams o=new LinearLayout.LayoutParams(-2,-2);o.topMargin=dp(14);empty.addView(open,o);
        stage.addView(empty,new FrameLayout.LayoutParams(-1,-1));emptyState=empty;
        TextView openAlbum=text("▦  BROWSE ALBUMS",12,BLUE,true);
        openAlbum.setGravity(Gravity.CENTER);
        openAlbum.setPadding(dp(18),dp(12),dp(18),dp(12));
        openAlbum.setBackground(stroke(Color.WHITE,Color.rgb(182,207,239),16));
        openAlbum.setOnClickListener(v->openAlbums());
        LinearLayout.LayoutParams oa=new LinearLayout.LayoutParams(-2,-2);oa.topMargin=dp(11);
        empty.addView(openAlbum,oa);
        // A long hold compares original; a horizontal drag changes the album photo.
        // Do not interrupt a swipe by instantly switching the image to original.
        stage.setOnTouchListener((v,event)->{
            if(originalPreview==null||busy)return true;
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    touchX=event.getX();touchY=event.getY();
                    showingCompare=false;
                    ui.removeCallbacks(showCompare);
                    ui.postDelayed(showCompare,290);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if(Math.abs(event.getX()-touchX)>dp(19)||
                       Math.abs(event.getY()-touchY)>dp(19)){
                        ui.removeCallbacks(showCompare);
                        restoreEdited();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    ui.removeCallbacks(showCompare);
                    float dx=event.getX()-touchX,dy=event.getY()-touchY;
                    restoreEdited();
                    if(Math.abs(dx)>dp(65)&&Math.abs(dx)>Math.abs(dy)*1.3f){
                        moveAlbum(dx<0?1:-1);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    ui.removeCallbacks(showCompare);restoreEdited();return true;
            }
            return true;
        });
        LinearLayout albumBar=horizontal();
        albumBar.setPadding(dp(20),dp(2),dp(20),dp(6));
        TextView previous=text("‹",25,BLUE,true);
        previous.setGravity(Gravity.CENTER);
        previous.setContentDescription("Previous photo in album");
        previous.setOnClickListener(v->moveAlbum(-1));
        add(albumBar,previous,35,28);
        albumStatus=text("OPEN AN ALBUM FOR SWIPE NAVIGATION",10,MUTED,true);
        albumStatus.setGravity(Gravity.CENTER);
        albumBar.addView(albumStatus,new LinearLayout.LayoutParams(0,dp(28),1));
        TextView next=text("›",25,BLUE,true);
        next.setGravity(Gravity.CENTER);
        next.setContentDescription("Next photo in album");
        next.setOnClickListener(v->moveAlbum(1));
        add(albumBar,next,35,28);
        root.addView(albumBar);

        LinearLayout report=vertical();report.setPadding(dp(15),dp(13),dp(15),dp(13));
        report.setBackground(stroke(Color.WHITE,Color.rgb(230,235,242),16));
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);
        rp.setMargins(dp(13),0,dp(13),dp(10));root.addView(report,rp);
        LinearLayout reportHeading=horizontal();
        chip=text("●  YOUR PHOTO, INDIVIDUALLY ASSESSED",10,BLUE,true);
        chip.setSingleLine(true);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        reportHeading.addView(chip,new LinearLayout.LayoutParams(0,dp(36),1));
        TextView newPhoto=text("＋  NEW PHOTO",10,BLUE,true);
        newPhoto.setGravity(Gravity.CENTER);
        newPhoto.setContentDescription("Open another photograph without exporting");
        newPhoto.setBackground(stroke(Color.rgb(239,246,255),Color.rgb(200,220,242),10));
        newPhoto.setPadding(dp(10),0,dp(10),0);
        add(reportHeading,newPhoto,-2,36);
        newPhoto.setOnClickListener(v->openPhoto());
        add(report,reportHeading,-1,36);
        summary=text("Open a photo, then tap Refine. AI vision can be connected securely in Settings.",12,MUTED,false);
        summary.setMaxLines(3);report.addView(summary);

        tabRow=horizontal();tabRow.setPadding(dp(13),0,dp(13),0);root.addView(tabRow);
        controls=vertical();controls.setPadding(dp(17),dp(3),dp(17),dp(3));
        root.addView(controls);drawTabs();drawControls();
        LinearLayout actions=horizontal();actions.setPadding(dp(13),dp(8),dp(13),dp(15));
        root.addView(actions);
        mainButton=new Button(this);mainButton.setAllCaps(false);mainButton.setText("✦  REFINE PHOTO");
        mainButton.setTextColor(Color.WHITE);mainButton.setTextSize(13);
        mainButton.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{BLUE,Color.rgb(5,51,129)}){{setCornerRadius(dp(17));}});
        mainButton.setOnClickListener(v->refine());
        actions.addView(mainButton,new LinearLayout.LayoutParams(0,dp(52),1));
        saveButton=new Button(this);saveButton.setAllCaps(false);saveButton.setText("EXPORT");
        saveButton.setTextColor(BLUE);saveButton.setTextSize(12);saveButton.setBackground(stroke(Color.WHITE,Color.rgb(191,211,235),14));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(94),dp(52));sp.leftMargin=dp(8);
        actions.addView(saveButton,sp);saveButton.setOnClickListener(v->exportPhoto());
        refreshAction();
    }
    private void drawTabs() {
        tabRow.removeAllViews();
        for(String label:new String[]{"Light","Colour","Presence","Masks"}) {
            TextView tab=text(label.toUpperCase(Locale.US),11,activeTab.equals(label)?Color.WHITE:BLUE,true);
            tab.setGravity(Gravity.CENTER);tab.setBackground(bg(activeTab.equals(label)?NAVY:Color.WHITE,12));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(34),1);p.rightMargin=dp(5);
            tabRow.addView(tab,p);
            tab.setOnClickListener(v->{activeTab=label;drawTabs();drawControls();});
        }
    }
    private void drawControls() {
        controls.removeAllViews();
        String[] list=activeTab.equals("Light")?LIGHT:
            activeTab.equals("Colour")?COLOUR:activeTab.equals("Masks")?MASKING:PRESENCE;
        // Two rows in Light, one row for the other panels.
        for(String key:list) {
            LinearLayout row=horizontal();
            TextView label=text(key,11,NAVY,true);
            add(row,label,86,29);
            SeekBar bar=new SeekBar(this);bar.setMax(200);
            bar.setProgress(Math.round((recipe.get(key)+1)*100));
            row.addView(bar,new LinearLayout.LayoutParams(0,dp(29),1));
            TextView amount=text(recipe.amount(key),11,BLUE,true);amount.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
            add(row,amount,37,29);
            controls.addView(row,new LinearLayout.LayoutParams(-1,dp(31)));
            boolean semantic=key.equals("Subject")||key.equals("Background");
            bar.setEnabled(chosen!=null&&!busy&&(!semantic||currentMask!=null));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onStartTrackingTouch(SeekBar b){}
                public void onStopTrackingTouch(SeekBar b){}
                public void onProgressChanged(SeekBar b,int progress,boolean fromUser) {
                    if(!fromUser)return;
                    recipe.set(key,(progress-100)/100f);
                    amount.setText(recipe.amount(key));
                    rememberCurrent();
                    chip.setText(recipe.cloud?"●  AI EDIT · MANUAL REFINEMENT":"●  PERSONAL EDIT");
                    ui.removeCallbacks(delayedRender);ui.postDelayed(delayedRender,110);
                }
            });
        }
        if(activeTab.equals("Masks")){
            maskStatus=text(currentMask!=null?
                "LOCAL ML MASK READY · subject and background":
                "Mask pending/unavailable · one-time model download may be needed",
                10,MUTED,false);
            maskStatus.setPadding(0,dp(3),0,dp(3));
            controls.addView(maskStatus);
        }
    }
    private void refreshAction(){
        if(mainButton==null)return;
        mainButton.setEnabled(!busy);saveButton.setEnabled(!busy&&chosen!=null);
        if(albumButton!=null)albumButton.setEnabled(!busy);
        mainButton.setText(busy?"WORKING…":chosen==null?"＋  OPEN PHOTO":"✦  REFINE PHOTO");
        updateAlbumStatus();
    }
    private void updateAlbumStatus(){
        if(albumStatus==null)return;
        albumStatus.setText(albumIndex>=0 && !albumPhotos.isEmpty()
            ? albumName.toUpperCase(Locale.US)+"  ·  "+(albumIndex+1)+" / "+albumPhotos.size()
            : "OPEN AN ALBUM FOR SWIPE NAVIGATION");
    }
    private void displayOriginalOnHold(){
        if(originalPreview!=null && !busy && !showingCompare){
            showingCompare=true;
            photograph.setImageBitmap(originalPreview);
            stageTag.setText("ORIGINAL · RELEASE TO COMPARE");
            stageTag.setVisibility(View.VISIBLE);
        }
    }
    private void restoreEdited(){
        if(showingCompare){
            showingCompare=false;
            photograph.setImageBitmap(editedPreview!=null?editedPreview:originalPreview);
            stageTag.setText("EDITED · HOLD TO COMPARE");
        }
    }
    private void rememberCurrent(){
        if(chosen!=null && originalPreview!=null){
            sessionEdits.put(chosen.toString(),recipe.copy());
        }
    }
    private void moveAlbum(int delta){
        if(busy || albumIndex<0 || albumPhotos.size()<2)return;
        int target=albumIndex+delta;
        if(target<0 || target>=albumPhotos.size()){
            toast(target<0?"First photo in album":"Last photo in album");
            return;
        }
        albumIndex=target;
        loadPhoto(albumPhotos.get(target));
    }
    private void chooseAlbum(ArrayList<Uri> list,int index,String title){
        if(list==null||list.isEmpty()||index<0||index>=list.size())return;
        rememberCurrent();
        albumPhotos.clear();albumPhotos.addAll(list);
        albumIndex=index;
        albumName=title;
        // New album starts a new session; edits remain separately keyed by URI.
        loadPhoto(albumPhotos.get(index));
    }
    private void openAlbums(){
        if(busy)return;
        if(hasAlbumPermission()){
            new AlbumBrowser(this,this::chooseAlbum).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Browse complete albums")
            .setMessage("Swiping through an album requires access to its photos. Choose Allow all photos in the Android permission prompt for full-album navigation. Limited access may show only selected images. The normal photo picker remains available without this permission.")
            .setPositiveButton("Continue",(d,w)->requestAlbumPermission())
            .setNegativeButton("Use Photo Picker",(d,w)->openPhoto())
            .show();
    }
    private boolean hasAlbumPermission(){
        if(Build.VERSION.SDK_INT>=33)
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)==PackageManager.PERMISSION_GRANTED
                || (Build.VERSION.SDK_INT>=34 &&
                    checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")==PackageManager.PERMISSION_GRANTED);
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;
    }
    private void requestAlbumPermission(){
        if(Build.VERSION.SDK_INT>=34){
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"},221);
        }else if(Build.VERSION.SDK_INT>=33){
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES},221);
        }else{
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},221);
        }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request!=221)return;
        if(hasAlbumPermission()){
            new AlbumBrowser(this,this::chooseAlbum).show();
        }else{
            new AlertDialog.Builder(this).setTitle("Album access unavailable")
                .setMessage("You can still open and edit individual photos with Android's private Photo Picker.")
                .setPositiveButton("Open Photo Picker",(d,w)->openPhoto())
                .setNegativeButton("Cancel",null).show();
        }
    }
    private void setBusy(boolean b,String message) {
        busy=b;if(message!=null)summary.setText(message);refreshAction();drawControls();
    }
    private void openPhoto(){
        if(busy)return;
        // Android 13+ offers a modern Photos / Albums gallery with system-controlled
        // read-only access; no broad storage permission is necessary.
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU){
            Intent picker=new Intent(MediaStore.ACTION_PICK_IMAGES);
            picker.setType("image/*");
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if(picker.resolveActivity(getPackageManager())!=null){
                try {
                    startActivityForResult(picker,10);
                    return;
                } catch(ActivityNotFoundException ignored) {
                    // Gracefully fall back to the system document picker.
                }
            }
        }
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent,10);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=10||result!=RESULT_OK||data==null||data.getData()==null)return;
        // A one-off system-picker image has no neighbouring-image permissions.
        // Open Albums instead when continuous album swiping is desired.
        rememberCurrent();
        albumPhotos.clear();albumIndex=-1;albumName="";
        Uri uri=data.getData();
        try {getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
        catch(Exception ignored){}
        loadPhoto(uri);
    }
    private void loadPhoto(Uri uri){
        if(uri==null)return;
        rememberCurrent();
        ui.removeCallbacks(delayedRender);
        ui.removeCallbacks(showCompare);
        restoreEdited();
        renderGeneration++;
        final int serial=++loadSerial;
        chosen=uri;
        currentMask=recentMasks.get(uri.toString());
        setBusy(true,"Opening photograph…");
        work.execute(()->{
            try{
                Bitmap bitmap=decode(uri,1800);
                AutoAnalyzer.Metrics measurement=AutoAnalyzer.measure(bitmap);
                ui.post(()->{
                    if(serial!=loadSerial || !uri.equals(chosen))return;
                    originalPreview=bitmap;editedPreview=bitmap;metrics=measurement;
                    EditRecipe saved=sessionEdits.get(uri.toString());
                    recipe=saved==null?new EditRecipe():saved.copy();
                    photograph.setImageBitmap(bitmap);
                    emptyState.setVisibility(View.GONE);
                    chip.setText(saved==null?"●  PHOTO READY":"●  SAVED SESSION ADJUSTMENTS");
                    summary.setText(saved==null
                        ?"Photo loaded. Swipe to the next album image, or tap Refine. Hold for before/after."
                        :"Restored this photo's adjustments. Swipe for next/previous, or export at JPEG quality 100.");
                    stageTag.setVisibility(View.VISIBLE);
                    stageTag.setText(saved==null?"ORIGINAL · HOLD TO COMPARE":"EDITED · HOLD TO COMPARE");
                    setBusy(false,null);
                    if(saved!=null)renderPreview();
                    if(currentMask==null)requestSubjectMask(bitmap,uri,serial);
                });
            }catch(Throwable ex){
                ui.post(()->{
                    if(serial!=loadSerial)return;
                    chosen=null;originalPreview=null;editedPreview=null;metrics=null;
                    photograph.setImageDrawable(null);
                    emptyState.setVisibility(View.VISIBLE);
                    setBusy(false,"Could not open this image: "+error(ex));
                });
            }
        });
    }
    private void requestSubjectMask(Bitmap preview,Uri uri,int serial){
        if(subjectVision==null)return;
        subjectVision.analyze(preview,new SubjectVision.Callback(){
            @Override public void success(SubjectMask mask){
                if(serial!=loadSerial||!uri.equals(chosen))return;
                currentMask=mask;
                recentMasks.put(uri.toString(),mask);
                if(activeTab.equals("Masks"))drawControls();
                if(Math.abs(recipe.subject)>.001f||Math.abs(recipe.background)>.001f)
                    renderPreview();
            }
            @Override public void unavailable(String reason){
                if(serial!=loadSerial||!uri.equals(chosen))return;
                if(activeTab.equals("Masks"))drawControls();
                // Failure is nonfatal; masks stay disabled, never fabricated.
            }
        });
    }
    private void processEdits(Bitmap bitmap,EditRecipe plan,SubjectMask mask){
        DetailEngine.denoiseInPlace(bitmap,Math.max(0f,plan.denoise));
        ToneEngine.applyInPlace(bitmap,plan);
        DetailEngine.dehazeInPlace(bitmap,Math.max(0f,plan.dehaze));
        DetailEngine.maskedExposureInPlace(bitmap,plan,mask);
    }
    private Bitmap decode(Uri uri,int maxDim)throws IOException {
        ImageDecoder.Source src=ImageDecoder.createSource(getContentResolver(),uri);
        return ImageDecoder.decodeBitmap(src,(decoder,info,source)->{
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMutableRequired(true);
            // Explicitly colour-convert tagged JPEG/HEIC/PNG into an sRGB
            // working image before 8-bit processing and JPEG output.
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
            if(maxDim>0){
                int w=info.getSize().getWidth(),h=info.getSize().getHeight();
                int max=Math.max(w,h);
                if(max>maxDim){float f=maxDim/(float)max;decoder.setTargetSize(Math.max(1,Math.round(w*f)),Math.max(1,Math.round(h*f)));}
            }
        });
    }
    private void renderPreview() {
        if(originalPreview==null)return;
        final int token=++renderGeneration;
        final EditRecipe plan=recipe.copy();
        final Bitmap src=originalPreview;
        final SubjectMask mask=currentMask;
        work.execute(()->{
            try{
                Bitmap out=src.copy(Bitmap.Config.ARGB_8888,true);
                processEdits(out,plan,mask);
                ui.post(()->{
                    if(token==renderGeneration && chosen!=null){
                        editedPreview=out;photograph.setImageBitmap(out);
                        stageTag.setText("EDITED · HOLD TO COMPARE");
                    } else out.recycle();
                });
            }catch(Throwable ex){ui.post(()->summary.setText("Preview problem: "+error(ex)));}
        });
    }
    private void refine() {
        if(chosen==null){openPhoto();return;}
        if(busy||originalPreview==null)return;
        String host=getPreferences(MODE_PRIVATE).getString("host","");
        if(host.isEmpty()){localRefine(null);return;}
        boolean permission=getPreferences(MODE_PRIVATE).getBoolean("cloud_permission",false);
        if(!permission){
            new AlertDialog.Builder(this).setTitle("Private AI assessment")
                .setMessage("A reduced-size copy of this photo will be sent to your configured HTTPS server, then to OpenAI for analysis. Your full-resolution original stays on this device. Continue?")
                .setPositiveButton("Continue",(d,w)->{
                    getPreferences(MODE_PRIVATE).edit().putBoolean("cloud_permission",true).apply();
                    remoteRefine();
                }).setNegativeButton("Use offline assessment",(d,w)->localRefine(null)).show();
        }else remoteRefine();
    }
    private void localRefine(String notice){
        EditRecipe next=AutoAnalyzer.suggest(metrics);
        if(notice!=null)next.summary=notice+" Using local tonal assessment. "+next.summary;
        applyPlan(next);
    }
    private void remoteRefine() {
        setBusy(true,"AI is assessing this image through your private server…");
        final String host=getPreferences(MODE_PRIVATE).getString("host","");
        final String token=getPreferences(MODE_PRIVATE).getString("token","");
        final Bitmap src=originalPreview;final AutoAnalyzer.Metrics stat=metrics;
        work.execute(()->{
            try {
                EditRecipe plan=RemoteAi.analyze(host,token,src,stat);
                ui.post(()->{setBusy(false,null);applyPlan(plan);});
            }catch(Exception ex){
                ui.post(()->{setBusy(false,null);localRefine("Online assessment unavailable ("+error(ex)+").");});
            }
        });
    }
    private void applyPlan(EditRecipe plan){
        recipe=plan;
        rememberCurrent();
        chip.setText(plan.cloud?"●  AI VISUAL ASSESSMENT":"●  LOCAL TONAL ASSESSMENT");
        summary.setText(plan.summary);
        drawControls();renderPreview();
    }
    private void exportPhoto() {
        if(chosen==null||busy)return;
        if(currentMask==null && (Math.abs(recipe.subject)>.001f
            ||Math.abs(recipe.background)>.001f)){
            new AlertDialog.Builder(this).setTitle("Subject mask unavailable")
                .setMessage("This edit uses a semantic mask that has not loaded. Return to Masks when the on-device model is available, then export. No unmasked substitute will be applied.")
                .setPositiveButton("OK",null).show();
            return;
        }
        final Uri input=chosen;final EditRecipe plan=recipe.copy();
        final SubjectMask mask=currentMask;
        setBusy(true,"Rendering original-resolution JPEG…");
        work.execute(()->{
            Uri target=null;
            try {
                Bitmap full=decode(input,0);
                if((long)full.getWidth()*full.getHeight()>60000000L) {
                    full.recycle();throw new IOException("Image exceeds the current 60 MP export safety limit");
                }
                processEdits(full,plan,mask);
                ContentValues values=new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME,"MIRRAN_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".jpg");
                values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/MIRRAN");
                values.put(MediaStore.Images.Media.IS_PENDING,1);
                target=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
                if(target==null)throw new IOException("Gallery refused new image");
                try(OutputStream stream=getContentResolver().openOutputStream(target)){
                    if(stream==null||!full.compress(Bitmap.CompressFormat.JPEG,100,stream))throw new IOException("Could not write JPEG");
                }
                full.recycle();
                values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);
                getContentResolver().update(target,values,null,null);
                ui.post(()->{setBusy(false,"Saved to Pictures/MIRRAN at JPEG quality 100, full original dimensions. The original was not changed.");toast("Export saved");});
            }catch(Throwable ex){
                if(target!=null)try{getContentResolver().delete(target,null,null);}catch(Exception ignored){}
                ui.post(()->{setBusy(false,"Export failed: "+error(ex));toast("Could not export image");});
            }
        });
    }
    private String error(Throwable ex) {return ex.getMessage()!=null?ex.getMessage():ex.getClass().getSimpleName();}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void showServerSettings() {
        LinearLayout area=vertical();area.setPadding(dp(22),dp(8),dp(22),0);
        TextView intro=text("Online AI requires your own secure server. Leave blank for the local tonal editor.",13,MUTED,false);intro.setPadding(0,0,0,dp(10));area.addView(intro);
        EditText host=new EditText(this);host.setSingleLine(true);host.setTextSize(13);
        host.setHint("https://your-private-server.example");
        host.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        host.setText(getPreferences(MODE_PRIVATE).getString("host",""));area.addView(host);
        EditText token=new EditText(this);token.setSingleLine(true);token.setTextSize(13);
        token.setHint("Private server access token");
        token.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(getPreferences(MODE_PRIVATE).getString("token",""));area.addView(token);
        new AlertDialog.Builder(this).setTitle("MIRRAN · Private AI").setView(area)
            .setPositiveButton("Save",(d,w)->{
                String h=host.getText().toString().trim(),t=token.getText().toString().trim();
                if(!h.isEmpty()&&!h.startsWith("https://")){toast("HTTPS is required. Settings not saved.");return;}
                getPreferences(MODE_PRIVATE).edit().putString("host",h).putString("token",t)
                    .putBoolean("cloud_permission",false).apply();
                chip.setText(h.isEmpty()?"●  LOCAL PHOTO EDITOR":"●  PRIVATE AI CONFIGURED");
                summary.setText(h.isEmpty()?"Local tonal assessment ready.":"Private AI endpoint saved. Use Refine to send a reduced-size image for analysis.");
            }).setNegativeButton("Cancel",null).show();
    }
    @Override protected void onDestroy() {
        ui.removeCallbacks(delayedRender);
        ui.removeCallbacks(showCompare);
        work.shutdownNow();
        if(subjectVision!=null)subjectVision.close();
        super.onDestroy();
    }

    private static final class MarkView extends View {
        private final Paint p=new Paint(3);
        MarkView(Context c){super(c);}
        @Override protected void onDraw(Canvas c){
            int w=getWidth(),h=getHeight();float sx=w/100f,sy=h/100f;
            p.setShader(new LinearGradient(0,0,w,h,new int[]{Color.rgb(3,29,81),Color.rgb(13,112,231)},
                null,Shader.TileMode.CLAMP));c.drawRoundRect(0,0,w,h,w*.25f,h*.25f,p);p.setShader(null);
            p.setColor(Color.rgb(234,193,140));c.drawCircle(w*.70f,h*.28f,w*.085f,p);
            p.setColor(Color.WHITE);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(w*.038f);
            p.setStrokeJoin(Paint.Join.ROUND);
            Path path=new Path();path.moveTo(13*sx,77*sy);path.lineTo(45*sx,25*sy);
            path.lineTo(71*sx,65*sy);path.lineTo(81*sx,54*sy);path.lineTo(94*sx,77*sy);
            c.drawPath(path,p);p.setStyle(Paint.Style.FILL);
        }
    }
}