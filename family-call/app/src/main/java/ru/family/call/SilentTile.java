package ru.family.call;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class SilentTile extends TileService {
    @Override public void onStartListening() { super.onStartListening(); update(); }
    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        Silence s = new Silence(this);
        s.reconcile();
        t.setState(s.active() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(s.active() ? "Вернуть звук" : "Тихо 30 мин");
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(s.active() ? "Таймер активен" : "Быстрый режим");
        t.updateTile();
    }
    @Override public void onClick() {
        super.onClick();
        Silence s = new Silence(this);
        try {
            if (s.active()) s.stop();
            else s.start(30);
            update();
        } catch (Exception e) {
            Intent intent = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pi = PendingIntent.getActivity(this, 9, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pi);
            } else startActivityAndCollapse(intent);
        }
    }
}
