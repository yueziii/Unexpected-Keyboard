package juloo.keyboard2.pinyin;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers what the user commits so future queries rank it first.
 *
 * Three kinds of state are kept, all in one preferences file:
 *
 * - Word usage counts: word -> int. The engine boosts a candidate by
 *   USER_W * log(1 + count) and ranks committed texts first among
 *   same-coverage candidates (PinyinEngine.rank_by_user_usage).
 * - Custom words (自动造词): the pinyin of committed texts that are not
 *   dictionary words ("py:" + word -> pinyin). The engine surfaces them as
 *   candidates for that pinyin, ranked by their usage count.
 * - Word pairs (用户词对): "p:" + prev + " " + cur -> int count. The
 *   next-word predictor (联想) ranks continuations the user actually typed
 *   above the corpus bigram.
 *
 * The maps are kept in memory and persisted with apply(), so recording
 * never blocks the input thread. In a JVM test environment (no Android
 * framework) the in-memory maps still work; only persistence is
 * unavailable.
 */
public final class PinyinUserDict
{
  private static final String PREFS_FILE = "pinyin_user_dict";
  /** Stop learning new words beyond this many entries; the least used are
      pruned first. */
  private static final int MAX_ENTRIES = 5000;
  /** Stop learning word pairs beyond this many. */
  private static final int MAX_PAIRS = 5000;
  /** Keys of the two non-count kinds of state; Chinese words cannot
      contain these characters. */
  private static final String PY_PREFIX = "py:";
  private static final String PAIR_PREFIX = "p:";

  private static Map<String, Integer> _counts = null;
  private static Map<String, String> _pinyins = null;
  /** prev -> (cur -> count). */
  private static Map<String, Map<String, Integer>> _pairs = null;
  private static SharedPreferences _prefs = null;

  private PinyinUserDict() {}

  /** Load the learned words. Call once at IME startup, before the first
      query. Safe to call multiple times. */
  public static void init(Context ctx)
  {
    if (_counts != null || ctx == null)
      return;
    _prefs = ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    Map<String, Integer> counts = new HashMap<String, Integer>();
    Map<String, String> pinyins = new HashMap<String, String>();
    Map<String, Map<String, Integer>> pairs =
      new HashMap<String, Map<String, Integer>>();
    for (Map.Entry<String, ?> e : _prefs.getAll().entrySet())
    {
      Object v = e.getValue();
      String k = e.getKey();
      if (k.startsWith(PY_PREFIX))
      {
        if (v instanceof String)
          pinyins.put(k.substring(PY_PREFIX.length()), (String)v);
      }
      else if (k.startsWith(PAIR_PREFIX))
      {
        if (v instanceof Integer)
        {
          String pair = k.substring(PAIR_PREFIX.length());
          int sep = pair.indexOf(' ');
          if (sep > 0)
          {
            String prev = pair.substring(0, sep);
            String cur = pair.substring(sep + 1);
            Map<String, Integer> succ = pairs.get(prev);
            if (succ == null)
              pairs.put(prev, succ = new HashMap<String, Integer>());
            succ.put(cur, (Integer)v);
          }
        }
      }
      else if (v instanceof Integer)
        counts.put(k, (Integer)v);
    }
    _counts = counts;
    _pinyins = pinyins;
    _pairs = pairs;
  }

  private static void ensure_loaded()
  {
    if (_counts == null)
    {
      _counts = new HashMap<String, Integer>();
      _pinyins = new HashMap<String, String>();
      _pairs = new HashMap<String, Map<String, Integer>>();
    }
  }

  /** How many times the user committed [word]. 0 if never. */
  public static int count(String word)
  {
    if (_counts == null || word == null)
      return 0;
    Integer c = _counts.get(word);
    return (c != null) ? c : 0;
  }

  /** The live word -> usage count map for the engine (never null). The
      engine reads it on every query; it must not be modified by callers. */
  public static java.util.Map<String, Integer> counts()
  {
    ensure_loaded();
    return _counts;
  }

  /** The live custom-word pinyin map (word -> pinyin, never null). */
  public static java.util.Map<String, String> pinyins()
  {
    ensure_loaded();
    return _pinyins;
  }

  /** The live user word pairs (prev -> (cur -> count), never null). */
  public static java.util.Map<String, java.util.Map<String, Integer>> pairs()
  {
    ensure_loaded();
    return _pairs;
  }

  /** Record that the user committed [word]. */
  public static void record(String word)
  {
    record(word, null);
  }

  /** Record a commit; [pinyin] (optional) learns [word] as a custom word
      for that pinyin (自动造词) when it is not a dictionary word. */
  public static void record(String word, String pinyin)
  {
    if (word == null || word.length() == 0)
      return;
    ensure_loaded();
    if (!_counts.containsKey(word) && _counts.size() >= MAX_ENTRIES)
      prune_least_used_word();
    int c = count(word) + 1;
    _counts.put(word, c);
    if (_prefs != null)
      _prefs.edit().putInt(word, c).apply();
    if (pinyin != null && !pinyin.isEmpty() && word.length() <= 8)
      learn_custom_pinyin(word, pinyin);
  }

  /** The pinyin learned for the custom word [word], or null. */
  public static String pinyin_of(String word)
  {
    if (_pinyins == null || word == null)
      return null;
    return _pinyins.get(word);
  }

  private static void learn_custom_pinyin(String word, String pinyin)
  {
    String old = _pinyins.get(word);
    if (pinyin.equals(old))
      return;
    _pinyins.put(word, pinyin);
    if (_prefs != null)
      _prefs.edit().putString(PY_PREFIX + word, pinyin).apply();
  }

  /** Record that [cur] was committed right after [prev] (for 联想). */
  public static void record_pair(String prev, String cur)
  {
    if (prev == null || prev.isEmpty() || cur == null || cur.isEmpty())
      return;
    ensure_loaded();
    if (pair_count(prev, cur) == 0 && count_pairs() >= MAX_PAIRS)
      prune_least_used_pair();
    Map<String, Integer> succ = _pairs.get(prev);
    if (succ == null)
      _pairs.put(prev, succ = new HashMap<String, Integer>());
    int c = (succ.containsKey(cur) ? succ.get(cur) : 0) + 1;
    succ.put(cur, c);
    if (_prefs != null)
      _prefs.edit().putInt(PAIR_PREFIX + prev + " " + cur, c).apply();
  }

  /** How many times [cur] followed [prev]. 0 if never. */
  public static int pair_count(String prev, String cur)
  {
    if (_pairs == null || prev == null || cur == null)
      return 0;
    Map<String, Integer> succ = _pairs.get(prev);
    if (succ == null)
      return 0;
    Integer c = succ.get(cur);
    return (c != null) ? c : 0;
  }

  private static int count_pairs()
  {
    int n = 0;
    for (Map<String, Integer> succ : _pairs.values())
      n += succ.size();
    return n;
  }

  /** Forget the word entirely: usage count, custom pinyin and every pair it
      took part in (candidate long-press "删除该词"). */
  public static void remove(String word)
  {
    if (word == null || word.isEmpty())
      return;
    ensure_loaded();
    _counts.remove(word);
    _pinyins.remove(word);
    boolean had_pairs = _pairs.remove(word) != null;
    for (Map.Entry<String, Map<String, Integer>> e : _pairs.entrySet())
      if (e.getValue().remove(word) != null)
        had_pairs = true;
    if (_prefs != null)
    {
      SharedPreferences.Editor ed = _prefs.edit().remove(word)
        .remove(PY_PREFIX + word);
      for (String prev : new ArrayList<String>(_pairs.keySet()))
        ed.remove(PAIR_PREFIX + prev + " " + word);
      if (had_pairs)
      {
        // Pairs where [word] was the first term carry the word in their
        // keys; they were removed above via _pairs.remove(word), remove
        // their persisted keys too.
        for (String k : _prefs.getAll().keySet())
          if (k.startsWith(PAIR_PREFIX + word + " "))
            ed.remove(k);
      }
      ed.apply();
    }
  }

  /** Forget all learned words and pairs. */
  public static void clear()
  {
    if (_counts != null)
      _counts.clear();
    if (_pinyins != null)
      _pinyins.clear();
    if (_pairs != null)
      _pairs.clear();
    if (_prefs != null)
      _prefs.edit().clear().apply();
  }

  /** Drop the least used word when the cap is reached. */
  private static void prune_least_used_word()
  {
    String min_word = null;
    int min_count = Integer.MAX_VALUE;
    for (Map.Entry<String, Integer> e : _counts.entrySet())
    {
      if (e.getValue() < min_count)
      {
        min_count = e.getValue();
        min_word = e.getKey();
      }
    }
    if (min_word != null)
    {
      _counts.remove(min_word);
      if (_prefs != null)
        _prefs.edit().remove(min_word).apply();
    }
  }

  /** Drop the least used pair when the cap is reached. */
  private static void prune_least_used_pair()
  {
    String min_prev = null, min_cur = null;
    int min_count = Integer.MAX_VALUE;
    for (Map.Entry<String, Map<String, Integer>> e : _pairs.entrySet())
      for (Map.Entry<String, Integer> s : e.getValue().entrySet())
      {
        if (s.getValue() < min_count)
        {
          min_count = s.getValue();
          min_prev = e.getKey();
          min_cur = s.getKey();
        }
      }
    if (min_prev != null)
    {
      Map<String, Integer> succ = _pairs.get(min_prev);
      if (succ != null)
        succ.remove(min_cur);
      if (_prefs != null)
        _prefs.edit().remove(PAIR_PREFIX + min_prev + " " + min_cur).apply();
    }
  }
}
