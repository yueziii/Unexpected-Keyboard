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
 * Every key pair expands to the standard orthographic written form of the
 * syllable — the spelling the dictionary stores — so "wo" (我) expands to
 * "wo", not the phonetic decomposition "wuo". The key tables and context
 * rules were verified against reference implementations of the scheme
 * (the rime double_pinyin_mspy algebra and published key charts).
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
   *   q→iu  r→uan/er s→ong/iong t→ue(üe) u→u v→ui/üe w→ia/ua x→ie
   *   y→uai/ü z→ei  ;→ing
   *
   * Context rules. Syllables written with a w/y glide (我 wo, 外 wai,
   * 一 yi, 要 yao ...) take the w/y initial with the SIMPLE final
   * (wai = w+l, wei = w+z, wen = w+f, yao = y+k, ye = y+e, you = y+b):
   * the glide finals (ua uai uan uang uo ui un / ia iao ie ian iang iu
   * iong) only occur after consonant initials. Expansions are written
   * forms, matched against the dictionary:
   *   d: "iang" after j,q,x,n,l (jiang qiang xiang niang liang);
   *      "uang" otherwise (guang kuang huang zhuang chuang shuang)
   *   w: "ia" after j,q,x,l,d (jia qia xia lia dia); "ua" otherwise
   *      (hua gua kua zhua chua shua rua)
   *   s: "iong" after j,q,x (jiong qiong xiong); "ong" otherwise
   *      (dong zhong yong = y+ong)
   *   r: "er" after zero-initial (儿 二 er); "uan" otherwise
   *      (duan juan quan xuan yuan ...)
   *   o: "o" after labials b,p,m,f (bo po mo fo), after w (wo 我),
   *      after y (yo 哟) and after zero-initial (哦 oo); "uo" otherwise
   *      (duo guo kuo huo zhuo chuo shuo ruo zuo cuo suo tuo nuo luo)
   *   t: "üe" written "ve" after n,l (nve 虐, lve 略); "ue" otherwise
   *      (jue que xue yue)
   *   v: "üe" written "ve" after n,l (nv keys → nve 虐, lv → lve 略);
   *      "ue" after j,q,x,y (jv → jue 决, yv → yue 月); "ui" otherwise
   *      (dui tui gui kui hui zhui chui shui rui zui cui sui)
   *   y: "ü" written "v" after n,l (ny → nv 女, ly → lv 绿); written "u"
   *      after j,q,x,y (jy → ju 居, yy → yu 雨); "uai" otherwise
   *      (guai kuai huai zhuai chuai shuai)
   *
   * Zero-initial syllables (啊爱安昂熬恩鞥哦欧儿诶饿) are typed with the
   * O key (A and E also work) followed by the final's key: ol=爱, oj=安,
   * oh=昂, ok=熬, of=恩, og=鞥, oz=诶, ob=欧, or=儿, oo=哦. Key pairs
   * whose final never occurs in that position (e.g. oc, os, ow) expand
   * to bare glide finals ("iao", "ong", "ua") that match no syllable and
   * simply produce no candidates.
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
      String init = initial == null ? "" : initial;
      switch (key)
      {
        // D: "iang" after j,q,x,n,l; "uang" otherwise
        case 'd': return (is_palatal(init) || is_dental(init))
            ? "iang" : "uang";
        // W: "ia" after j,q,x,l,d; "ua" otherwise
        case 'w': return (is_palatal(init) || init.equals("l")
            || init.equals("d")) ? "ia" : "ua";
        // S: "iong" after j,q,x; "ong" otherwise
        case 's': return is_palatal(init) ? "iong" : "ong";
        // R: "er" after zero-initial; "uan" otherwise
        case 'r': return init.isEmpty() ? "er" : "uan";
        // O: "o" after labials, w, y and zero-initial; "uo" otherwise
        case 'o': return (is_labial(init) || init.equals("w")
            || init.equals("y") || init.isEmpty()) ? "o" : "uo";
        // T: "üe" written "ve" after n,l; "ue" otherwise
        case 't': return is_dental(init) ? "ve" : "ue";
        // V: "üe" written "ve" after n,l; "ue" after j,q,x,y;
        //    "ui" otherwise
        case 'v':
          if (is_dental(init)) return "ve";
          if (is_palatal(init) || init.equals("y")) return "ue";
          return "ui";
        // Y: "ü" written "v" after n,l; written "u" after j,q,x,y;
        //    "uai" otherwise
        case 'y':
          if (is_dental(init)) return "v";
          if (is_palatal(init) || init.equals("y")) return "u";
          return "uai";
        default:  return base;
      }
    }

    private boolean is_labial(String i)
    {
      return i.equals("b") || i.equals("p")
          || i.equals("m") || i.equals("f");
    }
    private boolean is_dental(String i)
    {
      return i.equals("n") || i.equals("l");
    }
    /** j, q, x — the palatals. */
    private boolean is_palatal(String i)
    {
      return i.equals("j") || i.equals("q")
          || i.equals("x");
    }
  };
}
