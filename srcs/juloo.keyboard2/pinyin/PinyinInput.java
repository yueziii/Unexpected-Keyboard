package juloo.keyboard2.pinyin;

import java.util.ArrayList;
import java.util.List;
import juloo.cdict.Cdict;
import juloo.keyboard2.suggestions.Suggestions;

/**
 * Pinyin input state machine for double pinyin.
 *
 * Tracks keystrokes, decodes them into pinyin syllables, and provides
 * candidates. Supports continuous input: after accepting a candidate, the
 * remaining unmatched syllables stay in the buffer for further input.
 *
 * The candidates view shows:
 *   - Position 0: current pinyin preview (e.g. "ni hao") when no candidate
 *     has been selected yet, or the first candidate word
 *   - Positions 1-2: additional candidates
 */
public final class PinyinInput
{
  private PinyinSchemes.Scheme _scheme;
  private Cdict _dict;
  private Suggestions _suggestions;

  /** Raw keystroke buffer (lowercase letters). */
  private StringBuilder _buffer = new StringBuilder();
  /** Decoded syllables, e.g. ["ni", "hao"]. */
  private List<String> _syllables = new ArrayList<String>();

  public PinyinInput() {}

  public void started(PinyinSchemes.Scheme scheme, Cdict dict,
      Suggestions suggestions)
  {
    _scheme = scheme;
    _dict = dict;
    _suggestions = suggestions;
    reset();
  }

  public boolean is_active() { return _buffer.length() > 0; }

  /** Whether the buffer is waiting for a final (韵母) key, i.e. the last
      keystroke was an initial with no final yet. The ';' key ("ing") is only
      a valid pinyin keystroke in this state. */
  public boolean pending_final()
  {
    // In double pinyin every syllable is 2 keys; an odd-length buffer means
    // a final is pending.
    return (_buffer.length() % 2) == 1;
  }

  public String get_buffer() { return _buffer.toString(); }

  /** The decoded syllables as a space-separated pinyin string, e.g. "ni hao". */
  public String get_pinyin_string()
  {
    if (_syllables.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < _syllables.size(); i++)
    {
      if (i > 0) sb.append(' ');
      sb.append(_syllables.get(i));
    }
    return sb.toString();
  }

  /** The decoded syllables joined with apostrophe, e.g. "ni'hao".
      Used as the dictionary lookup key. */
  public String get_pinyin_key()
  {
    if (_syllables.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < _syllables.size(); i++)
      sb.append(_syllables.get(i));
    return sb.toString();
  }

  public boolean key_typed(char c)
  {
    c = Character.toLowerCase(c);
    // Accept letters and ';' (the "ing" final in Microsoft double pinyin).
    if (!Character.isLetter(c) && c != ';') return false;
    _buffer.append(c);
    decode_syllables();
    return true;
  }

  public boolean backspace()
  {
    if (_buffer.length() == 0) return false;
    _buffer.deleteCharAt(_buffer.length() - 1);
    decode_syllables();
    return true;
  }

  public void reset()
  {
    _buffer.setLength(0);
    _syllables.clear();
  }

  public void query_candidates()
  {
    if (_suggestions != null)
      _suggestions.pinyin_query(this);
  }

  public Cdict get_dict() { return _dict; }
  public List<String> get_syllables() { return _syllables; }

  /** Accept a candidate word. Removes the syllables that were consumed by
      this candidate from the buffer, keeping any remaining syllables for
      continued input. Returns true if the buffer still has content after
      accepting. */
  public boolean accept_candidate(String candidate, String pinyin_preview)
  {
    // If the candidate is the pinyin preview itself, commit the raw pinyin
    // and clear everything.
    if (candidate.equals(pinyin_preview))
    {
      reset();
      return false;
    }
    // Try to determine how many syllables this candidate consumes by
    // looking it up in the built-in dictionary.
    int consumed_syllables = count_matching_syllables(candidate);
    if (consumed_syllables <= 0)
      consumed_syllables = _syllables.size();
    // Remove consumed keystrokes from the buffer (2 keys per syllable in
    // double pinyin)
    int keys_to_remove = Math.min(consumed_syllables * 2, _buffer.length());
    _buffer.delete(0, keys_to_remove);
    decode_syllables();
    return _buffer.length() > 0;
  }

  /** Count how many syllables from the current buffer match the candidate
      word by looking it up in the built-in dictionary. */
  private int count_matching_syllables(String candidate)
  {
    PinyinDict builtin = PinyinDict.built_in();
    String pinyin_key = get_pinyin_key();
    List<PinyinDict.Entry> results = builtin.query(pinyin_key,
        _syllables.size());
    for (PinyinDict.Entry e : results)
    {
      if (e.word.equals(candidate))
      {
        // Count how many syllables this pinyin entry spans
        int count = 0;
        int pos = 0;
        for (String s : _syllables)
        {
          if (pos + s.length() <= e.pinyin.length())
          {
            pos += s.length();
            count++;
          }
          else
            break;
        }
        return count > 0 ? count : _syllables.size();
      }
    }
    // Not found in dict — assume it consumes all syllables
    return _syllables.size();
  }

  /**
   * Decode the raw buffer into syllables using the double pinyin scheme.
   * In double pinyin, every two keystrokes form one syllable.
   * An odd trailing keystroke is a pending initial (no final yet).
   */
  private void decode_syllables()
  {
    _syllables.clear();
    if (_scheme == null) return;
    String buf = _buffer.toString();
    int len = buf.length();
    int i = 0;
    while (i < len)
    {
      if (i + 1 < len)
      {
        char init_key = buf.charAt(i);
        char final_key = buf.charAt(i + 1);
        String init = _scheme.initial(init_key);
        String fin = _scheme.finals(final_key, init);
        if (init == null || fin == null)
        {
          _syllables.add(String.valueOf(init_key));
          i += 1;
          continue;
        }
        _syllables.add(init + fin);
        i += 2;
      }
      else
      {
        char init_key = buf.charAt(i);
        String init = _scheme.initial(init_key);
        _syllables.add(init != null ? init : String.valueOf(init_key));
        i += 1;
      }
    }
  }
}
