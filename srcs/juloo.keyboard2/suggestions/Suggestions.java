package juloo.keyboard2.suggestions;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import juloo.cdict.Cdict;
import juloo.keyboard2.dict.Dictionaries;
import juloo.keyboard2.Config;
import juloo.keyboard2.ComposeKey;
import juloo.keyboard2.ComposeKeyData;
import juloo.keyboard2.pinyin.PinyinInput;
import juloo.keyboard2.pinyin.PinyinDict;

/** Keep track of the word being typed and provide suggestions for
    [CandidatesView].

    English mode keeps the legacy fixed-size [suggestions]/[count] arrays
    (capped at [MAX_COUNT]).

    Pinyin (Chinese) mode uses the dynamic [pinyin_candidates] list which can
    hold many more candidates so the user can scroll through all the
    characters/words matching a syllable. The decoded pinyin preview is
    exposed separately via [pinyin_preview] so it no longer occupies a
    precious candidate slot. */
public final class Suggestions
{
  Callback _callback;
  Config _config;
  boolean _enabled;

  /** Current suggestions (English mode). The best suggestion is at index 0. */
  public String[] suggestions = new String[MAX_COUNT];
  /** Number of suggestions at the beginning of the [suggestions] array that
      are not [null]. */
  public int count = 0;
  public String emoji_suggestion = null;
  /** Number of suggestions in [suggestions] (English mode). */
  public static final int MAX_COUNT = 3;

  /** Maximum number of pinyin candidates to query and display. A single
      syllable can map to dozens of characters, so this is much larger than
      [MAX_COUNT]. The candidates bar scrolls horizontally to reveal them. */
  public static final int PINYIN_MAX_COUNT = 40;

  /** Pinyin mode candidates (Chinese). May contain many entries. */
  public List<String> pinyin_candidates = new ArrayList<String>();
  /** Decoded pinyin preview (e.g. "ni hao") shown separately from the
      candidate list in pinyin mode. Selecting it commits the raw pinyin. */
  public String pinyin_preview = null;

  public Suggestions(Callback c, Config conf)
  {
    _callback = c;
    _config = conf;
  }

  public void started()
  {
    _enabled = _config.editor_config.should_show_candidates_view;
    clear();
  }

  public void currently_typed_word(String word)
  {
    if (!_enabled)
      return;
    if (word.length() < 2 || _config.current_dictionary == null)
      clear();
    else
      query_suggestions(word);
    _callback.set_suggestions(this);
  }

  /** Called by [PinyinInput] when the pinyin buffer changes. Populates
      [pinyin_candidates] with as many dictionary matches as available (up to
      [PINYIN_MAX_COUNT]) and exposes the decoded pinyin via
      [pinyin_preview].

      Unlike the previous implementation, the preview no longer occupies the
      first candidate slot — it is rendered as a separate label. This frees
      all candidate slots for actual words/characters, which is essential
      because a single pinyin syllable can map to dozens of characters. */
  public void pinyin_query(PinyinInput pinyin)
  {
    // In pinyin mode, candidates must ALWAYS be shown regardless of the
    // _enabled flag (which follows the editor's suggestion flag). The user
    // needs to see pinyin candidates to type Chinese.
    List<String> syllables = pinyin.get_syllables();
    if (syllables.isEmpty())
    {
      clear();
      return;
    }
    pinyin_preview = pinyin.get_pinyin_string();
    // Build concatenated pinyin for dictionary lookup
    StringBuilder query = new StringBuilder();
    for (String s : syllables)
      query.append(s);
    String word = query.toString();

    List<String> candidates = new ArrayList<String>();
    Cdict dict = pinyin.get_dict();
    if (dict != null)
    {
      Cdict.Result r = dict.find(word);
      if (r.found)
        candidates.add(dict.word(r.index));
      int[] suffixes = dict.suffixes(r, PINYIN_MAX_COUNT);
      for (int j = 0; j < suffixes.length
          && candidates.size() < PINYIN_MAX_COUNT; j++)
        candidates.add(dict.word(suffixes[j]));
      if (candidates.size() < PINYIN_MAX_COUNT && word.length() >= 3)
      {
        int[] dist = dict.distance(word, 1,
            PINYIN_MAX_COUNT - candidates.size());
        for (int j = 0; j < dist.length
            && candidates.size() < PINYIN_MAX_COUNT; j++)
          candidates.add(dict.word(dist[j]));
      }
    }
    // Fall back to built-in PinyinDict
    if (candidates.isEmpty())
    {
      PinyinDict builtin = PinyinDict.built_in();
      List<PinyinDict.Entry> results = builtin.query(word, PINYIN_MAX_COUNT);
      for (PinyinDict.Entry e : results)
      {
        if (candidates.size() >= PINYIN_MAX_COUNT)
          break;
        candidates.add(e.word);
      }
    }

    pinyin_candidates.clear();
    pinyin_candidates.addAll(candidates);
    // Keep the legacy array empty in pinyin mode; the CandidatesView reads
    // [pinyin_candidates] / [pinyin_preview] instead.
    count = 0;
    for (int i = 0; i < MAX_COUNT; i++)
      suggestions[i] = null;
    emoji_suggestion = null;
    _callback.set_suggestions(this);
  }

  public void clear()
  {
    count = 0;
    for (int i = 0; i < MAX_COUNT; i++)
      suggestions[i] = null;
    emoji_suggestion = null;
    pinyin_candidates.clear();
    pinyin_preview = null;
  }

  int query_suggestions(String word)
  {
    Cdict dict = _config.current_dictionary;
    boolean first_char_upper = Character.isUpperCase(word.charAt(0));
    word = apply_substitutions(word);
    Cdict.Result r = dict.find(word);
    int i = 0;
    if (r.found)
      suggestions[i++] = dict.word(r.index);
    int[] suffixes = dict.suffixes(r, MAX_COUNT);
    // Disable distance search for small words
    int[] dist = (word.length() < 3 || i + 1 >= MAX_COUNT) ? NO_RESULTS :
      dict.distance(word, 1, MAX_COUNT);
    for (int j = 0; j < MAX_COUNT && i < MAX_COUNT; j++)
    {
      if (suffixes.length > j)
        suggestions[i++] = dict.word(suffixes[j]);
      if (dist.length > j && i < MAX_COUNT)
        suggestions[i++] = dict.word(dist[j]);
    }
    if (first_char_upper)
      capitalize_results();
    emoji_suggestion = query_emoji(word); // word with substitutions applied
    count = i;
    return i;
  }

  void capitalize_results()
  {
    for (int i = 0; i < count; i++)
      suggestions[i] = suggestions[i].substring(0, 1).toUpperCase()
        + suggestions[i].substring(1);
  }

  String query_emoji(String word)
  {
    Cdict dict = _config.emoji_dictionary;
    // Disable emoji suggestion for short words
    if (dict == null || word.length() < 3)
      return null;
    Cdict.Result r = dict.find(word);
    if (r.found)
      return dict.word(r.index);
    int[] s = dict.suffixes(r, 1);
    if (s.length > 0)
      return dict.word(s[0]);
    return null;
  }

  /** Apply the same substitutions that were used when building the
      dictionaries to find word aliases. This catches missing diacritics for
      example. */
  String apply_substitutions(String w)
  {
    StringBuilder b = new StringBuilder(w);
    int len = w.length();
    for (int i = 0; i < len; i++)
    {
      char r =
        ComposeKey.transform_char(ComposeKeyData.substitutions, b.charAt(i));
      if (r != 0) b.setCharAt(i, r);
    }
    return b.toString();
  }

  static final int[] NO_RESULTS = new int[0];

  public static interface Callback
  {
    public void set_suggestions(Suggestions suggestions);
  }
}
