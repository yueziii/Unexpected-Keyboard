package juloo.keyboard2;

import juloo.keyboard2.pinyin.PinyinUserDict;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for the user pinyin frequency learning:
 * 1. Recording a committed word increases its usage count
 * 2. rank_by_user_usage promotes words the user committed before to the
 *    front, most used first
 * 3. Words never committed keep their dictionary (frequency) order
 * 4. Recording the pinyin preview or empty strings is ignored
 */
public class PinyinUserDictTest
{
  @Before
  public void setUp() { PinyinUserDict.clear(); }

  @After
  public void tearDown() { PinyinUserDict.clear(); }

  @Test
  public void record_increases_count()
  {
    assertEquals(0, PinyinUserDict.count("你好"));
    PinyinUserDict.record("你好");
    assertEquals(1, PinyinUserDict.count("你好"));
    PinyinUserDict.record("你好");
    PinyinUserDict.record("你好");
    assertEquals(3, PinyinUserDict.count("你好"));
  }

  @Test
  public void unknown_word_has_count_zero()
  {
    PinyinUserDict.record("你好");
    assertEquals(0, PinyinUserDict.count("您好"));
    assertEquals(0, PinyinUserDict.count(null));
  }

  @Test
  public void empty_and_null_words_are_ignored()
  {
    PinyinUserDict.record("");
    PinyinUserDict.record(null);
    assertEquals(0, PinyinUserDict.count(""));
  }

  @Test
  public void used_words_rank_first_by_usage()
  {
    // Dictionary order: 你 (high freq), 便民, 民好 — user used 民好 twice
    // and 便民 once, so they must both rank before 你, 民好 before 便民.
    List<String> candidates = new ArrayList<String>(
        Arrays.asList("你", "便民", "民好"));
    PinyinUserDict.record("便民");
    PinyinUserDict.record("民好");
    PinyinUserDict.record("民好");
    PinyinUserDict.rank_by_user_usage(candidates);
    assertEquals(Arrays.asList("民好", "便民", "你"), candidates);
  }

  @Test
  public void unused_words_keep_dictionary_order()
  {
    List<String> candidates = new ArrayList<String>(
        Arrays.asList("甲", "乙", "丙"));
    PinyinUserDict.rank_by_user_usage(candidates);
    assertEquals(Arrays.asList("甲", "乙", "丙"), candidates);
  }

  @Test
  public void clear_forgets_all_words()
  {
    PinyinUserDict.record("你好");
    PinyinUserDict.clear();
    assertEquals(0, PinyinUserDict.count("你好"));
  }

  // --- custom words (自动造词) ---

  @Test
  public void custom_word_pinyin_is_learned()
  {
    PinyinUserDict.record("泥嚎", "nihao");
    assertEquals("nihao", PinyinUserDict.pinyin_of("泥嚎"));
    assertEquals(1, PinyinUserDict.count("泥嚎"));
    // Words without a pinyin are counted but not custom words.
    PinyinUserDict.record("你好");
    assertNull(PinyinUserDict.pinyin_of("你好"));
    // The live map for the engine contains only learned pinyins.
    assertTrue(PinyinUserDict.pinyins().containsKey("泥嚎"));
    assertFalse(PinyinUserDict.pinyins().containsKey("你好"));
  }

  @Test
  public void empty_pinyin_is_not_learned()
  {
    PinyinUserDict.record("泥嚎", "");
    assertNull(PinyinUserDict.pinyin_of("泥嚎"));
  }

  // --- word pairs (联想) ---

  @Test
  public void pair_counts_accumulate()
  {
    assertEquals(0, PinyinUserDict.pair_count("你", "好"));
    PinyinUserDict.record_pair("你", "好");
    PinyinUserDict.record_pair("你", "好");
    assertEquals(2, PinyinUserDict.pair_count("你", "好"));
    assertEquals(0, PinyinUserDict.pair_count("好", "你"));
    // Empty and null parts are ignored.
    PinyinUserDict.record_pair(null, "好");
    PinyinUserDict.record_pair("你", "");
    assertEquals(2, PinyinUserDict.pair_count("你", "好"));
    // The live nested map is exposed for the predictor.
    assertTrue(PinyinUserDict.pairs().containsKey("你"));
    assertEquals(Integer.valueOf(2),
        PinyinUserDict.pairs().get("你").get("好"));
  }

  // --- deletion (candidate long-press) ---

  @Test
  public void remove_forgets_everything_about_a_word()
  {
    PinyinUserDict.record("泥嚎", "nihao");
    PinyinUserDict.record("你好");
    PinyinUserDict.record_pair("泥嚎", "世界");
    PinyinUserDict.record_pair("你好", "泥嚎");
    PinyinUserDict.remove("泥嚎");
    assertEquals(0, PinyinUserDict.count("泥嚎"));
    assertNull(PinyinUserDict.pinyin_of("泥嚎"));
    assertEquals(0, PinyinUserDict.pair_count("泥嚎", "世界"));
    assertEquals(0, PinyinUserDict.pair_count("你好", "泥嚎"));
    // Other words are untouched.
    assertEquals(1, PinyinUserDict.count("你好"));
  }
}
