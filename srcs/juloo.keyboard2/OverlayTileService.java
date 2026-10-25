package juloo.keyboard2;

import android.content.Intent;
import android.os.Build.VERSION;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick settings tile to expand or collapse the floating keyboard. */
public class OverlayTileService extends TileService
{
  @Override
  public void onStartListening()
  {
    update_tile();
  }

  @Override
  public void onClick()
  {
    Intent intent = new Intent(this, OverlayKeyboardService.class);
    intent.setAction(OverlayKeyboardService.ACTION_TOGGLE);
    if (VERSION.SDK_INT >= 26)
      startForegroundService(intent);
    else
      startService(intent);
  }

  void update_tile()
  {
    Tile tile = getQsTile();
    if (tile == null)
      return;
    tile.setState(OverlayKeyboardService.is_expanded()
        ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
    tile.updateTile();
  }
}
