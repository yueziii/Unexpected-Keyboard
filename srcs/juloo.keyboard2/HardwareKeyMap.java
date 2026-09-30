package juloo.keyboard2;

import android.view.InputDevice;
import android.view.KeyEvent;
import java.util.HashMap;
import java.util.Map;

/** Helps sending key events that look like they come from a physical
    keyboard, for apps that discard synthetic events (e.g. remote desktop
    apps such as UU Remote, ToDesk or Sunlogin, which only forward hardware
    input for modifiers and shortcuts). */
public final class HardwareKeyMap
{
  private HardwareKeyMap() {}

  /** Whether a key event must be sent through the "physical keyboard"
      channel (no soft-keyboard flags, real keyboard device id and scancode).
      Only key events that involve Ctrl, Alt or Meta need it: remote desktop
      apps forward those only when they look like hardware input. Plain keys
      (Enter, Backspace, arrows...) are already forwarded through the
      editor's text handling, and Shift is left alone to avoid toggling the
      IME on the remote machine. */
  public static boolean needs_hardware_channel(int eventCode, int metaState)
  {
    if ((metaState & (KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON
          | KeyEvent.META_META_ON)) != 0)
      return true;
    // A bare Ctrl/Alt/Meta press is itself forwarded as hardware input
    // (e.g. the Win key opening the remote start menu).
    switch (eventCode)
    {
      case KeyEvent.KEYCODE_CTRL_LEFT:
      case KeyEvent.KEYCODE_CTRL_RIGHT:
      case KeyEvent.KEYCODE_ALT_LEFT:
      case KeyEvent.KEYCODE_ALT_RIGHT:
      case KeyEvent.KEYCODE_META_LEFT:
      case KeyEvent.KEYCODE_META_RIGHT:
        return true;
      default:
        return false;
    }
  }

  /** Whether [eventCode] is one of the modifier keys. Their ACTION_UP must
      be sent through the same channel as their ACTION_DOWN, even if the
      other modifiers of a combo were released in between. */
  public static boolean is_modifier_keycode(int eventCode)
  {
    switch (eventCode)
    {
      case KeyEvent.KEYCODE_CTRL_LEFT:
      case KeyEvent.KEYCODE_CTRL_RIGHT:
      case KeyEvent.KEYCODE_ALT_LEFT:
      case KeyEvent.KEYCODE_ALT_RIGHT:
      case KeyEvent.KEYCODE_META_LEFT:
      case KeyEvent.KEYCODE_META_RIGHT:
      case KeyEvent.KEYCODE_SHIFT_LEFT:
      case KeyEvent.KEYCODE_SHIFT_RIGHT:
        return true;
      default:
        return false;
    }
  }

  /** Device id of a connected keyboard, to be used as the [deviceId] of
      hardware-like key events. Synthetic events carry
      [KeyCharacterMap.VIRTUAL_KEYBOARD] (-1), and a device id that does not
      correspond to any input device (such as 0) is discarded by some apps,
      so the id of a real input device is preferred: first an alphabetic
      keyboard, then any non-virtual keyboard device, then any keyboard
      device. The result is cached when a non-virtual device is found;
      devices might connect later, so a worse match is retried. */
  public static int keyboard_device_id()
  {
    if (_cached_device_id != 0)
      return _cached_device_id;
    int any_device = 0;
    int non_virtual = 0;
    for (int id : InputDevice.getDeviceIds())
    {
      InputDevice dev = InputDevice.getDevice(id);
      if (dev == null
          || (dev.getSources() & InputDevice.SOURCE_KEYBOARD) == 0)
        continue;
      if (!dev.isVirtual())
      {
        if (dev.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC)
        {
          _cached_device_id = id;
          return id;
        }
        if (non_virtual == 0)
          non_virtual = id;
      }
      else if (any_device == 0)
        any_device = id;
    }
    if (non_virtual != 0)
    {
      _cached_device_id = non_virtual;
      return non_virtual;
    }
    return any_device;
  }

  /** Linux HID scancodes of the keycodes a remote session needs, as
      {keycode, scancode} pairs. Synthetic key events carry a zero scancode,
      which some apps filter out. */
  static final int[] SCANCODES = {
    // Digits row
    KeyEvent.KEYCODE_0, 11, KeyEvent.KEYCODE_1, 2, KeyEvent.KEYCODE_2, 3,
    KeyEvent.KEYCODE_3, 4, KeyEvent.KEYCODE_4, 5, KeyEvent.KEYCODE_5, 6,
    KeyEvent.KEYCODE_6, 7, KeyEvent.KEYCODE_7, 8, KeyEvent.KEYCODE_8, 9,
    KeyEvent.KEYCODE_9, 10,
    // Letters
    KeyEvent.KEYCODE_A, 30, KeyEvent.KEYCODE_B, 48, KeyEvent.KEYCODE_C, 46,
    KeyEvent.KEYCODE_D, 32, KeyEvent.KEYCODE_E, 18, KeyEvent.KEYCODE_F, 33,
    KeyEvent.KEYCODE_G, 34, KeyEvent.KEYCODE_H, 35, KeyEvent.KEYCODE_I, 23,
    KeyEvent.KEYCODE_J, 36, KeyEvent.KEYCODE_K, 37, KeyEvent.KEYCODE_L, 38,
    KeyEvent.KEYCODE_M, 39, KeyEvent.KEYCODE_N, 49, KeyEvent.KEYCODE_O, 24,
    KeyEvent.KEYCODE_P, 25, KeyEvent.KEYCODE_Q, 16, KeyEvent.KEYCODE_R, 19,
    KeyEvent.KEYCODE_S, 31, KeyEvent.KEYCODE_T, 20, KeyEvent.KEYCODE_U, 22,
    KeyEvent.KEYCODE_V, 47, KeyEvent.KEYCODE_W, 17, KeyEvent.KEYCODE_X, 45,
    KeyEvent.KEYCODE_Y, 21, KeyEvent.KEYCODE_Z, 44,
    // Modifiers
    KeyEvent.KEYCODE_CTRL_LEFT, 29, KeyEvent.KEYCODE_CTRL_RIGHT, 97,
    KeyEvent.KEYCODE_SHIFT_LEFT, 42, KeyEvent.KEYCODE_SHIFT_RIGHT, 54,
    KeyEvent.KEYCODE_ALT_LEFT, 56, KeyEvent.KEYCODE_ALT_RIGHT, 100,
    KeyEvent.KEYCODE_META_LEFT, 125, KeyEvent.KEYCODE_META_RIGHT, 126,
    KeyEvent.KEYCODE_CAPS_LOCK, 58, KeyEvent.KEYCODE_NUM_LOCK, 69,
    KeyEvent.KEYCODE_SCROLL_LOCK, 70,
    // Editing and navigation
    KeyEvent.KEYCODE_TAB, 15, KeyEvent.KEYCODE_ENTER, 28,
    KeyEvent.KEYCODE_DEL, 14, KeyEvent.KEYCODE_FORWARD_DEL, 111,
    KeyEvent.KEYCODE_ESCAPE, 1, KeyEvent.KEYCODE_SPACE, 57,
    KeyEvent.KEYCODE_DPAD_UP, 103, KeyEvent.KEYCODE_DPAD_DOWN, 108,
    KeyEvent.KEYCODE_DPAD_LEFT, 105, KeyEvent.KEYCODE_DPAD_RIGHT, 106,
    KeyEvent.KEYCODE_PAGE_UP, 104, KeyEvent.KEYCODE_PAGE_DOWN, 109,
    KeyEvent.KEYCODE_MOVE_HOME, 102, KeyEvent.KEYCODE_MOVE_END, 107,
    KeyEvent.KEYCODE_INSERT, 110,
    // Punctuation
    KeyEvent.KEYCODE_COMMA, 51, KeyEvent.KEYCODE_PERIOD, 52,
    KeyEvent.KEYCODE_GRAVE, 41, KeyEvent.KEYCODE_MINUS, 12,
    KeyEvent.KEYCODE_EQUALS, 13, KeyEvent.KEYCODE_LEFT_BRACKET, 26,
    KeyEvent.KEYCODE_RIGHT_BRACKET, 27, KeyEvent.KEYCODE_BACKSLASH, 43,
    KeyEvent.KEYCODE_SEMICOLON, 39, KeyEvent.KEYCODE_APOSTROPHE, 40,
    KeyEvent.KEYCODE_SLASH, 53,
    // Function keys
    KeyEvent.KEYCODE_F1, 59, KeyEvent.KEYCODE_F2, 60, KeyEvent.KEYCODE_F3, 61,
    KeyEvent.KEYCODE_F4, 62, KeyEvent.KEYCODE_F5, 63, KeyEvent.KEYCODE_F6, 64,
    KeyEvent.KEYCODE_F7, 65, KeyEvent.KEYCODE_F8, 66, KeyEvent.KEYCODE_F9, 67,
    KeyEvent.KEYCODE_F10, 68, KeyEvent.KEYCODE_F11, 87,
    KeyEvent.KEYCODE_F12, 88,
    // Numpad
    KeyEvent.KEYCODE_NUMPAD_0, 82, KeyEvent.KEYCODE_NUMPAD_1, 79,
    KeyEvent.KEYCODE_NUMPAD_2, 80, KeyEvent.KEYCODE_NUMPAD_3, 81,
    KeyEvent.KEYCODE_NUMPAD_4, 75, KeyEvent.KEYCODE_NUMPAD_5, 76,
    KeyEvent.KEYCODE_NUMPAD_6, 77, KeyEvent.KEYCODE_NUMPAD_7, 71,
    KeyEvent.KEYCODE_NUMPAD_8, 72, KeyEvent.KEYCODE_NUMPAD_9, 73,
    KeyEvent.KEYCODE_NUMPAD_DIVIDE, 98, KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 55,
    KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 74, KeyEvent.KEYCODE_NUMPAD_ADD, 78,
    KeyEvent.KEYCODE_NUMPAD_DOT, 83, KeyEvent.KEYCODE_NUMPAD_ENTER, 96,
  };

  static final Map<Integer, Integer> _scancodes = new HashMap<Integer, Integer>();
  static
  {
    for (int i = 0; i < SCANCODES.length; i += 2)
      _scancodes.put(SCANCODES[i], SCANCODES[i + 1]);
  }

  static int _cached_device_id = 0;

  /** Linux HID scancode for a keycode, 0 when unknown. */
  public static int scancode_for(int keycode)
  {
    Integer sc = _scancodes.get(keycode);
    return sc == null ? 0 : sc;
  }
}
