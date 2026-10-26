package juloo.keyboard2.voice;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import juloo.keyboard2.Config;
import juloo.keyboard2.KeyValue;
import juloo.keyboard2.R;

/** Drives hold-to-talk voice input from the mic key.

    The mic key press calls [mic_down] and its release [mic_up] (see
    [KeyEventHandler] and [OverlayInputHandler]). Recognition results are
    forwarded to the current [Config.IKeyEventHandler] — the IME's
    [KeyEventHandler] or the overlay's [OverlayInputHandler] — which inserts
    them into the editor.

    A session is bounded by [MAX_DURATION_MS]: a [key_down] event is not
    guaranteed a matching [key_up], so a safety timeout stops the recording
    like a key release would. Any other key pressed while recording cancels
    it (the pointer moved away from the mic key). */
public final class VoiceInputController implements VoiceEngine.Callback
{
  static final int MAX_DURATION_MS = 30000;

  static final VoiceInputController _instance = new VoiceInputController();

  Context _context = null;
  Handler _handler = null;
  VoiceEngine _engine = null;
  boolean _recording = false;

  final Runnable _timeout = new Runnable()
  {
    public void run() { finish_recording(); }
  };

  public static VoiceInputController get() { return _instance; }

  /** Remember the application context. Called from the services that host
      a keyboard view. */
  public static void init(Context ctx)
  {
    _instance._context = ctx.getApplicationContext();
    if (_instance._handler == null)
      _instance._handler = new Handler(Looper.getMainLooper());
  }

  /** Whether [kv] is the mic key that drives hold-to-talk voice input. */
  public static boolean is_mic_key(KeyValue kv)
  {
    return kv.getKind() == KeyValue.Kind.Event
      && kv.getEvent() == KeyValue.Event.SWITCH_VOICE_TYPING;
  }

  /** The mic key was pressed. */
  public static void mic_down()
  {
    VoiceInputController c = _instance;
    if (c._recording)
    {
      c.finish_recording();
      return;
    }
    if (c._context == null)
      return;
    VoiceEngine engine = c.resolve_engine();
    if (engine == null)
      return; // resolve_engine showed the message
    if (engine.needs_record_audio() && !has_record_audio(c._context))
    {
      // The IME service cannot show a permission dialog; send the user
      // through the transparent permission activity instead.
      engine.release();
      c.toast(R.string.voice_permission_rationale);
      Intent i = new Intent(c._context, VoicePermissionActivity.class);
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      c._context.startActivity(i);
      return;
    }
    c._engine = engine;
    c._recording = true;
    Config conf = Config.globalConfig();
    if (conf != null && conf.handler != null)
      conf.handler.voice_started();
    engine.start(c._context, c);
    c._handler.postDelayed(c._timeout, MAX_DURATION_MS);
  }

  /** The mic key was released (or the safety timeout fired): stop the
      recording and commit the transcript. */
  public static void mic_up()
  {
    VoiceInputController c = _instance;
    if (!c._recording)
      return;
    c.finish_recording();
  }

  /** Another key was pressed or released while recording: the pointer
      moved away from the mic key. Cancel without committing anything. */
  public static void key_interrupted()
  {
    VoiceInputController c = _instance;
    if (c._recording)
      c.cancel_recording();
  }

  /** The keyboard is being hidden or destroyed: stop recording without
      committing anything and release the microphone. */
  public static void abort()
  {
    _instance.cancel_recording();
  }

  void finish_recording()
  {
    _handler.removeCallbacks(_timeout);
    _recording = false;
    if (_engine != null)
      _engine.stop();
    // The engine delivers the final result (or an error) via callbacks.
  }

  void cancel_recording()
  {
    _handler.removeCallbacks(_timeout);
    _recording = false;
    if (_engine != null)
    {
      _engine.cancel();
      _engine.release();
      _engine = null;
    }
  }

  /** Pick an engine following the [voice_engine] setting. Shows a toast
      and returns [null] when no engine can run. */
  VoiceEngine resolve_engine()
  {
    Config conf = Config.globalConfig();
    String pref = conf == null ? "auto" : conf.voice_engine;
    VoiceEngine system = new SystemVoiceEngine();
    VoiceEngine local = SherpaVoiceEngine.create_if_installed(_context);
    if (pref.equals("system"))
    {
      if (system.is_available(_context))
        return system;
      toast(R.string.voice_error_no_system_engine);
      return null;
    }
    if (pref.equals("local"))
    {
      if (local != null)
        return local;
      toast(R.string.voice_error_no_model);
      return null;
    }
    // Automatic: prefer the offline model when installed, else the system
    // service. Devices without either (e.g. vivo without GMS) get a hint
    // towards downloading the offline model.
    if (local != null)
      return local;
    if (system.is_available(_context))
      return system;
    toast(R.string.voice_error_no_engine);
    return null;
  }

  void toast(final int res)
  {
    if (_context == null)
      return;
    Toast.makeText(_context, res, Toast.LENGTH_SHORT).show();
  }

  static boolean has_record_audio(Context ctx)
  {
    return VERSION.SDK_INT < 23
      || ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED;
  }

  // VoiceEngine.Callback. Engines may call from any thread; marshal to the
  // main thread before touching the config or the editor.

  public void on_partial(final String text)
  {
    _handler.post(new Runnable()
    {
      public void run()
      {
        Config conf = Config.globalConfig();
        if (conf != null && conf.handler != null)
          conf.handler.voice_partial(text);
      }
    });
  }

  public void on_final(final String text)
  {
    _handler.post(new Runnable()
    {
      public void run()
      {
        if (_engine != null)
        {
          _engine.release();
          _engine = null;
        }
        Config conf = Config.globalConfig();
        if (conf != null && conf.handler != null)
          conf.handler.voice_final(text);
      }
    });
  }

  public void on_error(final int message_res)
  {
    _handler.post(new Runnable()
    {
      public void run()
      {
        if (_engine != null)
        {
          _engine.release();
          _engine = null;
        }
        toast(message_res);
        Config conf = Config.globalConfig();
        if (conf != null && conf.handler != null)
          conf.handler.voice_final("");
      }
    });
  }
}
