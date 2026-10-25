package juloo.keyboard2;

import juloo.keyboard2.pinyin.PinyinData;
import juloo.keyboard2.pinyin.PinyinInput;
import juloo.keyboard2.pinyin.PinyinSchemes;
import juloo.keyboard2.suggestions.Suggestions;
import java.util.List;
import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;

/**
 * Tests for the PinyinInput state machine: the buffer, the full pinyin and
 * double pinyin translations, candidate acceptance (with the remaining
 * keystrokes kept for continued input), and backspace behavior.
 */
public class PinyinInputTest
{
  /** A simple mock callback that records the last Suggestions object set. */
  static class MockCallback implements Suggestions.Callback
  {
    Suggestions last;
    public void set_suggestions(Suggestions s) { last = s; }
  }

  MockCallback _cb;
  Suggestions _sug;

  @Before
  public void setUp() throws Exception
  {
    // Use the real compiled dictionary, as on the device.
    PinyinData.set_instance(PinyinDataTest.load_real_data());
    _cb = new MockCallback();
    _sug = new Suggestions(_cb, null);
  }

  // --- Full pinyin ---

  @Test
  public void full_pinyin_types_and_queries()
  {
    PinyinInput p = full();
    type(p, "ni");
    assertTrue(p.is_active());
    assertEquals("ni", p.get_buffer());
    assertFalse("candidates after typing", p.get_candidates().isEmpty());
    assertEquals("你", p.get_candidates().get(0).text);
    // The suggestions bar received the candidates.
    assertNotNull(_cb.last);
    assertFalse(_cb.last.pinyin_candidates.isEmpty());
    assertEquals("你", _cb.last.pinyin_candidates.get(0));
  }

  @Test
  public void full_pinyin_accept_keeps_remaining_keys()
  {
    PinyinInput p = full();
    type(p, "jintianhaoma");
    // 今天 must be offered as a prefix-word candidate (mainstream behavior:
    // commit the first word, keep the rest pending).
    assertTrue("今天 among candidates: " + texts(p), texts(p).contains("今天"));
    int consumed = p.accept_candidate("今天");
    assertEquals(7, consumed);
    assertTrue("remaining keys buffered", p.is_active());
    p.query_candidates();
    assertTrue("candidates for the rest", !p.get_candidates().isEmpty());
  }

  @Test
  public void full_pinyin_backspace_edits_buffer()
  {
    PinyinInput p = full();
    type(p, "nihao");
    p.backspace();
    p.query_candidates();
    assertEquals("niha", p.get_buffer());
    p.backspace();
    p.backspace();
    p.backspace();
    p.backspace();
    assertFalse(p.is_active());
  }

  @Test
  public void full_pinyin_preview_has_syllable_breaks()
  {
    PinyinInput p = full();
    type(p, "nihao");
    assertTrue("'" + p.get_pinyin_string() + "'",
        p.get_pinyin_string().contains("'"));
  }

  @Test
  public void full_pinyin_composing_prefers_conversion()
  {
    PinyinInput p = full();
    type(p, "nihao");
    assertEquals("你好", p.composing_text());
  }

  // --- Microsoft double pinyin ---

  @Test
  public void double_pinyin_two_keys_per_syllable()
  {
    PinyinInput p = double_pinyin();
    type(p, "ni");
    assertTrue("你 among candidates", texts(p).contains("你"));
    // "hao" = h + k in the Microsoft scheme ('k' is "ao").
    type(p, "hk");
    assertTrue("你好 among candidates", texts(p).contains("你好"));
  }

  @Test
  public void double_pinyin_semicolon_ing()
  {
    PinyinInput p = double_pinyin();
    type(p, "m");
    assertTrue("final pending after initial", p.pending_final());
    type(p, ";");
    assertFalse(p.pending_final());
    assertTrue("明 among candidates", texts(p).contains("明"));
  }

  @Test
  public void double_pinyin_accept_consumes_two_keys()
  {
    PinyinInput p = double_pinyin();
    type(p, "nihk"); // ni + hao
    int before = p.get_buffer().length();
    int consumed = p.accept_candidate("你好");
    assertEquals(4, consumed);
    assertEquals(before - 4, p.get_buffer().length());
  }

  @Test
  public void double_pinyin_odd_tail_is_initial()
  {
    PinyinInput p = double_pinyin();
    type(p, "v"); // the initial "zh" pending a final
    assertTrue(p.is_active());
    assertTrue(p.pending_final());
  }

  @Test
  public void double_pinyin_wo_types_wo()
  {
    // 我 wo = w + o; the O key expands to the written final "o" after w
    // (regression: it used to expand to "uo", producing "wuo")
    PinyinInput p = double_pinyin();
    type(p, "wo");
    assertTrue("我 among candidates", texts(p).contains("我"));
    assertEquals("wo", p.get_pinyin_string());
  }

  @Test
  public void double_pinyin_ny_types_n()
  {
    // 女 nv: the Y key expands to "v" (ü) after n
    PinyinInput p = double_pinyin();
    type(p, "ny");
    assertTrue("女 among candidates", texts(p).contains("女"));
  }

  @Test
  public void double_pinyin_qs_types_qiong()
  {
    // 穷 qiong: the S key expands to "iong" after palatals
    PinyinInput p = double_pinyin();
    type(p, "qs");
    assertTrue("穷 among candidates", texts(p).contains("穷"));
  }

  // --- helpers ---

  PinyinInput full()
  {
    PinyinInput p = new PinyinInput();
    p.started(null, _sug, false);
    return p;
  }

  PinyinInput double_pinyin()
  {
    PinyinInput p = new PinyinInput();
    p.started(PinyinSchemes.get(1), _sug, false);
    return p;
  }

  static void type(PinyinInput p, String keys)
  {
    for (char c : keys.toCharArray())
    {
      p.key_typed(c);
      p.query_candidates();
    }
  }

  static List<String> texts(PinyinInput p)
  {
    java.util.List<String> t = new java.util.ArrayList<String>();
    for (PinyinInput.Candidate c : p.get_candidates())
      t.add(c.text);
    return t;
  }
}
