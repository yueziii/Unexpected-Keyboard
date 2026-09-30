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
}
