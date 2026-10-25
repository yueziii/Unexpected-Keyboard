package juloo.keyboard2;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import juloo.cdict.Cdict;
import juloo.keyboard2.dict.Dictionaries;
import juloo.keyboard2.prefs.CustomExtraKeysPreference;
import juloo.keyboard2.prefs.ExtraKeysPreference;
import juloo.keyboard2.prefs.LayoutsPreference;

public final class Config
{
  /**
   * Width of the Android phones is around 300-600dp in portrait, 600-1400dp in landscape,
   * depending on the user's size settings.
   *
   * 600 dp seems a reasonable midpoint to determine whether the current orientation of the device is "wide"
   * (landsacpe, tablet, unfolded foldable etc.) or not, to switch to a different layout.
   */
  public static final int WIDE_DEVICE_THRESHOLD = 600;

  private final SharedPreferences _prefs;

  // From resources
  public final float marginTop;
  public final float keyPadding;

  public final float labelTextSize;
  public final float sublabelTextSize;

  // From preferences
  /** [null] represent the [system] layout. */
  public List<KeyboardData> layouts;
  public boolean show_numpad = false;
  // From the 'numpad_layout' option, also apply to the numeric pane.
  public boolean inverse_numpad = false;
  public boolean add_number_row;
  public boolean number_row_symbols;
  public float swipe_dist_px;
  public float slide_step_px;
  public boolean suggestions_enabled;
  // Let the system handle vibration when false.
  public boolean vibrate_custom;
  // Control the vibration if [vibrate_custom] is true.
  public long vibrate_duration;
  public long longPressTimeout;
  public long longPressInterval;
  public boolean keyrepeat_enabled;
  public float margin_bottom;
  public int keyboard_rows_height_pixels;
  public int screenHeightPixels;
  public int screenWidthPixels;
  public float horizontal_margin;
  public float key_vertical_margin;
  public float key_horizontal_margin;
  public int labelBrightness; // 0 - 255
  public int keyboardOpacity; // 0 - 255
  public float customBorderRadius; // 0 - 1
  public float customBorderLineWidth; // dp
  public int keyOpacity; // 0 - 255
  public int keyActivatedOpacity; // 0 - 255
  public boolean double_tap_lock_shift;
  public float characterSize; // Ratio
  public int theme; // Values are R.style.*
  public boolean autocapitalisation;
  public KeyValue change_method_key_replacement;
  public NumberLayout selected_number_layout;
  public boolean borderConfig;
  public int circle_sensitivity;
  public boolean clipboard_history_enabled;
  public int clipboard_history_duration;
  public boolean space_bar_auto_complete;
  public boolean physical_keyboard_hide;
  /** Compatibility mode for remote desktop apps (e.g. UU Remote, ToDesk,
      Sunlogin). When enabled, key events that involve Ctrl, Alt or Meta are
      sent without FLAG_SOFT_KEYBOARD and with the device id of a real
      keyboard plus a scancode, so they are forwarded like physical keyboard
      input. Plain keys (Enter, Backspace, ...) keep the soft-keyboard
      channel, as these apps already forward them through their editor's
      text handling. Defaults to off because it changes behavior
      system-wide. */
  public boolean keyevent_compat = false;

  // Dynamically set
  /** Configuration options implied by the connected editor. */
  public EditorConfig editor_config;
  public boolean shouldOfferVoiceTyping;
  public ExtraKeys extra_keys_subtype;
  public Map<KeyValue, KeyboardData.PreferredPos> extra_keys_param;
  public Map<KeyValue, KeyboardData.PreferredPos> extra_keys_custom;
  public DeviceLocales device_locales = null;
  public Cdict current_dictionary = null; // Might be 'null'.
  public Cdict emoji_dictionary = null; // Might be 'null'.
  // Pinyin input mode
  public boolean pinyin_enabled = false;
  /** 0 = full pinyin (全拼), 1 = Microsoft double pinyin (微软双拼). */
  public int pinyin_scheme = 0;
  /** When true, the user has manually toggled pinyin mode via the
      switch_lang key. [refresh_current_dictionary] must not override
      [pinyin_enabled] in that case; only a real subtype change resets it. */
  public boolean pinyin_user_override = false;
  /** 简拼: surface candidates whose pinyin initials spell the typed
      letters (nh -> 你好). */
  public boolean pinyin_abbrev = true;
  /** 联想: predict the next word after a commit. */
  public boolean pinyin_prediction = true;
  /** Enabled 模糊音 groups (rule names from PinyinData.FUZZY_RULES),
      empty when disabled. */
  public java.util.Set<String> pinyin_fuzzy = null;
  public IKeyEventHandler handler;
  public boolean orientation_landscape = false;
  public boolean foldable_unfolded = false;
  public boolean wide_screen = false;
  /** Index in 'layouts' of the currently used layout. See
      [get_current_layout()] and [set_current_layout()]. */
  int current_layout_narrow;
  int current_layout_wide;
  /** Whether to automatically split the layout. */
  public boolean split_layout;
  /** Whether the floating keyboard FEATURE is enabled in settings. When
      true, the user can toggle floating on/off via the fn (TOGGLE_FLOATING)
      key. This does NOT directly make the keyboard floating — it just enables
      the toggle capability. */
  public boolean floating_keyboard = false;
  /** Whether the keyboard is CURRENTLY in floating mode. This is a runtime
      state toggled by the TOGGLE_FLOATING key, initialized to false (docked)
      on startup. Only meaningful when [floating_keyboard] (the feature) is
      enabled. */
  public boolean floating_keyboard_active = false;
  /** Width of the floating keyboard, as a percentage of screen width (40-100). */
  public int floating_keyboard_width = 80;
  /** Saved X position of the floating keyboard, in pixels. [Float.MIN_VALUE] means unset. */
  public float floating_pos_x = Float.MIN_VALUE;
  /** Saved Y position of the floating keyboard, in pixels from the top. [Float.MIN_VALUE] means unset. */
  public float floating_pos_y = Float.MIN_VALUE;
  /** Height of the floating keyboard, as a percentage of screen height (10-80). */
  public int floating_keyboard_height = 35;
  /** Whether the floating size/position fields are for the unfolded foldable state. */
  private boolean _floating_for_unfolded = false;

  private Config(SharedPreferences prefs, Resources res,
      Boolean foldableUnfolded, Dictionaries dicts)
  {
    _prefs = prefs;
    editor_config = new EditorConfig();
    // static values
    marginTop = res.getDimension(R.dimen.margin_top);
    keyPadding = res.getDimension(R.dimen.key_padding);
    labelTextSize = 0.33f;
    sublabelTextSize = 0.22f;
    // from prefs
    refresh(res, foldableUnfolded, dicts);
    // initialized later
    shouldOfferVoiceTyping = false;
    extra_keys_subtype = null;
  }

  /*
   ** Reload prefs
   */
  public void refresh(Resources res, Boolean foldableUnfolded, Dictionaries dicts)
  {
    DisplayMetrics dm = res.getDisplayMetrics();
    orientation_landscape = res.getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    foldable_unfolded = foldableUnfolded;

    float characterSizeScale = 1.f;
    String show_numpad_s = _prefs.getString("show_numpad", "never");
    show_numpad = "always".equals(show_numpad_s);
    int keyboardHeightPercent;
    if (orientation_landscape)
    {
      if ("landscape".equals(show_numpad_s))
        show_numpad = true;
      keyboardHeightPercent = _prefs.getInt(foldable_unfolded ? "keyboard_height_landscape_unfolded" : "keyboard_height_landscape", 50);
      characterSizeScale = 1.25f;
    }
    else
    {
      keyboardHeightPercent = _prefs.getInt(foldable_unfolded ? "keyboard_height_unfolded" : "keyboard_height", 35);
    }
    layouts = LayoutsPreference.load_from_preferences(res, _prefs);
    inverse_numpad = _prefs.getString("numpad_layout", "default").equals("low_first");
    String number_row = _prefs.getString("number_row", "no_number_row");
    add_number_row = !number_row.equals("no_number_row");
    number_row_symbols = number_row.equals("symbols");
    suggestions_enabled = _prefs.getBoolean("suggestions", true);
    // 0 = full pinyin (全拼), 1 = Microsoft double pinyin (微软双拼)
    pinyin_scheme = _prefs.getString("pinyin_scheme", "full").equals("double")
      ? 1 : 0;
    pinyin_abbrev = _prefs.getBoolean("pinyin_abbrev", true);
    pinyin_prediction = _prefs.getBoolean("pinyin_prediction", true);
    {
      java.util.Set<String> rules = _prefs.getStringSet("pinyin_fuzzy", null);
      pinyin_fuzzy = (rules == null || rules.isEmpty())
        ? null : java.util.Collections.unmodifiableSet(
            new java.util.HashSet<String>(rules));
    }
    // The baseline for the swipe distance correspond to approximately the
    // width of a key in portrait mode, as most layouts have 10 columns.
    // Multipled by the DPI ratio because most swipes are made in the diagonals.
    // The option value uses an unnamed scale where the baseline is around 25.
    float dpi_ratio = Math.max(dm.xdpi, dm.ydpi) / Math.min(dm.xdpi, dm.ydpi);
    float swipe_scaling = Math.min(dm.widthPixels, dm.heightPixels) / 10.f * dpi_ratio;
    float swipe_dist_value = Float.valueOf(_prefs.getString("swipe_dist", "15"));
    swipe_dist_px = swipe_dist_value / 25.f * swipe_scaling;
    float slider_sensitivity = Float.valueOf(_prefs.getString("slider_sensitivity", "30")) / 100.f;
    slide_step_px = slider_sensitivity * swipe_scaling;
    vibrate_custom = _prefs.getBoolean("vibrate_custom", false);
    vibrate_duration = _prefs.getInt("vibrate_duration", 20);
    longPressTimeout = _prefs.getInt("longpress_timeout", 600);
    longPressInterval = _prefs.getInt("longpress_interval", 65);
    keyrepeat_enabled = _prefs.getBoolean("keyrepeat_enabled", true);
    margin_bottom = get_dip_pref_oriented(dm, "margin_bottom", 7, 3);
    key_vertical_margin = get_dip_pref(dm, "key_vertical_margin", 1.5f) / 100;
    key_horizontal_margin = get_dip_pref(dm, "key_horizontal_margin", 2) / 100;
    // Label brightness is used as the alpha channel
    labelBrightness = _prefs.getInt("label_brightness", 100) * 255 / 100;
    // Keyboard opacity
    keyboardOpacity = _prefs.getInt("keyboard_opacity", 100) * 255 / 100;
    keyOpacity = _prefs.getInt("key_opacity", 100) * 255 / 100;
    keyActivatedOpacity = _prefs.getInt("key_activated_opacity", 100) * 255 / 100;
    // keyboard border settings
    borderConfig = _prefs.getBoolean("border_config", false);
    customBorderRadius = _prefs.getInt("custom_border_radius", 0) / 100.f;
    customBorderLineWidth = get_dip_pref(dm, "custom_border_line_width", 0);
    screenHeightPixels = dm.heightPixels;
    screenWidthPixels = dm.widthPixels;
    // Rows height is proportional to the screen height, meaning it doesn't
    // change for layouts with more or less rows. 3.95 is the usual height of
    // a layout in KeyboardData unit. The keyboard will be higher if the layout
    // has more rows and smaller if it has less because rows stay the same
    // height.
    keyboard_rows_height_pixels = screenHeightPixels * keyboardHeightPercent / 395;
    horizontal_margin =
      get_dip_pref_oriented(dm, "horizontal_margin", 3, 28);
    double_tap_lock_shift = _prefs.getBoolean("lock_double_tap", false);
    characterSize =
      _prefs.getFloat("character_size", 1.15f)
      * characterSizeScale;
    theme = getThemeId(res, _prefs.getString("theme", ""));
    autocapitalisation = _prefs.getBoolean("autocapitalisation", true);
    change_method_key_replacement = get_change_method_key_replacement(_prefs);
    extra_keys_param = ExtraKeysPreference.get_extra_keys(_prefs);
    extra_keys_custom = CustomExtraKeysPreference.get(_prefs);
    selected_number_layout = NumberLayout.of_string(_prefs.getString("number_entry_layout", "pin"));
    current_layout_narrow = _prefs.getInt("current_layout_portrait", 0);
    current_layout_wide = _prefs.getInt("current_layout_landscape", 0);
    circle_sensitivity = Integer.valueOf(_prefs.getString("circle_sensitivity", "2"));
    clipboard_history_enabled = _prefs.getBoolean("clipboard_history_enabled", false);
    clipboard_history_duration = Integer.parseInt(_prefs.getString("clipboard_history_duration", "5"));
    space_bar_auto_complete = _prefs.getBoolean("space_bar_auto_complete", false);
    physical_keyboard_hide = _prefs.getString("physical_keyboard_behavior", "hide").equals("hide");
    keyevent_compat = _prefs.getBoolean("keyevent_compat", false);
    float screen_width_dp = dm.widthPixels / dm.density;
    wide_screen = screen_width_dp >= WIDE_DEVICE_THRESHOLD;
    floating_keyboard = _prefs.getBoolean("floating_keyboard", false);
    // Read fold-state-specific floating size/position. Folded and unfolded
    // states have separate values so adjusting in one state doesn't make the
    // keyboard huge when switching to the other.
    _floating_for_unfolded = foldable_unfolded;
    String fs = foldable_unfolded ? "_unfolded" : "";
    floating_keyboard_width = _prefs.getInt("floating_keyboard_width" + fs, 80);
    floating_pos_x = _prefs.getFloat("floating_pos_x" + fs, Float.MIN_VALUE);
    floating_pos_y = _prefs.getFloat("floating_pos_y" + fs, Float.MIN_VALUE);
    floating_keyboard_height = _prefs.getInt("floating_keyboard_height" + fs, 35);
    split_layout = get_split_layout();
    // In floating mode, override keyboard height and disable split layout
    // so the keyboard looks the same in portrait and landscape.
    if (floating_keyboard_active)
    {
      keyboard_rows_height_pixels = screenHeightPixels * floating_keyboard_height / 395;
      split_layout = false;
      // Disable the landscape-only character size scaling so the floating
      // keyboard has the same font size in portrait and landscape.
      characterSize = _prefs.getFloat("character_size", 1.15f);
      // Disable the landscape numpad so the floating keyboard layout is
      // consistent across orientations.
      show_numpad = "always".equals(show_numpad_s);
      // Use the portrait horizontal margin (3dp) instead of the landscape
      // margin (28dp) so the floating keyboard has the same side spacing in
      // portrait and landscape.
      horizontal_margin = get_dip_pref(dm, "horizontal_margin_portrait", 3);
      // Ensure the keyboard's bottom margin is large enough to accommodate
      // the floating handle bar (22dp) so the overlay handle sits within the
      // keyboard's own bottom margin area instead of adding extra height.
      if (margin_bottom < 22f)
        margin_bottom = 22f;
    }
  }

  public int get_current_layout()
  {
    return (wide_screen)
            ? current_layout_wide : current_layout_narrow;
  }

  public void set_current_layout(int l)
  {
    if (wide_screen)
      current_layout_wide = l;
    else
      current_layout_narrow = l;

    SharedPreferences.Editor e = _prefs.edit();
    e.putInt("current_layout_portrait", current_layout_narrow);
    e.putInt("current_layout_landscape", current_layout_wide);
    e.apply();
  }

  /** Persist the floating keyboard position so it's restored next time. */
  public void save_floating_pos(float x, float y)
  {
    floating_pos_x = x;
    floating_pos_y = y;
    String fs = _floating_for_unfolded ? "_unfolded" : "";
    SharedPreferences.Editor e = _prefs.edit();
    e.putFloat("floating_pos_x" + fs, x);
    e.putFloat("floating_pos_y" + fs, y);
    e.apply();
  }

  /** Persist the floating keyboard size so it's restored next time. */
  public void save_floating_size(int widthPct, int heightPct)
  {
    floating_keyboard_width = widthPct;
    floating_keyboard_height = heightPct;
    String fs = _floating_for_unfolded ? "_unfolded" : "";
    SharedPreferences.Editor e = _prefs.edit();
    e.putInt("floating_keyboard_width" + fs, widthPct);
    e.putInt("floating_keyboard_height" + fs, heightPct);
    e.apply();
  }

  public void set_clipboard_history_enabled(boolean e)
  {
    clipboard_history_enabled = e;
    _prefs.edit().putBoolean("clipboard_history_enabled", e).apply();
  }

  private float get_dip_pref(DisplayMetrics dm, String pref_name, float def)
  {
    float value;
    try { value = _prefs.getInt(pref_name, -1); }
    catch (Exception e) { value = _prefs.getFloat(pref_name, -1f); }
    if (value < 0f)
      value = def;
    return (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, dm));
  }

  /** [get_dip_pref] depending on orientation. */
  float get_dip_pref_oriented(DisplayMetrics dm, String pref_base_name, float def_port, float def_land)
  {
    final String suffix;
    if (foldable_unfolded) {
      suffix = orientation_landscape ? "_landscape_unfolded" : "_portrait_unfolded";
    } else {
      suffix = orientation_landscape ? "_landscape" : "_portrait";
    }

    float def = orientation_landscape ? def_land : def_port;
    return get_dip_pref(dm, pref_base_name + suffix, def);
  }

  private int getThemeId(Resources res, String theme_name)
  {
    int night_mode = res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
    switch (theme_name)
    {
      case "light": return R.style.Light;
      case "black": return R.style.Black;
      case "altblack": return R.style.AltBlack;
      case "dark": return R.style.Dark;
      case "white": return R.style.White;
      case "epaper": return R.style.ePaper;
      case "desert": return R.style.Desert;
      case "jungle": return R.style.Jungle;
      case "monetlight": return R.style.MonetLight;
      case "monetdark": return R.style.MonetDark;
      case "monet":
        if ((night_mode & Configuration.UI_MODE_NIGHT_NO) != 0)
          return R.style.MonetLight;
        return R.style.MonetDark;
      case "rosepine": return R.style.RosePine;
      case "everforestlight": return R.style.EverforestLight;
      case "cobalt": return R.style.Cobalt;
      case "pine": return R.style.Pine;
      case "epaperblack": return R.style.ePaperBlack;
      default:
      case "system":
        if ((night_mode & Configuration.UI_MODE_NIGHT_NO) != 0)
          return R.style.Light;
        return R.style.Dark;
    }
  }

  private static KeyValue get_change_method_key_replacement(SharedPreferences prefs)
  {
    switch (prefs.getString("change_method_key_replacement", "prev"))
    {
      case "prev": return KeyValue.CHANGE_METHOD_PREV;
      case "next": return KeyValue.CHANGE_METHOD_NEXT;
      default:
      case "picker": return KeyValue.CHANGE_METHOD;
    }
  }

  final boolean get_split_layout()
  {
    switch (_prefs.getString("split_layout", "wide"))
    {
      case "wide": return wide_screen;
      case "landscape": return orientation_landscape;
      default: return false;
    }
  }

  private static Config _globalConfig = null;

  public static void initGlobalConfig(SharedPreferences prefs, Resources res,
      Boolean foldableUnfolded, Dictionaries dicts)
  {
    migrate(prefs);
    _globalConfig = new Config(prefs, res, foldableUnfolded, dicts);
    LayoutModifier.init(_globalConfig, res);
  }

  public static Config globalConfig()
  {
    return _globalConfig;
  }

  public static SharedPreferences globalPrefs()
  {
    return _globalConfig._prefs;
  }

  public static interface IKeyEventHandler
  {
    public void key_down(KeyValue value, boolean is_swipe);
    public void key_up(KeyValue value, Pointers.Modifiers mods);
    public void mods_changed(Pointers.Modifiers mods);
    public void suggestion_entered(String text);
  }

  /** Config migrations. */

  private static int CONFIG_VERSION = 4;

  public static void migrate(SharedPreferences prefs)
  {
    int saved_version = prefs.getInt("version", 0);
    Logs.debug_config_migration(saved_version, CONFIG_VERSION);
    if (saved_version == CONFIG_VERSION)
      return;
    SharedPreferences.Editor e = prefs.edit();
    e.putInt("version", CONFIG_VERSION);
    // Migrations might run on an empty [prefs] for new installs, in this case
    // they set the default values of complex options.
    switch (saved_version)
    {
      case 0:
        // Primary, secondary and custom layout options are merged into the new
        // Layouts option. This also sets the default value.
        List<LayoutsPreference.Layout> l = new ArrayList<LayoutsPreference.Layout>();
        l.add(migrate_layout(prefs.getString("layout", "system")));
        String snd_layout = prefs.getString("second_layout", "none");
        if (snd_layout != null && !snd_layout.equals("none"))
          l.add(migrate_layout(snd_layout));
        String custom_layout = prefs.getString("custom_layout", "");
        if (custom_layout != null && !custom_layout.equals(""))
          l.add(LayoutsPreference.CustomLayout.parse(custom_layout));
        LayoutsPreference.save_to_preferences(e, l);
        // Fallthrough
      case 1:
        boolean add_number_row = prefs.getBoolean("number_row", false);
        e.putString("number_row", add_number_row ? "no_symbols" : "no_number_row");
        // Fallthrough
      case 2:
        if (!prefs.contains("number_entry_layout")) {
          e.putString("number_entry_layout", prefs.getBoolean("pin_entry_enabled", true) ? "pin" : "number");
        }
        // Fallthrough
      case 3:
        e.putString("change_method_key_replacement",
            prefs.getBoolean("switch_input_immediate", false) ? "prev" : "picker");
        // Fallthrough
      case 4:
      default: break;
    }
    e.apply();
  }

  private static LayoutsPreference.Layout migrate_layout(String name)
  {
    if (name == null || name.equals("system"))
      return new LayoutsPreference.SystemLayout();
    return new LayoutsPreference.NamedLayout(name);
  }
}
