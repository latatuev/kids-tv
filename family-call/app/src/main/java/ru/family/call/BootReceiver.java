package ru.family.call;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
            || "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED".equals(intent.getAction()))
            new Silence(context).onBoot();
    }
}
