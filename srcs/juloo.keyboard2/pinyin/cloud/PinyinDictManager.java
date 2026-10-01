package juloo.keyboard2.pinyin.cloud;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.ListPreference;
import android.preference.Preference;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import juloo.keyboard2.R;
import juloo.keyboard2.pinyin.PinyinData;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Downloads pinyin dictionary packs from a manifest hosted on GitHub and
 * installs them into the app's private storage, where PinyinData picks them
 * up at the next load (see PinyinData.load_installed).
 *
 * Privacy: only dictionary files are ever downloaded, nothing about the
 * user is uploaded. Each pack carries sha256 checksums that are verified
 * before anything is written, and the downloaded pair is parsed once
 * (rejecting bad magic, versions and mismatched bigram tables) before it
 * replaces the active dictionary. A failed download never leaves the
 * previously active dictionary in a broken state.
 */
public final class PinyinDictManager
{
  public static final String MANIFEST_URL =
    "https://raw.githubusercontent.com/yueziii/Unexpected-Keyboard-pinyin-dictionaries/main/manifest.json";

  private static final Object _install_lock = new Object();

  private PinyinDictManager() {}

  // ---- manifest ----------------------------------------------------------

  /** One downloadable dictionary pack, as described by the manifest. */
  public static final class Pack
  {
    public final String id;
    public final String name;
    public final String description;
    public final int entries;
    public final String date;
    public final String dict_path; // relative to the manifest's base URL
    public final String dict_sha256;
    public final String bigram_path;
    public final String bigram_sha256;

    Pack(JSONObject o) throws Exception
    {
      id = o.getString("id");
      name = o.getString("name");
      description = o.optString("description", "");
      entries = o.optInt("entries", 0);
      date = o.optString("date", "");
      dict_path = o.getJSONObject("dict").getString("path");
      dict_sha256 = o.getJSONObject("dict").getString("sha256");
      bigram_path = o.getJSONObject("bigram").getString("path");
      bigram_sha256 = o.getJSONObject("bigram").getString("sha256");
    }

    /** Absolute URL of the "dict" or "bigram" file. */
    public String url(String which)
    {
      String base = MANIFEST_URL;
      base = base.substring(0, base.lastIndexOf('/') + 1);
      return base + ("dict".equals(which) ? dict_path : bigram_path);
    }
  }

  /** Parse a manifest JSON document (see cloud/pinyin-dictionaries in the
      repository for the format and how it is produced). */
  public static List<Pack> parse_manifest(String json) throws Exception
  {
    JSONObject root = new JSONObject(json);
    if (root.optInt("format_version", 0) != 1)
      throw new Exception("Unsupported manifest format version");
    JSONArray arr = root.getJSONArray("packs");
    List<Pack> packs = new ArrayList<Pack>();
    for (int i = 0; i < arr.length(); i++)
      packs.add(new Pack(arr.getJSONObject(i)));
    return packs;
  }

  /** Fetch the manifest from the network and cache it for offline use.
      Blocking — call from a background thread. */
  public static List<Pack> fetch_manifest(Context ctx) throws Exception
  {
    String json = http_get(new URL(MANIFEST_URL));
    List<Pack> packs = parse_manifest(json);
    prefs(ctx).edit().putString("manifest_cache", json).apply();
    return packs;
  }

  /** The last successfully fetched manifest, or null when none was ever
      fetched. Used to populate the settings list when offline. */
  public static List<Pack> cached_manifest(Context ctx)
  {
    String json = prefs(ctx).getString("manifest_cache", null);
    if (json == null)
      return null;
    try
    {
      return parse_manifest(json);
    }
    catch (Exception e)
    {
      return null;
    }
  }

  // ---- install / remove --------------------------------------------------

  /** The id of the currently selected pack, "builtin" when none. */
  public static String selected_pack(Context ctx)
  {
    return prefs(ctx).getString("pinyin_dict_pack", "builtin");
  }

  /** Download [pack], verify it and make it the active dictionary. Blocking
      — call from a background thread. Throws on any failure; on failure the
      previously active dictionary is left untouched. */
  public static void install(Context ctx, Pack pack) throws Exception
  {
    synchronized (_install_lock)
    {
      File dir = files_dir(ctx);
      File dict_tmp = new File(dir, "py_dict.bin.tmp");
      File bigram_tmp = new File(dir, "py_bigram.bin.tmp");
      try
      {
        download_to(new URL(pack.url("dict")), dict_tmp);
        verify_sha256(dict_tmp, pack.dict_sha256);
        download_to(new URL(pack.url("bigram")), bigram_tmp);
        verify_sha256(bigram_tmp, pack.bigram_sha256);
        // Parse the pair once before installing. PinyinData rejects bad
        // magic, unsupported versions and a bigram word table that does not
        // match the dictionary.
        new PinyinData(read_file(dict_tmp), read_file(bigram_tmp));
        // Both files validated: swap them in. renameTo within one directory
        // is atomic on the filesystems used here.
        if (!dict_tmp.renameTo(new File(dir, "py_dict.bin")))
          throw new Exception("Could not move dictionary into place");
        if (!bigram_tmp.renameTo(new File(dir, "py_bigram.bin")))
          throw new Exception("Could not move bigram model into place");
        SharedPreferences.Editor ed = prefs(ctx).edit();
        ed.putString("pinyin_dict_pack", pack.id);
        ed.putString("pinyin_dict_pack_sha", pack.dict_sha256);
        ed.apply();
        PinyinData.reload(ctx.getApplicationContext());
      }
      finally
      {
        dict_tmp.delete();
        bigram_tmp.delete();
      }
    }
  }

  /** Remove the installed pack and fall back to the bundled dictionary. */
  public static void remove(Context ctx)
  {
    synchronized (_install_lock)
    {
      File dir = files_dir(ctx);
      new File(dir, "py_dict.bin").delete();
      new File(dir, "py_bigram.bin").delete();
      prefs(ctx).edit().remove("pinyin_dict_pack_sha").apply();
      PinyinData.reload(ctx.getApplicationContext());
    }
  }

  // ---- settings screen ---------------------------------------------------

  /** Wire the "pinyin dictionary" ListPreference: populate its entries from
      the manifest (from the cache immediately, then a background refresh)
      and download the selected pack when it is chosen. Selecting the
      built-in entry removes the installed pack. The displayed value only
      changes once the action actually succeeded. */
  public static void bind_preference(final Activity act, final ListPreference pref)
  {
    final Context ctx = act.getApplicationContext();
    refresh_entries(act, pref, cached_manifest(ctx));
    pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener()
      {
        public boolean onPreferenceClick(Preference p)
        {
          // Refresh the list in the background each time the preference is
          // opened. Failures are silent: the cached list stays usable.
          new Thread(new Runnable()
            {
              public void run()
              {
                try
                {
                  final List<Pack> packs = fetch_manifest(ctx);
                  act.runOnUiThread(new Runnable()
                    {
                      public void run()
                      {
                        if (!act.isFinishing())
                          refresh_entries(act, pref, packs);
                      }
                    });
                }
                catch (Exception _e) {}
              }
            }).start();
          return false;
        }
      });
    pref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener()
      {
        public boolean onPreferenceChange(final Preference p, Object newValue)
        {
          final String id = (String)newValue;
          if (id.equals(pref.getValue()))
            return false;
          toast(act, act.getString(R.string.pinyin_dict_installing));
          new Thread(new Runnable()
            {
              public void run()
              {
                Pack resolved = null;
                try
                {
                  if ("builtin".equals(id))
                  {
                    remove(ctx);
                  }
                  else
                  {
                    List<Pack> packs = cached_manifest(ctx);
                    if (packs == null)
                      packs = fetch_manifest(ctx);
                    for (Pack cand : packs)
                      if (cand.id.equals(id))
                        resolved = cand;
                    if (resolved == null)
                      throw new Exception("Unknown pack " + id);
                    // Already installed and up to date: just activate it.
                    String installed_sha =
                      prefs(ctx).getString("pinyin_dict_pack_sha", null);
                    if (!resolved.dict_sha256.equals(installed_sha))
                      install(ctx, resolved);
                  }
                  final Pack pack = resolved;
                  final String msg = "builtin".equals(id)
                    ? act.getString(R.string.pinyin_dict_removed)
                    : act.getString(R.string.pinyin_dict_installed,
                        pack.name);
                  act.runOnUiThread(new Runnable()
                    {
                      public void run()
                      {
                        if (act.isFinishing())
                          return;
                        ((ListPreference)p).setValue(id);
                        toast(act, msg);
                      }
                    });
                }
                catch (final Exception e)
                {
                  act.runOnUiThread(new Runnable()
                    {
                      public void run()
                      {
                        if (!act.isFinishing())
                          toast(act, act.getString(
                                R.string.pinyin_dict_install_failed,
                                e.getMessage() == null
                                  ? e.getClass().getSimpleName()
                                  : e.getMessage()));
                      }
                    });
                }
              }
            }).start();
          return false;
        }
      });
  }

  /** Rebuild the entry list: the built-in dictionary first, then one entry
      per manifest pack. Keeps the current selection when it still exists. */
  static void refresh_entries(Activity act, ListPreference pref,
      List<Pack> packs)
  {
    List<CharSequence> labels = new ArrayList<CharSequence>();
    List<CharSequence> values = new ArrayList<CharSequence>();
    labels.add(act.getString(R.string.pinyin_dict_builtin));
    values.add("builtin");
    if (packs != null)
      for (Pack p : packs)
      {
        labels.add(act.getString(R.string.pinyin_dict_pack_label,
              p.name, p.entries, p.date));
        values.add(p.id);
      }
    pref.setEntries(labels.toArray(new CharSequence[0]));
    pref.setEntryValues(values.toArray(new CharSequence[0]));
    if (pref.getValue() == null
        || values.indexOf(pref.getValue()) < 0)
      pref.setValue("builtin");
  }

  static void toast(Activity act, String msg)
  {
    Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
  }

  // ---- plumbing ----------------------------------------------------------

  static SharedPreferences prefs(Context ctx)
  {
    return ctx.getSharedPreferences("pinyin_cloud", Context.MODE_PRIVATE);
  }

  static File files_dir(Context ctx)
  {
    File dir = new File(ctx.getFilesDir(), "pinyin");
    dir.mkdirs();
    return dir;
  }

  static String http_get(URL url) throws IOException
  {
    URLConnection conn = url.openConnection();
    conn.setRequestProperty("Accept-Encoding", "identity");
    conn.setConnectTimeout(10000);
    conn.setReadTimeout(30000);
    InputStream in = conn.getInputStream();
    try
    {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) > 0)
        out.write(buf, 0, n);
      return new String(out.toByteArray(), "UTF-8");
    }
    finally
    {
      in.close();
    }
  }

  static void download_to(URL url, File out) throws IOException
  {
    URLConnection conn = url.openConnection();
    // Plain URLConnection does not transparently decompress a gzipped
    // answer; ask the server not to gzip in the first place.
    conn.setRequestProperty("Accept-Encoding", "identity");
    conn.setConnectTimeout(10000);
    conn.setReadTimeout(60000);
    InputStream in = conn.getInputStream();
    try
    {
      FileOutputStream out_st = new FileOutputStream(out);
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) > 0)
        out_st.write(buf, 0, n);
      out_st.close();
    }
    finally
    {
      in.close();
    }
  }

  /** Verify that [f]'s SHA-256 matches [expected] (lowercase hex). */
  public static void verify_sha256(File f, String expected) throws Exception
  {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    InputStream in = new FileInputStream(f);
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) > 0)
      md.update(buf, 0, n);
    in.close();
    String got = hex(md.digest());
    if (!got.equals(expected))
      throw new Exception("Checksum mismatch: expected " + expected
          + ", got " + got);
  }

  static String hex(byte[] bytes)
  {
    StringBuilder b = new StringBuilder(bytes.length * 2);
    for (byte x : bytes)
      b.append("0123456789abcdef".charAt((x >> 4) & 0xf))
        .append("0123456789abcdef".charAt(x & 0xf));
    return b.toString();
  }

  /** SHA-256 of [bytes] as lowercase hex. Used by the tests to prepare
      checksums without touching the filesystem. */
  public static String sha256_hex(byte[] bytes) throws Exception
  {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    return hex(md.digest(bytes));
  }

  static java.nio.ByteBuffer read_file(File f) throws IOException
  {
    InputStream in = new FileInputStream(f);
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    byte[] tmp = new byte[8192];
    int n;
    while ((n = in.read(tmp)) > 0)
      out.write(tmp, 0, n);
    in.close();
    return java.nio.ByteBuffer.wrap(out.toByteArray()).slice();
  }
}
