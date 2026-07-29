package juloo.keyboard2.pinyin;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in pinyin dictionary used as a fallback when no cdict pinyin dictionary
 * is installed. Contains common single-character and multi-character words
 * mapped by their pinyin (without tones, without spaces).
 *
 * The dictionary is loaded from assets/pinyin_dict.txt at startup. If the
 * asset is not available, a small hardcoded fallback is used.
 *
 * This is a simple prefix-based lookup: given a pinyin string like "nihao",
 * it returns matching candidates sorted by frequency.
 */
public final class PinyinDict
{
  /** A single dictionary entry. */
  public static class Entry implements Comparable<Entry>
  {
    public final String pinyin;
    public final String word;
    public final int freq;

    public Entry(String p, String w, int f)
    {
      pinyin = p;
      word = w;
      freq = f;
    }

    /** Sort by descending frequency, then by pinyin. */
    @Override
    public int compareTo(Entry o)
    {
      if (freq != o.freq)
        return o.freq - freq;
      return pinyin.compareTo(o.pinyin);
    }
  }

  private final List<Entry> _entries;
  /** Index from pinyin prefix to entry indices, for fast prefix lookup. */
  private final Map<String, List<Integer>> _prefix_index;

  public PinyinDict(List<Entry> entries)
  {
    _entries = entries;
    _prefix_index = new HashMap<String, List<Integer>>();
    // Build a simple index: for each entry, index by the full pinyin and
    // by common prefixes (first 1-6 chars).
    for (int i = 0; i < entries.size(); i++)
    {
      String py = entries.get(i).pinyin;
      // Index by full pinyin and by increasing prefixes up to the full length
      for (int len = 1; len <= py.length(); len++)
      {
        String prefix = py.substring(0, len);
        List<Integer> idx = _prefix_index.get(prefix);
        if (idx == null)
        {
          idx = new ArrayList<Integer>();
          _prefix_index.put(prefix, idx);
        }
        idx.add(i);
      }
    }
  }

  /** Query candidates for a pinyin string. Returns up to [max_count] entries
      that match the query as a prefix, sorted by frequency.
      Exact matches are prioritized over longer prefix matches. */
  public List<Entry> query(String pinyin, int max_count)
  {
    List<Integer> indices = _prefix_index.get(pinyin);
    if (indices == null)
    {
      // No exact prefix match — try progressively shorter prefixes
      for (int len = pinyin.length() - 1; len >= 1; len--)
      {
        indices = _prefix_index.get(pinyin.substring(0, len));
        if (indices != null)
          break;
      }
      if (indices == null)
        return new ArrayList<Entry>();
    }
    // Collect exact matches first, then prefix matches
    List<Entry> exact = new ArrayList<Entry>();
    List<Entry> prefix = new ArrayList<Entry>();
    for (int idx : indices)
    {
      Entry e = _entries.get(idx);
      if (e.pinyin.equals(pinyin))
        exact.add(e);
      else if (e.pinyin.startsWith(pinyin))
        prefix.add(e);
    }
    // Sort each list by frequency
    java.util.Collections.sort(exact);
    java.util.Collections.sort(prefix);
    // Combine: exact matches first, then prefix matches
    List<Entry> results = new ArrayList<Entry>();
    for (Entry e : exact)
    {
      if (results.size() >= max_count)
        break;
      results.add(e);
    }
    for (Entry e : prefix)
    {
      if (results.size() >= max_count)
        break;
      results.add(e);
    }
    return results;
  }

  /** The dictionary instance loaded from assets, or the small fallback. */
  private static PinyinDict _instance = null;

  /** Pre-load the dictionary from assets. Call this early (e.g. in IME
      onCreate) so that built_in() returns the full dictionary.
      Safe to call multiple times; only loads once. */
  public static void init(Context ctx)
  {
    if (_instance != null)
      return;
    if (ctx != null)
    {
      PinyinDict loaded = load_from_assets(ctx);
      if (loaded != null)
      {
        _instance = loaded;
        return;
      }
    }
    _instance = new PinyinDict(build_fallback());
  }

  /** Get the built-in dictionary. Loads from assets/pinyin_dict.txt on first
      call; falls back to a small hardcoded list if the asset is missing. */
  public static PinyinDict built_in()
  {
    return built_in(null);
  }

  /** Get the built-in dictionary, using the given Context to load from assets. */
  public static PinyinDict built_in(Context ctx)
  {
    if (_instance != null)
      return _instance;
    if (ctx != null)
    {
      PinyinDict loaded = load_from_assets(ctx);
      if (loaded != null)
      {
        _instance = loaded;
        return _instance;
      }
    }
    _instance = new PinyinDict(build_fallback());
    return _instance;
  }

  /** Load the dictionary from assets/pinyin_dict.txt.
      Format: pinyin\tword\tfreq (one per line, # for comments). */
  private static PinyinDict load_from_assets(Context ctx)
  {
    List<Entry> entries = new ArrayList<Entry>();
    try
    {
      BufferedReader reader = new BufferedReader(
          new InputStreamReader(ctx.getAssets().open("pinyin_dict.txt"), "UTF-8"));
      String line;
      while ((line = reader.readLine()) != null)
      {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#"))
          continue;
        String[] parts = line.split("\t");
        if (parts.length < 2)
          parts = line.split("\\s+");
        if (parts.length < 2)
          continue;
        String py = parts[0];
        String word = parts[1];
        int freq = (parts.length >= 3) ? Integer.parseInt(parts[2].trim()) : 100;
        entries.add(new Entry(py, word, freq));
      }
      reader.close();
    }
    catch (Exception e)
    {
      return null;
    }
    if (entries.isEmpty())
      return null;
    return new PinyinDict(entries);
  }

  /** Small hardcoded fallback dictionary used when the asset file is missing. */
  private static List<Entry> build_fallback()
  {
    List<Entry> entries = new ArrayList<Entry>();
    String[] raw = FALLBACK_WORDS.split("\n");
    for (String line : raw)
    {
      line = line.trim();
      if (line.isEmpty() || line.startsWith("#"))
        continue;
      String[] parts = line.split("\\s+");
      if (parts.length < 2)
        continue;
      String py = parts[0];
      String word = parts[1];
      int freq = (parts.length >= 3) ? Integer.parseInt(parts[2]) : 100;
      entries.add(new Entry(py, word, freq));
    }
    return entries;
  }

  /** Small fallback word list. The full dictionary is in assets/pinyin_dict.txt
      (~78k entries covering 20900+ characters and 50k+ words). */
  static final String FALLBACK_WORDS = ""
    + "de 的 9999\n"
    + "le 了 9900\n"
    + "shi 是 9800\n"
    + "zai 在 9700\n"
    + "wo 我 9600\n"
    + "ni 你 9500\n"
    + "ta 他 9400\n"
    + "zhe 这 9300\n"
    + "zhong 中 9200\n"
    + "da 大 9100\n"
    + "lai 来 9000\n"
    + "shang 上 8900\n"
    + "ge 个 8800\n"
    + "guo 国 8700\n"
    + "hao 好 8600\n"
    + "nihao 你好 9500\n"
    + "xiexie 谢谢 9000\n"
    + "zaijian 再见 8500\n"
    + "zhongguo 中国 8800\n"
    + "jintian 今天 9000\n"
    + "shenme 什么 9000\n"
    + "zenme 怎么 8500\n"
    + "xianzai 现在 7500\n"
    + "cou 凑 1000\n"
    + "xing 行 1600\n"
    + "ming 明 1600\n"
    + "qing 请 1300\n"
    + "qing 情 1500\n"
    + "qing 清 1400\n"
    + "bing 并 1500\n"
    ;
}
