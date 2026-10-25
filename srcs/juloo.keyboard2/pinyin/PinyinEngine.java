package juloo.keyboard2.pinyin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pinyin sentence decoder.
 *
 * Converts a pinyin letter string (e.g. "jintiantianqihenhao") into ranked
 * Chinese candidates using the same lattice approach as mainstream IMEs:
 *
 * A lattice node is a dictionary entry whose pinyin equals a substring of
 * the input letters (multi-character words and single characters alike).
 * A path through the lattice covers the complete-syllable prefix of the
 * input. Paths are scored with a unigram word model (log frequency, with a
 * per-word penalty so shorter paths don't win just by having more terms)
 * plus a bigram transition bonus from the corpus model (PinyinData), plus a
 * boost for words the user committed before. The best paths (Viterbi with a
 * beam) become the sentence candidates; exact dictionary matches and
 * progressive prefix extensions complete the candidate list.
 *
 * No explicit syllable segmentation is needed for the lattice: a word's
 * pinyin is matched directly against the letter string, so ambiguous splits
 * like "xian" (先 / xi'an 西安) are handled naturally by whichever entries
 * exist for the span.
 */
public final class PinyinEngine
{
  /** Longest pinyin of a single syllable ("zhuang"). */
  static final int MAX_SYLLABLE_LEN = 6;
  /** Longest word pinyin considered for a lattice span: a 12-character
      phrase (poem lines, proverbs) with 6-letter syllables. Words longer
      than one syllable are common (jintian = 7 letters) and must be lattice
      nodes. */
  static final int MAX_SPAN_LEN = 48;
  /** Convert at most this many input letters at once. */
  static final int MAX_INPUT_LEN = 60;
  /** Beam width for the Viterbi search. */
  static final int BEAM = 8;
  /** Per-span cap on dictionary entries considered as lattice nodes. */
  static final int MAX_NODES_PER_SPAN = 14;
  /** Per-word penalty (≈ log of the frequency scale), so a path made of two
      words pays it twice. Prevents "很+好" from outranking a good 2-char
      word merely by summing two unigram terms. */
  static final float WORD_PENALTY = 14.0f;
  /** Extra penalty for single-character lattice nodes, like mainstream
      IMEs: a chain of common characters (一新一一) must not outrank the
      matching idiom (一心一意) as a sentence candidate. Characters stay
      reachable when no word covers the span. */
  static final float CHAR_PENALTY = 4.0f;
  /** Weight of the corpus bigram bonus: BIGRAM_W * log(1 + count), capped
      at BONUS_CAP. The cap keeps transitions from overriding genuinely
      common words: with linear frequency scaling the unigram gap between a
      strong word and a garbage path is several nats, while leaked
      co-occurrences (是/极 from 是极其) sit around log(5). */
  static final float BIGRAM_W = 0.3f;
  static final float BONUS_CAP = 1.2f;
  /** Weight of the user history boost: USER_W * log(1 + count). */
  static final float USER_W = 3.0f;
  /** Candidate list size. */
  static final int MAX_CANDIDATES = 32;

  public static final class Candidate
  {
    public final String text;
    /** Pinyin letters consumed when this candidate is committed. */
    public final int letters_consumed;
    /** Syllable strings of the consumed part, for the pinyin preview. */
    public final List<String> syllables;

    Candidate(String t, int consumed, List<String> syls)
    {
      text = t;
      letters_consumed = consumed;
      syllables = syls;
    }
  }

  /** One lattice span: [start, end) with its dictionary entries. */
  private static final class Span
  {
    final int start, end;
    final List<PinyinData.Entry> entries;
    Span(int s, int e, List<PinyinData.Entry> es)
    {
      start = s; end = e; entries = es;
    }
  }

  /** One Viterbi beam state: a path ending at a position. */
  private static final class State
  {
    final float score;
    final int last_word_id;
    final Span span;      // span of the last node
    final int entry_idx;  // index into span.entries of the last node
    final State back;

    State(float s, int lwid, Span sp, int ei, State back_)
    {
      score = s; last_word_id = lwid; span = sp; entry_idx = ei; back = back_;
    }
  }

  /**
   * Decode [letters] (lowercase a-z, possibly a double-pinyin translation)
   * into candidates. [context_word] is the word committed just before this
   * buffer (bigram context), [user_counts] maps words to usage counts,
   * [user_pinyins] maps custom learned words to their pinyin (自动造词,
   * may be null), [abbrev] enables the abbreviated-pinyin (简拼)
   * candidates.
   *
   * Returns the ranked candidates; empty when the input has no complete
   * syllable and no completion is available.
   */
  public static List<Candidate> decode(PinyinData data, String letters,
      String context_word, Map<String, Integer> user_counts,
      Map<String, String> user_pinyins, boolean abbrev)
  {
    List<Candidate> out = new ArrayList<Candidate>();
    if (data == null || letters == null || letters.isEmpty())
      return out;
    if (letters.length() > MAX_INPUT_LEN)
      letters = letters.substring(0, MAX_INPUT_LEN);
    int n = letters.length();

    // Build the lattice spans.
    List<List<Span>> spans_ending = new ArrayList<List<Span>>();
    for (int i = 0; i <= n; i++)
      spans_ending.add(null);
    boolean[] reachable = new boolean[n + 1];
    reachable[0] = true;
    List<PinyinData.Entry> buf = new ArrayList<PinyinData.Entry>();
    for (int end = 1; end <= n; end++)
    {
      for (int start = Math.max(0, end - MAX_SPAN_LEN); start < end;
          start++)
      {
        if (!reachable[start])
          continue;
        buf.clear();
        data.query_exact(letters.substring(start, end), MAX_NODES_PER_SPAN,
            buf);
        if (buf.isEmpty())
          continue;
        if (spans_ending.get(end) == null)
          spans_ending.set(end, new ArrayList<Span>());
        spans_ending.get(end).add(new Span(start, end,
              new ArrayList<PinyinData.Entry>(buf)));
        reachable[end] = true;
      }
    }
    // The conversion covers the longest prefix that ends on a syllable
    // boundary; letters beyond it are a partial syllable still being typed.
    int best_end = n;
    while (best_end > 0 && !reachable[best_end])
      best_end--;

    Set<String> seen = new HashSet<String>();
    Map<String, Integer> users = user_counts;

    if (best_end > 0)
    {
      // Viterbi with a beam over the positions 0..best_end.
      List<List<State>> beams = new ArrayList<List<State>>();
      for (int i = 0; i <= best_end; i++)
        beams.add(null);
      State start_state = new State(0f, context_word_id(data, context_word),
          null, -1, null);
      List<State> init = new ArrayList<State>();
      init.add(start_state);
      beams.set(0, init);
      List<State> finals = new ArrayList<State>();
      for (int end = 1; end <= best_end; end++)
      {
        List<Span> spans = spans_ending.get(end);
        if (spans == null)
          continue;
        List<State> beam = new ArrayList<State>();
        for (Span span : spans)
        {
          List<State> prev_beam = beams.get(span.start);
          if (prev_beam == null)
            continue;
          for (int ei = 0; ei < span.entries.size(); ei++)
          {
            PinyinData.Entry e = span.entries.get(ei);
            float uni = unigram_score(e, users);
            for (State prev : prev_beam)
            {
              float bonus = 0f;
              if (prev.span != null || prev.last_word_id >= 0)
              {
                int c = data.bigram_count(prev.last_word_id, e.word_id);
                if (c > 0)
                  bonus = Math.min(BONUS_CAP,
                      BIGRAM_W * (float)Math.log1p(c));
              }
              State st = new State(prev.score + uni + bonus, e.word_id,
                  span, ei, prev);
              beam.add(st);
            }
          }
        }
        if (beam.isEmpty())
          continue;
        java.util.Collections.sort(beam, BY_SCORE);
        if (beam.size() > BEAM)
          beam = new ArrayList<State>(beam.subList(0, BEAM));
        beams.set(end, beam);
        if (end == best_end)
          finals = beam;
      }

      // Reconstruct the best paths into sentence candidates.
      int emitted = 0;
      Candidate best_path = null;
      for (State st : finals)
      {
        if (emitted >= 5)
          break;
        StringBuilder sb = new StringBuilder();
        List<String> syls = new ArrayList<String>();
        State cur = st;
        while (cur != null && cur.span != null)
        {
          String w = cur.span.entries.get(cur.entry_idx).word;
          sb.insert(0, w);
          syls.add(0, letters.substring(cur.span.start, cur.span.end));
          cur = cur.back;
        }
        String text = sb.toString();
        if (seen.add(text))
        {
          Candidate c = new Candidate(text, best_end, syls);
          out.add(c);
          if (best_path == null)
            best_path = c;
          emitted++;
        }
      }

      // Prefix-word candidates along the best path's word boundaries: after
      // typing "jintianhaoma", 今天 must be committable on its own while the
      // rest of the buffer stays pending (mainstream behavior).
      if (best_path != null)
      {
        List<Candidate> prefix_words = new ArrayList<Candidate>();
        int k = 0;
        for (String syl : best_path.syllables)
        {
          k += syl.length();
          if (k >= best_end)
            break;
          List<PinyinData.Entry> pw = new ArrayList<PinyinData.Entry>();
          data.query_exact(letters.substring(0, k), 1, pw);
          if (!pw.isEmpty() && seen.add(pw.get(0).word))
          {
            PinyinData.Entry e = pw.get(0);
            List<String> syls = new ArrayList<String>();
            syls.add(letters.substring(0, k));
            prefix_words.add(new Candidate(e.word, k, syls));
          }
        }
        // Longest prefix first (今天 before 今), right after the full
        // sentences, like mainstream IMEs.
        java.util.Collections.reverse(prefix_words);
        out.addAll(prefix_words);
      }

      // Exact dictionary matches for the whole complete part (single
      // characters for a one-syllable input, full words otherwise),
      // frequency-ranked with user boost. These fill in candidates the beam
      // did not surface.
      List<PinyinData.Entry> exact = new ArrayList<PinyinData.Entry>();
      data.query_exact(letters.substring(0, best_end), 24, exact);
      append_user_words(exact, letters.substring(0, best_end), user_pinyins,
          user_counts);
      java.util.Collections.sort(exact, BY_EXACT_SCORE(users));
      for (PinyinData.Entry e : exact)
      {
        if (out.size() >= MAX_CANDIDATES)
          break;
        if (!seen.add(e.word))
          continue;
        List<String> syls = new ArrayList<String>();
        syls.add(letters.substring(0, best_end));
        out.add(new Candidate(e.word, best_end, syls));
      }

      // Progressive prefix extensions: words whose pinyin extends beyond
      // what was typed so far (e.g. 你好吗 while typing "nihao"). Committing
      // one consumes the whole buffer.
      {
        List<PinyinData.Entry> ext = new ArrayList<PinyinData.Entry>();
        data.query_prefix(letters, 14, ext);
        for (PinyinData.Entry e : ext)
        {
          if (out.size() >= MAX_CANDIDATES)
            break;
          if (e.pinyin.length() <= best_end || !seen.add(e.word))
            continue;
          List<String> syls = new ArrayList<String>();
          syls.add(e.pinyin);
          out.add(new Candidate(e.word, Math.min(e.pinyin.length(), n),
                syls));
        }
      }

      // 简拼: words whose pinyin initials spell the typed letters (nh ->
      // 你好), below the full-pinyin candidates like mainstream IMEs.
      if (abbrev)
        append_abbrev_candidates(data, letters, n, seen, out);
    }
    else
    {
      // No complete syllable yet. 简拼 first: the letters may be the
      // initials of a word (nh -> 你好, sz -> 深圳). Committing consumes the
      // whole buffer.
      if (abbrev && n >= 2)
      {
        List<PinyinData.Entry> ab = new ArrayList<PinyinData.Entry>();
        data.query_abbrev(letters, 14, ab);
        for (PinyinData.Entry e : ab)
        {
          if (out.size() >= MAX_CANDIDATES)
            break;
          if (!seen.add(e.word))
            continue;
          List<String> syls = new ArrayList<String>();
          syls.add(letters);
          out.add(new Candidate(e.word, n, syls));
        }
      }
      // Complete the partial syllable to its most frequent characters
      // (e.g. "x" -> 西/系/喜...), then words starting with the same
      // letters (喜欢/系统...), like mainstream IMEs. Committing consumes
      // the whole buffer.
      List<PinyinData.Entry> compl = new ArrayList<PinyinData.Entry>();
      data.query_prefix(letters, 60, compl);
      int chars = 0, words = 0;
      for (PinyinData.Entry e : compl)
      {
        if (out.size() >= MAX_CANDIDATES || (chars >= 14 && words >= 8))
          break;
        if (!seen.add(e.word))
          continue;
        if (e.word.length() == 1)
        {
          if (chars >= 14)
            continue;
          chars++;
        }
        else
        {
          if (words >= 8)
            continue;
          words++;
        }
        List<String> syls = new ArrayList<String>();
        syls.add(e.pinyin);
        out.add(new Candidate(e.word, n, syls));
      }
    }
    return out;
  }

  /** Words whose initials exactly spell [letters] (e.g. "nh" -> 你好),
      appended below the full-pinyin candidates. */
  static void append_abbrev_candidates(PinyinData data, String letters,
      int n, Set<String> seen, List<Candidate> out)
  {
    if (!data.has_abbrev())
      return;
    List<PinyinData.Entry> ab = new ArrayList<PinyinData.Entry>();
    data.query_abbrev_exact(letters, 8, ab);
    for (PinyinData.Entry e : ab)
    {
      if (out.size() >= MAX_CANDIDATES)
        break;
      if (!seen.add(e.word))
        continue;
      List<String> syls = new ArrayList<String>();
      syls.add(letters);
      out.add(new Candidate(e.word, n, syls));
    }
  }

  /** Add the user's custom words (自动造词) whose pinyin is exactly [span]
      to the exact-match list. A synthetic frequency scaled by the usage
      count ranks them near the top for their own pinyin once the user has
      committed the phrase a couple of times. */
  static void append_user_words(List<PinyinData.Entry> exact, String span,
      Map<String, String> user_pinyins, Map<String, Integer> user_counts)
  {
    if (user_pinyins == null || user_pinyins.isEmpty())
      return;
    for (Map.Entry<String, String> e : user_pinyins.entrySet())
    {
      if (!e.getValue().equals(span))
        continue;
      int count = (user_counts != null && user_counts.get(e.getKey()) != null)
        ? user_counts.get(e.getKey()) : 1;
      exact.add(new PinyinData.Entry(span, e.getKey(),
            Math.min(count * 100000, 1000000), -1));
    }
  }

  static float unigram_score(PinyinData.Entry e, Map<String, Integer> users)
  {
    float score = (float)Math.log(e.freq + 1) - WORD_PENALTY;
    if (e.word.length() == 1)
      score -= CHAR_PENALTY;
    if (users != null)
    {
      Integer c = users.get(e.word);
      if (c != null && c > 0)
        score += USER_W * (float)Math.log1p(c);
    }
    return score;
  }

  static final java.util.Comparator<State> BY_SCORE =
    new java.util.Comparator<State>()
    {
      public int compare(State a, State b)
      {
        return Float.compare(b.score, a.score);
      }
    };

  static java.util.Comparator<PinyinData.Entry> BY_EXACT_SCORE(
      final Map<String, Integer> users)
  {
    return new java.util.Comparator<PinyinData.Entry>()
    {
      public int compare(PinyinData.Entry a, PinyinData.Entry b)
      {
        float sa = (float)Math.log(a.freq + 1) + user_boost(users, a.word);
        float sb = (float)Math.log(b.freq + 1) + user_boost(users, b.word);
        return Float.compare(sb, sa);
      }
    };
  }

  static float user_boost(Map<String, Integer> users, String word)
  {
    if (users == null)
      return 0f;
    Integer c = users.get(word);
    return (c != null && c > 0) ? USER_W * (float)Math.log1p(c) : 0f;
  }

  static int context_word_id(PinyinData data, String context_word)
  {
    if (context_word == null || context_word.isEmpty())
      return -1;
    // Find the id of the most frequent entry with this word text by
    // scanning its entries through a pinyin-agnostic lookup: the word table
    // is sorted by word, so binary search it.
    return data.lookup_word_id(context_word);
  }
}
