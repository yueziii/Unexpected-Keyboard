package juloo.keyboard2;

import juloo.keyboard2.pinyin.PinyinData;
import juloo.keyboard2.pinyin.PinyinHotwords;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for the cloud hotword overlay: the lenient line parser, the rank
 * ordering (file position, synthetic frequency above the dictionary's
 * scale) and the three lookup shapes the engine uses.
 */
public class PinyinHotwordsTest
{
  static final String SAMPLE = ""
    + "# Unexpected Keyboard hotwords (云端热词)\n"
    + "# date: 2026-10-25\n"
    + "neijuan\t内卷\t5000\n"
    + "xianyanbao\t显眼包\t5000\n"
    + "broken line without tabs\n"
    + "badpinyin!\t坏词\t5000\n"
    + "tayanta\t太_yaml\tNaN\n"
    + "@abbrev\tyyds\t永远的神\n"
    + "yongyuandeshen\t永远的神\t5000\n"
    + "@abbrev\txswl\t笑死我了\n"
    + "xiaosiwole\t笑死我了\t5000\n";

  @Test
  public void parse_counts_entries_and_skips_bad_lines() throws Exception
  {
    PinyinHotwords hw = PinyinHotwords.parse(SAMPLE);
    // 4 usable word lines (太_yaml has a non-numeric frequency, one line
    // has no tabs, 坏词 has non-pinyin letters — both skipped).
    assertEquals(4, hw.count());
    assertTrue(hw.has_abbrev());
  }

  @Test
  public void query_exact_ranks_by_file_position() throws Exception
  {
    PinyinHotwords hw = PinyinHotwords.parse(SAMPLE);
    List<PinyinData.Entry> out = new ArrayList<PinyinData.Entry>();
    hw.query_exact("xiaosiwole", out);
    assertEquals(1, out.size());
    assertEquals("笑死我了", out.get(0).word);
    // Synthetic frequency above the dictionary's 1000000 scale; earlier
    // file positions rank higher. Ranks are counted over the usable word
    // lines only and this is the fourth one, after 内卷, 显眼包 and
    // 永远的神; the unparsable lines in between consume none.
    assertEquals(PinyinHotwords.HOTWORD_FREQ - 3, out.get(0).freq);
  }

  @Test
  public void query_exact_misses_unknown_pinyin() throws Exception
  {
    PinyinHotwords hw = PinyinHotwords.parse(SAMPLE);
    List<PinyinData.Entry> out = new ArrayList<PinyinData.Entry>();
    hw.query_exact("bugcizai", out);
    assertTrue(out.isEmpty());
  }

  @Test
  public void query_prefix_offers_partially_typed_words() throws Exception
  {
    PinyinHotwords hw = PinyinHotwords.parse(SAMPLE);
    List<PinyinData.Entry> out = new ArrayList<PinyinData.Entry>();
    hw.query_prefix("yongyuan", 6, out);
    assertEquals(1, out.size());
    assertEquals("永远的神", out.get(0).word);
    assertEquals("yongyuandeshen", out.get(0).pinyin);
  }

  @Test
  public void query_abbrev_exact_resolves_pairs() throws Exception
  {
    PinyinHotwords hw = PinyinHotwords.parse(SAMPLE);
    List<PinyinData.Entry> out = new ArrayList<PinyinData.Entry>();
    hw.query_abbrev_exact("yyds", 6, out);
    assertEquals(1, out.size());
    assertEquals("永远的神", out.get(0).word);
  }

  @Test(expected = Exception.class)
  public void rejects_content_without_usable_entries() throws Exception
  {
    PinyinHotwords.parse("# only comments\n\nbroken line\n");
  }

  @Test
  public void empty_instance_is_inert()
  {
    PinyinHotwords hw = PinyinHotwords.empty();
    assertEquals(0, hw.count());
    assertFalse(hw.has_abbrev());
    List<PinyinData.Entry> out = new ArrayList<PinyinData.Entry>();
    hw.query_exact("neijuan", out);
    hw.query_prefix("nei", 6, out);
    hw.query_abbrev_exact("yyds", 6, out);
    assertTrue(out.isEmpty());
  }
}
