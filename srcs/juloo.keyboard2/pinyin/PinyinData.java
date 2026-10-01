package juloo.keyboard2.pinyin;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Memory-mapped pinyin dictionary and bigram model.
 *
 * The data is compiled by scripts/gen_pinyin_bin.py into two assets:
 *
 * py_dict.bin — words and single characters sorted by pinyin for prefix
 * binary search, plus a word table assigning an integer id to every distinct
 * word (the bigram file references words by id).
 *
 * py_bigram.bin — counts of adjacent word pairs observed in a segmented
 * corpus, sorted by (prev_id, cur_id) for binary search. The engine adds a
 * transition bonus for observed pairs when converting whole sentences.
 *
 * See the generator script for the exact binary formats. The formats are
 * parsed with plain ByteBuffer reads so the same code path works with a
 * heap buffer (unit tests, built-in fallback).
 */
public final class PinyinData
{
  public static final class Entry
  {
    public final String pinyin;
    public final String word;
    public final int freq;
    public final int word_id;

    Entry(String p, String w, int f, int id)
    {
      pinyin = p;
      word = w;
      freq = f;
      word_id = id;
    }
  }

  private static PinyinData _instance = null;

  /** Pre-load the dictionary. Call once at IME startup. Safe to call twice. */
  public static void init(Context ctx)
  {
    if (_instance != null)
      return;
    _instance = load(ctx);
  }

  /** Swap in whatever dictionary is currently installed on disk. Called
      after PinyinDictManager installs or removes a pack, without restarting
      the input method. */
  public static void reload(Context ctx)
  {
    _instance = load(ctx);
  }

  /** Loading order: a dictionary pack installed by PinyinDictManager, then
      the bundled assets, then the tiny built-in fallback. Never returns
      null. Invalid files are skipped, never trusted. */
  private static PinyinData load(Context ctx)
  {
    if (ctx != null)
    {
      PinyinData d = load_installed(ctx);
      if (d != null)
        return d;
      d = load_from_assets(ctx);
      if (d != null)
        return d;
    }
    return new PinyinData(fallback_buffer(), fallback_bigram());
  }

  /** Load the dictionary pack installed by PinyinDictManager, if any. */
  private static PinyinData load_installed(Context ctx)
  {
    try
    {
      File dir = new File(ctx.getFilesDir(), "pinyin");
      File dict = new File(dir, "py_dict.bin");
      File bigram = new File(dir, "py_bigram.bin");
      if (!dict.isFile() || !bigram.isFile())
        return null;
      return new PinyinData(read_file(dict), read_file(bigram));
    }
    catch (Exception e)
    {
      return null;
    }
  }

  private static ByteBuffer read_file(File f) throws IOException
  {
    InputStream in = new FileInputStream(f);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] tmp = new byte[8192];
    int n;
    while ((n = in.read(tmp)) > 0)
      out.write(tmp, 0, n);
    in.close();
    return ByteBuffer.wrap(out.toByteArray()).slice();
  }

  /** The loaded data. Never null: falls back to a tiny built-in dictionary
      when the assets are missing (e.g. before init, or unit tests). */
  public static PinyinData get()
  {
    if (_instance == null)
      _instance = new PinyinData(fallback_buffer(), fallback_bigram());
    return _instance;
  }

  /** Replace the instance (used by tests). */
  public static void set_instance(PinyinData d) { _instance = d; }

  /** Load the assets through a memory-mapped channel. */
  private static PinyinData load_from_assets(Context ctx)
  {
    try
    {
      ByteBuffer dict = mmap_asset(ctx, "py_dict.bin");
      ByteBuffer bigram = mmap_asset(ctx, "py_bigram.bin");
      if (dict == null)
        return null;
      return new PinyinData(dict, bigram);
    }
    catch (Exception e)
    {
      return null;
    }
  }

  private static ByteBuffer mmap_asset(Context ctx, String name)
    throws IOException
  {
    InputStream in = ctx.getAssets().open(name);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] tmp = new byte[8192];
    int n;
    while ((n = in.read(tmp)) > 0)
      out.write(tmp, 0, n);
    in.close();
    byte[] bytes = out.toByteArray();
    ByteBuffer b = ByteBuffer.wrap(bytes);
    b.position(0);
    return b.slice();
  }

  private final ByteBuffer _dict;
  private final int _n_entries;
  private final int _entries_base;
  private final int _entries_end;
  private final int[] _py_offsets; // first-entry offset per distinct pinyin
  private final int _n_words;
  private final int[] _word_offsets; // byte offset of each word string
  private final int _words_base;

  private final ByteBuffer _bigram; // null when no model is available
  private final int _n_pairs;
  private final int _pairs_base;
  private final int _pairs_end;
  private final int[] _prev_offsets; // first-pair offset per distinct prev

  /** Parse a dictionary buffer and a bigram buffer (either may be null for
      the bigram). Buffers must start at the magic bytes. */
  public PinyinData(ByteBuffer dict, ByteBuffer bigram)
  {
    _dict = dict.slice();
    _dict.order(java.nio.ByteOrder.BIG_ENDIAN);
    check_magic(_dict, "PYD1");
    int version = _dict.getInt();
    if (version != 1)
      throw new IllegalArgumentException("Unsupported py_dict version");
    _n_entries = _dict.getInt();
    _entries_base = _dict.position();
    // Walk the entry region to find where the pinyin index starts.
    int pos = _entries_base;
    for (int i = 0; i < _n_entries; i++)
      pos = skip_entry(pos);
    _entries_end = pos;
    _dict.position(pos);
    int n_py = _dict.getInt();
    _py_offsets = new int[n_py];
    for (int i = 0; i < n_py; i++)
      _py_offsets[i] = _dict.getInt();
    int words_region = _dict.position();
    _n_words = _dict.getInt();
    _words_base = _dict.position();
    _word_offsets = new int[_n_words];
    int wpos = _words_base;
    for (int i = 0; i < _n_words; i++)
    {
      _word_offsets[i] = wpos;
      wpos += 2 + _dict.getShort(wpos);
    }

    if (bigram != null)
    {
      _bigram = bigram.slice();
      _bigram.order(java.nio.ByteOrder.BIG_ENDIAN);
      check_magic(_bigram, "PYB1");
      int bversion = _bigram.getInt();
      int bwords = _bigram.getInt();
      if (bversion != 1 || bwords != _n_words)
        throw new IllegalArgumentException("Bigram/word table mismatch");
      _n_pairs = _bigram.getInt();
      _pairs_base = _bigram.position();
      _pairs_end = _pairs_base + _n_pairs * 10;
      _bigram.position(_pairs_end);
      int n_prev = _bigram.getInt();
      _prev_offsets = new int[n_prev];
      for (int i = 0; i < n_prev; i++)
        _prev_offsets[i] = _bigram.getInt();
    }
    else
    {
      _bigram = null;
      _n_pairs = 0;
      _pairs_base = 0;
      _pairs_end = 0;
      _prev_offsets = new int[0];
    }
  }

  private static void check_magic(ByteBuffer b, String magic)
  {
    byte[] m = new byte[4];
    b.get(m);
    if (m[0] != magic.charAt(0) || m[1] != magic.charAt(1)
        || m[2] != magic.charAt(2) || m[3] != magic.charAt(3))
      throw new IllegalArgumentException("Bad magic, expected " + magic);
  }

  // ---- entry accessors -------------------------------------------------

  /** Byte offset of entry [i] is not stored; entries are reached through
      the pinyin index instead. Read the pinyin at an entry offset. */
  private int entry_py_len(int off) { return _dict.get(off) & 0xff; }

  private int entry_word_len(int off)
  {
    return _dict.getShort(off + 1 + entry_py_len(off)) & 0xffff;
  }

  private int entry_freq(int off)
  {
    return _dict.getInt(off + 1 + entry_py_len(off) + 2 + entry_word_len(off));
  }

  private int entry_word_id(int off)
  {
    return _dict.getInt(off + 1 + entry_py_len(off) + 2
        + entry_word_len(off) + 4);
  }

  private int next_entry(int off)
  {
    return off + 1 + entry_py_len(off) + 2 + entry_word_len(off) + 8;
  }

  private int skip_entry(int off) { return next_entry(off); }

  private String entry_pinyin(int off)
  {
    int len = entry_py_len(off);
    StringBuilder b = new StringBuilder(len);
    for (int i = 0; i < len; i++)
      b.append((char)(_dict.get(off + 1 + i) & 0xff));
    return b.toString();
  }

  private String entry_word(int off)
  {
    int woff = off + 1 + entry_py_len(off) + 2;
    int len = entry_word_len(off);
    byte[] bytes = new byte[len];
    for (int i = 0; i < len; i++)
      bytes[i] = _dict.get(woff + i);
    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
  }

  /** Compare the pinyin at entry offset [off] with [key]. Returns <0, 0, >0.
      Pinyin bytes are a-z so signed and unsigned orders agree. */
  private int cmp_entry_pinyin(int off, byte[] key)
  {
    int len = entry_py_len(off);
    int n = Math.min(len, key.length);
    for (int i = 0; i < n; i++)
    {
      int d = (_dict.get(off + 1 + i) & 0xff) - (key[i] & 0xff);
      if (d != 0)
        return d;
    }
    return len - key.length;
  }

  /** Whether the pinyin at entry offset [off] starts with [key]. */
  private boolean entry_pinyin_starts(int off, byte[] key)
  {
    int len = entry_py_len(off);
    if (len < key.length)
      return false;
    for (int i = 0; i < key.length; i++)
      if ((_dict.get(off + 1 + i) & 0xff) != (key[i] & 0xff))
        return false;
    return true;
  }

  /** First entry offset whose pinyin is >= [key], or -1 when there is no
      such entry. */
  private int lower_bound(byte[] key)
  {
    int lo = 0, hi = _py_offsets.length;
    while (lo < hi)
    {
      int mid = (lo + hi) / 2;
      int off = _entries_base + _py_offsets[mid];
      if (cmp_entry_pinyin(off, key) < 0)
        lo = mid + 1;
      else
        hi = mid;
    }
    if (lo >= _py_offsets.length)
      return -1;
    return _entries_base + _py_offsets[lo];
  }

  /** Exact matches for a full pinyin string, most frequent first (entries
      are pre-sorted by descending frequency). */
  public void query_exact(String pinyin, int max, List<Entry> out)
  {
    byte[] key = ascii(pinyin);
    if (key == null)
      return;
    int off = lower_bound(key);
    while (off > 0 && off < _entries_end && out.size() < max)
    {
      if (cmp_entry_pinyin(off, key) != 0)
        break;
      out.add(new Entry(pinyin, entry_word(off), entry_freq(off),
            entry_word_id(off)));
      off = next_entry(off);
    }
  }

  /** Entries whose pinyin starts with [prefix], in dictionary order (which
      is ascending pinyin, descending frequency within a pinyin). */
  public void query_prefix(String prefix, int max, List<Entry> out)
  {
    byte[] key = ascii(prefix);
    if (key == null)
      return;
    int off = lower_bound(key);
    while (out.size() < max && off > 0 && off < _entries_end
        && entry_pinyin_starts(off, key))
    {
      out.add(new Entry(entry_pinyin(off), entry_word(off),
            entry_freq(off), entry_word_id(off)));
      off = next_entry(off);
    }
  }

  /** The word string for a word id. */
  public String word_of(int word_id)
  {
    if (word_id < 0 || word_id >= _n_words)
      return null;
    int off = _word_offsets[word_id];
    int len = _dict.getShort(off) & 0xffff;
    byte[] bytes = new byte[len];
    for (int i = 0; i < len; i++)
      bytes[i] = _dict.get(off + 2 + i);
    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
  }

  /** Number of distinct words (bigram id space). */
  public int word_count() { return _n_words; }

  /** The word id of [word] (the dictionary's canonical entry for it), or -1
      when the word is unknown. The word table is sorted by UTF-8 bytes, so
      this is a binary search. */
  public int lookup_word_id(String word)
  {
    byte[] key = word.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    int lo = 0, hi = _n_words;
    while (lo < hi)
    {
      int mid = (lo + hi) / 2;
      int d = cmp_word(mid, key);
      if (d < 0)
        lo = mid + 1;
      else if (d > 0)
        hi = mid;
      else
        return mid;
    }
    if (lo < _n_words && cmp_word(lo, key) == 0)
      return lo;
    return -1;
  }

  private int cmp_word(int word_id, byte[] key)
  {
    int off = _word_offsets[word_id];
    int len = _dict.getShort(off) & 0xffff;
    int n = Math.min(len, key.length);
    for (int i = 0; i < n; i++)
    {
      int d = (_dict.get(off + 2 + i) & 0xff) - (key[i] & 0xff);
      if (d != 0)
        return d;
    }
    return len - key.length;
  }

  // ---- bigram ----------------------------------------------------------

  /** Count of adjacent occurrences of (prev, cur) in the corpus. 0 when the
      pair was never observed or no model is loaded. */
  public int bigram_count(int prev, int cur)
  {
    if (_bigram == null || prev < 0 || cur < 0)
      return 0;
    int lo = 0, hi = _prev_offsets.length;
    while (lo < hi)
    {
      int mid = (lo + hi) / 2;
      int off = _pairs_base + _prev_offsets[mid];
      if (_bigram.getInt(off) < prev)
        lo = mid + 1;
      else
        hi = mid;
    }
    if (lo >= _prev_offsets.length)
      return 0;
    int off = _pairs_base + _prev_offsets[lo];
    while (off < _pairs_end)
    {
      int p = _bigram.getInt(off);
      if (p != prev)
        break;
      int c = _bigram.getInt(off + 4);
      if (c == cur)
        return _bigram.getShort(off + 8) & 0xffff;
      if (c > cur)
        break;
      off += 10;
    }
    return 0;
  }

  public boolean has_bigram() { return _bigram != null; }

  // ---- helpers ---------------------------------------------------------

  private static byte[] ascii(String s)
  {
    byte[] b = new byte[s.length()];
    for (int i = 0; i < s.length(); i++)
    {
      char c = s.charAt(i);
      if (c < 'a' || c > 'z')
        return null;
      b[i] = (byte)c;
    }
    return b;
  }

  // ---- built-in fallback ------------------------------------------------

  /** Build a dictionary buffer in the same binary format from the tiny
      hardcoded fallback word list, so the engine has one code path. */
  private static ByteBuffer fallback_buffer()
  {
    List<String[]> entries = new ArrayList<String[]>(); // {pinyin, word, freq}
    for (String line : FALLBACK_WORDS.split("\n"))
    {
      line = line.trim();
      if (line.isEmpty() || line.startsWith("#"))
        continue;
      String[] parts = line.split("\\s+");
      if (parts.length < 2)
        continue;
      entries.add(new String[]{ parts[0], parts[1],
          parts.length >= 3 ? parts[2] : "100" });
    }
    // Sort by (pinyin, freq desc, word).
    java.util.Collections.sort(entries,
        new java.util.Comparator<String[]>()
        {
          public int compare(String[] a, String[] b)
          {
            int d = a[0].compareTo(b[0]);
            if (d != 0) return d;
            d = Integer.parseInt(b[2]) - Integer.parseInt(a[2]);
            if (d != 0) return d;
            return a[1].compareTo(b[1]);
          }
        });
    // Word table sorted by word bytes.
    java.util.TreeSet<String> words = new java.util.TreeSet<String>();
    for (String[] e : entries)
      words.add(e[1]);
    java.util.Map<String, Integer> word_id =
      new java.util.HashMap<String, Integer>();
    int id = 0;
    for (String w : words)
      word_id.put(w, id++);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try
    {
      out.write(new byte[]{ 'P', 'Y', 'D', '1' });
      write_int(out, 1);
      write_int(out, entries.size());
      int[] offsets = new int[entries.size()];
      int n_offsets = 0;
      String prev_py = null;
      for (String[] e : entries)
      {
        if (!e[0].equals(prev_py))
        {
          offsets[n_offsets++] = out.size();
          prev_py = e[0];
        }
        out.write(e[0].length());
        out.write(e[0].getBytes("US-ASCII"));
        byte[] w = e[1].getBytes("UTF-8");
        out.write((w.length >> 8) & 0xff);
        out.write(w.length & 0xff);
        out.write(w);
        write_int(out, Integer.parseInt(e[2]));
        write_int(out, word_id.get(e[1]));
      }
      write_int(out, n_offsets);
      for (int i = 0; i < n_offsets; i++)
        write_int(out, offsets[i]);
      write_int(out, words.size());
      for (String w : words)
      {
        byte[] wb = w.getBytes("UTF-8");
        out.write((wb.length >> 8) & 0xff);
        out.write(wb.length & 0xff);
        out.write(wb);
      }
    }
    catch (IOException e)
    {
      throw new RuntimeException(e);
    }
    byte[] bytes = out.toByteArray();
    ByteBuffer b = ByteBuffer.wrap(bytes);
    return b.slice();
  }

  private static ByteBuffer fallback_bigram() { return null; }

  private static void write_int(ByteArrayOutputStream out, int v)
  {
    out.write((v >> 24) & 0xff);
    out.write((v >> 16) & 0xff);
    out.write((v >> 8) & 0xff);
    out.write(v & 0xff);
  }

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
    + "hao 好 8600\n"
    + "nihao 你好 9500\n"
    + "jintian 今天 9000\n"
    + "tianqi 天气 6000\n"
    + "hen 很 7000\n"
    + "zheshi 这是 5000\n"
    + "shijie 世界 4000\n"
    + "meiguo 美国 4000\n"
    + "xian 先 1600\n"
    + "xian 西安 1600\n"
    + "fangan 方案 2000\n"
    + "fang'an 方案 2000\n"
    + "zhongguo 中国 8800\n"
    + "shenme 什么 9000\n";
}
