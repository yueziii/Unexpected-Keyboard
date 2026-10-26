package juloo.keyboard2.pinyin.cloud;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
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
import juloo.keyboard2.pinyin.PinyinHotwords;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Cloud dictionary plumbing: parses the manifest hosted on GitHub, installs
 * hotword overlays (the current product) and, kept for older clients and
 * tests, full dictionary packs.
 *
 * Hotwords: the manifest's "hotwords" section describes a small text
 * overlay (PinyinHotwords format) with the current trending words. The
 * built-in dictionary always carries a baseline word list, so a missing
 * repository, no network or no section simply means "stay on the built-in
 * list" — installing is best-effort by design.
 *
 * Privacy: only dictionary files are ever downloaded, nothing about the
 * user is uploaded. Every file carries a sha256 checksum that is verified
 * before anything is written, and the download is parsed once before it
 * replaces the active data. A failed download never leaves the previously
 * active state broken.
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
      return manifest_base_url() + ("dict".equals(which) ? dict_path
          : bigram_path);
    }
  }

  /** The manifest's optional "hotwords" section: a small text overlay with
      the current trending words (PinyinHotwords format). */
  public static final class HotwordsInfo
  {
    public final String path; // relative to the manifest's base URL
    public final String sha256;
    public final int bytes;
    public final int count;
    public final String date;

    HotwordsInfo(JSONObject o) throws Exception
    {
      path = o.getString("path");
      sha256 = o.getString("sha256");
      bytes = o.getInt("bytes");
      count = o.optInt("count", 0);
      date = o.optString("date", "");
    }

    /** Absolute URL of the hotwords file. */
    public String url() { return manifest_base_url() + path; }
  }

  static String manifest_base_url()
  {
    String base = MANIFEST_URL;
    return base.substring(0, base.lastIndexOf('/') + 1);
  }

  /** Parse a manifest JSON document (see cloud/pinyin-dictionaries in the
      repository for the format and how it is produced). Version 2 packs
      use the PYD2 dictionary format (with the 简拼 index); both are
      accepted, the binaries are validated on install. */
  public static List<Pack> parse_manifest(String json) throws Exception
  {
    JSONObject root = new JSONObject(json);
    int version = root.optInt("format_version", 0);
    if (version != 1 && version != 2)
      throw new Exception("Unsupported manifest format version");
    JSONArray arr = root.getJSONArray("packs");
    List<Pack> packs = new ArrayList<Pack>();
    for (int i = 0; i < arr.length(); i++)
      packs.add(new Pack(arr.getJSONObject(i)));
    return packs;
  }

  /** The manifest's "hotwords" section, or null when the repository does
      not publish one (clients then stay on the built-in word list). */
  public static HotwordsInfo parse_hotwords(String json) throws Exception
  {
    JSONObject root = new JSONObject(json);
    if (!root.has("hotwords"))
      return null;
    return new HotwordsInfo(root.getJSONObject("hotwords"));
  }

  /** Fetch the manifest from the network and cache it for offline use.
      Blocking — call from a background thread. */
  public static String fetch_manifest_json(Context ctx) throws Exception
  {
    String json = http_get(new URL(MANIFEST_URL));
    parse_manifest(json); // Reject garbage before caching it.
    prefs(ctx).edit().putString("manifest_cache", json).apply();
    return json;
  }

  /** Fetch the manifest and return its packs. */
  public static List<Pack> fetch_manifest(Context ctx) throws Exception
  {
    return parse_manifest(fetch_manifest_json(ctx));
  }

  /** The last successfully fetched manifest, or null when none was ever
      fetched. Used to populate the settings list when offline. */
  public static String cached_manifest_json(Context ctx)
  {
    return prefs(ctx).getString("manifest_cache", null);
  }

  public static List<Pack> cached_manifest(Context ctx)
  {
    String json = cached_manifest_json(ctx);
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

  /** The date of the installed hotwords file, or null when none. */
  public static String hotwords_installed_date(Context ctx)
  {
    return prefs(ctx).getString("hotwords_date", null);
  }

  /** Download [info], verify it, validate the overlay and make it the
      active hotwords file. Blocking — call from a background thread.
      Throws on any failure; the previously installed file is left
      untouched. */
  public static void install_hotwords(Context ctx, HotwordsInfo info)
    throws Exception
  {
    synchronized (_install_lock)
    {
      File dir = files_dir(ctx);
      File tmp = new File(dir, "hotwords.tsv.tmp");
      try
      {
        download_to(new URL(info.url()), tmp);
        verify_sha256(tmp, info.sha256);
        // Parse the overlay once before installing, so a corrupt file is
        // rejected instead of replacing a working one.
        PinyinHotwords.parse(read_file_text(tmp));
        if (!tmp.renameTo(new File(dir, "hotwords.tsv")))
          throw new Exception("Could not move hotwords into place");
        prefs(ctx).edit()
          .putString("hotwords_date", info.date)
          .putString("hotwords_sha", info.sha256)
          .apply();
        PinyinHotwords.reload(ctx.getApplicationContext());
      }
      finally
      {
        tmp.delete();
      }
    }
  }

  /** Remove the installed hotwords file and fall back to the built-in
      word list. */
  public static void remove_hotwords(Context ctx)
  {
    synchronized (_install_lock)
    {
      File dir = files_dir(ctx);
      new File(dir, "hotwords.tsv").delete();
      prefs(ctx).edit().remove("hotwords_date").remove("hotwords_sha")
        .apply();
      PinyinHotwords.reload(ctx.getApplicationContext());
    }
  }

  /** Download [pack], verify it and make it the active dictionary. Kept
      for older clients and tests; the settings screen no longer exposes
      full packs (the built-in dictionary carries the full word list).
      Blocking — call from a background thread. Throws on any failure; on
      failure the previously active dictionary is left untouched. */
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

  /** Wire the "hotwords update" preference: a click fetches the manifest,
      downloads a new overlay when the repository publishes one and falls
      back to the built-in word list when it does not (no network, no
      section, any failure — the built-in dictionary keeps working). */
  public static void bind_hotwords_preference(final Activity act,
      final Preference pref)
  {
    final Context ctx = act.getApplicationContext();
    refresh_hotwords_summary(act, pref);
    pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener()
      {
        public boolean onPreferenceClick(Preference p)
        {
          toast(act, act.getString(R.string.pinyin_hotwords_checking));
          new Thread(new Runnable()
            {
              public void run()
              {
                try
                {
                  String json = fetch_manifest_json(ctx);
                  final HotwordsInfo info = parse_hotwords(json);
                  final String msg;
                  if (info == null)
                    // No section published: the built-in list is current.
                    msg = act.getString(R.string.pinyin_hotwords_none);
                  else if (info.sha256.equals(
                        prefs(ctx).getString("hotwords_sha", null)))
                    msg = act.getString(R.string.pinyin_hotwords_up_to_date,
                        info.date);
                  else
                  {
                    install_hotwords(ctx, info);
                    msg = act.getString(R.string.pinyin_hotwords_updated,
                        info.date, info.count);
                  }
                  act.runOnUiThread(new Runnable()
                    {
                      public void run()
                      {
                        if (act.isFinishing())
                          return;
                        refresh_hotwords_summary(act, pref);
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
                                R.string.pinyin_hotwords_failed,
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

  /** The preference summary states the installed overlay's date, or that
      the built-in list is in use. */
  static void refresh_hotwords_summary(Activity act, Preference pref)
  {
    String date = hotwords_installed_date(act.getApplicationContext());
    pref.setSummary(date == null
        ? act.getString(R.string.pref_pinyin_hotwords_summary_builtin)
        : act.getString(R.string.pref_pinyin_hotwords_summary_updated,
            date));
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

  /** Read [f] as UTF-8 text. Used to validate a downloaded hotwords file
      before installing it. */
  static String read_file_text(File f) throws IOException
  {
    InputStream in = new FileInputStream(f);
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    byte[] tmp = new byte[8192];
    int n;
    while ((n = in.read(tmp)) > 0)
      out.write(tmp, 0, n);
    in.close();
    return new String(out.toByteArray(), "UTF-8");
  }
}
