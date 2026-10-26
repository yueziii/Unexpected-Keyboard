package juloo.keyboard2;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import juloo.keyboard2.pinyin.PinyinData;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for the cloud dictionary packs produced by scripts/make_pinyin_pack.py
 * (cloud/pinyin-dictionaries/packs/). The packs are gitignored — the tests
 * are skipped when they have not been built locally.
 */
public class PinyinPackTest
{
  static File pack_dir()
  {
    String[] candidates = new String[]{
      "cloud/pinyin-dictionaries/packs",
      "../cloud/pinyin-dictionaries/packs" };
    for (String c : candidates)
    {
      File f = new File(c);
      if (f.isDirectory() && f.listFiles().length > 0)
        return f;
    }
    return null;
  }

  /** The dict/bigram pair of the first pack in the tree. */
  PinyinData load_first_pack() throws Exception
  {
    File packs = pack_dir();
    Assume.assumeTrue("packs not built — run scripts/make_pinyin_pack.py",
        packs != null);
    File pack = packs.listFiles()[0];
    // packs/<id>/<date>/{py_dict.bin,py_bigram.bin}
    File date = pack.listFiles()[0];
    File dict = new File(date, "py_dict.bin");
    File bigram = new File(date, "py_bigram.bin");
    Assume.assumeTrue(dict.isFile() && bigram.isFile());
    return new PinyinData(
        ByteBuffer.wrap(Files.readAllBytes(dict.toPath())).slice(),
        ByteBuffer.wrap(Files.readAllBytes(bigram.toPath())).slice());
  }

  @Test
  public void pack_is_a_valid_matched_pair() throws Exception
  {
    PinyinData data = load_first_pack();
    // The constructor already rejects a bigram word table that does not
    // match the dictionary; look up a word to make sure it answers.
    List<PinyinData.Entry> r = new ArrayList<PinyinData.Entry>();
    data.query_exact("nihao", 10, r);
    assertFalse(r.isEmpty());
  }

  @Test
  public void bundled_dictionary_ships_the_extended_pack() throws Exception
  {
    // The extended pack is bundled in the APK (the cloud update path moved
    // to the hotwords overlay), so the two are the same build; the pack
    // must never be smaller than the built-in dictionary.
    PinyinData data = load_first_pack();
    PinyinData bundled = PinyinDataTest.load_real_data();
    assertTrue("cloud pack >= the built-in dictionary",
        data.word_count() >= bundled.word_count());
  }
}
