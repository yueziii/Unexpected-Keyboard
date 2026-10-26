package juloo.keyboard2.voice;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import juloo.keyboard2.R;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Downloads offline speech recognition models from a manifest hosted on
 * GitHub and installs them into the app's private storage
 * (files/voice/<id>/), where [SherpaVoiceEngine] picks up the selected one.
 *
 * The model list ships inside the APK (assets/voice-models.json), so the
 * feature works without any server. The MANIFEST_URL is an optional
 * override: when it is reachable, it wins over the bundled list, allowing
 * new models without shipping an app update. Nothing needs to be hosted
 * for the feature to work.
 *
 * Privacy: only model files are ever downloaded, nothing about the user is
 * uploaded. Each file carries a sha256 checksum verified before install;
 * the download happens into a temporary directory that is renamed into
 * place atomically, so a failed download never breaks a working model.
 * Re-selecting a model whose installed files still match the manifest
 * checksums selects it without downloading anything.
 *
 * Every file lists several mirror URLs tried in order: hf-mirror.com first
 * (reachable from mainland China), then huggingface.co.
 */
public final class VoiceModelManager
{
  public static final String MANIFEST_URL =
    "https://raw.githubusercontent.com/yueziii/Unexpected-Keyboard-pinyin-dictionaries/main/voice-models.json";

  /** The manifest bundled in the APK (assets/voice-models.json). */
  public static final String BUNDLED_MANIFEST_ASSET = "voice-models.json";

  static final String PREF_SELECTED = "voice_local_model";

  private static final Object _install_lock = new Object();

  private VoiceModelManager() {}

  // ---- manifest ----------------------------------------------------------

  /** One downloadable model file. */
  public static final class ModelFile
  {
    public final String role; // encoder, decoder, joiner or tokens
    public final String path; // file name inside the model directory
    public final String sha256;
    public final long bytes;
    public final List<String> urls; // mirrors, tried in order

    ModelFile(JSONObject o) throws Exception
    {
      role = o.getString("role");
      path = o.getString("path");
      sha256 = o.getString("sha256");
      bytes = o.optLong("bytes", 0);
      urls = new ArrayList<String>();
      JSONArray u = o.getJSONArray("urls");
      for (int i = 0; i < u.length(); i++)
        urls.add(u.getString(i));
    }
  }

  /** One downloadable model, as described by the manifest. */
  public static final class Model
  {
    public final String id;
    public final String name;
    public final String description;
    /** streaming_paraformer or streaming_zipformer. */
    public final String type;
    public final int sampleRate;
    public final int featureDim;
    public final long bytes; // total download size
    public final List<ModelFile> files;

    Model(JSONObject o) throws Exception
    {
      id = o.getString("id");
      name = o.getString("name");
      description = o.optString("description", "");
      type = o.getString("type");
      sampleRate = o.optInt("sample_rate", 16000);
      featureDim = o.optInt("feature_dim", 80);
      bytes = o.optLong("bytes", 0);
      files = new ArrayList<ModelFile>();
      JSONArray arr = o.getJSONArray("files");
      for (int i = 0; i < arr.length(); i++)
        files.add(new ModelFile(arr.getJSONObject(i)));
    }
  }

  /** An installed model, described by the model.json written on install. */
  public static final class ModelSpec
  {
    public final File dir;
    public final String id;
    public final String type;
    public final int sampleRate;
    public final int featureDim;
    /** Role → file name inside [dir]. */
    public final Map<String, String> files;

    public ModelSpec(File dir, JSONObject o) throws Exception
    {
      this.dir = dir;
      id = o.getString("id");
      type = o.getString("type");
      sampleRate = o.optInt("sample_rate", 16000);
      featureDim = o.optInt("feature_dim", 80);
      files = new HashMap<String, String>();
      JSONObject fs = o.getJSONObject("files");
      java.util.Iterator<String> it = fs.keys();
      while (it.hasNext())
      {
        String role = it.next();
        files.put(role, fs.getString(role));
      }
    }

    public File file(String role)
    {
      String name = files.get(role);
      return name == null ? null : new File(dir, name);
    }
  }

  /** Parse a manifest JSON document (see cloud/pinyin-dictionaries/
      voice-models.json in the repository for the format). */
  public static List<Model> parse_manifest(String json) throws Exception
  {
    JSONObject root = new JSONObject(json);
    int version = root.optInt("format_version", 0);
    if (version != 1)
      throw new Exception("Unsupported manifest format version");
    JSONArray arr = root.getJSONArray("models");
    List<Model> models = new ArrayList<Model>();
    for (int i = 0; i < arr.length(); i++)
      models.add(new Model(arr.getJSONObject(i)));
    return models;
  }

  /** Fetch the manifest from the network and cache it for offline use.
      Blocking — call from a background thread. */
  public static List<Model> fetch_manifest(Context ctx) throws Exception
  {
    String json = http_get(new URL(MANIFEST_URL));
    List<Model> models = parse_manifest(json);
    prefs(ctx).edit().putString("manifest_cache", json).apply();
    return models;
  }

  /** The last successfully fetched manifest, or null when none was ever
      fetched. */
  public static List<Model> cached_manifest(Context ctx)
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

  /** The manifest bundled in the APK. Always available; used before any
      remote manifest was fetched, and as the fallback when the remote
      manifest cannot be reached. */
  public static List<Model> bundled_manifest(Context ctx)
  {
    try
    {
      java.io.InputStream in = ctx.getAssets().open(BUNDLED_MANIFEST_ASSET);
      try
      {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0)
          out.write(buf, 0, n);
        return parse_manifest(new String(out.toByteArray(), "UTF-8"));
      }
      finally
      {
        in.close();
      }
    }
    catch (Exception e)
    {
      return null;
    }
  }

  /** The best available model list: the fetched remote manifest, else the
      manifest bundled in the APK. */
  public static List<Model> available_models(Context ctx)
  {
    List<Model> models = cached_manifest(ctx);
    return models != null ? models : bundled_manifest(ctx);
  }

  // ---- install / remove --------------------------------------------------

  /** The id of the selected model, "none" when no offline model is used. */
  public static String selected_model(Context ctx)
  {
    return prefs(ctx).getString(PREF_SELECTED, "none");
  }

  /** The spec of the selected installed model, or null. */
  public static ModelSpec load_selected_spec(Context ctx)
  {
    String id = selected_model(ctx);
    if (id.equals("none"))
      return null;
    return load_spec(ctx, id);
  }

  /** The spec of the installed model [id], or null when it is not
      installed (or broken). */
  public static ModelSpec load_spec(Context ctx, String id)
  {
    File dir = new File(models_dir(ctx), id);
    File desc = new File(dir, "model.json");
    if (!desc.isFile())
      return null;
    try
    {
      return new ModelSpec(dir, new JSONObject(read_json(desc)));
    }
    catch (Exception e)
    {
      return null;
    }
  }

  /** Download [model], verify every file and select it. Blocking — call
      from a background thread. Throws on any failure; on failure
      previously installed models are left untouched. A model that is
      already installed with files matching the manifest checksums is not
      downloaded again, so switching between installed models is instant
      and works offline. */
  public static void install(Context ctx, Model model) throws Exception
  {
    synchronized (_install_lock)
    {
      File models = models_dir(ctx);
      File dir = new File(models, model.id);
      if (matches_manifest(dir, model))
      {
        prefs(ctx).edit().putString(PREF_SELECTED, model.id).apply();
        return;
      }
      File tmp = new File(models, model.id + ".tmp");
      delete_recursive(tmp);
      tmp.mkdirs();
      try
      {
        for (ModelFile f : model.files)
        {
          File out = new File(tmp, f.path);
          download_file(f, out);
          verify_sha256(out, f.sha256);
        }
        write_descriptor(tmp, model);
        delete_recursive(dir);
        if (!tmp.renameTo(dir))
          throw new Exception("Could not move model into place");
        prefs(ctx).edit().putString(PREF_SELECTED, model.id).apply();
      }
      finally
      {
        delete_recursive(tmp);
      }
    }
  }

  /** Whether [dir] already holds a complete install of [model]. Files are
      re-verified against the manifest checksums rather than trusted, so a
      truncated file or a manifest update under the same id still triggers
      a download. */
  public static boolean matches_manifest(File dir, Model model)
  {
    if (!new File(dir, "model.json").isFile())
      return false;
    for (ModelFile f : model.files)
    {
      File local = new File(dir, f.path);
      if (!local.isFile())
        return false;
      try
      {
        verify_sha256(local, f.sha256);
      }
      catch (Exception e)
      {
        return false;
      }
    }
    return true;
  }

  /** Delete every installed model and deselect the offline engine. */
  public static void remove(Context ctx)
  {
    synchronized (_install_lock)
    {
      delete_recursive(models_dir(ctx));
      prefs(ctx).edit().putString(PREF_SELECTED, "none").apply();
    }
  }

  /** Write the model.json descriptor consumed by [SherpaVoiceEngine]. */
  public static void write_descriptor(File dir, Model model) throws Exception
  {
    JSONObject o = new JSONObject();
    o.put("id", model.id);
    o.put("type", model.type);
    o.put("sample_rate", model.sampleRate);
    o.put("feature_dim", model.featureDim);
    JSONObject files = new JSONObject();
    for (ModelFile f : model.files)
      files.put(f.role, f.path);
    o.put("files", files);
    FileOutputStream out = new FileOutputStream(new File(dir, "model.json"));
    try
    {
      out.write(o.toString().getBytes("UTF-8"));
    }
    finally
    {
      out.close();
    }
  }

  // ---- settings screen ---------------------------------------------------

  /** Wire the "offline voice model" ListPreference, following the pattern
      of the pinyin dictionary packs: the list refreshes in the background
      when opened, choosing an entry downloads (or deletes) the model, and
      the displayed value only changes once the action succeeded. */
  public static void bind_preference(final Activity act, final ListPreference pref)
  {
    final Context ctx = act.getApplicationContext();
    refresh_entries(act, pref, available_models(ctx));
    pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener()
      {
        public boolean onPreferenceClick(Preference p)
        {
          new Thread(new Runnable()
            {
              public void run()
              {
                try
                {
                  final List<Model> models = fetch_manifest(ctx);
                  act.runOnUiThread(new Runnable()
                    {
                      public void run()
                      {
                        if (!act.isFinishing())
                          refresh_entries(act, pref, models);
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
          toast(act, act.getString(R.string.voice_model_installing));
          new Thread(new Runnable()
            {
              public void run()
              {
                try
                {
                  if ("none".equals(id))
                  {
                    remove(ctx);
                  }
                  else
                  {
                    List<Model> models = available_models(ctx);
                    if (models == null)
                      models = fetch_manifest(ctx);
                    Model resolved = null;
                    for (Model cand : models)
                      if (cand.id.equals(id))
                        resolved = cand;
                    if (resolved == null)
                      throw new Exception("Unknown model " + id);
                    install(ctx, resolved);
                  }
                  final String msg = "none".equals(id)
                    ? act.getString(R.string.voice_model_removed)
                    : act.getString(R.string.voice_model_installed);
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
                                R.string.voice_model_install_failed,
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

  /** Rebuild the entry list: "not installed" first, then one entry per
      manifest model with its download size and install state. */
  static void refresh_entries(Activity act, ListPreference pref,
      List<Model> models)
  {
    List<CharSequence> labels = new ArrayList<CharSequence>();
    List<CharSequence> values = new ArrayList<CharSequence>();
    labels.add(act.getString(R.string.voice_model_none));
    values.add("none");
    Context ctx = act.getApplicationContext();
    if (models != null)
      for (Model m : models)
      {
        boolean installed = load_spec(ctx, m.id) != null;
        labels.add(act.getString(R.string.voice_model_label,
              m.name, (int)Math.max(1, m.bytes / 1024 / 1024),
              installed ? act.getString(R.string.voice_model_state_installed)
                : ""));
        values.add(m.id);
      }
    pref.setEntries(labels.toArray(new CharSequence[0]));
    pref.setEntryValues(values.toArray(new CharSequence[0]));
    if (pref.getValue() == null
        || values.indexOf(pref.getValue()) < 0)
      pref.setValue("none");
  }

  static void toast(Activity act, String msg)
  {
    Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
  }

  // ---- plumbing ----------------------------------------------------------

  static SharedPreferences prefs(Context ctx)
  {
    return ctx.getSharedPreferences("voice_cloud", Context.MODE_PRIVATE);
  }

  static File models_dir(Context ctx)
  {
    return new File(ctx.getFilesDir(), SherpaVoiceEngine.MODELS_DIR);
  }

  /** Download [f] trying every mirror URL in order. */
  static void download_file(ModelFile f, File out) throws Exception
  {
    Exception last = null;
    for (String u : f.urls)
    {
      try
      {
        download_to(new URL(u), out);
        return;
      }
      catch (Exception e)
      {
        last = e;
        out.delete();
      }
    }
    throw new Exception("Download failed for " + f.path
        + (last == null ? "" : ": " + last.getMessage()));
  }

  static void delete_recursive(File f)
  {
    if (f.isDirectory())
      for (File c : f.listFiles())
        delete_recursive(c);
    f.delete();
  }

  static String read_json(File f) throws IOException
  {
    InputStream in = new FileInputStream(f);
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
    conn.setRequestProperty("Accept-Encoding", "identity");
    conn.setConnectTimeout(15000);
    conn.setReadTimeout(120000);
    InputStream in = conn.getInputStream();
    try
    {
      FileOutputStream out_st = new FileOutputStream(out);
      byte[] buf = new byte[65536];
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
    byte[] buf = new byte[65536];
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
}
