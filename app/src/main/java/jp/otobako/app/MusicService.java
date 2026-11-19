package jp.otobako.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;

public class MusicService extends Service {
    static MusicService inst;
    static long[] queue = new long[0];
    static int idx = -1;
    private static final String CH = "otobako_play";
    private MediaPlayer mp;
    private String title = "", artist = "";
    private int errors = 0;
    private AudioManager am;
    private AudioFocusRequest afr;

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        NotificationChannel ch = new NotificationChannel(CH, "再生中", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        AudioAttributes aa = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        afr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(aa)
                .setOnAudioFocusChangeListener(f -> {
                    if (f == AudioManager.AUDIOFOCUS_LOSS || f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause();
                }).build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if ("PLAY".equals(a)) playIndex(idx);
        else if ("TOGGLE".equals(a)) toggle();
        else if ("NEXT".equals(a)) next();
        else if ("PREV".equals(a)) prev();
        else if ("STOP".equals(a)) { stopAll(); }
        if (mp == null && !"STOP".equals(a)) notifyFg();
        return START_NOT_STICKY;
    }

    void playIndex(int i) {
        if (queue.length == 0) return;
        idx = ((i % queue.length) + queue.length) % queue.length;
        releaseMp();
        long id = queue[idx];
        loadMeta(id);
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setDataSource(this, uri(id));
            mp.setOnCompletionListener(m -> next());
            mp.prepare();
            am.requestAudioFocus(afr);
            mp.start();
            errors = 0;
            count(id);
        } catch (Exception e) {
            releaseMp();
            if (++errors < queue.length) { next(); return; }
        }
        notifyFg();
    }

    void toggle() { if (mp == null) { playIndex(idx); return; } if (mp.isPlaying()) pause(); else resume(); }
    void pause() { if (mp != null && mp.isPlaying()) mp.pause(); notifyFg(); }
    void resume() { if (mp == null) { playIndex(idx); return; } am.requestAudioFocus(afr); mp.start(); notifyFg(); }
    void next() { playIndex(idx + 1); }
    void prev() { if (mp != null && mp.getCurrentPosition() > 4000) { mp.seekTo(0); return; } playIndex(idx - 1); }
    void seek(int ms) { if (mp != null) mp.seekTo(ms); }

    void stopAll() {
        releaseMp();
        am.abandonAudioFocusRequest(afr);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    String state() {
        long id = idx >= 0 && idx < queue.length ? queue[idx] : -1;
        boolean pl = false; int pos = 0, dur = 0;
        try { if (mp != null) { pl = mp.isPlaying(); pos = mp.getCurrentPosition(); dur = mp.getDuration(); } } catch (Exception e) { }
        return "{\"id\":" + id + ",\"playing\":" + pl + ",\"pos\":" + pos + ",\"dur\":" + dur + "}";
    }

    private Uri uri(long id) { return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id); }

    private void loadMeta(long id) {
        title = "音楽"; artist = "";
        try (Cursor c = getContentResolver().query(uri(id), new String[]{MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST}, null, null, null)) {
            if (c != null && c.moveToFirst()) { title = c.getString(0); artist = c.getString(1); }
        } catch (Exception e) { }
        if (artist == null || "<unknown>".equals(artist)) artist = "";
    }

    private void count(long id) {
        SharedPreferences sp = getSharedPreferences("counts", MODE_PRIVATE);
        String k = String.valueOf(id);
        sp.edit().putInt(k, sp.getInt(k, 0) + 1).apply();
    }

    private PendingIntent pi(String action, int rc) {
        Intent i = new Intent(this, MusicService.class).setAction(action);
        return PendingIntent.getService(this, rc, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void notifyFg() {
        boolean pl = mp != null && mp.isPlaying();
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle(title)
                .setContentText(artist)
                .setContentIntent(open)
                .setOngoing(pl)
                .setDeleteIntent(pi("STOP", 5))
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_previous), "前へ", pi("PREV", 1)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, pl ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play), pl ? "一時停止" : "再生", pi("TOGGLE", 2)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_next), "次へ", pi("NEXT", 3)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "停止", pi("STOP", 4)).build())
                .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(1, n);
    }

    private void releaseMp() { if (mp != null) { try { mp.release(); } catch (Exception e) { } mp = null; } }

    @Override
    public void onDestroy() { releaseMp(); inst = null; super.onDestroy(); }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
