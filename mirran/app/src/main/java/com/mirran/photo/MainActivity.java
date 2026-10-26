package com.mirran.photo;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
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
    private static final String[] PRESENCE={"Clarity"};
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
        TextView cfg=text("SETTINGS  ⋯",11,BLUE,true);cfg.setPadding(dp(10),dp(12),0,dp(12));
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
        stage.setOnTouchListener((v,event)->{
            if(originalPreview==null)return false;
            if(event.getAction()==MotionEvent.ACTION_DOWN) {
                photograph.setImageBitmap(originalPreview);stageTag.setText("ORIGINAL · HOLD TO COMPARE");
                stageTag.setVisibility(View.VISIBLE);return true;
            }
            if(event.getAction()==MotionEvent.ACTION_UP||event.getAction()==MotionEvent.ACTION_CANCEL) {
                photograph.setImageBitmap(editedPreview!=null?editedPreview:originalPreview);
                stageTag.setText("EDITED · HOLD TO COMPARE");return true;
            }
            return true;
        });

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
        for(String label:new String[]{"Light","Colour","Presence"}) {
            TextView tab=text(label.toUpperCase(Locale.US),11,activeTab.equals(label)?Color.WHITE:BLUE,true);
            tab.setGravity(Gravity.CENTER);tab.setBackground(bg(activeTab.equals(label)?NAVY:Color.WHITE,12));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(34),1);p.rightMargin=dp(5);
            tabRow.addView(tab,p);
            tab.setOnClickListener(v->{activeTab=label;drawTabs();drawControls();});
        }
    }
    private void drawControls() {
        controls.removeAllViews();
        String[] list=activeTab.equals("Light")?LIGHT:activeTab.equals("Colour")?COLOUR:PRESENCE;
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
            bar.setEnabled(chosen!=null&&!busy);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onStartTrackingTouch(SeekBar b){}
                public void onStopTrackingTouch(SeekBar b){}
                public void onProgressChanged(SeekBar b,int progress,boolean fromUser) {
                    if(!fromUser)return;
                    recipe.set(key,(progress-100)/100f);
                    amount.setText(recipe.amount(key));
                    chip.setText(recipe.cloud?"●  AI EDIT · MANUAL REFINEMENT":"●  PERSONAL EDIT");
                    ui.removeCallbacks(delayedRender);ui.postDelayed(delayedRender,110);
                }
            });
        }
    }
    private void refreshAction(){
        if(mainButton==null)return;
        mainButton.setEnabled(!busy);saveButton.setEnabled(!busy&&chosen!=null);
        mainButton.setText(busy?"WORKING…":chosen==null?"＋  OPEN PHOTO":"✦  REFINE PHOTO");
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
        chosen=data.getData();
        try {getContentResolver().takePersistableUriPermission(chosen,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
        catch(Exception ignored){}
        // Switching photos never requires exporting. Canceling the picker retains the current edit.
        // Invalidate any queued preview renders from the previous photograph.
        ui.removeCallbacks(delayedRender);
        renderGeneration++;
        recipe=new EditRecipe();originalPreview=null;editedPreview=null;metrics=null;
        photograph.setImageDrawable(null);
        stageTag.setVisibility(View.GONE);emptyState.setVisibility(View.GONE);
        setBusy(true,"Opening photograph…");
        Uri uri=chosen;
        work.execute(()->{
            try {
                Bitmap bitmap=decode(uri,1800);
                AutoAnalyzer.Metrics sample=AutoAnalyzer.measure(bitmap);
                ui.post(()->{
                    if(chosen!=uri)return;
                    originalPreview=bitmap;editedPreview=bitmap;metrics=sample;
                    photograph.setImageBitmap(bitmap);
                    chip.setText("●  PHOTO READY");
                    summary.setText("Photograph loaded. Tap Refine for an individual tonal assessment. Hold the image for before/after.");
                    stageTag.setText("ORIGINAL · HOLD TO COMPARE");stageTag.setVisibility(View.VISIBLE);
                    setBusy(false,null);
                });
            }catch(Throwable ex){ui.post(()->{setBusy(false,"Could not open this image: "+error(ex));emptyState.setVisibility(View.VISIBLE);chosen=null;});}
        });
    }
    private Bitmap decode(Uri uri,int maxDim)throws IOException {
        ImageDecoder.Source src=ImageDecoder.createSource(getContentResolver(),uri);
        return ImageDecoder.decodeBitmap(src,(decoder,info,source)->{
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMutableRequired(true);
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
        work.execute(()->{
            try{
                Bitmap out=src.copy(Bitmap.Config.ARGB_8888,true);
                ToneEngine.applyInPlace(out,plan);
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
        chip.setText(plan.cloud?"●  AI VISUAL ASSESSMENT":"●  LOCAL TONAL ASSESSMENT");
        summary.setText(plan.summary);
        drawControls();renderPreview();
    }
    private void exportPhoto() {
        if(chosen==null||busy)return;
        final Uri input=chosen;final EditRecipe plan=recipe.copy();
        setBusy(true,"Rendering original-resolution JPEG…");
        work.execute(()->{
            Uri target=null;
            try {
                Bitmap full=decode(input,0);
                if((long)full.getWidth()*full.getHeight()>60000000L) {
                    full.recycle();throw new IOException("Image exceeds the current 60 MP export safety limit");
                }
                ToneEngine.applyInPlace(full,plan);
                ContentValues values=new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME,"MIRRAN_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".jpg");
                values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/MIRRAN");
                values.put(MediaStore.Images.Media.IS_PENDING,1);
                target=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
                if(target==null)throw new IOException("Gallery refused new image");
                try(OutputStream stream=getContentResolver().openOutputStream(target)){
                    if(stream==null||!full.compress(Bitmap.CompressFormat.JPEG,95,stream))throw new IOException("Could not write JPEG");
                }
                full.recycle();
                values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);
                getContentResolver().update(target,values,null,null);
                ui.post(()->{setBusy(false,"Saved to Pictures/MIRRAN as a full-resolution JPEG. The original was not changed.");toast("Export saved");});
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
        ui.removeCallbacks(delayedRender);work.shutdownNow();super.onDestroy();
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