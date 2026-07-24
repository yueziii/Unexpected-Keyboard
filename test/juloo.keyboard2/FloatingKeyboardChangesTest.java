package juloo.keyboard2;

import juloo.keyboard2.KeyValue;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for the floating keyboard changes:
 * 1. TOGGLE_FLOATING key has FLAG_SMALLER_FONT (for padding fix)
 * 2. TOGGLE_FLOATING key has FLAG_KEY_FONT (icon key)
 * 3. toggle_floating can be parsed from key name
 * 4. FLAG_SMALLER_FONT is set on icon sublabels (affects padding)
 */
public class FloatingKeyboardChangesTest
{
  public FloatingKeyboardChangesTest() {}

  @Test
  public void toggle_floating_key_has_smaller_font_flag()
  {
    // The TOGGLE_FLOATING key should have FLAG_SMALLER_FONT so the
    // sublabel padding fix (2x padding for smaller-font sublabels) applies.
    KeyValue kv = KeyValue.getKeyByName("toggle_floating");
    assertNotNull("toggle_floating key should exist", kv);
    assertTrue("TOGGLE_FLOATING should have FLAG_SMALLER_FONT",
        kv.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT));
  }

  @Test
  public void toggle_floating_key_has_key_font_flag()
  {
    // The TOGGLE_FLOATING key should have FLAG_KEY_FONT (it's an icon key)
    KeyValue kv = KeyValue.getKeyByName("toggle_floating");
    assertNotNull(kv);
    assertTrue("TOGGLE_FLOATING should have FLAG_KEY_FONT",
        kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT));
  }

  @Test
  public void toggle_floating_is_special_key()
  {
    // TOGGLE_FLOATING should be a special key (not a regular character)
    KeyValue kv = KeyValue.getKeyByName("toggle_floating");
    assertNotNull(kv);
    assertTrue("TOGGLE_FLOATING should have FLAG_SPECIAL",
        kv.hasFlagsAny(KeyValue.FLAG_SPECIAL));
  }

  @Test
  public void toggle_floating_event_type()
  {
    // TOGGLE_FLOATING should produce the TOGGLE_FLOATING event
    KeyValue kv = KeyValue.getKeyByName("toggle_floating");
    assertNotNull(kv);
    assertEquals("TOGGLE_FLOATING should be an Event kind",
        KeyValue.Kind.Event, kv.getKind());
    assertEquals("TOGGLE_FLOATING should have TOGGLE_FLOATING event",
        KeyValue.Event.TOGGLE_FLOATING, kv.getEvent());
  }

  @Test
  public void smaller_font_flag_affects_hasFlagsAny()
  {
    // Verify that FLAG_SMALLER_FONT can be detected via hasFlagsAny
    // This is the mechanism used in Keyboard2View.drawSubLabel for padding
    KeyValue kv = KeyValue.getKeyByName("toggle_floating");
    assertNotNull(kv);
    // This is the exact check used in Keyboard2View:
    // kv.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT)
    boolean hasSmallerFont = kv.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT);
    assertTrue("toggle_floating should trigger the smaller-font padding path", hasSmallerFont);
  }

  @Test
  public void other_icon_keys_also_have_smaller_font()
  {
    // Other icon keys like change_method, config, switch_emoji should also
    // have FLAG_SMALLER_FONT, ensuring consistent padding behavior
    String[] iconKeys = {"change_method", "config", "switch_emoji",
        "switch_numeric", "switch_forward", "switch_backward"};
    for (String keyName : iconKeys)
    {
      KeyValue kv = KeyValue.getKeyByName(keyName);
      assertNotNull(keyName + " should exist", kv);
      assertTrue(keyName + " should have FLAG_SMALLER_FONT",
          kv.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT));
    }
  }
}
