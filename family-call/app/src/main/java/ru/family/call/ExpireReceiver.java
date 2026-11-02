package ru.family.call;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public final class ExpireReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Silence.EXPIRE.equals(intent.getAction())) new Silence(context).reconcile();
    }
}
