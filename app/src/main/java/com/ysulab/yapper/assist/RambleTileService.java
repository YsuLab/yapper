package com.ysulab.yapper.assist;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: tap to start a ramble, tap again to stop and save. */
public final class RambleTileService extends TileService {

    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t != null) {
            t.setState(RambleService.isRunning() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            t.updateTile();
        }
    }

    @Override
    public void onClick() {
        Intent i = new Intent(this, RambleActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(i);
        }
    }
}
