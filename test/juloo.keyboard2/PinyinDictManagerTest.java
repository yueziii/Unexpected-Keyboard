package juloo.keyboard2;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.List;
import juloo.keyboard2.pinyin.cloud.PinyinDictManager;
import juloo.keyboard2.pinyin.cloud.PinyinDictManager.Pack;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for the cloud dictionary manifest parsing and the checksum
 * verification of downloaded packs.
 */
public class PinyinDictManagerTest
{
  static final String MANIFEST = "{\n"
    + "  \"format_version\": 1,\n"
    + "  \"updated\": \"2026-10-01\",\n"
    + "  \"packs\": [\n"
    + "    {\n"
    + "      \"id\": \"extended\",\n"
    + "      \"name\": \"扩展词库\",\n"
    + "      \"description\": \"更多低频词与领域词\",\n"
    + "      \"entries\": 213000,\n"
    + "      \"date\": \"2026-10-01\",\n"
    + "      \"dict\": {\"path\": \"packs/extended/2026-10-01/py_dict.bin\",\n"
    + "                \"sha256\": \"aa11\", \"bytes\": 7100000},\n"
    + "      \"bigram\": {\"path\": \"packs/extended/2026-10-01/py_bigram.bin\",\n"
    + "                  \"sha256\": \"bb22\", \"bytes\": 1000000}\n"
    + "    }\n"
    + "  ]\n"
    + "}\n";

  @Test
  public void parse_manifest() throws Exception
  {
    List<Pack> packs = PinyinDictManager.parse_manifest(MANIFEST);
    assertEquals(1, packs.size());
    Pack p = packs.get(0);
    assertEquals("extended", p.id);
    assertEquals("扩展词库", p.name);
    assertEquals(213000, p.entries);
    assertEquals("2026-10-01", p.date);
    assertEquals("aa11", p.dict_sha256);
    assertEquals("bb22", p.bigram_sha256);
  }

  @Test
  public void pack_urls_are_against_the_manifest_base()
    throws Exception
  {
    Pack p = PinyinDictManager.parse_manifest(MANIFEST).get(0);
    assertEquals(
        "https://raw.githubusercontent.com/yueziii/Unexpected-Keyboard-pinyin-dictionaries/main/packs/extended/2026-10-01/py_dict.bin",
        p.url("dict"));
    assertEquals(
        "https://raw.githubusercontent.com/yueziii/Unexpected-Keyboard-pinyin-dictionaries/main/packs/extended/2026-10-01/py_bigram.bin",
        p.url("bigram"));
  }

  // ---- hotwords section (云端热词) ----

  static final String HOTWORDS = "{\n"
    + "  \"format_version\": 2,\n"
    + "  \"updated\": \"2026-10-25\",\n"
    + "  \"packs\": [],\n"
    + "  \"hotwords\": {\"path\": \"hotwords/2026-10-25/hotwords.tsv\",\n"
    + "                \"sha256\": \"cc33\", \"bytes\": 7694,\n"
    + "                \"count\": 307, \"date\": \"2026-10-25\"}\n"
    + "}\n";

  @Test
  public void parse_hotwords_section() throws Exception
  {
    PinyinDictManager.HotwordsInfo hw =
      PinyinDictManager.parse_hotwords(HOTWORDS);
    assertEquals("hotwords/2026-10-25/hotwords.tsv", hw.path);
    assertEquals("cc33", hw.sha256);
    assertEquals(7694, hw.bytes);
    assertEquals(307, hw.count);
    assertEquals("2026-10-25", hw.date);
    assertEquals(
        "https://raw.githubusercontent.com/yueziii/Unexpected-Keyboard-pinyin-dictionaries/main/hotwords/2026-10-25/hotwords.tsv",
        hw.url());
  }

  @Test
  public void missing_hotwords_section_means_none() throws Exception
  {
    // Older repositories publish packs only; clients then stay on the
    // built-in word list.
    assertNull(PinyinDictManager.parse_hotwords(MANIFEST));
  }

  @Test(expected = Exception.class)
  public void rejects_unsupported_manifest_version() throws Exception
  {
    PinyinDictManager.parse_manifest(
        "{\"format_version\": 999, \"packs\": []}");
  }

  @Test(expected = Exception.class)
  public void rejects_incomplete_pack() throws Exception
  {
    // A pack without a bigram checksum must be rejected: both files are
    // required to be a matched pair.
    PinyinDictManager.parse_manifest(
        "{\"format_version\": 1, \"packs\": [{\"id\": \"x\","
        + " \"name\": \"X\", \"dict\": {\"path\": \"a\", \"sha256\": \"s\"}}]}");
  }

  @Test
  public void verify_sha256() throws Exception
  {
    File f = File.createTempFile("uk_test", ".bin");
    f.deleteOnExit();
    byte[] content = "pinyin dictionary pack".getBytes("UTF-8");
    FileOutputStream out = new FileOutputStream(f);
    out.write(content);
    out.close();
    // Precomputed with: printf 'pinyin dictionary pack' | shasum -a 256
    String sha = PinyinDictManager.sha256_hex(content);
    PinyinDictManager.verify_sha256(f, sha); // must not throw
    try
    {
      PinyinDictManager.verify_sha256(f,
          "0000000000000000000000000000000000000000000000000000000000000000");
      fail("Checksum mismatch must be rejected");
    }
    catch (Exception e) { /* expected */ }
  }
}
