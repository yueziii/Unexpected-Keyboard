package juloo.keyboard2.pinyin;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers how many times the user committed each pinyin candidate so that
 * frequently used words are ranked first in future queries.
 *
 * The counts are kept in memory and persisted to a dedicated preferences
 * file with apply(), so recording a word never blocks the input thread.
 * On a JVM test environment (no Android framework) the in-memory map still
 * works; only persistence is unavailable.
 */
public final class PinyinUserDict
{
  private static final String PREFS_FILE = "pinyin_user_dict";
  /** Stop learning new words beyond this many entries. */
  private static final int MAX_ENTRIES = 1000;

  private static Map<String, Integer> _counts = null;
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
    for (Map.Entry<String, ?> e : _prefs.getAll().entrySet())
    {
      Object v = e.getValue();
      if (v instanceof Integer)
        counts.put(e.getKey(), (Integer)v);
    }
    _counts = counts;
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
    if (_counts == null)
      _counts = new HashMap<String, Integer>();
    return _counts;
  }

  /** Record that the user committed [word]. */
  public static void record(String word)
  {
    if (word == null || word.length() == 0)
      return;
    if (_counts == null)
      _counts = new HashMap<String, Integer>();
    if (!_counts.containsKey(word) && _counts.size() >= MAX_ENTRIES)
      return;
    int c = count(word) + 1;
    _counts.put(word, c);
    if (_prefs != null)
      _prefs.edit().putInt(word, c).apply();
  }

  /** Forget all learned words. */
  public static void clear()
  {
    if (_counts != null)
      _counts.clear();
    if (_prefs != null)
      _prefs.edit().clear().apply();
  }

  /** Reorder [candidates] in place so that words the user committed before
      come first, most used first. The remaining candidates keep their
      original order, which is already frequency-ranked by the dictionary. */
  public static void rank_by_user_usage(List<String> candidates)
  {
    if (_counts == null || _counts.isEmpty() || candidates.size() < 2)
      return;
    List<String> used = new ArrayList<String>();
    List<String> rest = new ArrayList<String>();
    for (String w : candidates)
    {
      if (count(w) > 0)
        used.add(w);
      else
        rest.add(w);
    }
    if (used.isEmpty())
      return;
    java.util.Collections.sort(used, new Comparator<String>()
      {
        @Override
        public int compare(String a, String b)
        {
          return count(b) - count(a);
        }
      });
    candidates.clear();
    candidates.addAll(used);
    candidates.addAll(rest);
  }
}
