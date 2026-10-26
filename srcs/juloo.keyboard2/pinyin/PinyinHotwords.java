package juloo.keyboard2.pinyin;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cloud-updated hotword overlay (云端热词).
 *
 * A small list of trending words downloaded from the cloud dictionary
 * repository (see PinyinDictManager.install_hotwords), layered on top of
 * the compiled dictionary so fresh buzzwords reach users without an app
 * release. The file format is lines of "pinyin\tword\tfreq" plus
 * "@abbrev\tletters\tword" abbreviation lines and '#' comments — the same
 * shape as the text dictionary the binaries are compiled from.
 *
 * The overlay ranks above every dictionary entry: without it the built-in
 * dictionary already carries the buzzwords that were current at build
 * time, so an installed file only needs to add what came after. Lookups
 * mirror PinyinData's (exact pinyin, prefix extensions, 简拼 initials);
 * the returned entries are synthetic PinyinData.Entry values with
 * word_id -1 (no bigram context), which the engine merges like the
 * user-learned words.
 */
public final class PinyinHotwords
{
  /** Synthetic frequency of overlay entries: above the dictionary's
      frequency scale (≤ 1000000) so a matching hotword outranks every
      ordinary entry, while [rank] (position in the file) breaks ties. */
  public static final int HOTWORD_FREQ = 2000000;

  private final Map<String, List<PinyinData.Entry>> _by_pinyin;
  private final Map<String, List<PinyinData.Entry>> _by_abbrev;
  private final int _count;

  private PinyinHotwords(Map<String, List<PinyinData.Entry>> by_pinyin,
      Map<String, List<PinyinData.Entry>> by_abbrev, int count)
  {
    _by_pinyin = by_pinyin;
    _by_abbrev = by_abbrev;
    _count = count;
  }

  public static PinyinHotwords empty()
  {
    return new PinyinHotwords(new HashMap<String, List<PinyinData.Entry>>(),
        new HashMap<String, List<PinyinData.Entry>>(), 0);
  }

  // ---- singleton ---------------------------------------------------------

  private static PinyinHotwords _instance = empty();

  /** Pre-load the installed overlay. Call once at IME startup. */
  public static void init(Context ctx)
  {
    if (_instance.count() > 0)
      return;
    _instance = load(ctx);
  }

  /** Swap in whatever overlay file is currently installed. Called after
      PinyinDictManager installs or removes the hotwords file. */
  public static void reload(Context ctx)
  {
    _instance = load(ctx);
  }

  /** The active overlay. Never null; empty when nothing is installed
      (the built-in dictionary already carries the buzzwords). */
  public static PinyinHotwords get()
  {
    return _instance;
  }

  /** Replace the instance (used by tests). */
  public static void set_instance(PinyinHotwords h) { _instance = h; }

  private static PinyinHotwords load(Context ctx)
  {
    if (ctx == null)
      return empty();
    try
    {
      File f = new File(new File(ctx.getFilesDir(), "pinyin"),
          "hotwords.tsv");
      if (!f.isFile())
        return empty();
      return parse(read_file(f));
    }
    catch (Exception e)
    {
      return empty();
    }
  }

  private static String read_file(File f) throws IOException
  {
    InputStream in = new FileInputStream(f);
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    byte[] tmp = new byte[8192];
    int n;
    while ((n = in.read(tmp)) > 0)
      out.write(tmp, 0, n);
    in.close();
    return new String(out.toByteArray(), "UTF-8");
  }

  // ---- parsing -----------------------------------------------------------

  /** Parse the overlay file format. Lenient about individual lines (bad
      lines are skipped) but throws when nothing usable remains, so a
      truncated or corrupt download is rejected before it replaces a
      working overlay. */
  public static PinyinHotwords parse(String content) throws Exception
  {
    Map<String, List<PinyinData.Entry>> by_pinyin =
      new HashMap<String, List<PinyinData.Entry>>();
    Map<String, List<PinyinData.Entry>> by_abbrev =
      new HashMap<String, List<PinyinData.Entry>>();
    // Word → best pinyin (first occurrence = best rank), for resolving
    // abbreviation pairs to the word's pinyin.
    Map<String, String> word_pinyin = new HashMap<String, String>();
    int rank = 0;
    int count = 0;
    // Abbreviation pairs rank with the words: resolved after the file is
    // read, so the pair may precede its word line.
    List<String[]> pending_abbrevs = new ArrayList<String[]>();
    for (String line : content.split("\n"))
    {
      line = line.trim();
      if (line.isEmpty() || line.startsWith("#"))
        continue;
      if (line.startsWith("@abbrev\t"))
      {
        String[] parts = line.substring("@abbrev\t".length()).split("\t");
        if (parts.length == 2 && valid_abbrev(parts[0])
            && !parts[1].isEmpty())
          pending_abbrevs.add(new String[]{parts[0], parts[1]});
        continue;
      }
      String[] parts = line.split("\t");
      if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty()
          || !valid_pinyin(parts[0]))
        continue;
      try
      {
        Integer.parseInt(parts[2].trim());
      }
      catch (NumberFormatException e)
      {
        continue;
      }
      // The file's frequency column is informational; the overlay ranks by
      // file position with a synthetic frequency above the dictionary's.
      add_entry(by_pinyin, parts[0],
          new PinyinData.Entry(parts[0], parts[1], HOTWORD_FREQ - rank++,
            -1));
      if (!word_pinyin.containsKey(parts[1]))
        word_pinyin.put(parts[1], parts[0]);
      count++;
    }
    for (String[] ab : pending_abbrevs)
    {
      String py = word_pinyin.containsKey(ab[1])
        ? word_pinyin.get(ab[1]) : ab[1];
      add_entry(by_abbrev, ab[0],
          new PinyinData.Entry(py, ab[1], HOTWORD_FREQ - rank, -1));
    }
    if (count == 0)
      throw new Exception("Hotwords file has no usable entries");
    return new PinyinHotwords(by_pinyin, by_abbrev, count);
  }

  static void add_entry(Map<String, List<PinyinData.Entry>> index,
      String key, PinyinData.Entry e)
  {
    List<PinyinData.Entry> l = index.get(key);
    if (l == null)
    {
      l = new ArrayList<PinyinData.Entry>();
      index.put(key, l);
    }
    l.add(e);
  }

  /** Overlay keys must be plain lowercase pinyin letters — the engine
      matches them against the typed letters. */
  static boolean valid_pinyin(String pinyin)
  {
    for (int i = 0; i < pinyin.length(); i++)
    {
      char c = pinyin.charAt(i);
      if (c < 'a' || c > 'z')
        return false;
    }
    return pinyin.length() > 0;
  }

  static boolean valid_abbrev(String letters)
  {
    for (int i = 0; i < letters.length(); i++)
    {
      char c = letters.charAt(i);
      if (c < 'a' || c > 'z')
        return false;
    }
    return letters.length() > 0;
  }

  // ---- lookups -----------------------------------------------------------

  /** Number of entries (words; abbreviations excluded) in the overlay. */
  public int count() { return _count; }

  public boolean has_abbrev() { return !_by_abbrev.isEmpty(); }

  /** Entries whose full pinyin equals [pinyin], in file (rank) order. */
  public void query_exact(String pinyin, List<PinyinData.Entry> out)
  {
    List<PinyinData.Entry> l = _by_pinyin.get(pinyin);
    if (l != null)
      out.addAll(ranked(l));
  }

  /** Entries whose pinyin starts with [letters] and is longer — the
      progressive-extension candidates for a partially typed word. */
  public void query_prefix(String letters, int limit,
      List<PinyinData.Entry> out)
  {
    List<PinyinData.Entry> matches = new ArrayList<PinyinData.Entry>();
    for (Map.Entry<String, List<PinyinData.Entry>> e :
        _by_pinyin.entrySet())
    {
      if (e.getKey().length() > letters.length()
          && e.getKey().startsWith(letters))
        matches.addAll(ranked(e.getValue()));
    }
    matches = ranked(matches);
    if (matches.size() > limit)
      matches = matches.subList(0, limit);
    out.addAll(matches);
  }

  /** Entries whose pinyin initials exactly spell [letters] (yyds -> 永远的神). */
  public void query_abbrev_exact(String letters, int limit,
      List<PinyinData.Entry> out)
  {
    List<PinyinData.Entry> l = _by_abbrev.get(letters);
    if (l != null)
      out.addAll(ranked(l));
  }

  /** Re-rank entries by their file position: the parse assigns descending
      synthetic frequencies, the map may hold several pinyin variants per
      word, so sorting by frequency restores the curated order. */
  static List<PinyinData.Entry> ranked(List<PinyinData.Entry> l)
  {
    if (l.size() == 1)
      return l;
    List<PinyinData.Entry> out = new ArrayList<PinyinData.Entry>(l);
    java.util.Collections.sort(out, new java.util.Comparator<PinyinData.Entry>()
      {
        public int compare(PinyinData.Entry a, PinyinData.Entry b)
        {
          return Integer.compare(b.freq, a.freq);
        }
      });
    return out;
  }
}
