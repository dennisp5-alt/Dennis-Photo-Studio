package com.dennis.craftmobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Album-aware, read-only MediaStore browser. It is separate from the photographic engine.
 * Neither opening an album nor moving between its images writes to the original photographs.
 * Permission acquisition and the non-destructive edit session are owned by MainActivity.
 */
final class AlbumBrowser {
    interface OnPhotoChosen {
        void onPhotoChosen(ArrayList<Uri> albumPhotos, int selectedIndex, String albumName);
    }

    private static final class Album {
        final String bucketId;
        final String name;
        final String relativePath;
        long coverId = -1L;
        int count;
        Album(String id, String title, String path) {
            bucketId = id;
            name = title;
            relativePath = path == null ? "" : path.replaceAll("/+$", "");
        }
    }

    private final Activity activity;
    private final OnPhotoChosen listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService queries = Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor thumbnails = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingDeque<>(48),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    // Thumbnails only: never retain full-sized source images in the album browser.
    private final LruCache<String, Bitmap> thumbnailCache =
            new LruCache<String, Bitmap>(12 * 1024 * 1024) {
                @Override protected int sizeOf(String key, Bitmap bitmap) {
                    return bitmap.getByteCount();
                }
            };
    private boolean closed;

    AlbumBrowser(Activity activity, OnPhotoChosen listener) {
        this.activity = activity;
        this.listener = listener;
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }


    private static final int SURFACE = Color.rgb(18, 28, 41);
    private static final int TEXT_MUTED = Color.rgb(154, 172, 195);

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    // Tinted translucent surface, not the default Android grey AlertDialog.
    private void styleWindow(AlertDialog dialog, int maxHeight) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams params = window.getAttributes();
        params.dimAmount = 0.48f;
        window.setAttributes(params);
        int width = Math.min(activity.getResources().getDisplayMetrics().widthPixels - dp(28), dp(540));
        if (maxHeight > 0) {
            int height = Math.min(activity.getResources().getDisplayMetrics().heightPixels - dp(100),
                    dp(maxHeight));
            window.setLayout(width, height);
        } else window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(activity);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        return t;
    }

    void showAlbums() {
        if (closed) return;
        final AlertDialog progress = progressDialog("Finding photo albums…");
        queries.execute(() -> {
            try {
                List<Album> albums = queryAlbums();
                main.post(() -> {
                    progress.dismiss();
                    if (!closed && !activity.isFinishing()) showAlbumList(albums);
                });
            } catch (Exception ex) {
                reportError(progress, "Could not read albums: " + ex.getMessage());
            }
        });
    }

    private AlertDialog progressDialog(String message) {
        LinearLayout body = new LinearLayout(activity);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(24), dp(20), dp(24), dp(20));
        ProgressBar progress = new ProgressBar(activity);
        body.addView(progress, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView text = new TextView(activity);
        text.setText(message);
        text.setTextColor(Color.WHITE);
        text.setTextSize(14);
        text.setPadding(dp(16), 0, 0, 0);
        body.addView(text);
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(body)
                .setCancelable(true).create();
        dialog.show();
        return dialog;
    }

    private void reportError(AlertDialog progress, String error) {
        main.post(() -> {
            progress.dismiss();
            if (!closed && !activity.isFinishing())
                new AlertDialog.Builder(activity).setTitle("Album browser")
                        .setMessage(error).setPositiveButton("OK", null).show();
        });
    }

    private List<Album> queryAlbums() {
        final Map<String, Album> grouped = new LinkedHashMap<>();
        String[] columns = {MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media._ID};
        try (Cursor cursor = activity.getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                columns, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (cursor == null) throw new IllegalStateException("No photo database available");
            int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID);
            int nameIndex = cursor.getColumnIndexOrThrow(
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
            int pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH);
            int coverIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            while (cursor.moveToNext()) {
                String id = cursor.getString(idIndex);
                if (id == null) continue;
                Album album = grouped.get(id);
                if (album == null) {
                    String name = cursor.getString(nameIndex);
                    album = new Album(id, name == null || name.trim().isEmpty()
                            ? "Unsorted photos" : name, cursor.getString(pathIndex));
                    album.coverId = cursor.getLong(coverIndex);
                    grouped.put(id, album);
                }
                album.count++;
            }
        }
        ArrayList<Album> albums = new ArrayList<>(grouped.values());
        Collections.sort(albums, Comparator.comparing(a -> a.name.toLowerCase(java.util.Locale.ROOT)));
        return albums;
    }

    private void showAlbumList(List<Album> albums) {
        if (albums.isEmpty()) {
            new AlertDialog.Builder(activity).setTitle("No accessible albums")
                    .setMessage("There are no accessible photos. Allow access to all photos in Android settings, or use the individual photo picker under More.")
                    .setPositiveButton("OK", null).show();
            return;
        }

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(15), dp(14), dp(10));
        GradientDrawable panelBg = rounded(Color.argb(247, 16, 26, 40), 19);
        panelBg.setStroke(dp(1), Color.rgb(54, 75, 97));
        panel.setBackground(panelBg);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(7), dp(2), dp(3), dp(8));
        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Photo albums", 20, Color.WHITE, true);
        TextView subtitle = text(albums.size() + " folders  ·  choose one to edit", 12,
                TEXT_MUTED, false);
        subtitle.setPadding(0, dp(5), 0, 0);
        heading.addView(title);
        heading.addView(subtitle);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = text("×", 26, Color.rgb(207, 220, 234), false);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Close albums");
        header.addView(close, new LinearLayout.LayoutParams(dp(42), dp(44)));
        panel.addView(header);

        View rule = new View(activity);
        rule.setBackgroundColor(Color.rgb(51, 68, 89));
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(-1, dp(1));
        ruleParams.setMargins(dp(7), 0, dp(7), dp(6));
        panel.addView(rule, ruleParams);

        ListView list = new ListView(activity);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setVerticalScrollBarEnabled(false);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return albums.size(); }
            @Override public Object getItem(int index) { return albums.get(index); }
            @Override public long getItemId(int index) { return index; }
            @Override public View getView(int index, View recycled, ViewGroup parent) {
                LinearLayout row;
                ImageView cover;
                TextView name;
                TextView detail;
                if (recycled instanceof LinearLayout) {
                    row = (LinearLayout) recycled;
                    cover = (ImageView) row.getChildAt(0);
                    LinearLayout info = (LinearLayout) row.getChildAt(1);
                    name = (TextView) info.getChildAt(0);
                    detail = (TextView) info.getChildAt(1);
                } else {
                    row = new LinearLayout(activity);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(9), dp(7), dp(9), dp(7));
                    cover = new ImageView(activity);
                    cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    cover.setBackground(rounded(Color.rgb(42, 58, 78), 9));
                    cover.setClipToOutline(true);
                    LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(dp(54), dp(54));
                    coverLp.setMargins(0, 0, dp(13), 0);
                    row.addView(cover, coverLp);

                    LinearLayout info = new LinearLayout(activity);
                    info.setOrientation(LinearLayout.VERTICAL);
                    info.setGravity(Gravity.CENTER_VERTICAL);
                    name = text("", 15, Color.WHITE, true);
                    name.setSingleLine(true);
                    name.setEllipsize(TextUtils.TruncateAt.END);
                    detail = text("", 11, TEXT_MUTED, false);
                    detail.setSingleLine(true);
                    detail.setEllipsize(TextUtils.TruncateAt.END);
                    detail.setPadding(0, dp(5), 0, 0);
                    info.addView(name);
                    info.addView(detail);
                    row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
                    TextView chevron = text("›", 23, Color.rgb(124, 165, 210), false);
                    chevron.setGravity(Gravity.CENTER);
                    row.addView(chevron, new LinearLayout.LayoutParams(dp(22), dp(48)));
                }
                Album album = albums.get(index);
                name.setText(album.name);
                String count = album.count + (album.count == 1 ? " photo" : " photos");
                detail.setText(album.relativePath.isEmpty() ? count :
                        count + "  ·  " + album.relativePath);
                row.setContentDescription(album.name + ", " + count);
                row.setBackground(rounded(index % 2 == 0
                        ? Color.argb(60, 40, 62, 85)
                        : Color.argb(40, 36, 55, 78), 10));

                if (album.coverId >= 0) {
                    Uri uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, album.coverId);
                    String key = uri.toString();
                    cover.setTag(key);
                    Bitmap cached = thumbnailCache.get(key);
                    cover.setImageBitmap(cached);
                    if (cached == null && !closed) {
                        thumbnails.execute(() -> {
                            Bitmap thumbnail = null;
                            try {
                                thumbnail = activity.getContentResolver().loadThumbnail(
                                        uri, new Size(112, 112), null);
                            } catch (Exception | OutOfMemoryError ignored) { }
                            if (thumbnail == null || closed) return;
                            thumbnailCache.put(key, thumbnail);
                            final Bitmap found = thumbnail;
                            main.post(() -> {
                                if (!closed && key.equals(cover.getTag()))
                                    cover.setImageBitmap(found);
                            });
                        });
                    }
                } else cover.setImageBitmap(null);
                return row;
            }
        });
        panel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        AlertDialog dialog = new AlertDialog.Builder(activity).setView(panel).create();
        close.setOnClickListener(v -> dialog.dismiss());
        list.setOnItemClickListener((parent, view, index, id) -> {
            dialog.dismiss();
            showAlbum(albums.get(index));
        });
        dialog.show();
        styleWindow(dialog, 700);
    }

    private ArrayList<Uri> queryAlbumPhotos(String bucketId) {
        ArrayList<Uri> all = new ArrayList<>();
        String[] columns = {MediaStore.Images.Media._ID};
        String selection = MediaStore.Images.Media.BUCKET_ID + "=?";
        try (Cursor cursor = activity.getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                columns, selection, new String[]{bucketId},
                MediaStore.Images.Media.DATE_TAKEN + " DESC, " +
                        MediaStore.Images.Media._ID + " DESC")) {
            if (cursor == null) throw new IllegalStateException("Album could not be opened");
            int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            while (cursor.moveToNext()) {
                all.add(ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idIndex)));
            }
        }
        return all;
    }

    private void showAlbum(Album album) {
        final AlertDialog progress = progressDialog("Loading " + album.name + "…");
        queries.execute(() -> {
            try {
                ArrayList<Uri> images = queryAlbumPhotos(album.bucketId);
                main.post(() -> {
                    progress.dismiss();
                    if (closed || activity.isFinishing()) return;
                    if (images.isEmpty()) {
                        Toast.makeText(activity, "This album has no accessible photos",
                                Toast.LENGTH_LONG).show();
                        showAlbums();
                    } else showPhotoGrid(album.name, images);
                });
            } catch (Exception ex) {
                reportError(progress, "Could not open album: " + ex.getMessage());
            }
        });
    }

    private void showPhotoGrid(String albumName, ArrayList<Uri> images) {
        GridView grid = new GridView(activity);
        grid.setNumColumns(3);
        grid.setVerticalSpacing(dp(3));
        grid.setHorizontalSpacing(dp(3));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(dp(5), dp(6), dp(5), dp(6));
        grid.setClipToPadding(false);
        grid.setBackgroundColor(Color.rgb(9, 16, 25));
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return images.size(); }
            @Override public Object getItem(int position) { return images.get(position); }
            @Override public long getItemId(int position) { return position; }

            @Override public View getView(int position, View recycled, ViewGroup parent) {
                ImageView tile = recycled instanceof ImageView
                        ? (ImageView) recycled : new ImageView(activity);
                tile.setLayoutParams(new AbsListView.LayoutParams(-1, dp(108)));
                tile.setScaleType(ImageView.ScaleType.CENTER_CROP);
                tile.setBackgroundColor(Color.rgb(30, 40, 53));
                Uri uri = images.get(position);
                String key = uri.toString();
                tile.setTag(key);
                tile.setContentDescription("Photo " + (position + 1) +
                        " of " + images.size() + ", tap to edit");
                Bitmap cached = thumbnailCache.get(key);
                tile.setImageBitmap(cached);
                if (cached == null && !closed) {
                    thumbnails.execute(() -> {
                        Bitmap thumb = null;
                        try {
                            thumb = activity.getContentResolver().loadThumbnail(
                                    uri, new Size(180, 180), null);
                        } catch (Exception | OutOfMemoryError ignored) { }
                        if (thumb == null || closed) return;
                        thumbnailCache.put(key, thumb);
                        final Bitmap image = thumb;
                        main.post(() -> {
                            if (!closed && key.equals(tile.getTag())) tile.setImageBitmap(image);
                        });
                    });
                }
                return tile;
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(albumName + "  ·  " + images.size() + " photos")
                .setView(grid)
                .setPositiveButton("Albums", (d, which) -> showAlbums())
                .setNegativeButton("Close", null).create();
        grid.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            listener.onPhotoChosen(images, position, albumName);
        });
        dialog.show();
        // Keep the grid scrollable rather than allocating a view for every photograph.
        android.view.Window window = dialog.getWindow();
        if (window != null) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(activity.getResources().getDisplayMetrics().heightPixels - dp(90),
                        dp(690)));
    }

    void close() {
        closed = true;
        queries.shutdownNow();
        thumbnails.shutdownNow();
        thumbnailCache.evictAll();
    }
}
