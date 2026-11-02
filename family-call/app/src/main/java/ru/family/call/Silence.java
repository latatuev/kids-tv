package ru.family.call;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.service.quicksettings.TileService;

/** One local timed silence session. Never communicates with the network. */
public final class Silence {
    static final String EXPIRE = "ru.family.call.EXPIRE";
    final Context ctx;
    final SharedPreferences state;
    final AlarmManager alarm;
    final AudioManager audio;
    final NotificationManager notifications;

    public Silence(Context c) {
        ctx = c.getApplicationContext();
        state = ctx.getSharedPreferences("silence-v1", Context.MODE_PRIVATE);
        alarm = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        notifications = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    public boolean policyAccess() { return notifications.isNotificationPolicyAccessGranted(); }
    public boolean exactAccess() { return Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms(); }
    public boolean active() { return state.getBoolean("active", false); }
    public long until() { return state.getLong("until", 0L); }
    public int mode() { return audio.getRingerMode(); }

    private PendingIntent callback() {
        Intent i = new Intent(ctx, ExpireReceiver.class).setAction(EXPIRE);
        return PendingIntent.getBroadcast(ctx, 401, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public void start(int minutes) {
        if (minutes < 1 || minutes > 1439) throw new IllegalArgumentException("Длительность: от 1 минуты до 23 часов 59 минут.");
        if (!policyAccess() || !exactAccess()) throw new IllegalStateException("Сначала предоставь разрешения на тишину и точные таймеры.");
        long deadline = System.currentTimeMillis() + 60000L * minutes;
        int old = active() ? state.getInt("previous", AudioManager.RINGER_MODE_NORMAL) : audio.getRingerMode();
        try {
            // Never silence a phone unless the restoration alarm was accepted.
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, callback());
            try {
                audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
                if (audio.getRingerMode() != AudioManager.RINGER_MODE_SILENT)
                    throw new IllegalStateException("Не удалось включить беззвучный режим.");
            } catch (RuntimeException e) {
                alarm.cancel(callback());
                throw e;
            }
            if (!state.edit().putBoolean("active", true).putLong("until", deadline).putInt("previous", old).commit()) {
                alarm.cancel(callback());
                if (audio.getRingerMode() == AudioManager.RINGER_MODE_SILENT) audio.setRingerMode(old);
                throw new IllegalStateException("Не удалось сохранить таймер.");
            }
            updateTile();
        } catch (SecurityException e) {
            throw new IllegalStateException("Android отказал в управлении звуком или будильником.", e);
        }
    }

    public void stop() {
        if (!active()) return;
        int old = state.getInt("previous", AudioManager.RINGER_MODE_NORMAL);
        if (audio.getRingerMode() == AudioManager.RINGER_MODE_SILENT) {
            try { audio.setRingerMode(old); }
            catch (SecurityException e) { throw new IllegalStateException("Не удалось вернуть звук.", e); }
        }
        clear();
    }

    private void clear() {
        alarm.cancel(callback());
        state.edit().clear().commit();
        updateTile();
    }

    public void reconcile() {
        if (!active()) return;
        if (System.currentTimeMillis() >= until()) {
            if (audio.getRingerMode() == AudioManager.RINGER_MODE_SILENT) {
                try { audio.setRingerMode(state.getInt("previous", AudioManager.RINGER_MODE_NORMAL)); }
                catch (SecurityException e) { return; }
            }
            clear();
        } else if (audio.getRingerMode() != AudioManager.RINGER_MODE_SILENT) {
            // User manually changed the mode: respect that choice.
            clear();
        }
    }

    public void onBoot() {
        if (!active()) return;
        if (System.currentTimeMillis() >= until()) { reconcile(); return; }
        if (!exactAccess()) return;
        try { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until(), callback()); }
        catch (SecurityException ignored) { }
        updateTile();
    }

    private void updateTile() {
        TileService.requestListeningState(ctx, new ComponentName(ctx, SilentTile.class));
    }
}
