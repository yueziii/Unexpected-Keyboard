package juloo.keyboard2;

import juloo.keyboard2.pinyin.PinyinData;
import juloo.keyboard2.pinyin.PinyinEngine;
import juloo.keyboard2.pinyin.PinyinHotwords;
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
    return PinyinEngine.decode(_data, null, letters, null, null, null,
        false);
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
  public void single_syllable_ranks_characters_first()
  {
    // Like mainstream IMEs, a single-syllable input lists its characters
    // first; the multi-character word sharing the whole syllable (西安)
    // stays reachable but below them.
    List<PinyinEngine.Candidate> c = decode("xian");
    assertFalse(c.isEmpty());
    assertEquals("a character first", 1, c.get(0).text.length());
    List<String> t = texts(c);
    assertTrue("西安 still reachable: " + t, t.contains("西安"));
    assertNotEquals("西安", t.get(0));
  }

  @Test
  public void sentence_candidates_carry_tail_word()
  {
    // The tail word is what continues the bigram context after committing
    // the sentence: 今天天气真好 ends on 真好, not on the whole sentence.
    List<PinyinEngine.Candidate> c = decode("jintiantianqizhenhao");
    assertFalse(c.isEmpty());
    assertEquals("今天天气真好", c.get(0).text);
    assertEquals("真好", c.get(0).tail_word);
  }

  @Test
  public void first_segment_alternatives_offered_for_long_input()
  {
    // 断句提示: beyond the whole-sentence candidates, the alternatives for
    // the first syllable (今) and the first word (今天) must be committable
    // on their own, leaving the rest of the buffer pending.
    List<PinyinEngine.Candidate> c = decode("jintiantianqizhenhao");
    boolean has_first_syllable = false;
    boolean has_first_word = false;
    for (PinyinEngine.Candidate cand : c)
    {
      if (cand.letters_consumed == 3)
        has_first_syllable = true;
      if (cand.letters_consumed == 7)
        has_first_word = true;
    }
    assertTrue("first-syllable alternatives offered: " + texts(c),
        has_first_syllable);
    assertTrue("first-word candidates offered: " + texts(c),
        has_first_word);
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
    List<PinyinEngine.Candidate> c = PinyinEngine.decode(_data, null, "ni", null,
        users, null, false);
    assertEquals("泥", c.get(0).text);
  }

  @Test
  public void committed_word_ranks_first()
  {
    // One commit is enough: the word the user committed before leads the
    // candidates for its own pinyin, above the more frequent dictionary
    // words (时间 is ~300x more frequent than 尸检).
    java.util.Map<String, Integer> users =
      new java.util.HashMap<String, Integer>();
    users.put("尸检", 1);
    List<String> t = texts(PinyinEngine.decode(_data, null, "shijian", null,
          users, null, false));
    assertTrue("尸检 offered: " + t, t.contains("尸检"));
    assertEquals("尸检 first: " + t, "尸检", t.get(0));
  }

  @Test
  public void most_used_committed_word_leads()
  {
    // Both committed: the more used one ranks first.
    java.util.Map<String, Integer> users =
      new java.util.HashMap<String, Integer>();
    users.put("事件", 5);
    users.put("实践", 2);
    List<String> t = texts(PinyinEngine.decode(_data, null, "shijian", null,
          users, null, false));
    assertEquals("事件 first: " + t, "事件", t.get(0));
    assertTrue("实践 second: " + t, "实践".equals(t.get(1)));
  }

  @Test
  public void committed_word_does_not_hijack_longer_input()
  {
    // A heavily used word (今天) must not displace the whole-sentence
    // conversion for a longer input: only candidates covering the same
    // letters as the head are promoted.
    java.util.Map<String, Integer> users =
      new java.util.HashMap<String, Integer>();
    users.put("今天", 100);
    List<PinyinEngine.Candidate> c = PinyinEngine.decode(_data, null,
        "jintiantianqihenhao", null, users, null, false);
    assertEquals("今天天气很好", c.get(0).text);
    assertEquals(19, c.get(0).letters_consumed);
  }

  @Test
  public void context_word_biases_first_word()
  {
    List<PinyinEngine.Candidate> c = PinyinEngine.decode(_data, null, "shi", "他",
        null, null, false);
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

  // --- 简拼 (abbreviated pinyin) ---

  @Test
  public void abbrev_candidates_when_no_syllable_parses()
  {
    // "nh" parses as no syllable sequence; with abbrev enabled, the
    // initials of 你好 (and friends) become the candidates.
    List<String> no_abbrev = texts(PinyinEngine.decode(_data, null, "nh", null,
          null, null, false));
    assertTrue("no candidates without abbrev", no_abbrev.isEmpty());
    List<String> t = texts(PinyinEngine.decode(_data, null, "nh", null, null,
          null, true));
    assertTrue("你好 via abbrev: " + t, t.contains("你好"));
    // Committing consumes the whole buffer.
    for (PinyinEngine.Candidate c : PinyinEngine.decode(_data, null, "nh", null,
          null, null, true))
      if (c.text.equals("你好"))
        assertEquals(2, c.letters_consumed);
  }

  @Test
  public void abbrev_latin_pairs()
  {
    // Curated abbreviation pairs from the buzzword list.
    List<String> t = texts(PinyinEngine.decode(_data, null, "yyds", null, null,
          null, true));
    assertTrue("永远的神 via yyds: " + t, t.contains("永远的神"));
  }

  @Test
  public void full_pinyin_still_wins_over_abbrev()
  {
    // "nihao" is a full pinyin parse; abbrev must not displace 你好.
    List<String> t = texts(PinyinEngine.decode(_data, null, "nihao", null,
          null, null, true));
    assertEquals("你好", t.get(0));
  }

  // --- 云端热词 (hotword overlay) ---

  @Test
  public void hotword_overlay_surfaces_new_words() throws Exception
  {
    // A buzzword absent from the compiled dictionary: the overlay must
    // make it decodable and rank it first for its pinyin.
    PinyinHotwords hw = PinyinHotwords.parse(
        "# test overlay\nxiandaikuaijie\t显代快街\t5000\n");
    List<String> t = texts(PinyinEngine.decode(_data, hw,
            "xiandaikuaijie", null, null, null, false));
    assertFalse("hotword decodable: " + t, t.isEmpty());
    assertEquals("显代快街", t.get(0));
  }

  @Test
  public void hotword_abbreviations_surface() throws Exception
  {
    PinyinHotwords hw = PinyinHotwords.parse(
        "# test overlay\nlaohuji\t老虎机\t5000\n@abbrev\tlhj\t老虎机\n");
    // "lhj" parses as no syllable sequence; the overlay's abbreviation
    // pair must appear among the candidates.
    List<String> t = texts(PinyinEngine.decode(_data, hw, "lhj", null,
          null, null, true));
    assertTrue("老虎机 via lhj: " + t, t.contains("老虎机"));
  }

  // --- 自动造词 (custom user words) ---

  @Test
  public void user_custom_words_surface_for_their_pinyin()
  {
    java.util.Map<String, String> pinyins =
      new java.util.HashMap<String, String>();
    pinyins.put("泥嚎", "nihao");
    java.util.Map<String, Integer> users =
      new java.util.HashMap<String, Integer>();
    users.put("泥嚎", 3);
    List<String> t = texts(PinyinEngine.decode(_data, null, "nihao", null, users,
          pinyins, false));
    // The custom word's synthetic frequency is above the dictionary scale,
    // so it leads 你好 instead of tying with the top dictionary words.
    assertTrue("custom word 泥嚎 offered: " + t, t.contains("泥嚎"));
    assertEquals("泥嚎 first: " + t, "泥嚎", t.get(0));
  }

  // --- long phrases (poem lines, proverbs) ---

  @Test
  public void long_phrase_decodes_as_one_candidate()
  {
    List<String> t = texts(decode("chuheridangwu"));
    assertTrue("锄禾日当午 first: " + t,
        t.size() > 0 && t.get(0).equals("锄禾日当午"));
    List<String> t2 = texts(decode("yixinyiyi"));
    assertTrue("一心一意 first: " + t2,
        t2.size() > 0 && t2.get(0).equals("一心一意"));
  }
}
