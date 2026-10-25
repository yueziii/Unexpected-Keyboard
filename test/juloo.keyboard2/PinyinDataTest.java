package juloo.keyboard2;

import juloo.keyboard2.pinyin.PinyinData;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;

/**
 * Tests for the compiled binary dictionary (PinyinData), loaded from the
 * real assets produced by scripts/gen_pinyin_bin.py.
 */
public class PinyinDataTest
{
  PinyinData _data;

  @Before
  public void setUp() throws Exception
  {
    _data = load_real_data();
  }

  /** Load assets/py_dict.bin and assets/py_bigram.bin from the repository. */
  static PinyinData load_real_data() throws Exception
  {
    File dict = find_asset("py_dict.bin");
    File bigram = find_asset("py_bigram.bin");
    ByteBuffer db = ByteBuffer.wrap(Files.readAllBytes(dict.toPath()));
    ByteBuffer bb = ByteBuffer.wrap(Files.readAllBytes(bigram.toPath()));
    return new PinyinData(db, bb);
  }

  static File find_asset(String name) throws Exception
  {
    String[] candidates = new String[]{
      "assets/" + name, "../assets/" + name, "app/assets/" + name };
    for (String c : candidates)
    {
      File f = new File(c);
      if (f.exists())
        return f;
    }
    throw new AssertionError("Missing asset " + name
        + " — run scripts/gen_pinyin_bin.py");
  }

  @Test
  public void exact_lookup_single_chars()
  {
    List<PinyinData.Entry> r = new ArrayList<PinyinData.Entry>();
    _data.query_exact("ni", 10, r);
    assertFalse("entries for 'ni'", r.isEmpty());
    assertEquals("你", r.get(0).word);
    List<PinyinData.Entry> le = new ArrayList<PinyinData.Entry>();
    _data.query_exact("le", 10, le);
    assertFalse("entries for 'le'", le.isEmpty());
    assertTrue("了 among 'le' entries", has_word(le, "了"));
    // An unknown pinyin has no exact entries.
    List<PinyinData.Entry> none = new ArrayList<PinyinData.Entry>();
    _data.query_exact("zzzz", 10, none);
    assertTrue(none.isEmpty());
  }

  @Test
  public void exact_lookup_words()
  {
    List<PinyinData.Entry> r = new ArrayList<PinyinData.Entry>();
    _data.query_exact("nihao", 10, r);
    assertFalse(r.isEmpty());
    boolean has_nihao = false;
    for (PinyinData.Entry e : r)
      if (e.word.equals("你好"))
        has_nihao = true;
    assertTrue("你好 among exact matches for nihao", has_nihao);
  }

  @Test
  public void u_equals_v_entries()
  {
    // 绿 must be reachable both as lv (typed) — never as a second-class
    // encoding.
    List<PinyinData.Entry> r = new ArrayList<PinyinData.Entry>();
    _data.query_exact("lv", 20, r);
    assertTrue(has_word(r, "绿"));
    r.clear();
    _data.query_exact("nv", 20, r);
    assertTrue(has_word(r, "女"));
  }

  @Test
  public void industry_vocabulary_is_bundled()
  {
    // The mainstream head of every THUOCL domain list ships in the built-in
    // dictionary (see DOMAIN_WORDS in gen_pinyin_dict.py): one representative
    // term per field that the global frequency cut used to drop.
    String[] pinyin_word = new String[]{
      "manxingqiguanyan", "慢性气管炎", // medical
      "neihediaoshi", "内核调试",       // IT
      "kaitingshenli", "开庭审理",      // law
      "fanzuixianyiren", "犯罪嫌疑人",  // law
      "yuecai", "粤菜",                 // food
      "tingpai", "停牌",                // finance
    };
    for (int i = 0; i < pinyin_word.length; i += 2)
    {
      List<PinyinData.Entry> r = new ArrayList<PinyinData.Entry>();
      _data.query_exact(pinyin_word[i], 50, r);
      assertTrue("domain word " + pinyin_word[i + 1] + " ("
            + pinyin_word[i] + ") in the bundled dictionary",
          has_word(r, pinyin_word[i + 1]));
    }
  }

  static boolean has_word(List<PinyinData.Entry> l, String w)
  {
    for (PinyinData.Entry e : l)
      if (e.word.equals(w))
        return true;
    return false;
  }

  @Test
  public void prefix_lookup()
  {
    // The scan follows pinyin lexicographic order, so a short prefix like
    // "jin" first returns the single-character entries; use a prefix that
    // pins the target word.
    List<PinyinData.Entry> r = new ArrayList<PinyinData.Entry>();
    _data.query_prefix("jinti", 30, r);
    assertFalse(r.isEmpty());
    for (PinyinData.Entry e : r)
      assertTrue(e.pinyin.startsWith("jinti"));
    boolean has_jintian = false;
    for (PinyinData.Entry e : r)
      if (e.word.equals("今天") && e.pinyin.equals("jintian"))
        has_jintian = true;
    assertTrue("今天 in prefix matches of jinti", has_jintian);
  }

  @Test
  public void word_id_lookup()
  {
    List<PinyinData.Entry> r = new ArrayList<PinyinData.Entry>();
    _data.query_exact("nihao", 10, r);
    assertFalse(r.isEmpty());
    int id = _data.lookup_word_id(r.get(0).word);
    assertTrue(id >= 0);
    assertEquals(r.get(0).word, _data.word_of(id));
    assertEquals(-1, _data.lookup_word_id("绝不是词zz"));
  }

  @Test
  public void bigram_counts()
  {
    // The corpus certainly contains common function-word transitions.
    int de = _data.lookup_word_id("的");
    int shi = _data.lookup_word_id("是");
    assertTrue("的 in dictionary", de >= 0);
    assertTrue("是 in dictionary", shi >= 0);
    assertTrue("bigram(的, 是) observed in corpus",
        _data.bigram_count(de, shi) > 0);
    assertEquals(0, _data.bigram_count(-1, -1));
  }

  @Test
  public void v2_format_with_abbrev_index()
  {
    assertTrue("compiled dictionary has the abbrev index",
        _data.has_abbrev());
    // Word frequencies are exposed (v2 word table).
    int ni = _data.lookup_word_id("你");
    assertTrue(ni >= 0);
    assertTrue("word freq of 你 positive", _data.word_freq(ni) > 0);
  }

  @Test
  public void abbrev_lookup_by_initials()
  {
    // 简拼: initials of multi-character words.
    List<PinyinData.Entry> r = new ArrayList<PinyinData.Entry>();
    _data.query_abbrev("nh", 10, r);
    assertTrue("nh -> 你好", has_word(r, "你好"));
    r.clear();
    _data.query_abbrev("bj", 10, r);
    assertFalse("bj has candidates", r.isEmpty());
    // Results are ranked by word frequency.
    for (int i = 1; i < r.size(); i++)
      assertTrue("ranked by freq", r.get(i - 1).freq >= r.get(i).freq);
  }

  @Test
  public void abbrev_lookup_explicit_pairs()
  {
    // Curated latin abbreviations from scripts/data/extra/buzzwords.txt.
    List<PinyinData.Entry> r = new ArrayList<PinyinData.Entry>();
    _data.query_abbrev_exact("yyds", 10, r);
    assertTrue("yyds -> 永远的神", has_word(r, "永远的神"));
    r.clear();
    _data.query_abbrev_exact("pyq", 10, r);
    assertTrue("pyq -> 朋友圈", has_word(r, "朋友圈"));
  }

  @Test
  public void bigram_top_continuations()
  {
    int de = _data.lookup_word_id("的");
    List<PinyinData.BigramCand> cs = _data.bigram_top(de, 5);
    assertFalse("continuations of 的", cs.isEmpty());
    assertTrue("at most k", cs.size() <= 5);
    for (int i = 1; i < cs.size(); i++)
      assertTrue("sorted by count", cs.get(i - 1).count >= cs.get(i).count);
    for (PinyinData.BigramCand c : cs)
      assertTrue(_data.word_of(c.word_id) != null);
  }

  @Test
  public void fuzzy_query_expands()
  {
    // Without rules, no expansion.
    assertFalse(PinyinData.fuzzy_enabled());
    List<PinyinData.Entry> r = new ArrayList<PinyinData.Entry>();
    _data.query_exact("zong", 10, r);
    assertFalse("中 not matched without fuzzy", has_word(r, "中"));
    try
    {
      java.util.Set<String> rules = new java.util.HashSet<String>();
      rules.add("z_zh");
      PinyinData.set_fuzzy(rules);
      assertTrue(PinyinData.fuzzy_enabled());
      r.clear();
      _data.query_exact("zong", 10, r);
      assertTrue("zong matches 中 (zhong) with z/zh", has_word(r, "中"));
      // Prefix queries expand as well: "zan" also matches the zh- initial
      // entries (战 zhan...) that a plain prefix scan would never return.
      r.clear();
      _data.query_prefix("zan", 40, r);
      boolean has_zhan_word = false;
      for (PinyinData.Entry e : r)
        if (e.pinyin.startsWith("zhan"))
          has_zhan_word = true;
      assertTrue("prefix zan matches zhan words with z/zh", has_zhan_word);
    }
    finally
    {
      PinyinData.set_fuzzy(null);
    }
    assertFalse(PinyinData.fuzzy_enabled());
  }

  @Test
  public void categories_are_bundled()
  {
    // Idioms, poem lines, proverbs and buzzwords from the new corpora.
    String[][] pinyin_word = new String[][]{
      { "yixinyiyi", "一心一意" },          // 成语
      { "xiongyouchengzhu", "胸有成竹" },   // 成语
      { "chuheridangwu", "锄禾日当午" },    // 唐诗
      { "chuangqianmingyueguang", "床前明月光" }, // 名句清单
      { "xianyanbao", "显眼包" },           // 网络热词
      { "yaoyaolingxian", "遥遥领先" },     // 网络热词
      { "santiandayuliangtianshaiwang", "三天打鱼两天晒网" }, // 俗语
    };
    for (String[] pw : pinyin_word)
    {
      List<PinyinData.Entry> r = new ArrayList<PinyinData.Entry>();
      _data.query_exact(pw[0], 50, r);
      assertTrue(pw[1] + " (" + pw[0] + ") in the dictionary",
          has_word(r, pw[1]));
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejects_bad_magic()
  {
    // A downloaded file with garbage content must be rejected by the
    // constructor so the loader can fall back to the bundled dictionary.
    new PinyinData(ByteBuffer.wrap(new byte[64]), null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejects_bigram_mismatch() throws Exception
  {
    // A bigram file whose word table does not match the dictionary's word
    // count is corrupt (the two must always be a matched pack).
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    out.write(new byte[]{ 'P', 'Y', 'B', '1' });
    write_int(out, 1); // version
    write_int(out, _data.word_count() + 1); // wrong n_words
    write_int(out, 0); // n_pairs
    write_int(out, 0); // n_distinct_prev
    new PinyinData(
        ByteBuffer.wrap(Files.readAllBytes(find_asset("py_dict.bin").toPath())),
        ByteBuffer.wrap(out.toByteArray()));
  }

  static void write_int(java.io.ByteArrayOutputStream out, int v)
  {
    out.write((v >> 24) & 0xff);
    out.write((v >> 16) & 0xff);
    out.write((v >> 8) & 0xff);
    out.write(v & 0xff);
  }
}
