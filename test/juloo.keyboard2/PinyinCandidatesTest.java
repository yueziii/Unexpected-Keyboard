package juloo.keyboard2;

import juloo.keyboard2.pinyin.PinyinDict;
import juloo.keyboard2.pinyin.PinyinInput;
import juloo.keyboard2.pinyin.PinyinSchemes;
import juloo.keyboard2.suggestions.Suggestions;
import java.util.List;
import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;

/**
 * Tests for pinyin candidate query behavior.
 *
 * Verifies that:
 * 1. The built-in PinyinDict can be loaded and queried
 * 2. PinyinInput correctly decodes double pinyin keystrokes
 * 3. PinyinInput.query_candidates() populates suggestions even when
 *    the _enabled flag would normally suppress them
 * 4. Single-character and multi-character (word) candidates are returned
 */
public class PinyinCandidatesTest
{
  PinyinSchemes.Scheme _scheme;
  PinyinDict _dict;

  /** A simple mock callback that records the last Suggestions object set. */
  static class MockCallback implements Suggestions.Callback
  {
    Suggestions last;
    public void set_suggestions(Suggestions s) { last = s; }
  }

  @Before
  public void setUp()
  {
    _scheme = PinyinSchemes.get(0);
    _dict = PinyinDict.built_in();
  }

  // --- Dictionary query tests ---

  @Test
  public void test_dict_query_single_char_ni()
  {
    List<PinyinDict.Entry> r = _dict.query("ni", 3);
    assertFalse("Should find candidates for 'ni'", r.isEmpty());
    assertEquals("你", r.get(0).word);
  }

  @Test
  public void test_dict_query_single_char_hao()
  {
    List<PinyinDict.Entry> r = _dict.query("hao", 3);
    assertFalse("Should find candidates for 'hao'", r.isEmpty());
    assertEquals("好", r.get(0).word);
  }

  @Test
  public void test_dict_query_word_nihao()
  {
    List<PinyinDict.Entry> r = _dict.query("nihao", 3);
    assertFalse("Should find candidates for 'nihao'", r.isEmpty());
    assertEquals("你好", r.get(0).word);
  }

  @Test
  public void test_dict_query_word_xiexie()
  {
    List<PinyinDict.Entry> r = _dict.query("xiexie", 3);
    assertFalse("Should find candidates for 'xiexie'", r.isEmpty());
    assertEquals("谢谢", r.get(0).word);
  }

  @Test
  public void test_dict_query_prefix_match()
  {
    // "ni" should also match "nihao" as a prefix
    List<PinyinDict.Entry> r = _dict.query("ni", 5);
    assertFalse(r.isEmpty());
    boolean found_ni = false;
    for (PinyinDict.Entry e : r)
    {
      if (e.word.equals("你")) found_ni = true;
    }
    assertTrue("Should find 你 in results for 'ni'", found_ni);
  }

  @Test
  public void test_dict_query_no_match()
  {
    List<PinyinDict.Entry> r = _dict.query("zzzzz", 3);
    assertTrue("Should find no candidates for 'zzzzz'", r.isEmpty());
  }

  // --- PinyinInput decode tests ---

  @Test
  public void test_pinyin_decode_ni()
  {
    PinyinInput pi = new PinyinInput();
    pi.started(_scheme, null, null);
    pi.key_typed('n');
    assertTrue("Should be active after typing 'n'", pi.is_active());
    assertTrue("Should be pending final after one key", pi.pending_final());
    pi.key_typed('i');
    assertEquals("ni", pi.get_pinyin_string());
    assertEquals("ni", pi.get_pinyin_key());
  }

  @Test
  public void test_pinyin_decode_nihao()
  {
    PinyinInput pi = new PinyinInput();
    pi.started(_scheme, null, null);
    pi.key_typed('n');
    pi.key_typed('i');
    pi.key_typed('h');
    pi.key_typed('k');
    assertEquals("ni hao", pi.get_pinyin_string());
    assertEquals("nihao", pi.get_pinyin_key());
  }

  @Test
  public void test_pinyin_backspace()
  {
    PinyinInput pi = new PinyinInput();
    pi.started(_scheme, null, null);
    pi.key_typed('n');
    pi.key_typed('i');
    assertEquals("ni", pi.get_pinyin_string());
    pi.backspace();
    assertEquals("n", pi.get_pinyin_string());
    assertTrue(pi.pending_final());
    pi.backspace();
    assertFalse(pi.is_active());
  }

  // --- PinyinInput accept_candidate tests ---

  @Test
  public void test_accept_candidate_consumes_all()
  {
    PinyinInput pi = new PinyinInput();
    pi.started(_scheme, null, null);
    pi.key_typed('n');
    pi.key_typed('i');
    pi.key_typed('h');
    pi.key_typed('k');
    assertEquals("ni hao", pi.get_pinyin_string());
    // Accept "你好" which should consume all syllables
    boolean has_remaining = pi.accept_candidate("你好", "ni hao");
    assertFalse("Should have no remaining syllables", has_remaining);
    assertFalse("Buffer should be empty", pi.is_active());
  }

  @Test
  public void test_accept_pinyin_preview_commits_raw()
  {
    PinyinInput pi = new PinyinInput();
    pi.started(_scheme, null, null);
    pi.key_typed('n');
    pi.key_typed('i');
    // Accept the pinyin preview itself
    boolean has_remaining = pi.accept_candidate("ni", "ni");
    assertFalse("Should have no remaining after accepting preview", has_remaining);
    assertFalse("Buffer should be empty", pi.is_active());
  }

  // --- Suggestions pinyin_query tests ---

  @Test
  public void test_pinyin_query_returns_preview_and_candidates()
  {
    MockCallback cb = new MockCallback();
    Suggestions s = new Suggestions(cb, null);
    PinyinInput pi = new PinyinInput();
    pi.started(_scheme, null, s);
    pi.key_typed('n');
    pi.key_typed('i');
    pi.query_candidates();
    // The preview is now exposed separately and no longer occupies a
    // candidate slot.
    assertEquals("ni", s.pinyin_preview);
    assertFalse("Should have candidates", s.pinyin_candidates.isEmpty());
  }

  @Test
  public void test_pinyin_query_with_nihao()
  {
    MockCallback cb = new MockCallback();
    Suggestions s = new Suggestions(cb, null);
    PinyinInput pi = new PinyinInput();
    pi.started(_scheme, null, s);
    pi.key_typed('n');
    pi.key_typed('i');
    pi.key_typed('h');
    pi.key_typed('k');
    pi.query_candidates();
    assertEquals("ni hao", s.pinyin_preview);
    assertFalse("Should have candidates", s.pinyin_candidates.isEmpty());
  }

  @Test
  public void test_pinyin_query_clears_on_empty()
  {
    MockCallback cb = new MockCallback();
    Suggestions s = new Suggestions(cb, null);
    PinyinInput pi = new PinyinInput();
    pi.started(_scheme, null, s);
    pi.query_candidates();
    assertNull(s.pinyin_preview);
    assertTrue(s.pinyin_candidates.isEmpty());
  }

  @Test
  public void test_pinyin_query_works_when_disabled()
  {
    // The key test: pinyin_query must return candidates even when _enabled
    // is false (e.g. when the editor says NO_SUGGESTIONS)
    MockCallback cb = new MockCallback();
    Suggestions s = new Suggestions(cb, null);
    // _enabled defaults to false since started() is not called
    PinyinInput pi = new PinyinInput();
    pi.started(_scheme, null, s);
    pi.key_typed('n');
    pi.key_typed('i');
    pi.query_candidates();
    assertFalse("Pinyin candidates must show even when _enabled is false",
        s.pinyin_candidates.isEmpty());
  }
}
