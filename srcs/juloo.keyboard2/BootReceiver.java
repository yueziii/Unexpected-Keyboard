package juloo.keyboard2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores the persistent overlay bubble after a reboot when the
    "persistent bubble" setting is on. The overlay permission persists across
    reboots; the service is simply not started automatically by the system. */
public class BootReceiver extends BroadcastReceiver
{
  @Override
  public void onReceive(Context context, Intent intent)
  {
    if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()))
      OverlayKeyboardService.start_if_setting_on(context);
  }
}
