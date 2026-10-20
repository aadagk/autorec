package com.callrec.auto;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Плитка в шторке: быстро включить/выключить автозапись. */
public class RecTile extends TileService {
    @Override public void onStartListening() { update(); }
    @Override public void onTileAdded() { update(); }

    @Override public void onClick() {
        Prefs.setAuto(this, !Prefs.auto(this));
        update();
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(Prefs.auto(this) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(getString(R.string.tile_label));
        t.updateTile();
    }
}
