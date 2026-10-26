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
 * boost for words the user committed before; texts the user committed
 * before also lead the final list outright (same coverage only, see
 * [rank_by_user_usage]). The best paths (Viterbi with a
 * beam) become the sentence candidates; exact dictionary matches and
 * progressive prefix extensions complete the candidate list.
 *
 * Long inputs also expose the sentence's segmentation (断句): prefix words
 * along the best path's boundaries commit the head of the sentence while
 * the rest of the buffer stays pending, and the alternatives for the first
 * segment (plus its first syllable) allow correcting the first word without
 * retyping. Every candidate carries the last word of its text (tail_word),
 * which becomes the bigram context for the remaining buffer after a commit.
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
      common words. The corpus mixes news, encyclopedia and daily dialogue
      (LCCC), so both the right path and stylistic noise carry counts; the
      unigram model (sum-merged frequencies) remains the primary ranking,
      with the bigram strong enough to flip near-ties like 一只/一直. */
  static final float BIGRAM_W = 0.3f;
  static final float BONUS_CAP = 1.2f;
  /** Weight of the user history boost: USER_W * log(1 + count). At 4, one
      commit of a word beats a ~16x frequency gap in the beam search and
      three commits a ~250x gap, so a personally common word reaches the
      beam's top paths quickly; the same-coverage reordering (see
      [rank_by_user_usage]) then puts it first outright. */
  static final float USER_W = 4.0f;
  /** Candidate list size. */
  static final int MAX_CANDIDATES = 32;

  public static final class Candidate
  {
    public final String text;
    /** Pinyin letters consumed when this candidate is committed. */
    public final int letters_consumed;
    /** Syllable strings of the consumed part, for the pinyin preview. */
    public final List<String> syllables;
    /** The last word of the candidate (the tail node of its path, or the
        word itself for single-node candidates). Becomes the bigram context
        for the rest of the buffer, so continuing a sentence after
        committing one of its segments keeps the language-model context of
        the word that actually precedes the cursor. */
    public final String tail_word;

    Candidate(String t, int consumed, List<String> syls, String tail)
    {
      text = t;
      letters_consumed = consumed;
      syllables = syls;
      tail_word = tail;
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
   * into candidates. [data] is the compiled dictionary, [hotwords] the
   * cloud hotword overlay (may be null), [context_word] is the word
   * committed just before this buffer (bigram context), [user_counts] maps
   * words to usage counts, [user_pinyins] maps custom learned words to
   * their pinyin (自动造词, may be null), [abbrev] enables the
   * abbreviated-pinyin (简拼) candidates.
   *
   * Returns the ranked candidates; empty when the input has no complete
   * syllable and no completion is available.
   */
  public static List<Candidate> decode(PinyinData data,
      PinyinHotwords hotwords, String letters, String context_word,
      Map<String, Integer> user_counts, Map<String, String> user_pinyins,
      boolean abbrev)
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
        // Hotword overlay: matching hotwords become lattice nodes too, so
        // a buzzword missing from the compiled dictionary is still
        // reachable and ranks above ordinary entries.
        if (hotwords != null)
          hotwords.query_exact(letters.substring(start, end), buf);
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

    // A single-syllable input ("xian") is what it is: mainstream IMEs rank
    // its characters first and keep multi-character words sharing the whole
    // syllable (西安) below them. The lattice degenerates here (one span),
    // and its word-vs-character scoring would put 西安 above 先, so the
    // beam paths are skipped and the frequency-ranked exact matches lead.
    boolean single_syllable = best_end == n
      && PinyinSyllables.is_syllable(letters);
    Candidate best_path = null;

    if (best_end > 0 && !single_syllable)
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
      for (State st : finals)
      {
        if (emitted >= 5)
          break;
        StringBuilder sb = new StringBuilder();
        List<String> syls = new ArrayList<String>();
        // The state's own node is the LAST word of the path: it becomes the
        // bigram context for the rest of the buffer after a commit.
        String tail = st.span != null
          ? st.span.entries.get(st.entry_idx).word : null;
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
          Candidate c = new Candidate(text, best_end, syls, tail);
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
            prefix_words.add(new Candidate(e.word, k, syls, e.word));
          }
        }
        // Longest prefix first (今天 before 今), right after the full
        // sentences, like mainstream IMEs.
        java.util.Collections.reverse(prefix_words);
        out.addAll(prefix_words);
      }

      // First-segment alternatives (断句提示): the other entries matching
      // the best path's first node — and the first syllable alone when the
      // node spans several — commit just that segment and leave the rest of
      // the buffer pending, so a wrong first word can be corrected without
      // retyping the whole sentence, like mainstream IMEs.
      if (best_path != null)
      {
        int first_node = best_path.syllables.get(0).length();
        append_segment_alternatives(data, hotwords, letters, user_pinyins,
            user_counts, first_node, seen, out);
        int first_syl = PinyinSyllables.max_syllable(letters, 0);
        if (first_syl > 0 && first_syl < first_node)
          append_segment_alternatives(data, hotwords, letters, user_pinyins,
              user_counts, first_syl, seen, out);
      }
    }

    if (best_end > 0)
    {
      // Exact dictionary matches for the whole complete part (single
      // characters for a one-syllable input, full words otherwise),
      // frequency-ranked with user boost. These fill in candidates the beam
      // did not surface; for a single-syllable input they lead the list.
      List<PinyinData.Entry> exact = new ArrayList<PinyinData.Entry>();
      data.query_exact(letters.substring(0, best_end), 24, exact);
      if (hotwords != null)
        hotwords.query_exact(letters.substring(0, best_end), exact);
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
        out.add(new Candidate(e.word, best_end, syls, e.word));
      }

      // Progressive prefix extensions: words whose pinyin extends beyond
      // what was typed so far (e.g. 你好吗 while typing "nihao"). Committing
      // one consumes the whole buffer.
      {
        List<PinyinData.Entry> ext = new ArrayList<PinyinData.Entry>();
        data.query_prefix(letters, 14, ext);
        if (hotwords != null)
          hotwords.query_prefix(letters, 6, ext);
        for (PinyinData.Entry e : ext)
        {
          if (out.size() >= MAX_CANDIDATES)
            break;
          if (e.pinyin.length() <= best_end || !seen.add(e.word))
            continue;
          List<String> syls = new ArrayList<String>();
          syls.add(e.pinyin);
          out.add(new Candidate(e.word, Math.min(e.pinyin.length(), n),
                syls, e.word));
        }
      }

      // 简拼: words whose pinyin initials spell the typed letters (nh ->
      // 你好), below the full-pinyin candidates like mainstream IMEs.
      if (abbrev)
        append_abbrev_candidates(data, hotwords, letters, n, seen, out);
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
        if (hotwords != null)
          hotwords.query_abbrev_exact(letters, 6, ab);
        for (PinyinData.Entry e : ab)
        {
          if (out.size() >= MAX_CANDIDATES)
            break;
          if (!seen.add(e.word))
            continue;
          List<String> syls = new ArrayList<String>();
          syls.add(letters);
          out.add(new Candidate(e.word, n, syls, e.word));
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
        out.add(new Candidate(e.word, n, syls, e.word));
      }
    }
    // What the user committed before leads the list (see
    // [rank_by_user_usage]).
    rank_by_user_usage(out, users);
    return out;
  }

  /** Words whose initials exactly spell [letters] (e.g. "nh" -> 你好),
      appended below the full-pinyin candidates. */
  static void append_abbrev_candidates(PinyinData data,
      PinyinHotwords hotwords, String letters, int n, Set<String> seen,
      List<Candidate> out)
  {
    if (!data.has_abbrev() && (hotwords == null || !hotwords.has_abbrev()))
      return;
    List<PinyinData.Entry> ab = new ArrayList<PinyinData.Entry>();
    data.query_abbrev_exact(letters, 8, ab);
    if (hotwords != null)
      hotwords.query_abbrev_exact(letters, 4, ab);
    for (PinyinData.Entry e : ab)
    {
      if (out.size() >= MAX_CANDIDATES)
        break;
      if (!seen.add(e.word))
        continue;
      List<String> syls = new ArrayList<String>();
      syls.add(letters);
      out.add(new Candidate(e.word, n, syls, e.word));
    }
  }

  /** Exact matches for the first [upto] letters: alternatives for
      committing the best path's first segment while the rest of the buffer
      stays pending (断句提示). Frequency-ranked like the exact-match list;
      the user's custom words for the same span are included. */
  static void append_segment_alternatives(PinyinData data,
      PinyinHotwords hotwords, String letters,
      Map<String, String> user_pinyins, Map<String, Integer> user_counts,
      int upto, Set<String> seen, List<Candidate> out)
  {
    String span = letters.substring(0, upto);
    List<PinyinData.Entry> alts = new ArrayList<PinyinData.Entry>();
    data.query_exact(span, 5, alts);
    if (hotwords != null)
      hotwords.query_exact(span, alts);
    append_user_words(alts, span, user_pinyins, user_counts);
    java.util.Collections.sort(alts, BY_EXACT_SCORE(user_counts));
    for (PinyinData.Entry e : alts)
    {
      if (out.size() >= MAX_CANDIDATES)
        break;
      if (!seen.add(e.word))
        continue;
      List<String> syls = new ArrayList<String>();
      syls.add(span);
      out.add(new Candidate(e.word, upto, syls, e.word));
    }
  }

  /** Add the user's custom words (自动造词) whose pinyin is exactly [span]
      to the exact-match list. Their synthetic frequency sits above the
      dictionary's scale (which tops at 1,000,000) and grows with the usage
      count, so a committed custom word outranks every dictionary word for
      its own pinyin instead of tying with the top ones. */
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
            (int)Math.min(1000000L + (long)count * 100000L, 2000000L), -1));
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

  /** Reorder [cands] in place so the texts the user committed before come
      first, most used first, like mainstream IMEs where one's own word takes
      the top slot. Only candidates covering the same letters as the current
      head are promoted: candidates consuming fewer letters (prefix words,
      first-segment alternatives) must never displace a longer, more
      complete conversion. The remaining candidates keep their order, which
      is already frequency-ranked. */
  static void rank_by_user_usage(List<Candidate> cands,
      Map<String, Integer> users)
  {
    if (users == null || users.isEmpty() || cands.size() < 2)
      return;
    int coverage = cands.get(0).letters_consumed;
    List<Candidate> used = new ArrayList<Candidate>();
    List<Candidate> rest = new ArrayList<Candidate>();
    for (Candidate c : cands)
    {
      Integer n = users.get(c.text);
      if (c.letters_consumed == coverage && n != null && n > 0)
        used.add(c);
      else
        rest.add(c);
    }
    if (used.isEmpty())
      return;
    java.util.Collections.sort(used, new java.util.Comparator<Candidate>()
    {
      public int compare(Candidate a, Candidate b)
      {
        return users.get(b.text) - users.get(a.text);
      }
    });
    cands.clear();
    cands.addAll(used);
    cands.addAll(rest);
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
