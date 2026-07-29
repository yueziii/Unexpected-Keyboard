package juloo.keyboard2.pinyin;

import java.util.HashMap;
import java.util.Map;

/**
 * Double Pinyin scheme mapping tables.
 *
 * In double pinyin, each Chinese syllable is encoded as exactly two
 * keystrokes: the first selects an "initial" (声母), the second selects a
 * "final" (韵母). Some finals are context-dependent on the initial.
 *
 * This class provides the Microsoft Double Pinyin scheme (微软拼音双拼).
 */
public final class PinyinSchemes
{
  public interface Scheme
  {
    public String name();
    public String initial(char key);
    public String finals(char key, String initial);
  }

  public static Scheme get(int index)
  {
    return MICROSOFT;
  }

  private static Map<Character, String> build_map(String[] entries)
  {
    Map<Character, String> m = new HashMap<Character, String>();
    for (String e : entries)
    {
      String[] kv = e.split("=", 2);
      if (kv.length == 2) m.put(kv[0].charAt(0), kv[1]);
    }
    return m;
  }

  private static abstract class TableScheme implements Scheme
  {
    protected final Map<Character, String> _initials;
    protected final Map<Character, String> _finals;
    TableScheme(String[] init_entries, String[] final_entries)
    {
      _initials = build_map(init_entries);
      _finals = build_map(final_entries);
    }
    @Override public String initial(char key)
    {
      return _initials.get(Character.toLowerCase(key));
    }
  }

  /**
   * Microsoft Double Pinyin (微软双拼).
   *
   * Initial (声母) — key → pinyin initial:
   *   b→b c→c d→d f→f g→g h→h j→j k→k l→l m→m n→n p→p q→q r→r s→s t→t
   *   w→w x→x y→y z→z
   *   v→zh  i→ch  u→sh  o→""(零声母)  a→""(零声母)  e→""(零声母)
   *
   * Final (韵母) — key → final (context-dependent where noted):
   *   a→a   b→ou  c→iao  d→iang/uang   e→e   f→en  g→eng  h→ang
   *   i→i   j→an  k→ao   l→ai    m→ian n→in  o→uo/o  p→un
   *   q→iu  r→uan/er s→ong/iong t→ue(üe) u→u v→ui/ü w→ia/ua x→ie
   *   y→uai z→ei  ;→ing
   *
   * Context rules:
   *   d: "iang" after j,q,x,n,l; "uang" otherwise
   *   w: "ia" after j,q,x (palatals); "ua" otherwise
   *   s: "ong" normally; "iong" after zero-initial
   *   r: "uan" normally; "er" after zero-initial
   *   o: "uo" normally; "o" after labials (b,p,m,f) and zero-initial
   *   v: "ui" normally; "ü"(written "v") after n,l
   */
  public static final Scheme MICROSOFT = new TableScheme(
    new String[]{
      "a=", "b=b", "c=c", "d=d", "e=", "f=f", "g=g", "h=h",
      "i=ch", "j=j", "k=k", "l=l", "m=m", "n=n", "o=", "p=p",
      "q=q", "r=r", "s=s", "t=t", "u=sh", "v=zh", "w=w", "x=x",
      "y=y", "z=z",
    },
    new String[]{
      "a=a", "b=ou", "c=iao", "d=iang", "e=e", "f=en", "g=eng",
      "h=ang", "i=i", "j=an", "k=ao", "l=ai", "m=ian", "n=in",
      "o=uo", "p=un", "q=iu", "r=uan", "s=ong", "t=ue", "u=u",
      "v=ui", "w=ia", "x=ie", "y=uai", "z=ei", ";=ing",
    }
  )
  {
    @Override public String name() { return "Microsoft"; }

    @Override
    public String finals(char key, String initial)
    {
      key = Character.toLowerCase(key);
      String base = _finals.get(key);
      if (base == null) return null;
      boolean zero = (initial == null || initial.length() == 0);
      boolean labial = is_labial(initial);
      boolean dental = is_dental(initial);
      boolean palatal = is_palatal(initial);
      switch (key)
      {
        // D: "iang" after j,q,x,n,l; "uang" otherwise (incl. labials, zero)
        case 'd': return (palatal || dental) ? "iang" : "uang";
        // W: "ia" after j,q,x (palatals); "ua" otherwise
        case 'w': return palatal ? "ia" : "ua";
        // S: "ong" normally; "iong" after zero-initial
        case 's': return zero ? "iong" : "ong";
        // R: "uan" normally; "er" after zero-initial
        case 'r': return zero ? "er" : "uan";
        // O: "uo" normally; "o" after labials and zero-initial
        case 'o': return (labial || zero) ? "o" : "uo";
        // V: "ui" normally; "ü"(written "v") after n,l
        case 'v': return dental ? "v" : "ui";
        default:  return base;
      }
    }

    private boolean is_labial(String i)
    {
      return i != null && (i.equals("b") || i.equals("p")
          || i.equals("m") || i.equals("f"));
    }
    private boolean is_dental(String i)
    {
      return i != null && (i.equals("n") || i.equals("l"));
    }
    /** j, q, x — the palatals. Used for D key's iang/uang and W key's ia/ua splits. */
    private boolean is_palatal(String i)
    {
      return i != null && (i.equals("j") || i.equals("q")
          || i.equals("x"));
    }
  };
}
