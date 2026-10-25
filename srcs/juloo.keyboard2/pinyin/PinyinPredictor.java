package juloo.keyboard2.pinyin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Next-word prediction (联想): what the user is likely to type after the
 * last committed word.
 *
 * Two sources are merged, the user's own history first: word pairs the
 * user committed in sequence (PinyinUserDict), then the corpus bigram
 * continuations (PinyinData). Single characters (的, 是, 了...) surface
 * naturally through the bigram, which is what mainstream IMEs show after a
 * commit. Tapping a prediction commits it and re-predicts, chaining the
 * association (mainstream 联想 behavior).
 */
public final class PinyinPredictor
{
  /** Maximum predictions shown in the candidates bar. */
  public static final int MAX = 10;
  /** How many corpus continuations to consider before filtering. */
  static final int CORPUS_CANDIDATES = 2 * MAX;
  /** Words longer than this are not predicted. */
  public static final int MAX_LEN = 4;

  /**
   * Predict the likely next words after [context_word]. [user_pairs] is the
   * live pair map from PinyinUserDict (prev -> (cur -> count)), may be
   * null. Returns at most [MAX] words, user history first; empty when
   * nothing is known about the context.
   */
  public static List<String> predict(PinyinData data, String context_word,
      Map<String, Map<String, Integer>> user_pairs)
  {
    List<String> out = new ArrayList<String>();
    if (context_word == null || context_word.isEmpty())
      return out;
    Set<String> seen = new HashSet<String>();
    // The user's own continuations first, most used first.
    if (user_pairs != null)
    {
      Map<String, Integer> succ = user_pairs.get(context_word);
      if (succ != null && !succ.isEmpty())
      {
        List<Map.Entry<String, Integer>> entries =
          new ArrayList<Map.Entry<String, Integer>>(succ.entrySet());
        java.util.Collections.sort(entries,
            new java.util.Comparator<Map.Entry<String, Integer>>()
            {
              public int compare(Map.Entry<String, Integer> a,
                  Map.Entry<String, Integer> b)
              {
                return b.getValue() - a.getValue();
              }
            });
        for (Map.Entry<String, Integer> e : entries)
        {
          if (out.size() >= MAX)
            break;
          String w = e.getKey();
          if (accept_word(w, seen))
            out.add(w);
        }
      }
    }
    // Corpus bigram continuations fill the rest.
    if (data != null && data.has_bigram() && out.size() < MAX)
    {
      int prev_id = data.lookup_word_id(context_word);
      if (prev_id >= 0)
      {
        for (PinyinData.BigramCand c : data.bigram_top(prev_id,
              CORPUS_CANDIDATES))
        {
          if (out.size() >= MAX)
            break;
          String w = data.word_of(c.word_id);
          if (accept_word(w, seen))
            out.add(w);
        }
      }
    }
    return out;
  }

  static boolean accept_word(String w, Set<String> seen)
  {
    return w != null && !w.isEmpty() && w.length() <= MAX_LEN
      && seen.add(w);
  }

  private PinyinPredictor() {}
}
