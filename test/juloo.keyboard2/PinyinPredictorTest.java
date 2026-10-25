package juloo.keyboard2;

import juloo.keyboard2.pinyin.PinyinData;
import juloo.keyboard2.pinyin.PinyinPredictor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import static org.junit.Assert.*;

/**
 * Tests for the next-word predictor (联想): the user's word pairs rank
 * above the corpus bigram, the results are deduplicated, capped and
 * length-filtered, and an empty context predicts nothing.
 */
public class PinyinPredictorTest
{
  PinyinData _data;
  Map<String, Map<String, Integer>> _pairs;

  @Before
  public void setUp() throws Exception
  {
    _data = PinyinDataTest.load_real_data();
    _pairs = new HashMap<String, Map<String, Integer>>();
  }

  @After
  public void tearDown()
  {
    juloo.keyboard2.pinyin.PinyinUserDict.clear();
  }

  static Map<String, Map<String, Integer>> pairs(Object... kv)
  {
    Map<String, Map<String, Integer>> out =
      new HashMap<String, Map<String, Integer>>();
    for (int i = 0; i + 1 < kv.length; i += 2)
    {
      @SuppressWarnings("unchecked")
      Map<String, Integer> succ = (Map<String, Integer>)kv[i + 1];
      out.put((String)kv[i], succ);
    }
    return out;
  }

  static Map<String, Integer> counts(Object... kv)
  {
    Map<String, Integer> out = new HashMap<String, Integer>();
    for (int i = 0; i + 1 < kv.length; i += 2)
      out.put((String)kv[i], (Integer)kv[i + 1]);
    return out;
  }

  @Test
  public void user_pairs_rank_first()
  {
    _pairs = pairs("你好", counts("世界", 5, "吗", 2));
    List<String> p = PinyinPredictor.predict(_data, "你好", _pairs);
    assertFalse(p.isEmpty());
    assertEquals("世界", p.get(0));
    assertEquals("吗", p.get(1));
  }

  @Test
  public void user_pairs_precede_corpus()
  {
    // 的 has strong corpus continuations; the user pair still comes first.
    _pairs = pairs("的", counts("确", 1));
    List<String> p = PinyinPredictor.predict(_data, "的", _pairs);
    assertEquals("确", p.get(0));
  }

  @Test
  public void corpus_bigram_fills()
  {
    List<String> p = PinyinPredictor.predict(_data, "的", _pairs);
    assertFalse("corpus continuations of 的", p.isEmpty());
    assertTrue("capped at MAX", p.size() <= PinyinPredictor.MAX);
    for (String w : p)
      assertTrue("length filter", w.length() <= PinyinPredictor.MAX_LEN);
  }

  @Test
  public void user_words_are_deduped_against_corpus()
  {
    // 的->是 exists in the corpus; a user pair with the same continuation
    // must not produce a duplicate.
    int shi_id = _data.lookup_word_id("是");
    assertTrue("是 has a corpus continuation from 的",
        _data.bigram_count(_data.lookup_word_id("的"), shi_id) > 0);
    _pairs = pairs("的", counts("是", 7));
    List<String> p = PinyinPredictor.predict(_data, "的", _pairs);
    int n = 0;
    for (String w : p)
      if (w.equals("是"))
        n++;
    assertEquals("是 appears once", 1, n);
    assertEquals("是", p.get(0));
  }

  @Test
  public void empty_context_predicts_nothing()
  {
    assertTrue(PinyinPredictor.predict(_data, null, _pairs).isEmpty());
    assertTrue(PinyinPredictor.predict(_data, "", _pairs).isEmpty());
    // Unknown context: no corpus match, no user pairs.
    assertTrue(PinyinPredictor.predict(_data, "绝不是词zz", _pairs).isEmpty());
  }

  @Test
  public void long_user_words_are_filtered()
  {
    _pairs = pairs("你好", counts("六个字的长词组", 9));
    List<String> p = PinyinPredictor.predict(_data, "你好", _pairs);
    assertFalse("six-char word filtered", p.contains("六个字的长词组"));
  }
}
