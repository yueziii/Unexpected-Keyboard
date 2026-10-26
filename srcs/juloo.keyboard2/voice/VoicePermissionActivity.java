package juloo.keyboard2.voice;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build.VERSION;
import android.os.Bundle;
import android.widget.Toast;
import juloo.keyboard2.R;

/** Transparent activity requesting the microphone permission for the
    offline voice engine. The IME service cannot show a runtime permission
    dialog, so it launches this activity instead. */
public class VoicePermissionActivity extends Activity
{
  @Override
  protected void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    if (VERSION.SDK_INT < 23)
    {
      finish();
      return;
    }
    String permission = Manifest.permission.RECORD_AUDIO;
    if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
    {
      finish();
      return;
    }
    requestPermissions(new String[]{ permission }, 0);
  }

  @Override
  public void onRequestPermissionsResult(int req, String[] permissions,
      int[] grantResults)
  {
    boolean granted = grantResults.length > 0
      && grantResults[0] == PackageManager.PERMISSION_GRANTED;
    Toast.makeText(this, granted ? R.string.voice_permission_granted
        : R.string.voice_permission_denied, Toast.LENGTH_SHORT).show();
    finish();
  }
}
