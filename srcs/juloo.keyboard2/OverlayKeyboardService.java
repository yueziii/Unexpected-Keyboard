package juloo.keyboard2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.os.Build.VERSION;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.FrameLayout;
import juloo.keyboard2.dict.Dictionaries;

/** Shows the keyboard as an independent overlay window on top of other apps,
    without being the active input method. Key presses are typed into the
    focused editor through [OverlayAccessibilityService]. A draggable bubble
    stays visible at all times; tapping it shows or hides the keyboard. The
    keyboard window is resized with the grip in its top bar like the floating
    keyboard (dragging horizontally changes the width, vertically the height)
    and can be pinned on top of other apps, in which case tapping outside
    doesn't hide it. */
public class OverlayKeyboardService extends Service
  implements OverlayInputHandler.Ui
{
  public static final String ACTION_START = "juloo.keyboard2.overlay.START";
  public static final String ACTION_EXPAND = "juloo.keyboard2.overlay.EXPAND";
  public static final String ACTION_COLLAPSE = "juloo.keyboard2.overlay.COLLAPSE";
  public static final String ACTION_TOGGLE = "juloo.keyboard2.overlay.TOGGLE";
  public static final String ACTION_STOP = "juloo.keyboard2.overlay.STOP";

  static final String NOTIF_CHANNEL_ID = "overlay_keyboard";
  static final int NOTIF_ID = 2;

  /** The "persistent bubble" setting ([res/xml/settings.xml]). [true] keeps
      the bubble on screen until the service is stopped explicitly, and
      restores it at boot ([BootReceiver]). */
  public static final String PREF_BUBBLE_SETTING = "overlay_bubble";

  static final String PREF_WIDTH_FRACTION = "overlay_width_fraction";
  static final String PREF_HEIGHT_PERCENT = "overlay_height_percent";
  static final String PREF_PINNED = "overlay_pinned";
  static final String PREF_BUBBLE_X = "overlay_bubble_x";
  static final String PREF_BUBBLE_Y = "overlay_bubble_y";
  static final String PREF_KEYBOARD_X = "overlay_keyboard_x";
  static final String PREF_KEYBOARD_Y = "overlay_keyboard_y";

  /** The keyboard width as a fraction of the screen width, and its height as
      a percentage of the screen height using the same 395-unit convention as
      [Config.keyboard_rows_height_pixels]. Same bounds as the floating
      keyboard. */
  static final float MIN_WIDTH_FRACTION = 0.4f;
  static final float MAX_WIDTH_FRACTION = 0.9f;
  static final float DEFAULT_WIDTH_FRACTION = 0.9f;
  static final int MIN_HEIGHT_PERCENT = 10;
  static final int MAX_HEIGHT_PERCENT = 80;

  static OverlayKeyboardService _instance = null;

  /** Whether the overlay service is running (the bubble is then visible,
      possibly with the keyboard expanded on top of it). Used by the settings
      screen to keep the bubble switch in sync. */
  public static boolean is_running()
  {
    return _instance != null;
  }

  /** Whether the keyboard window is currently expanded. Used by the tile. */
  public static boolean is_expanded()
  {
    OverlayKeyboardService svc = _instance;
    return svc != null && svc._keyboard_root != null;
  }

  /** Start the service to show the bubble if the persistent-bubble setting
      is on but the service isn't running, e.g. after a reboot
      ([BootReceiver]) or after the process was killed. Does nothing if the
      setting is off or the overlay permission isn't granted. */
  public static void start_if_setting_on(Context context)
  {
    if (is_running())
      return;
    if (VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context))
      return;
    SharedPreferences prefs =
      DirectBootAwarePreferences.get_shared_preferences(context);
    if (!prefs.getBoolean(PREF_BUBBLE_SETTING, false))
      return;
    Intent svc = new Intent(context, OverlayKeyboardService.class);
    svc.setAction(ACTION_START);
    try
    {
      if (VERSION.SDK_INT >= 26)
        context.startForegroundService(svc);
      else
        context.startService(svc);
    }
    catch (Exception e)
    {
      // The system can restrict starting services from the background.
    }
  }

  WindowManager _wm;
  Config _config;
  OverlayInputHandler _input_handler;
  /** Event handler to restore when the overlay stops driving key events. */
  Config.IKeyEventHandler _saved_handler;
  View _keyboard_root;
  /** Content of the keyboard window, scaled uniformly to resize it. */
  View _content;
  Keyboard2View _kb_view;
  WindowManager.LayoutParams _keyboard_lp;
  View _bubble;
  WindowManager.LayoutParams _bubble_lp;
  /** Pin button, dimmed while the keyboard is not pinned. */
  View _pin_btn;
  /** Keyboard width as a fraction of the screen width. */
  float _width_fraction;
  /** Keyboard height as a percentage of the screen height, following the
      [Config.keyboard_rows_height_pixels] convention. */
  int _height_percent;
  /** Value of [Config.keyboard_rows_height_pixels] before the keyboard was
      expanded; restored when the keyboard is hidden so the input method is
      not affected by the overlay resizing it. */
  int _saved_rows_height;
  /** Split layout config value before the keyboard was expanded; the overlay
      is a narrow floating window and never uses the split layout. */
  boolean _saved_split_layout;
  /** Whether [_saved_rows_height]/[_saved_split_layout] hold values to
      restore. */
  boolean _config_overridden;
  /** System bar heights on the display. Overlay window Y coordinates are
      relative to the area below the status bar. */
  int _status_bar_height, _nav_bar_height;
  /** Whether the keyboard stays on top of other apps when they are tapped. */
  boolean _pinned;
  SharedPreferences _prefs;
  /** Whether the keyboard was expanded when the current bubble touch started. */
  boolean _bubble_was_expanded;
  float _touch_down_x, _touch_down_y;
  int _window_down_x, _window_down_y;
  boolean _dragging;
  /** Resize gesture state, kept separate from the drag state as the two
      gestures can run at the same time. */
  float _resize_down_x, _resize_down_y;
  int _resize_down_width, _resize_down_height;
  boolean _resizing;
  /** Ratio between the keyboard height (the window height minus the fixed
      bottom bar) and [Config.keyboard_rows_height_pixels] measured at resize
      start; converts the dragged height into a rows height so the keyboard
      tracks the finger, like [FloatingKeyboardManager]. */
  float _rows_height_scale = 4f;
  /** Height of the bottom handle bar, which doesn't scale with the keyboard. */
  int _bar_height;

  @Override
  public void onCreate()
  {
    super.onCreate();
    // Config might not be initialized if the input method never ran in this
    // process.
    _prefs = DirectBootAwarePreferences.get_shared_preferences(this);
    if (Config.globalConfig() == null)
      Config.initGlobalConfig(_prefs, getResources(), true,
          Dictionaries.instance(this));
    _config = Config.globalConfig();
    _wm = (WindowManager)getSystemService(Context.WINDOW_SERVICE);
    DisplayMetrics dm = getResources().getDisplayMetrics();
    _status_bar_height = system_bar_height("status_bar_height",
        (int)(dm.density * 24));
    _nav_bar_height = system_bar_height("navigation_bar_height", 0);
    _input_handler = new OverlayInputHandler(this);
    juloo.keyboard2.voice.VoiceInputController.init(this);
    _width_fraction = clamp_width_fraction(
        _prefs.getFloat(PREF_WIDTH_FRACTION, DEFAULT_WIDTH_FRACTION));
    int saved_height = _prefs.getInt(PREF_HEIGHT_PERCENT, -1);
    _height_percent = (saved_height != -1) ? clamp_height_percent(saved_height)
      : clamp_height_percent(Math.round(
            _config.keyboard_rows_height_pixels * 395f / screen_height()));
    _pinned = _prefs.getBoolean(PREF_PINNED, false);
    _instance = this;
  }

  @Override
  public IBinder onBind(Intent intent)
  {
    return null;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId)
  {
    String action = (intent == null) ? ACTION_START : intent.getAction();
    start_foreground();
    if (ACTION_TOGGLE.equals(action))
      action = (_keyboard_root != null) ? ACTION_COLLAPSE : ACTION_EXPAND;
    if (ACTION_EXPAND.equals(action))
      expand();
    else if (ACTION_COLLAPSE.equals(action))
      collapse();
    else if (ACTION_STOP.equals(action))
      stop_floating();
    else // START: show the bubble as a resting state
      show_bubble();
    return START_NOT_STICKY;
  }

  @Override
  public void onDestroy()
  {
    juloo.keyboard2.voice.VoiceInputController.abort();
    remove_window(_keyboard_root);
    _keyboard_root = null;
    remove_window(_bubble);
    _bubble = null;
    _content = null;
    _pin_btn = null;
    restore_config();
    set_input_handler_active(false);
    _instance = null;
    super.onDestroy();
  }

  /** Overlay permission must have been granted for this to work. On
      Android < 6 the permission is granted at install time. */
  boolean can_show_window()
  {
    if (VERSION.SDK_INT < 23)
      return true;
    return Settings.canDrawOverlays(this);
  }

  void expand()
  {
    if (!can_show_window() || _keyboard_root != null)
      return;
    View root = View.inflate(new ContextThemeWrapper(this, _config.theme),
        R.layout.overlay_keyboard, null);
    _content = root.findViewById(R.id.overlay_content);
    _kb_view = (Keyboard2View)root.findViewById(R.id.overlay_keyboard_view);
    _pin_btn = root.findViewById(R.id.overlay_pin);
    override_config();
    show_text_layout();
    root.findViewById(R.id.overlay_collapse).setOnClickListener(new View.OnClickListener()
    {
      public void onClick(View v) { collapse(); }
    });
    root.findViewById(R.id.overlay_close).setOnClickListener(new View.OnClickListener()
    {
      public void onClick(View v) { stop_floating(); }
    });
    _pin_btn.setOnClickListener(new View.OnClickListener()
    {
      public void onClick(View v) { toggle_pinned(); }
    });
    attach_drag(root.findViewById(R.id.overlay_drag));
    attach_resize(root.findViewById(R.id.overlay_resize));
    // Tap outside of the keyboard collapses it back to the bubble unless it
    // is pinned on top of other apps.
    root.setOnTouchListener(new View.OnTouchListener()
    {
      @Override
      public boolean onTouch(View v, MotionEvent ev)
      {
        if (ev.getAction() == MotionEvent.ACTION_OUTSIDE && !_pinned)
          collapse();
        return false;
      }
    });
    set_input_handler_active(true);
    // Like the floating keyboard, the content is laid out at the real window
    // size: [Keyboard2View.onMeasure] adapts the key and label sizes to the
    // width, and the height comes from [Config.keyboard_rows_height_pixels].
    int w = (int)(screen_width() * _width_fraction);
    _content.measure(
        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    int h = _content.getMeasuredHeight();
    _bar_height = root.findViewById(R.id.overlay_bar).getMeasuredHeight();
    _content.setLayoutParams(new FrameLayout.LayoutParams(w, h));
    _keyboard_root = root;
    _keyboard_lp = keyboard_layout_params(w, h);
    _wm.addView(root, _keyboard_lp);
    update_pin_button();
    // The bubble stays visible so the keyboard can be toggled from it.
  }

  void collapse()
  {
    remove_window(_keyboard_root);
    _keyboard_root = null;
    _kb_view = null;
    restore_config();
    set_input_handler_active(false);
    show_bubble(); // No-op while the bubble is already visible.
  }

  void toggle_pinned()
  {
    _pinned = !_pinned;
    _prefs.edit().putBoolean(PREF_PINNED, _pinned).apply();
    update_pin_button();
    if (_keyboard_root != null)
      try
      {
        // Re-adding the window raises it above other overlay windows.
        _wm.removeView(_keyboard_root);
        _wm.addView(_keyboard_root, _keyboard_lp);
      }
      catch (Exception e)
      {
      }
  }

  void update_pin_button()
  {
    if (_pin_btn != null)
      _pin_btn.setAlpha(_pinned ? 1f : 0.4f);
  }

  void show_bubble()
  {
    if (!can_show_window() || _bubble != null)
      return;
    View bubble = View.inflate(new ContextThemeWrapper(this, _config.theme),
        R.layout.overlay_bubble, null);
    bubble.setOnTouchListener(new View.OnTouchListener()
    {
      @Override
      public boolean onTouch(View v, MotionEvent ev)
      {
        return bubble_touch(ev);
      }
    });
    _bubble = bubble;
    _bubble_lp = bubble_layout_params();
    _wm.addView(bubble, _bubble_lp);
  }

  void stop_floating()
  {
    // The bubble won't come back on its own: turn the persistent-bubble
    // setting off so the switch in the settings screen reflects this. The
    // default prefs are also written because the settings screen reads them
    // before copying to the device-protected storage.
    _prefs.edit().putBoolean(PREF_BUBBLE_SETTING, false).apply();
    try
    {
      PreferenceManager.getDefaultSharedPreferences(this).edit()
        .putBoolean(PREF_BUBBLE_SETTING, false).apply();
    }
    catch (Exception e) {}
    if (VERSION.SDK_INT >= 24)
      stopForeground(STOP_FOREGROUND_REMOVE);
    else
      stopForeground(true);
    stopSelf();
  }

  /** Touch handler for the bubble: drag to move, tap to show or hide the
      keyboard. */
  boolean bubble_touch(MotionEvent ev)
  {
    switch (ev.getActionMasked())
    {
      case MotionEvent.ACTION_DOWN:
        _dragging = false;
        // Recorded at down time: the tap outside might already have collapsed
        // the keyboard by the time the finger is lifted.
        _bubble_was_expanded = _keyboard_root != null;
        _touch_down_x = ev.getRawX();
        _touch_down_y = ev.getRawY();
        _window_down_x = _bubble_lp.x;
        _window_down_y = _bubble_lp.y;
        return true;
      case MotionEvent.ACTION_MOVE:
      {
        float dx = ev.getRawX() - _touch_down_x;
        float dy = ev.getRawY() - _touch_down_y;
        if (!_dragging && Math.abs(dx) + Math.abs(dy) >
            ViewConfiguration.get(this).getScaledTouchSlop())
          _dragging = true;
        if (_dragging)
        {
          _bubble_lp.x = clamp_coord((int)(_window_down_x + dx), 0,
              screen_width() - _bubble.getWidth());
          _bubble_lp.y = clamp_coord((int)(_window_down_y + dy), 0,
              Math.max(0, usable_height() - _bubble.getHeight()));
          _wm.updateViewLayout(_bubble, _bubble_lp);
        }
        return true;
      }
      case MotionEvent.ACTION_UP:
        if (!_dragging)
        {
          if (_bubble_was_expanded)
            collapse();
          else
            expand();
        }
        _prefs.edit().putInt(PREF_BUBBLE_X, _bubble_lp.x)
          .putInt(PREF_BUBBLE_Y, _bubble_lp.y).apply();
        return true;
      default:
        return _dragging;
    }
  }

  void attach_drag(View v)
  {
    v.setOnTouchListener(new View.OnTouchListener()
    {
      @Override
      public boolean onTouch(View vv, MotionEvent ev)
      {
        switch (ev.getActionMasked())
        {
          case MotionEvent.ACTION_DOWN:
            _dragging = false;
            _touch_down_x = ev.getRawX();
            _touch_down_y = ev.getRawY();
            _window_down_x = _keyboard_lp.x;
            _window_down_y = _keyboard_lp.y;
            return true;
          case MotionEvent.ACTION_MOVE:
          {
            float dx = ev.getRawX() - _touch_down_x;
            float dy = ev.getRawY() - _touch_down_y;
            if (!_dragging && Math.abs(dx) + Math.abs(dy) >
                ViewConfiguration.get(OverlayKeyboardService.this).getScaledTouchSlop())
              _dragging = true;
            if (_dragging)
            {
              _keyboard_lp.x = clamp_coord((int)(_window_down_x + dx), 0,
                  screen_width() - _keyboard_lp.width);
              _keyboard_lp.y = clamp_coord((int)(_window_down_y + dy), 0,
                  Math.max(0, usable_height() - _keyboard_lp.height));
              _wm.updateViewLayout(_keyboard_root, _keyboard_lp);
            }
            return true;
          }
          case MotionEvent.ACTION_UP:
            if (_dragging)
              _prefs.edit().putInt(PREF_KEYBOARD_X, _keyboard_lp.x)
                .putInt(PREF_KEYBOARD_Y, _keyboard_lp.y).apply();
            return _dragging;
          default:
            return _dragging;
        }
      }
    });
  }

  /** Touch handler for the resize grip, mirroring [FloatingKeyboardManager]:
      dragging horizontally changes the width and vertically the height, both
      tracking the finger. The dragged height is converted into a rows height
      so the keyboard is re-laid out instead of being scaled out of shape. */
  void attach_resize(View v)
  {
    v.setOnTouchListener(new View.OnTouchListener()
    {
      @Override
      public boolean onTouch(View vv, MotionEvent ev)
      {
        switch (ev.getActionMasked())
        {
          case MotionEvent.ACTION_DOWN:
            _resize_down_x = ev.getRawX();
            _resize_down_y = ev.getRawY();
            _resize_down_width = _keyboard_lp.width;
            _resize_down_height = _keyboard_lp.height;
            if (_config.keyboard_rows_height_pixels > 0 && _bar_height > 0)
              _rows_height_scale =
                (_resize_down_height - _bar_height)
                / (float)_config.keyboard_rows_height_pixels;
            _resizing = false;
            return true;
          case MotionEvent.ACTION_MOVE:
          {
            float dx = ev.getRawX() - _resize_down_x;
            float dy = ev.getRawY() - _resize_down_y;
            if (!_resizing && Math.abs(dx) + Math.abs(dy) >
                ViewConfiguration.get(OverlayKeyboardService.this).getScaledTouchSlop())
              _resizing = true;
            if (_resizing)
            {
              int w = clamp_coord((int)(_resize_down_width + dx),
                  (int)(screen_width() * MIN_WIDTH_FRACTION),
                  (int)(screen_width() * MAX_WIDTH_FRACTION));
              int h = clamp_coord((int)(_resize_down_height + dy),
                  screen_height() * MIN_HEIGHT_PERCENT / 100,
                  screen_height() * MAX_HEIGHT_PERCENT / 100);
              _config.keyboard_rows_height_pixels = Math.max(1,
                  (int)((h - _bar_height) / _rows_height_scale));
              apply_size(w, h);
            }
            return true;
          }
          case MotionEvent.ACTION_UP:
          case MotionEvent.ACTION_CANCEL:
            if (_resizing)
            {
              _width_fraction = clamp_width_fraction(
                  _keyboard_lp.width / (float)screen_width());
              _height_percent = clamp_height_percent(
                  Math.round(_config.keyboard_rows_height_pixels * 395f
                    / screen_height()));
              _prefs.edit()
                .putFloat(PREF_WIDTH_FRACTION, _width_fraction)
                .putInt(PREF_HEIGHT_PERCENT, _height_percent).apply();
            }
            return true;
          default:
            return _resizing;
        }
      }
    });
  }

  /** Overlay keyboard event handler ([OverlayInputHandler.Ui]). */

  @Override
  public void hide_overlay()
  {
    collapse();
  }

  @Override
  public void set_compose_pending(boolean pending)
  {
    if (_kb_view != null)
      _kb_view.set_compose_pending(pending);
  }

  @Override
  public void cycle_text_layout(int delta)
  {
    int n = _config.layouts.size();
    _config.set_current_layout((_config.get_current_layout() + delta + n) % n);
    show_text_layout();
  }

  @Override
  public void show_text_layout()
  {
    int i = _config.get_current_layout();
    if (i >= _config.layouts.size())
      i = 0;
    KeyboardData kw = (i < _config.layouts.size()) ? _config.layouts.get(i) : null;
    if (kw == null)
      kw = KeyboardData.load(getResources(), R.xml.latn_qwerty_us);
    if (_kb_view != null)
      _kb_view.setKeyboard(LayoutModifier.modify_layout(kw));
  }

  @Override
  public void show_special_layout(int xml_id)
  {
    if (_kb_view == null)
      return;
    int i = _config.get_current_layout();
    KeyboardData main = (i < _config.layouts.size()) ? _config.layouts.get(i) : null;
    if (main == null)
      main = KeyboardData.load(getResources(), R.xml.latn_qwerty_us);
    _kb_view.setKeyboard(
        LayoutModifier.modify_numpad(KeyboardData.load(getResources(), xml_id), main));
  }

  /** Route key events to the overlay handler while the keyboard is shown. */
  void set_input_handler_active(boolean active)
  {
    if (active)
    {
      if (_config.handler != _input_handler)
        _saved_handler = _config.handler;
      _config.handler = _input_handler;
    }
    else if (_config.handler == _input_handler)
      _config.handler = _saved_handler;
  }

  /** Windows. */

  WindowManager.LayoutParams keyboard_layout_params(int w, int h)
  {
    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
        window_type(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT);
    lp.gravity = Gravity.TOP | Gravity.START;
    lp.x = clamp_coord(_prefs.getInt(PREF_KEYBOARD_X, (screen_width() - w) / 2),
        0, screen_width() - w);
    lp.y = clamp_coord(_prefs.getInt(PREF_KEYBOARD_Y, screen_height() / 3), 0,
        Math.max(0, usable_height() - h));
    return lp;
  }

  WindowManager.LayoutParams bubble_layout_params()
  {
    // Explicit size: layout_width/height of the inflated root are dropped
    // when inflating without a parent.
    int size = (int)(44 * getResources().getDisplayMetrics().density);
    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size,
        window_type(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT);
    lp.gravity = Gravity.TOP | Gravity.START;
    lp.x = clamp_coord(_prefs.getInt(PREF_BUBBLE_X, screen_width() - 150), 0,
        screen_width() - size);
    lp.y = clamp_coord(_prefs.getInt(PREF_BUBBLE_Y, screen_height() / 2), 0,
        Math.max(0, usable_height() - size));
    return lp;
  }

  /** Resize the window to [w]×[h]; the content is laid out again at the new
      size so the keyboard adapts instead of being scaled. */
  void apply_size(int w, int h)
  {
    if (_keyboard_lp.width == w && _keyboard_lp.height == h)
      return;
    _keyboard_lp.width = w;
    _keyboard_lp.height = h;
    _keyboard_lp.x = clamp_coord(_keyboard_lp.x, 0, screen_width() - w);
    _keyboard_lp.y = clamp_coord(_keyboard_lp.y, 0,
        Math.max(0, usable_height() - h));
    _content.setLayoutParams(new FrameLayout.LayoutParams(w, h));
    _wm.updateViewLayout(_keyboard_root, _keyboard_lp);
  }

  /** Rows height in pixels for the current [_height_percent], using the same
      convention as [Config] (395 units are a full keyboard). */
  int rows_height_pixels()
  {
    return Math.max(1, screen_height() * _height_percent / 395);
  }

  /** Apply the config overrides while the overlay keyboard is expanded: the
      rows height for the chosen [_height_percent], and no split layout (the
      overlay is a narrow floating window, like the floating keyboard). The
      previous values are restored in [restore_config]. */
  void override_config()
  {
    if (!_config_overridden)
    {
      _saved_rows_height = _config.keyboard_rows_height_pixels;
      _saved_split_layout = _config.split_layout;
      _config_overridden = true;
    }
    _config.keyboard_rows_height_pixels = rows_height_pixels();
    _config.split_layout = false;
  }

  /** Give the saved config values back to [Config] so the input method is
      not affected by the size and layout the overlay used. */
  void restore_config()
  {
    if (!_config_overridden)
      return;
    _config_overridden = false;
    if (_saved_rows_height > 0)
      _config.keyboard_rows_height_pixels = _saved_rows_height;
    _config.split_layout = _saved_split_layout;
  }

  float clamp_width_fraction(float v)
  {
    return Math.max(MIN_WIDTH_FRACTION, Math.min(MAX_WIDTH_FRACTION, v));
  }

  int clamp_height_percent(int v)
  {
    return Math.max(MIN_HEIGHT_PERCENT, Math.min(MAX_HEIGHT_PERCENT, v));
  }

  void remove_window(View v)
  {
    if (v != null)
      try
      {
        _wm.removeView(v);
      }
      catch (Exception e)
      {
      }
  }

  int window_type()
  {
    return (VERSION.SDK_INT >= 26)
      ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      : WindowManager.LayoutParams.TYPE_PHONE;
  }

  int clamp_coord(int v, int min, int max)
  {
    return Math.max(min, Math.min(max, v));
  }

  int screen_width()
  {
    if (VERSION.SDK_INT >= 30)
      return _wm.getCurrentWindowMetrics().getBounds().width();
    DisplayMetrics dm = new DisplayMetrics();
    _wm.getDefaultDisplay().getMetrics(dm);
    return dm.widthPixels;
  }

  int screen_height()
  {
    if (VERSION.SDK_INT >= 30)
      return _wm.getCurrentWindowMetrics().getBounds().height();
    DisplayMetrics dm = new DisplayMetrics();
    _wm.getDefaultDisplay().getMetrics(dm);
    return dm.heightPixels;
  }

  /** Height of the area where overlay windows can be placed: the display
      minus the system bars. Overlay window Y coordinates are relative to the
      bottom of the status bar, so [y=0] is just below it. */
  int usable_height()
  {
    return Math.max(0,
        screen_height() - _status_bar_height - _nav_bar_height);
  }

  /** Height of a system bar from the framework resources, [fallback] if the
      resource doesn't exist on this system. */
  int system_bar_height(String name, int fallback)
  {
    int id = getResources().getIdentifier(name, "dimen", "android");
    return (id > 0) ? getResources().getDimensionPixelSize(id) : fallback;
  }

  /** Notification. */

  void start_foreground()
  {
    NotificationManager nm =
      (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
    if (VERSION.SDK_INT >= 26)
      nm.createNotificationChannel(new NotificationChannel(NOTIF_CHANNEL_ID,
            getString(R.string.overlay_notif_channel),
            NotificationManager.IMPORTANCE_LOW));
    Notification.Builder b = (VERSION.SDK_INT >= 26)
      ? new Notification.Builder(this, NOTIF_CHANNEL_ID)
      : new Notification.Builder(this);
    Intent toggle = new Intent(this, OverlayKeyboardService.class);
    toggle.setAction(ACTION_TOGGLE);
    Intent stop = new Intent(this, OverlayKeyboardService.class);
    stop.setAction(ACTION_STOP);
    b.setSmallIcon(R.drawable.ic_overlay_bubble)
      .setContentTitle(getString(R.string.overlay_notif_title))
      .setContentText(getString(R.string.overlay_notif_text))
      .setContentIntent(PendingIntent.getActivity(this, 0,
            new Intent(this, LauncherActivity.class),
            PendingIntent.FLAG_IMMUTABLE))
      .addAction(new Notification.Action.Builder(
            android.R.drawable.ic_menu_manage,
            getString(R.string.overlay_notif_toggle),
            PendingIntent.getService(this, 1, toggle,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
            .build())
      .addAction(new Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.overlay_notif_stop),
            PendingIntent.getService(this, 2, stop,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
            .build())
      .setOngoing(true);
    if (VERSION.SDK_INT >= 34)
      startForeground(NOTIF_ID, b.build(),
          android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    else
      startForeground(NOTIF_ID, b.build());
  }
}
