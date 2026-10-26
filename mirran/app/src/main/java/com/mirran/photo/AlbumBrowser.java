package com.mirran.photo;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentUris;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Read-only, permission-aware MediaStore browser for swipeable album sessions. */
public final class AlbumBrowser {
    public interface OnSelect {
        void selected(ArrayList<Uri> photoUris, int photoIndex, String albumName);
    }
    private static final int NAVY=Color.rgb(10,31,66);
    private static final int BLUE=Color.rgb(8,84,196);
    private static final int MUTED=Color.rgb(86,105,129);
    private final Activity activity;
    private final OnSelect callback;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService loader=Executors.newFixedThreadPool(3);
    private final LinkedHashMap<String,Album> albums=new LinkedHashMap<>();
    // Bitmaps in this cache are at most 240px and only used as grid thumbnails.
    private final LruCache<String,Bitmap> thumbs=new LruCache<String,Bitmap>(24*1024*1024){
        @Override protected int sizeOf(String key,Bitmap bitmap){
            return bitmap.getByteCount();
        }
    };
    private Dialog dialog;
    private AlertDialog loading;
    private LinearLayout root;
    private boolean stopped=false;
    private Album selectedAlbum;

    private static final class Album {
        final String id,name;
        final ArrayList<Uri> photos=new ArrayList<>();
        Album(String key,String label) {id=key;name=label;}
    }
    public AlbumBrowser(Activity a,OnSelect cb){activity=a;callback=cb;}
    private int dp(float v){return (int)(activity.getResources().getDisplayMetrics().density*v+.5f);}
    private GradientDrawable bg(int colour,int radius){
        GradientDrawable d=new GradientDrawable();
        d.setColor(colour);
        d.setCornerRadius(dp(radius));
        return d;
    }
    private TextView label(String name,int size,int colour,boolean bold){
        TextView t=new TextView(activity);
        t.setText(name);t.setTextColor(colour);t.setTextSize(size);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        return t;
    }
    public void show(){
        if(stopped)return;
        loading=new AlertDialog.Builder(activity)
            .setTitle("Opening Albums")
            .setMessage("Reading available photo albums…")
            .setNegativeButton("Cancel",(d,w)->stop())
            .create();
        loading.setOnCancelListener(d->stop());
        loading.show();
        loader.execute(()->{
            try{
                loadAlbums();
                handler.post(()->{
                    if(stopped)return;
                    if(loading!=null){loading.setOnCancelListener(null);loading.dismiss();loading=null;}
                    if(albums.isEmpty()){
                        new AlertDialog.Builder(activity).setTitle("No album photos available")
                            .setMessage("Android has not made any album photos available to MIRRAN. Try granting full photo access in system Settings, or use the normal Photo Picker.")
                            .setPositiveButton("OK",null).show();
                        stop();
                    }else{
                        dialog=new Dialog(activity);
                        dialog.getWindow();
                        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
                        dialog.setOnDismissListener(d->stop());
                        dialog.setOnKeyListener((d,key,event)->{
                            if(key==KeyEvent.KEYCODE_BACK && event.getAction()==KeyEvent.ACTION_UP && selectedAlbum!=null){
                                showAlbumList();return true;
                            }
                            return false;
                        });
                        showAlbumList();
                        dialog.show();
                        Window win=dialog.getWindow();
                        if(win!=null){
                            win.setBackgroundDrawableResource(android.R.color.white);
                            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);
                            win.setStatusBarColor(Color.WHITE);win.setNavigationBarColor(Color.WHITE);
                            win.getDecorView().setSystemUiVisibility(
                                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                        }
                    }
                });
            }catch(Exception error){
                handler.post(()->{
                    if(loading!=null){loading.setOnCancelListener(null);loading.dismiss();loading=null;}
                    if(!stopped)new AlertDialog.Builder(activity).setTitle("Unable to load albums")
                        .setMessage("Photo access may have been limited or changed. "+error.getMessage())
                        .setPositiveButton("OK",null).show();
                    stop();
                });
            }
        });
    }
    private void loadAlbums(){
        final String[] columns={
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        };
        try(Cursor c=activity.getContentResolver().query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,columns,null,null,
            MediaStore.Images.Media.DATE_ADDED+" DESC, "+MediaStore.Images.Media._ID+" DESC")){
            if(c==null)throw new IllegalStateException("Android gallery returned no results");
            while(!stopped&&c.moveToNext()){
                long id=c.getLong(0);
                String bucket=c.getString(1);
                if(bucket==null||bucket.isEmpty())bucket="unknown";
                String name=c.getString(2);
                if(name==null||name.trim().isEmpty())name="Other photos";
                Album a=albums.get(bucket);
                if(a==null){a=new Album(bucket,name);albums.put(bucket,a);}
                a.photos.add(ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,id));
            }
        }
    }
    private void makeRoot(String title,String subtitle,boolean back){
        root=new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(249,251,254));
        LinearLayout bar=new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(15),dp(13),dp(15),dp(13));
        TextView backButton=label(back?"‹  ALBUMS":"MIRRAN",13,BLUE,true);
        backButton.setPadding(dp(4),dp(10),dp(16),dp(10));
        backButton.setOnClickListener(v->{if(back)showAlbumList();else dialog.dismiss();});
        bar.addView(backButton);
        LinearLayout group=new LinearLayout(activity);group.setOrientation(LinearLayout.VERTICAL);
        TextView heading=label(title,17,NAVY,true);group.addView(heading);
        TextView detail=label(subtitle,11,MUTED,false);group.addView(detail);
        bar.addView(group,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        TextView close=label("✕",20,NAVY,true);close.setGravity(Gravity.CENTER);
        close.setContentDescription("Close album browser");
        close.setOnClickListener(v->dialog.dismiss());
        bar.addView(close,new LinearLayout.LayoutParams(dp(36),dp(42)));
        root.addView(bar);
        dialog.setContentView(root);
        if(dialog.isShowing()){
            Window win=dialog.getWindow();
            if(win!=null)win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }
    private void showAlbumList(){
        selectedAlbum=null;
        makeRoot("Your albums",albums.size()+" available albums",false);
        GridView grid=grid(2);
        ArrayList<Album> list=new ArrayList<>(albums.values());
        grid.setAdapter(new BaseAdapter(){
            @Override public int getCount(){return list.size();}
            @Override public Object getItem(int position){return list.get(position);}
            @Override public long getItemId(int position){return position;}
            @Override public View getView(int position,View recycled,ViewGroup parent){
                Album a=list.get(position);
                FrameLayout tile=tile(recycled,186);
                ImageView img=(ImageView)tile.getChildAt(0);
                TextView caption=(TextView)tile.getChildAt(1);
                caption.setText(a.name+"  ·  "+a.photos.size());
                thumbnail(img,a.photos.get(0));
                return tile;
            }
        });
        grid.setOnItemClickListener((parent,view,index,id)->showPhotoGrid(list.get(index)));
        root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void showPhotoGrid(Album album){
        selectedAlbum=album;
        makeRoot(album.name,album.photos.size()+" photos · tap to edit, swipe to browse",true);
        GridView grid=grid(3);
        grid.setAdapter(new BaseAdapter(){
            @Override public int getCount(){return album.photos.size();}
            @Override public Object getItem(int position){return album.photos.get(position);}
            @Override public long getItemId(int position){return position;}
            @Override public View getView(int position,View recycled,ViewGroup parent){
                FrameLayout tile=tile(recycled,124);
                ImageView img=(ImageView)tile.getChildAt(0);
                TextView caption=(TextView)tile.getChildAt(1);
                caption.setText("  "+(position+1));
                thumbnail(img,album.photos.get(position));
                return tile;
            }
        });
        grid.setOnItemClickListener((parent,view,index,id)->{
            ArrayList<Uri> photos=new ArrayList<>(album.photos);
            String title=album.name;
            dialog.dismiss();
            callback.selected(photos,index,title);
        });
        root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
    }
    private GridView grid(int columns){
        GridView g=new GridView(activity);
        g.setNumColumns(columns);
        g.setHorizontalSpacing(dp(5));g.setVerticalSpacing(dp(6));
        g.setPadding(dp(9),dp(7),dp(9),dp(14));
        g.setClipToPadding(false);
        g.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        g.setBackgroundColor(Color.rgb(249,251,254));
        return g;
    }
    private FrameLayout tile(View recycled,int height){
        if(recycled instanceof FrameLayout){
            FrameLayout f=(FrameLayout)recycled;
            f.setLayoutParams(new GridView.LayoutParams(-1,dp(height)));
            return f;
        }
        FrameLayout frame=new FrameLayout(activity);
        frame.setLayoutParams(new GridView.LayoutParams(-1,dp(height)));
        ImageView image=new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(bg(Color.rgb(226,235,248),10));
        frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
        TextView caption=label("",11,Color.WHITE,true);
        caption.setPadding(dp(10),dp(5),dp(8),dp(5));
        caption.setBackgroundColor(Color.argb(195,8,31,67));
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        frame.addView(caption,cp);
        return frame;
    }
    private void thumbnail(ImageView view,Uri uri){
        final String key=uri.toString();
        view.setTag(key);
        Bitmap hit=thumbs.get(key);
        view.setImageBitmap(hit);
        if(hit!=null||stopped)return;
        try{
            loader.execute(()->{
                try{
                    Bitmap b=activity.getContentResolver().loadThumbnail(uri,new Size(240,240),null);
                    if(b==null||stopped)return;
                    thumbs.put(key,b);
                    handler.post(()->{
                        if(!stopped&&key.equals(view.getTag()))view.setImageBitmap(b);
                    });
                }catch(Exception ignored){
                    // A MediaStore photo can disappear while the grid is visible.
                }
            });
        }catch(RejectedExecutionException ignored){}
    }
    private void stop(){
        stopped=true;
        loader.shutdownNow();
    }
}
