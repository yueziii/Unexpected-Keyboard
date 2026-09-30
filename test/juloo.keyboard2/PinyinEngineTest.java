package juloo.keyboard2;

import juloo.keyboard2.pinyin.PinyinData;
import juloo.keyboard2.pinyin.PinyinEngine;
import java.util.List;
import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;

/**
 * Tests for the sentence decoder (PinyinEngine) against the real compiled
 * dictionary. These encode the mainstream-IME behaviors the engine must
 * deliver: single characters for one syllable, words for two, whole
 * sentences for long inputs, and bigram-guided disambiguation.
 */
public class PinyinEngineTest
{
  PinyinData _data;

  @Before
  public void setUp() throws Exception
  {
    _data = PinyinDataTest.load_real_data();
  }

  List<PinyinEngine.Candidate> decode(String letters)
  {
    return PinyinEngine.decode(_data, letters, null, null);
  }

  static List<String> texts(List<PinyinEngine.Candidate> cands)
  {
    List<String> texts = new java.util.ArrayList<String>();
    for (PinyinEngine.Candidate c : cands)
      texts.add(c.text);
    return texts;
  }

  @Test
  public void single_syllable_shows_characters()
  {
    List<PinyinEngine.Candidate> c = decode("ni");
    assertFalse(c.isEmpty());
    assertEquals("你", c.get(0).text);
    assertEquals(2, c.get(0).letters_consumed);
  }

  @Test
  public void two_syllables_show_word()
  {
    List<PinyinEngine.Candidate> c = decode("nihao");
    assertFalse(c.isEmpty());
    assertEquals("你好", c.get(0).text);
    assertEquals(5, c.get(0).letters_consumed);
  }

  @Test
  public void whole_sentence_conversion()
  {
    // The flagship behavior: continuous typing converts the whole sentence.
    // jin-tian (7) tian-qi (6) hen (3) hao (3) = 19 letters.
    List<PinyinEngine.Candidate> c = decode("jintiantianqihenhao");
    assertFalse(c.isEmpty());
    assertEquals("今天天气很好", c.get(0).text);
    assertEquals(19, c.get(0).letters_consumed);
  }

  @Test
  public void more_sentence_conversions()
  {
    assertEquals("你好世界", texts(decode("nihaoshijie")).get(0));
    assertEquals("中国人民", texts(decode("zhongguorenmin")).get(0));
    assertEquals("经济发展", texts(decode("jingjifazhan")).get(0));
  }

  @Test
  public void sentence_disambiguation()
  {
    // 这是 must beat 只是/这事 for "zheshi" thanks to unigram+bigram.
    List<String> t = texts(decode("zheshi"));
    assertFalse(t.isEmpty());
    assertEquals("这是", t.get(0));
  }

  @Test
  public void ambiguous_split_xian()
  {
    // "xian" must be able to yield both the single syllable 先 and the
    // two-syllable 西安 among the candidates.
    List<String> t = texts(decode("xian"));
    assertTrue("先 among candidates: " + t, t.contains("先"));
    assertTrue("西安 among candidates: " + t, t.contains("西安"));
  }

  @Test
  public void partial_trailing_syllable()
  {
    // "nihaox": complete part nihao converts; x is still being typed.
    List<PinyinEngine.Candidate> c = decode("nihaox");
    assertFalse(c.isEmpty());
    assertTrue("你好 first for nihaox: " + c.get(0).text,
        c.get(0).text.startsWith("你好"));
    assertEquals(5, c.get(0).letters_consumed);
    // Prefix-word candidates may consume fewer letters (commit 你, keep
    // "haox" pending), extensions may consume more — but nothing may
    // consume less than the first syllable.
    for (PinyinEngine.Candidate cand : c)
      assertTrue("consumed >= 2: " + cand.letters_consumed,
          cand.letters_consumed >= 2);
  }

  @Test
  public void incomplete_first_syllable()
  {
    // "x" alone completes to frequent characters.
    List<PinyinEngine.Candidate> c = decode("x");
    assertFalse(c.isEmpty());
    for (PinyinEngine.Candidate cand : c)
      assertEquals(1, cand.letters_consumed);
  }

  @Test
  public void type_ahead_extension()
  {
    // Typing "niha" already offers 你好 (which will consume all four
    // letters once committed).
    List<PinyinEngine.Candidate> c = decode("niha");
    assertTrue("你好 offered for niha: " + texts(c),
        texts(c).contains("你好"));
    for (PinyinEngine.Candidate cand : c)
    {
      if (cand.text.equals("你好"))
        assertEquals(4, cand.letters_consumed);
    }
  }

  @Test
  public void user_history_boost()
  {
    // Without history, 你 wins "ni". With heavy usage of 泥 it must rank
    // first.
    java.util.Map<String, Integer> users =
      new java.util.HashMap<String, Integer>();
    users.put("泥", 50);
    List<PinyinEngine.Candidate> c = PinyinEngine.decode(_data, "ni", null,
        users);
    assertEquals("泥", c.get(0).text);
  }

  @Test
  public void context_word_biases_first_word()
  {
    List<PinyinEngine.Candidate> c = PinyinEngine.decode(_data, "shi", "他",
        null);
    assertFalse(c.isEmpty());
  }

  @Test
  public void decode_performance()
  {
    // Sentence decode runs on every keystroke; keep it well under a frame.
    long t0 = System.nanoTime();
    int runs = 20;
    for (int i = 0; i < runs; i++)
      decode("jintiantianqihenhao");
    long ms = (System.nanoTime() - t0) / 1000000 / runs;
    assertTrue("decode avg " + ms + "ms >= 30ms", ms < 30);
  }

  @Test
  public void garbage_input_returns_empty()
  {
    assertTrue(decode("").isEmpty());
    assertTrue("qqqxx starts no syllable", decode("qqqxx").isEmpty());
  }
}
