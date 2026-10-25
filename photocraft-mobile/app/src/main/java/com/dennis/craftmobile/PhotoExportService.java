package com.dennis.craftmobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground mediaProcessing job: full-resolution JPEG saving survives leaving
 * the editor and Activity destruction. No ongoing editor/camera service.
 * Exports still run offline and never overwrite the input photograph.
 */
public final class PhotoExportService extends Service {
    static final String ACTION_FINISHED = "com.dennis.craftmobile.EXPORT_FINISHED";
    static final String EXTRA_SOURCE = "source";
    static final String EXTRA_EDIT = "edit";
    static final String EXTRA_QUALITY = "quality";
    static final String EXTRA_SHARE = "share";
    static final String EXTRA_RESULT = "result";
    static final String EXTRA_ERROR = "error";
    static final String EXTRA_SECONDS = "seconds";
    private static final String CHANNEL = "photo_studio_exports";
    private static final int NOTIFICATION_ID = 1014;
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile Uri pendingOutput;

    static boolean isBusy() { return BUSY.get(); }

    static boolean begin(Context context, Uri source, MainActivity.Edit edit,
                         int quality, boolean share) {
        if (!BUSY.compareAndSet(false, true)) return false;
        Intent job = new Intent(context, PhotoExportService.class);
        job.putExtra(EXTRA_SOURCE, source);
        job.putExtra(EXTRA_EDIT, edit.copy());
        job.putExtra(EXTRA_QUALITY, quality);
        job.putExtra(EXTRA_SHARE, share);
        job.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        job.setClipData(ClipData.newUri(context.getContentResolver(),
                "Source photograph", source));
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(job);
            else context.startService(job);
            return true;
        } catch (RuntimeException error) {
            BUSY.set(false);
            Log.e("PhotoStudioExport", "Could not start export service", error);
            return false;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications=getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT>=26) {
            NotificationChannel channel=new NotificationChannel(CHANNEL,
                    "Photo Studio exports", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Progress and completion of JPEG photo exports");
            notifications.createNotificationChannel(channel);
        }
    }

    private Notification notification(String message, boolean ongoing,
                                      PendingIntent action) {
        Notification.Builder b=Build.VERSION.SDK_INT>=26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentTitle("Photo Studio")
                .setContentText(message)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setAutoCancel(!ongoing);
        if(action != null) b.setContentIntent(action);
        if(ongoing) b.setProgress(0,0,true);
        return b.build();
    }

    private void progress(String message) {
        getSystemService(NotificationManager.class).notify(
                NOTIFICATION_ID, notification(message,true,null));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent==null) { stopSelf(startId);return START_NOT_STICKY; }
        try {
            Notification initial=notification("Preparing full-resolution JPEG…",true,null);
            if (Build.VERSION.SDK_INT>=35)
                startForeground(NOTIFICATION_ID, initial,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
            else startForeground(NOTIFICATION_ID,initial);
        } catch (RuntimeException exception) {
            BUSY.set(false);
            Log.e("PhotoStudioExport", "Foreground export unavailable",exception);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        Uri source=Build.VERSION.SDK_INT>=33
                ? intent.getParcelableExtra(EXTRA_SOURCE, Uri.class)
                : intent.getParcelableExtra(EXTRA_SOURCE);
        MainActivity.Edit edit=Build.VERSION.SDK_INT>=33
                ? intent.getSerializableExtra(EXTRA_EDIT,MainActivity.Edit.class)
                : (MainActivity.Edit)intent.getSerializableExtra(EXTRA_EDIT);
        int quality=intent.getIntExtra(EXTRA_QUALITY,95);
        boolean share=intent.getBooleanExtra(EXTRA_SHARE,false);
        if(source==null || edit==null || quality<1 || quality>100) {
            BUSY.set(false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        worker.execute(()->export(startId,source,edit,quality,share));
        return START_NOT_STICKY;
    }

    private void export(int startId, Uri source, MainActivity.Edit edit,
                        int quality, boolean share) {
        Bitmap input=null, result=null;
        Uri output=null;
        long start=SystemClock.elapsedRealtime();
        long decodedAt=0,processedAt=0,encodedAt=0;
        try {
            progress("Reading original photo…");
            input=MainActivity.decodeForExport(this,source,8192,50_000_000);
            decodedAt=SystemClock.elapsedRealtime();
            if(Thread.currentThread().isInterrupted())
                throw new InterruptedException("Export interrupted");
            progress("Processing full-resolution photo…");
            result=MainActivity.hasNoEdits(edit) ? input :
                    MainActivity.processForExport(input,edit);
            processedAt=SystemClock.elapsedRealtime();
            if(Thread.currentThread().isInterrupted())
                throw new InterruptedException("Export interrupted");
            progress("Encoding JPEG Q"+quality+"…");
            String filename="CRAFT_"+new SimpleDateFormat(
                    "yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date())+".jpg";
            ContentValues values=new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,filename);
            values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/Photo Studio");
            values.put(MediaStore.Images.Media.IS_PENDING,1);
            output=getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
            if(output==null)throw new IllegalStateException("Unable to create saved JPEG");
            pendingOutput=output;
            try(OutputStream stream=getContentResolver().openOutputStream(output)) {
                if(stream==null || !result.compress(Bitmap.CompressFormat.JPEG,
                        quality,stream))
                    throw new IllegalStateException("JPEG encoding failed");
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING,0);
            getContentResolver().update(output,values,null,null);
            pendingOutput=null;
            encodedAt=SystemClock.elapsedRealtime();
            Log.i("PhotoStudioExport",String.format(Locale.US,
                    "Full-resolution export %dx%d (ms): decode=%d processing=%d jpeg=%d total=%d",
                    result.getWidth(),result.getHeight(),
                    decodedAt-start,processedAt-decodedAt,encodedAt-processedAt,
                    encodedAt-start));
            notifyResult(source,output,null,share,(encodedAt-start)/1000L);
            PendingIntent openSaved=destination(output,share);
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID+1,
                    notification(share?"Photo saved · tap to share":
                            "JPEG saved to Pictures/Photo Studio",false,openSaved));
        } catch(Exception | OutOfMemoryError error) {
            if(output!=null) {
                try {getContentResolver().delete(output,null,null);}
                catch(RuntimeException ignored){}
            }
            pendingOutput=null;
            Log.e("PhotoStudioExport", "JPEG export failed",error);
            notifyResult(source,null,error.getMessage()==null?
                    error.getClass().getSimpleName():error.getMessage(),share,0);
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID+1,
                    notification("Export failed — open Photo Studio to retry",
                            false,openEditor()));
        } finally {
            if(result!=null && result!=input && !result.isRecycled())result.recycle();
            if(input!=null && !input.isRecycled())input.recycle();
            BUSY.set(false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        }
    }

    private PendingIntent destination(Uri saved, boolean share) {
        Intent open;
        if(share) {
            Intent send=new Intent(Intent.ACTION_SEND)
                    .setType("image/jpeg")
                    .putExtra(Intent.EXTRA_STREAM,saved)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newUri(getContentResolver(),"Saved photo",saved));
            open=Intent.createChooser(send,"Share edited photo");
        } else {
            open=new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(saved,"image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this,42,open,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent openEditor() {
        Intent open=new Intent(this,MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this,43,open,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }

    private void notifyResult(Uri source, Uri result,String error,boolean share,long seconds) {
        Intent broadcast=new Intent(ACTION_FINISHED);
        broadcast.setPackage(getPackageName());
        broadcast.putExtra(EXTRA_SOURCE,source);
        if(result!=null)broadcast.putExtra(EXTRA_RESULT,result);
        broadcast.putExtra(EXTRA_ERROR,error);
        broadcast.putExtra(EXTRA_SHARE,share);
        broadcast.putExtra(EXTRA_SECONDS,seconds);
        sendBroadcast(broadcast);
    }

    @Override public void onTimeout(int startId,int fgsType) {
        Log.w("PhotoStudioExport","Foreground mediaProcessing time allowance reached");
        worker.shutdownNow();
        Uri item=pendingOutput;
        if(item!=null) {
            try{getContentResolver().delete(item,null,null);}catch(RuntimeException ignored){}
            pendingOutput=null;
        }
        BUSY.set(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(startId);
    }

    @Override public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
