package juloo.keyboard2;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import juloo.keyboard2.voice.VoiceModelManager;
import juloo.keyboard2.voice.VoiceModelManager.Model;
import juloo.keyboard2.voice.VoiceModelManager.ModelSpec;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for the offline voice model manifest parsing and the installed
 * model descriptor.
 */
public class VoiceModelManagerTest
{
  static final String MANIFEST = "{\n"
    + "  \"format_version\": 1,\n"
    + "  \"models\": [\n"
    + "    {\n"
    + "      \"id\": \"paraformer-tri\",\n"
    + "      \"name\": \"标准档\",\n"
    + "      \"type\": \"streaming_paraformer\",\n"
    + "      \"sample_rate\": 16000,\n"
    + "      \"feature_dim\": 80,\n"
    + "      \"bytes\": 238506638,\n"
    + "      \"files\": [\n"
    + "        {\"role\": \"encoder\", \"path\": \"encoder.int8.onnx\",\n"
    + "         \"sha256\": \"aa11\", \"bytes\": 166362800,\n"
    + "         \"urls\": [\"https://hf-mirror.com/a\", \"https://huggingface.co/a\"]},\n"
    + "        {\"role\": \"decoder\", \"path\": \"decoder.int8.onnx\",\n"
    + "         \"sha256\": \"bb22\", \"bytes\": 72062549,\n"
    + "         \"urls\": [\"https://hf-mirror.com/b\"]},\n"
    + "        {\"role\": \"tokens\", \"path\": \"tokens.txt\",\n"
    + "         \"sha256\": \"cc33\", \"bytes\": 81289,\n"
    + "         \"urls\": [\"https://hf-mirror.com/c\"]}\n"
    + "      ]\n"
    + "    }\n"
    + "  ]\n"
    + "}\n";

  @Test
  public void parse_manifest() throws Exception
  {
    List<Model> models = VoiceModelManager.parse_manifest(MANIFEST);
    assertEquals(1, models.size());
    Model m = models.get(0);
    assertEquals("paraformer-tri", m.id);
    assertEquals("标准档", m.name);
    assertEquals("streaming_paraformer", m.type);
    assertEquals(16000, m.sampleRate);
    assertEquals(80, m.featureDim);
    assertEquals(238506638, m.bytes);
    assertEquals(3, m.files.size());
    assertEquals("encoder", m.files.get(0).role);
    assertEquals("encoder.int8.onnx", m.files.get(0).path);
    assertEquals("aa11", m.files.get(0).sha256);
    assertEquals(2, m.files.get(0).urls.size());
  }

  @Test
  public void unsupported_version_rejected()
  {
    try
    {
      VoiceModelManager.parse_manifest("{\"format_version\": 2, \"models\": []}");
      fail("Expected an exception");
    }
    catch (Exception e) { /* expected */ }
  }

  /** The manifest bundled in the APK parses and every file has a valid
      sha256 and at least one mirror URL. Paths are relative to the project
      root like in [PinyinPackTest]. */
  @Test
  public void bundled_manifest_is_valid() throws Exception
  {
    String[] candidates = {
      "assets/voice-models.json",
      "../assets/voice-models.json" };
    File manifest = null;
    for (String c : candidates)
    {
      File f = new File(c);
      if (f.isFile())
        manifest = f;
    }
    org.junit.Assume.assumeTrue(manifest != null);
    String json = new String(Files.readAllBytes(manifest.toPath()), "UTF-8");
    List<Model> models = VoiceModelManager.parse_manifest(json);
    assertTrue("expected at least one model", models.size() >= 1);
    for (Model m : models)
    {
      assertFalse(m.id.isEmpty());
      assertTrue(m.type.equals("streaming_paraformer")
          || m.type.equals("streaming_zipformer"));
      assertTrue(m.bytes > 0);
      assertTrue("sample rate", m.sampleRate == 16000);
      assertTrue("feature dim", m.featureDim == 80);
      long sum = 0;
      for (VoiceModelManager.ModelFile f : m.files)
      {
        assertEquals("sha256 is 64 hex chars for " + f.path,
            64, f.sha256.length());
        assertTrue("urls for " + f.path, f.urls.size() >= 1);
        sum += f.bytes;
      }
      assertEquals("total bytes for " + m.id, sum, m.bytes);
    }
  }

  /** The descriptor written on install round-trips through [ModelSpec],
      mapping roles to files inside the model directory. */
  @Test
  public void descriptor_roundtrip() throws Exception
  {
    File dir = Files.createTempDirectory("voice-model").toFile();
    try
    {
      Model m = VoiceModelManager.parse_manifest(MANIFEST).get(0);
      VoiceModelManager.write_descriptor(dir, m);
      File desc = new File(dir, "model.json");
      assertTrue(desc.isFile());
      String json = new String(Files.readAllBytes(desc.toPath()), "UTF-8");
      ModelSpec spec = new ModelSpec(dir, new JSONObject(json));
      assertEquals("paraformer-tri", spec.id);
      assertEquals("streaming_paraformer", spec.type);
      assertEquals(16000, spec.sampleRate);
      assertEquals(3, spec.files.size());
      assertEquals(new File(dir, "encoder.int8.onnx"), spec.file("encoder"));
      assertEquals(new File(dir, "tokens.txt"), spec.file("tokens"));
      assertNull(spec.file("joiner"));
    }
    finally
    {
      delete_recursive(dir);
    }
  }

  /** A directory holding every manifest file with the expected checksum,
      plus the descriptor, counts as installed; anything else does not.
      This is what lets install() re-select a model without downloading. */
  @Test
  public void matches_manifest_checks_files_and_checksums() throws Exception
  {
    File dir = Files.createTempDirectory("voice-model").toFile();
    try
    {
      String enc = "encoder-bytes";
      String dec = "decoder-bytes";
      String tok = "tokens-bytes";
      Model m = VoiceModelManager.parse_manifest(manifest_with_checksums(
            sha256(enc), sha256(dec), sha256(tok))).get(0);
      // Nothing installed yet, then incomplete installs.
      assertFalse(VoiceModelManager.matches_manifest(dir, m));
      write(dir, "model.json", "{}");
      assertFalse(VoiceModelManager.matches_manifest(dir, m));
      write(dir, "encoder.int8.onnx", enc);
      assertFalse(VoiceModelManager.matches_manifest(dir, m));
      // Complete install matches.
      write(dir, "decoder.int8.onnx", dec);
      write(dir, "tokens.txt", tok);
      assertTrue(VoiceModelManager.matches_manifest(dir, m));
      // A corrupted or truncated file no longer matches.
      write(dir, "tokens.txt", tok + "!");
      assertFalse(VoiceModelManager.matches_manifest(dir, m));
      write(dir, "tokens.txt", tok);
      assertTrue(VoiceModelManager.matches_manifest(dir, m));
      // Without the descriptor the install is not recognized.
      new File(dir, "model.json").delete();
      assertFalse(VoiceModelManager.matches_manifest(dir, m));
    }
    finally
    {
      delete_recursive(dir);
    }
  }

  /** A manifest for the same three files as [MANIFEST] with the given
      encoder/decoder/tokens checksums. */
  static String manifest_with_checksums(String enc_sha, String dec_sha,
      String tok_sha)
  {
    return "{\n"
      + "  \"format_version\": 1,\n"
      + "  \"models\": [\n"
      + "    {\n"
      + "      \"id\": \"paraformer-tri\",\n"
      + "      \"name\": \"标准档\",\n"
      + "      \"type\": \"streaming_paraformer\",\n"
      + "      \"files\": [\n"
      + "        {\"role\": \"encoder\", \"path\": \"encoder.int8.onnx\",\n"
      + "         \"sha256\": \"" + enc_sha + "\", \"bytes\": 14,\n"
      + "         \"urls\": [\"https://hf-mirror.com/a\"]},\n"
      + "        {\"role\": \"decoder\", \"path\": \"decoder.int8.onnx\",\n"
      + "         \"sha256\": \"" + dec_sha + "\", \"bytes\": 13,\n"
      + "         \"urls\": [\"https://hf-mirror.com/b\"]},\n"
      + "        {\"role\": \"tokens\", \"path\": \"tokens.txt\",\n"
      + "         \"sha256\": \"" + tok_sha + "\", \"bytes\": 12,\n"
      + "         \"urls\": [\"https://hf-mirror.com/c\"]}\n"
      + "      ]\n"
      + "    }\n"
      + "  ]\n"
      + "}\n";
  }

  static String sha256(String content) throws Exception
  {
    StringBuilder b = new StringBuilder();
    for (byte x : java.security.MessageDigest.getInstance("SHA-256")
          .digest(content.getBytes("UTF-8")))
    {
      b.append("0123456789abcdef".charAt((x >> 4) & 0xf))
        .append("0123456789abcdef".charAt(x & 0xf));
    }
    return b.toString();
  }

  static void write(File dir, String name, String content) throws Exception
  {
    Files.write(new File(dir, name).toPath(), content.getBytes("UTF-8"));
  }

  static void delete_recursive(File f)
  {
    if (f.isDirectory())
      for (File c : f.listFiles())
        delete_recursive(c);
    f.delete();
  }
}
