package juloo.keyboard2;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.widget.Toast;
import juloo.keyboard2.pinyin.PinyinUserDict;
import juloo.keyboard2.pinyin.cloud.PinyinDictManager;

public class SettingsActivity extends PreferenceActivity
{
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

    PinyinDictManager.bind_preference(this,
        (ListPreference)findPreference("pinyin_dict_pack"));

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
