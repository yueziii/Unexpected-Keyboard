package juloo.keyboard2;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.widget.Toast;
import juloo.keyboard2.pinyin.PinyinUserDict;
import juloo.keyboard2.pinyin.cloud.PinyinDictManager;

public class SettingsActivity extends PreferenceActivity
  implements SharedPreferences.OnSharedPreferenceChangeListener
{
  /** Prevents re-handling the overlay switch when it is reverted from within
      the preference change listener. */
  boolean _reverting_overlay_bubble = false;

  @Override
  public void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    // The preferences can't be read when in direct-boot mode. Avoid crashing
    // and don't allow changing the settings.
    // Run the config migration on this prefs as it might be different from the
    // one used by the keyboard, which have been migrated.
    try
    {
      Config.migrate(getPreferenceManager().getSharedPreferences());
    }
    catch (Exception _e) { fallbackEncrypted(); return; }
    addPreferencesFromResource(R.xml.settings);

    boolean foldableDevice = FoldStateTracker.isFoldableDevice(this);
    findPreference("margin_bottom_portrait_unfolded").setEnabled(foldableDevice);
    findPreference("margin_bottom_landscape_unfolded").setEnabled(foldableDevice);
    findPreference("horizontal_margin_portrait_unfolded").setEnabled(foldableDevice);
    findPreference("horizontal_margin_landscape_unfolded").setEnabled(foldableDevice);
    findPreference("keyboard_height_unfolded").setEnabled(foldableDevice);
    findPreference("keyboard_height_landscape_unfolded").setEnabled(foldableDevice);

    PinyinDictManager.bind_hotwords_preference(this,
        findPreference("pinyin_hotwords_update"));

    // Clearing the learned pinyin words asks for confirmation; the click
    // is otherwise not a preference change.
    findPreference("pinyin_clear_user_dict")
      .setOnPreferenceClickListener(new Preference.OnPreferenceClickListener()
        {
          @Override
          public boolean onPreferenceClick(Preference p)
          {
            confirm_clear_user_dict();
            return true;
          }
        });

    // The bubble is supposed to stay on screen while the setting is on; if
    // the service died, bring it back when the user opens the settings.
    OverlayKeyboardService.start_if_setting_on(this);
    getPreferenceManager().getSharedPreferences()
      .registerOnSharedPreferenceChangeListener(this);
  }

  @Override
  public void onDestroy()
  {
    getPreferenceManager().getSharedPreferences()
      .unregisterOnSharedPreferenceChangeListener(this);
    super.onDestroy();
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences prefs, String key)
  {
    if (!OverlayKeyboardService.PREF_BUBBLE_SETTING.equals(key))
      return;
    if (prefs.getBoolean(key, false))
      start_overlay_bubble(prefs);
    else
      stop_overlay_bubble();
  }

  /** Turn the persistent bubble on. Requires the overlay permission: without
      it, the switch is reverted and the system permission screen is shown. */
  void start_overlay_bubble(SharedPreferences prefs)
  {
    if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this))
    {
      if (!_reverting_overlay_bubble)
      {
        _reverting_overlay_bubble = true;
        prefs.edit()
          .putBoolean(OverlayKeyboardService.PREF_BUBBLE_SETTING, false)
          .apply();
        _reverting_overlay_bubble = false;
      }
      startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName())));
      return;
    }
    if (Build.VERSION.SDK_INT >= 33)
      requestPermissions(
          new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 0);
    Intent intent = new Intent(this, OverlayKeyboardService.class);
    intent.setAction(OverlayKeyboardService.ACTION_START);
    if (Build.VERSION.SDK_INT >= 26)
      startForegroundService(intent);
    else
      startService(intent);
  }

  void stop_overlay_bubble()
  {
    Intent intent = new Intent(this, OverlayKeyboardService.class);
    intent.setAction(OverlayKeyboardService.ACTION_STOP);
    if (Build.VERSION.SDK_INT >= 26)
      startForegroundService(intent);
    else
      startService(intent);
  }

  void fallbackEncrypted()
  {
    // Can't communicate with the user here.
    finish();
  }

  /** Ask before forgetting the learned words, custom words and prediction
      pairs. */
  void confirm_clear_user_dict()
  {
    new android.app.AlertDialog.Builder(this)
      .setTitle(R.string.pref_pinyin_clear_user_dict_title)
      .setMessage(R.string.pref_pinyin_clear_user_dict_summary)
      .setPositiveButton(android.R.string.ok,
          new android.content.DialogInterface.OnClickListener()
          {
            public void onClick(android.content.DialogInterface d, int w)
            {
              PinyinUserDict.clear();
              Toast.makeText(SettingsActivity.this,
                  R.string.pinyin_user_dict_cleared, Toast.LENGTH_SHORT)
                .show();
            }
          })
      .setNegativeButton(android.R.string.cancel, null)
      .show();
  }

  protected void onStop()
  {
    DirectBootAwarePreferences
      .copy_preferences_to_protected_storage(this,
          getPreferenceManager().getSharedPreferences());
    super.onStop();
  }
}
