package com.dennis.craftmobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
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
        int count;
        Album(String id, String title) { bucketId = id; name = title; }
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
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME};
        try (Cursor cursor = activity.getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                columns, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("No photo database available");
            int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID);
            int nameIndex = cursor.getColumnIndexOrThrow(
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
            while (cursor.moveToNext()) {
                String id = cursor.getString(idIndex);
                if (id == null) continue;
                Album album = grouped.get(id);
                if (album == null) {
                    String name = cursor.getString(nameIndex);
                    album = new Album(id, name == null || name.trim().isEmpty()
                            ? "Unsorted photos" : name);
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
                    .setMessage("No photos are currently available. To browse a complete album, allow access to all photos in Android's permission settings. You can still use the individual photo picker.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] labels = new String[albums.size()];
        for (int i = 0; i < albums.size(); i++) {
            Album a = albums.get(i);
            labels[i] = a.name + "     ·     " + a.count + (a.count == 1 ? " photo" : " photos");
        }
        ListView list = new ListView(activity);
        list.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, labels));
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("PHOTO ALBUMS")
                .setView(list)
                .setNegativeButton("Close", null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            Album album = albums.get(position);
            dialog.dismiss();
            showAlbum(album);
        });
        dialog.show();
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
