package juloo.keyboard2.pinyin;

import java.util.ArrayList;
import java.util.List;
import juloo.keyboard2.suggestions.Suggestions;

/**
 * Pinyin input state machine.
 *
 * Holds the raw keystroke buffer and produces candidates through
 * [PinyinEngine]. Two input schemes are supported:
 *
 * - Full pinyin (全拼): the buffer is the pinyin letters themselves; the
 *   engine matches word pinyins against the letter string directly.
 * - Double pinyin (双拼, e.g. Microsoft): every syllable is two keystrokes;
 *   the buffer is translated to pinyin letters through the scheme before
 *   decoding, and consumed keystrokes are 2 per accepted syllable.
 *
 * The candidates view shows the pinyin preview separately from the
 * scrollable candidate strip.
 */
public final class PinyinInput
{
  /** One candidate: the text to commit and how many buffer keystrokes it
      consumes. The remaining keystrokes stay in the buffer for continued
      input (整句输入: accept pieces of the sentence one after another). */
  public static final class Candidate
  {
    public final String text;
    public final int keys_consumed;

    Candidate(String t, int keys)
    {
      text = t;
      keys_consumed = keys;
    }
  }

  /** Null for full pinyin; the scheme tables for double pinyin. */
  private PinyinSchemes.Scheme _scheme;
  private Suggestions _suggestions;

  /** Raw keystroke buffer (lowercase letters and ';' in double pinyin). */
  private StringBuilder _buffer = new StringBuilder();
  /** Pinyin letters fed to the engine (buffer itself in full pinyin; the
      scheme translation in double pinyin). */
  private String _letters = "";
  /** Candidates for the current buffer, or null when not queried yet. */
  private List<Candidate> _candidates = null;
  /** Pinyin preview, e.g. "ni'hao" (full) or "ni hao" (double). */
  private String _preview = "";
  /** Best candidate's syllables, used for the preview. */
  private List<String> _preview_syllables = new ArrayList<String>();
  /** The word committed right before the current buffer (bigram context). */
  private String _context_word = null;

  public PinyinInput() {}

  public void started(PinyinSchemes.Scheme scheme, Suggestions suggestions)
  {
    _scheme = scheme;
    _suggestions = suggestions;
    reset();
  }

  public boolean is_active() { return _buffer.length() > 0; }

  public String get_buffer() { return _buffer.toString(); }

  /** The pinyin preview shown in the candidates bar, e.g. "ni'hao". */
  public String get_pinyin_string()
  {
    if (_preview.isEmpty())
    {
      // Not decoded yet (e.g. right after a keystroke in double pinyin):
      // show the raw buffer so the user always sees something.
      return _buffer.toString();
    }
    return _preview;
  }

  public List<Candidate> get_candidates()
  {
    if (_candidates == null)
      return new ArrayList<Candidate>();
    return _candidates;
  }

  /** Whether the buffer is waiting for a final (韵母) key: the last
      keystroke was an initial with no final yet. Only meaningful in double
      pinyin. The ';' key ("ing") is only a valid pinyin keystroke in this
      state. */
  public boolean pending_final()
  {
    if (_scheme == null)
      return false;
    // In double pinyin every syllable is 2 keys; an odd-length buffer means
    // a final is pending.
    return (_buffer.length() % 2) == 1;
  }

  public boolean key_typed(char c)
  {
    c = Character.toLowerCase(c);
    // Accept letters, and ';' as the "ing" final in Microsoft double pinyin.
    if (!Character.isLetter(c) && c != ';')
      return false;
    if (_scheme == null && c == ';')
      return false;
    _buffer.append(c);
    _letters = translate();
    return true;
  }

  public boolean backspace()
  {
    if (_buffer.length() == 0)
      return false;
    _buffer.deleteCharAt(_buffer.length() - 1);
    _letters = translate();
    return true;
  }

  public void reset()
  {
    _buffer.setLength(0);
    _letters = "";
    _candidates = null;
    _preview = "";
    _preview_syllables.clear();
    // Keep [_context_word]: it still describes what precedes the cursor.
  }

  public void query_candidates()
  {
    if (_letters.isEmpty())
    {
      _candidates = new ArrayList<Candidate>();
      _preview = "";
      _preview_syllables.clear();
      publish();
      return;
    }
    List<PinyinEngine.Candidate> decoded = PinyinEngine.decode(
        PinyinData.get(), _letters, _context_word, PinyinUserDict.counts());
    List<Candidate> candidates = new ArrayList<Candidate>();
    for (PinyinEngine.Candidate d : decoded)
      candidates.add(new Candidate(d.text,
            keys_for_letters(d.letters_consumed)));
    _candidates = candidates;
    compute_preview();
    publish();
  }

  /** Accept a candidate by text (the candidate bar passes the tapped text).
      Returns the number of keystrokes consumed, or -1 if the text is not a
      current candidate. */
  public int accept_candidate(String text)
  {
    if (_candidates == null)
      return -1;
    for (Candidate c : _candidates)
    {
      if (c.text.equals(text))
      {
        _buffer.delete(0, Math.min(c.keys_consumed, _buffer.length()));
        _context_word = last_word_of(text);
        _letters = translate();
        _candidates = null;
        return c.keys_consumed;
      }
    }
    return -1;
  }

  /** The text to show as the editor's composing region: the best candidate
      if available, otherwise the pinyin preview. */
  public String composing_text()
  {
    List<Candidate> cs = get_candidates();
    if (!cs.isEmpty())
      return cs.get(0).text;
    return get_pinyin_string();
  }

  public Suggestions suggestions() { return _suggestions; }

  /** Publish the current candidates to the suggestions bar. */
  private void publish()
  {
    if (_suggestions != null)
      _suggestions.pinyin_query(this);
  }

  /** Compute the pinyin preview from the best candidate's syllables. */
  private void compute_preview()
  {
    _preview = "";
    _preview_syllables.clear();
    if (_letters.isEmpty())
      return;
    // Re-derive the syllable split of the best path from the engine via a
    // lightweight greedy segmentation of the letters (for display only).
    int consumed = 0;
    if (_candidates != null && !_candidates.isEmpty())
    {
      // consumed keys -> letters via the same mapping used for accepts
      int best = _candidates.get(0).keys_consumed;
      consumed = letters_for_keys(best);
    }
    else
      consumed = _letters.length();
    String complete = _letters.substring(0,
        Math.min(consumed, _letters.length()));
    StringBuilder py = new StringBuilder();
    int i = 0;
    while (i < complete.length())
    {
      int len = max_syllable_at(complete, i);
      if (len == 0)
        len = 1;
      if (py.length() > 0)
        py.append(_scheme == null ? "'" : " ");
      py.append(complete.substring(i, i + len));
      i += len;
    }
    if (i < _letters.length())
    {
      if (py.length() > 0)
        py.append(_scheme == null ? "'" : " ");
      py.append(_letters.substring(i));
    }
    _preview = py.toString();
  }

  /** Length of the longest valid syllable starting at [i] in [s], using
      dictionary knowledge: a span is a syllable when the dictionary has
      entries whose pinyin start with it — approximate with a prefix query
      cap. 0 when not even a 1-letter prefix exists. */
  private static int max_syllable_at(String s, int i)
  {
    // Split the letters into syllables with a greedy longest-match against
    // the standard syllable table embedded in the dictionary: try lengths
    // from longest to shortest and pick the first that is a known syllable.
    for (int len = Math.min(PinyinEngine.MAX_SYLLABLE_LEN, s.length() - i);
        len >= 1; len--)
    {
      if (PinyinSyllables.is_syllable(s.substring(i, i + len)))
        return len;
    }
    return 0;
  }

  /** Translate the buffer into pinyin letters for the engine. */
  private String translate()
  {
    if (_scheme == null)
      return _buffer.toString();
    // Double pinyin: decode 2-key syllables; an odd trailing keystroke
    // contributes its initial letters.
    StringBuilder out = new StringBuilder();
    String buf = _buffer.toString();
    int i = 0;
    while (i < buf.length())
    {
      if (i + 1 < buf.length())
      {
        char init_key = buf.charAt(i);
        char final_key = buf.charAt(i + 1);
        String init = _scheme.initial(init_key);
        String fin = _scheme.finals(final_key, init);
        if (init == null || fin == null)
        {
          // Invalid pair: emit the initial only and resync on the next key.
          out.append(init != null ? init : String.valueOf(init_key));
          i += 1;
          continue;
        }
        out.append(init).append(fin);
        i += 2;
      }
      else
      {
        char init_key = buf.charAt(i);
        String init = _scheme.initial(init_key);
        out.append(init != null ? init : String.valueOf(init_key));
        i += 1;
      }
    }
    return out.toString();
  }

  /** Map engine letters back to buffer keystrokes: full pinyin is 1:1;
      double pinyin consumes 2 keystrokes per syllable. */
  private int keys_for_letters(int letters_consumed)
  {
    if (_scheme == null)
      return Math.min(letters_consumed, _buffer.length());
    int syllables = count_syllables(_letters, letters_consumed);
    return Math.min(syllables * 2, _buffer.length());
  }

  private int letters_for_keys(int keys_consumed)
  {
    if (_scheme == null)
      return Math.min(keys_consumed, _letters.length());
    // Each syllable is 2 keys; find how many letters the first N syllables
    // span.
    int syllables = (keys_consumed + 1) / 2;
    int pos = 0;
    int i = 0;
    while (i < _letters.length() && syllables > 0)
    {
      int len = max_syllable_at(_letters, i);
      if (len == 0)
        len = 1;
      i += len;
      pos = i;
      syllables--;
    }
    return pos;
  }

  /** Number of syllables in the first [upto] letters of [letters]. */
  private static int count_syllables(String letters, int upto)
  {
    int count = 0;
    int i = 0;
    while (i < letters.length() && i < upto)
    {
      int len = PinyinSyllables.max_syllable(letters, i);
      if (len == 0)
        len = 1;
      i += len;
      count++;
    }
    return count;
  }

  /** For the bigram context: the last committed word is the tail of a
      sentence candidate. */
  private static String last_word_of(String text)
  {
    // A committed candidate may itself be a multi-word sentence; without
    // segmentation knowledge, use the whole text as context — the engine's
    // context lookup uses the dictionary word id, and compound strings
    // simply won't match (neutral context).
    return text.length() <= 8 ? text : null;
  }
}
