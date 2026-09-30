package juloo.keyboard2;

import android.view.KeyEvent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class HardwareKeyMapTest
{
  /** Ctrl+C is a shortcut that remote desktop apps only forward when the
      events look like physical keyboard input. */
  @Test
  public void ctrl_combo_needs_hardware_channel()
  {
    assertTrue(HardwareKeyMap.needs_hardware_channel(KeyEvent.KEYCODE_C,
          KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON));
  }

  @Test
  public void alt_and_meta_combos_need_hardware_channel()
  {
    assertTrue(HardwareKeyMap.needs_hardware_channel(KeyEvent.KEYCODE_DPAD_LEFT,
          KeyEvent.META_ALT_ON));
    assertTrue(HardwareKeyMap.needs_hardware_channel(KeyEvent.KEYCODE_DPAD_LEFT,
          KeyEvent.META_META_ON));
  }

  @Test
  public void bare_ctrl_alt_meta_need_hardware_channel()
  {
    assertTrue(HardwareKeyMap.needs_hardware_channel(KeyEvent.KEYCODE_CTRL_LEFT,
          KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_ON));
    assertTrue(HardwareKeyMap.needs_hardware_channel(KeyEvent.KEYCODE_ALT_LEFT,
          KeyEvent.META_ALT_LEFT_ON | KeyEvent.META_ALT_ON));
    assertTrue(HardwareKeyMap.needs_hardware_channel(KeyEvent.KEYCODE_META_LEFT,
          KeyEvent.META_META_LEFT_ON | KeyEvent.META_META_ON));
  }

  /** Bare Shift is not sent as hardware input: forwarding it would toggle
      the IME on the remote machine (e.g. Chinese/English on Windows). */
  @Test
  public void shift_stays_on_soft_channel()
  {
    assertFalse(HardwareKeyMap.needs_hardware_channel(KeyEvent.KEYCODE_SHIFT_LEFT,
          KeyEvent.META_SHIFT_LEFT_ON | KeyEvent.META_SHIFT_ON));
    assertFalse(HardwareKeyMap.needs_hardware_channel(KeyEvent.KEYCODE_ENTER,
          KeyEvent.META_SHIFT_ON));
  }

  /** Enter, Backspace, arrows... are already forwarded by remote desktop
      apps through their editor's text handling and must not change. */
  @Test
  public void plain_keys_stay_on_soft_channel()
  {
    assertFalse(HardwareKeyMap.needs_hardware_channel(KeyEvent.KEYCODE_ENTER, 0));
    assertFalse(HardwareKeyMap.needs_hardware_channel(KeyEvent.KEYCODE_DEL, 0));
    assertFalse(HardwareKeyMap.needs_hardware_channel(KeyEvent.KEYCODE_DPAD_UP, 0));
    assertFalse(HardwareKeyMap.needs_hardware_channel(KeyEvent.KEYCODE_TAB, 0));
  }

  @Test
  public void all_modifier_keycodes_recognized()
  {
    int[] mods = {
      KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
      KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
      KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
      KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
    };
    for (int code : mods)
      assertTrue(HardwareKeyMap.is_modifier_keycode(code));
    assertFalse(HardwareKeyMap.is_modifier_keycode(KeyEvent.KEYCODE_ENTER));
  }

  @Test
  public void scancodes_match_linux_hid()
  {
    assertEquals(30, HardwareKeyMap.scancode_for(KeyEvent.KEYCODE_A));
    assertEquals(46, HardwareKeyMap.scancode_for(KeyEvent.KEYCODE_C));
    assertEquals(44, HardwareKeyMap.scancode_for(KeyEvent.KEYCODE_Z));
    assertEquals(28, HardwareKeyMap.scancode_for(KeyEvent.KEYCODE_ENTER));
    assertEquals(14, HardwareKeyMap.scancode_for(KeyEvent.KEYCODE_DEL));
    assertEquals(111, HardwareKeyMap.scancode_for(KeyEvent.KEYCODE_FORWARD_DEL));
    assertEquals(29, HardwareKeyMap.scancode_for(KeyEvent.KEYCODE_CTRL_LEFT));
    assertEquals(42, HardwareKeyMap.scancode_for(KeyEvent.KEYCODE_SHIFT_LEFT));
    assertEquals(56, HardwareKeyMap.scancode_for(KeyEvent.KEYCODE_ALT_LEFT));
    assertEquals(125, HardwareKeyMap.scancode_for(KeyEvent.KEYCODE_META_LEFT));
    assertEquals(57, HardwareKeyMap.scancode_for(KeyEvent.KEYCODE_SPACE));
    assertEquals(1, HardwareKeyMap.scancode_for(KeyEvent.KEYCODE_ESCAPE));
    assertEquals(103, HardwareKeyMap.scancode_for(KeyEvent.KEYCODE_DPAD_UP));
    assertEquals(87, HardwareKeyMap.scancode_for(KeyEvent.KEYCODE_F11));
    assertEquals(6, HardwareKeyMap.scancode_for(KeyEvent.KEYCODE_5));
    assertEquals(0, HardwareKeyMap.scancode_for(KeyEvent.KEYCODE_POUND));
  }
}
