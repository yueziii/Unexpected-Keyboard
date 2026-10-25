package juloo.keyboard2;

import juloo.keyboard2.pinyin.PinyinSchemes;
import juloo.keyboard2.pinyin.PinyinSchemes.Scheme;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;

/**
 * Tests for the Microsoft Double Pinyin scheme (微软双拼).
 *
 * Standard Microsoft double pinyin key mapping (verified against the rime
 * double_pinyin_mspy algebra and published key charts):
 *
 * Initial (声母):
 *   b→b  c→c  d→d  f→f  g→g  h→h  j→j  k→k  l→l  m→m  n→n  p→p
 *   q→q  r→r  s→s  t→t  w→w  x→x  y→y  z→z
 *   v→zh  i→ch  u→sh  o→""(零声母)  a→""(零声母)  e→""(零声母)
 *
 * Final (韵母):
 *   a→a   b→ou  c→iao  d→iang/uang  e→e   f→en  g→eng  h→ang
 *   i→i   j→an  k→ao   l→ai   m→ian  n→in  o→o/uo  p→un
 *   q→iu  r→uan/er  s→ong/iong  t→ue(üe)  u→u  v→ui/üe  w→ia/ua
 *   x→ie  y→uai/ü  z→ei  ;→ing
 *
 * Context rules (expansions are written forms, as the dictionary spells
 * them — "wo" for 我, "yao" for 要, not "wuo"/"yiao"):
 *   d: "iang" after j,q,x,n,l; "uang" otherwise
 *   w: "ia" after j,q,x,l,d; "ua" otherwise
 *   s: "iong" after j,q,x; "ong" otherwise
 *   r: "er" after zero-initial; "uan" otherwise
 *   o: "o" after b,p,m,f,w,y and zero-initial; "uo" otherwise
 *   t: "ve" (üe) after n,l (nve lve); "ue" otherwise (jue yue)
 *   v: "ve" (üe) after n,l; "ue" after j,q,x,y (jv→jue); "ui" otherwise
 *   y: "v" (ü) after n,l (ny→nv 女); "u" after j,q,x,y (jy→ju, yy→yu);
 *      "uai" otherwise
 *
 * Syllables written with a w/y glide use the w/y initial and the SIMPLE
 * final: 我=wo, 歪=w+l, 为=w+z, 文=w+f, 翁=w+g, 弯=w+j, 王=w+h,
 * 一=y+i, 要=y+k, 也=y+e, 有=y+b, 用=y+s, 元=y+r, 月=y+t, 云=y+p.
 * Zero-initial syllables (啊爱安昂熬恩鞥哦欧儿诶饿) are typed with the
 * O key (A and E also work) followed by the final's key.
 */
public class PinyinTest
{
  Scheme _scheme;

  @Before
  public void setUp() { _scheme = PinyinSchemes.get(1); }

  // --- Initial (声母) tests ---

  @Test
  public void test_basic_initials()
  {
    assertEquals("b", _scheme.initial('b'));
    assertEquals("p", _scheme.initial('p'));
    assertEquals("m", _scheme.initial('m'));
    assertEquals("d", _scheme.initial('d'));
    assertEquals("n", _scheme.initial('n'));
    assertEquals("l", _scheme.initial('l'));
    assertEquals("g", _scheme.initial('g'));
    assertEquals("h", _scheme.initial('h'));
    assertEquals("j", _scheme.initial('j'));
    assertEquals("q", _scheme.initial('q'));
    assertEquals("x", _scheme.initial('x'));
    assertEquals("r", _scheme.initial('r'));
    assertEquals("z", _scheme.initial('z'));
    assertEquals("c", _scheme.initial('c'));
    assertEquals("s", _scheme.initial('s'));
    assertEquals("t", _scheme.initial('t'));
    assertEquals("f", _scheme.initial('f'));
    assertEquals("k", _scheme.initial('k'));
    assertEquals("w", _scheme.initial('w'));
    assertEquals("y", _scheme.initial('y'));
  }

  @Test
  public void test_special_initials()
  {
    assertEquals("zh", _scheme.initial('v'));
    assertEquals("ch", _scheme.initial('i'));
    assertEquals("sh", _scheme.initial('u'));
    assertEquals("", _scheme.initial('o'));
    assertEquals("", _scheme.initial('a'));
    assertEquals("", _scheme.initial('e'));
  }

  @Test
  public void test_case_insensitive()
  {
    assertEquals("b", _scheme.initial('B'));
    assertEquals("zh", _scheme.initial('V'));
    assertEquals("ch", _scheme.initial('I'));
    assertEquals("sh", _scheme.initial('U'));
    assertEquals("ou", _scheme.finals('B', "b"));
  }

  // --- Final (韵母) tests, one per key ---

  @Test
  public void test_final_a_a()
  {
    // A → "a"
    assertEquals("a", _scheme.finals('a', "b"));
    assertEquals("a", _scheme.finals('a', ""));
  }

  @Test
  public void test_final_b_ou()
  {
    // B → "ou"
    assertEquals("ou", _scheme.finals('b', "z"));
    assertEquals("ou", _scheme.finals('b', ""));
  }

  @Test
  public void test_final_c_iao()
  {
    // C → "iao"
    assertEquals("iao", _scheme.finals('c', "j"));
    assertEquals("iao", _scheme.finals('c', "b"));
    assertEquals("iao", _scheme.finals('c', ""));
  }

  @Test
  public void test_final_d_iang_vs_uang()
  {
    // D → "iang" after j,q,x,n,l; "uang" otherwise
    assertEquals("iang", _scheme.finals('d', "j"));
    assertEquals("iang", _scheme.finals('d', "q"));
    assertEquals("iang", _scheme.finals('d', "x"));
    assertEquals("iang", _scheme.finals('d', "n"));
    assertEquals("iang", _scheme.finals('d', "l"));
    assertEquals("uang", _scheme.finals('d', "g"));
    assertEquals("uang", _scheme.finals('d', "h"));
    assertEquals("uang", _scheme.finals('d', "zh"));
    assertEquals("uang", _scheme.finals('d', "sh"));
    assertEquals("uang", _scheme.finals('d', "b"));
  }

  @Test
  public void test_final_e_e()
  {
    // E → "e"
    assertEquals("e", _scheme.finals('e', "d"));
    assertEquals("e", _scheme.finals('e', ""));
  }

  @Test
  public void test_final_f_en()
  {
    // F → "en"
    assertEquals("en", _scheme.finals('f', "b"));
    assertEquals("en", _scheme.finals('f', ""));
  }

  @Test
  public void test_final_g_eng()
  {
    // G → "eng"
    assertEquals("eng", _scheme.finals('g', "h"));
    assertEquals("eng", _scheme.finals('g', ""));
  }

  @Test
  public void test_final_h_ang()
  {
    // H → "ang"
    assertEquals("ang", _scheme.finals('h', "b"));
    assertEquals("ang", _scheme.finals('h', ""));
  }

  @Test
  public void test_final_i_i()
  {
    // I → "i"
    assertEquals("i", _scheme.finals('i', "b"));
    assertEquals("i", _scheme.finals('i', "ch"));
    assertEquals("i", _scheme.finals('i', ""));
  }

  @Test
  public void test_final_j_an()
  {
    // J → "an"
    assertEquals("an", _scheme.finals('j', "b"));
    assertEquals("an", _scheme.finals('j', ""));
  }

  @Test
  public void test_final_k_ao()
  {
    // K → "ao"
    assertEquals("ao", _scheme.finals('k', "h"));
    assertEquals("ao", _scheme.finals('k', ""));
  }

  @Test
  public void test_final_l_ai()
  {
    // L → "ai"
    assertEquals("ai", _scheme.finals('l', "z"));
    assertEquals("ai", _scheme.finals('l', ""));
  }

  @Test
  public void test_final_m_ian()
  {
    // M → "ian"
    assertEquals("ian", _scheme.finals('m', "j"));
    assertEquals("ian", _scheme.finals('m', "b"));
    assertEquals("ian", _scheme.finals('m', ""));
  }

  @Test
  public void test_final_n_in()
  {
    // N → "in"
    assertEquals("in", _scheme.finals('n', "b"));
    assertEquals("in", _scheme.finals('n', "j"));
    assertEquals("in", _scheme.finals('n', ""));
  }

  @Test
  public void test_final_o_context()
  {
    // O → "o" after labials (b,p,m,f), after w (wo 我), after y (yo 哟)
    // and after zero-initial (哦 oo); "uo" otherwise (duo guo shuo)
    assertEquals("o", _scheme.finals('o', "b"));
    assertEquals("o", _scheme.finals('o', "p"));
    assertEquals("o", _scheme.finals('o', "m"));
    assertEquals("o", _scheme.finals('o', "f"));
    assertEquals("o", _scheme.finals('o', "w"));
    assertEquals("o", _scheme.finals('o', "y"));
    assertEquals("o", _scheme.finals('o', ""));
    assertEquals("uo", _scheme.finals('o', "d"));
    assertEquals("uo", _scheme.finals('o', "zh"));
    assertEquals("uo", _scheme.finals('o', "g"));
    assertEquals("uo", _scheme.finals('o', "sh"));
  }

  @Test
  public void test_final_p_un()
  {
    // P → "un" (also the ün of jun qun xun yun, written "un")
    assertEquals("un", _scheme.finals('p', "b"));
    assertEquals("un", _scheme.finals('p', "zh"));
    assertEquals("un", _scheme.finals('p', "j"));
  }

  @Test
  public void test_final_q_iu()
  {
    // Q → "iu"
    assertEquals("iu", _scheme.finals('q', "b"));
    assertEquals("iu", _scheme.finals('q', "m"));
    assertEquals("iu", _scheme.finals('q', ""));
  }

  @Test
  public void test_final_r_uan_vs_er()
  {
    // R → "uan" normally; "er" after zero-initial
    assertEquals("uan", _scheme.finals('r', "b"));
    assertEquals("uan", _scheme.finals('r', "zh"));
    assertEquals("uan", _scheme.finals('r', "j"));
    assertEquals("uan", _scheme.finals('r', "y"));
    assertEquals("er", _scheme.finals('r', ""));
  }

  @Test
  public void test_final_s_ong_vs_iong()
  {
    // S → "iong" after j,q,x (jiong qiong xiong); "ong" otherwise
    // (zhong dong yong = y+ong)
    assertEquals("iong", _scheme.finals('s', "j"));
    assertEquals("iong", _scheme.finals('s', "q"));
    assertEquals("iong", _scheme.finals('s', "x"));
    assertEquals("ong", _scheme.finals('s', "b"));
    assertEquals("ong", _scheme.finals('s', "zh"));
    assertEquals("ong", _scheme.finals('s', "y"));
    assertEquals("ong", _scheme.finals('s', ""));
  }

  @Test
  public void test_final_t_ue()
  {
    // T → "üe" written "ve" after n,l (nve lve); "ue" otherwise
    // (jue que xue yue)
    assertEquals("ve", _scheme.finals('t', "n"));
    assertEquals("ve", _scheme.finals('t', "l"));
    assertEquals("ue", _scheme.finals('t', "j"));
    assertEquals("ue", _scheme.finals('t', "q"));
    assertEquals("ue", _scheme.finals('t', "x"));
    assertEquals("ue", _scheme.finals('t', "y"));
  }

  @Test
  public void test_final_u_u()
  {
    // U → "u"
    assertEquals("u", _scheme.finals('u', "b"));
    assertEquals("u", _scheme.finals('u', "sh"));
    assertEquals("u", _scheme.finals('u', ""));
  }

  @Test
  public void test_final_v_context()
  {
    // V → "üe" written "ve" after n,l (nv keys → nve 虐, lv → lve 略);
    // "ue" after j,q,x,y (jv → jue, yv → yue); "ui" otherwise
    assertEquals("ve", _scheme.finals('v', "n"));
    assertEquals("ve", _scheme.finals('v', "l"));
    assertEquals("ue", _scheme.finals('v', "j"));
    assertEquals("ue", _scheme.finals('v', "q"));
    assertEquals("ue", _scheme.finals('v', "x"));
    assertEquals("ue", _scheme.finals('v', "y"));
    assertEquals("ui", _scheme.finals('v', "b"));
    assertEquals("ui", _scheme.finals('v', "zh"));
    assertEquals("ui", _scheme.finals('v', "ch"));
    assertEquals("ui", _scheme.finals('v', "sh"));
    assertEquals("ui", _scheme.finals('v', "d"));
    assertEquals("ui", _scheme.finals('v', ""));
  }

  @Test
  public void test_final_w_ia_vs_ua()
  {
    // W → "ia" after j,q,x,l,d (jia xia lia dia); "ua" otherwise
    // (hua gua zhua chua shua rua)
    assertEquals("ia", _scheme.finals('w', "j"));
    assertEquals("ia", _scheme.finals('w', "q"));
    assertEquals("ia", _scheme.finals('w', "x"));
    assertEquals("ia", _scheme.finals('w', "l"));
    assertEquals("ia", _scheme.finals('w', "d"));
    assertEquals("ua", _scheme.finals('w', "h"));
    assertEquals("ua", _scheme.finals('w', "zh"));
    assertEquals("ua", _scheme.finals('w', "n"));
    assertEquals("ua", _scheme.finals('w', "b"));
    assertEquals("ua", _scheme.finals('w', "g"));
  }

  @Test
  public void test_final_x_ie()
  {
    // X → "ie"
    assertEquals("ie", _scheme.finals('x', "j"));
    assertEquals("ie", _scheme.finals('x', "b"));
    assertEquals("ie", _scheme.finals('x', ""));
  }

  @Test
  public void test_final_y_context()
  {
    // Y → "ü" written "v" after n,l (ny → nv 女, ly → lv 绿); written "u"
    // after j,q,x,y (jy → ju, yy → yu); "uai" otherwise
    assertEquals("v", _scheme.finals('y', "n"));
    assertEquals("v", _scheme.finals('y', "l"));
    assertEquals("u", _scheme.finals('y', "j"));
    assertEquals("u", _scheme.finals('y', "q"));
    assertEquals("u", _scheme.finals('y', "x"));
    assertEquals("u", _scheme.finals('y', "y"));
    assertEquals("uai", _scheme.finals('y', "g"));
    assertEquals("uai", _scheme.finals('y', "k"));
    assertEquals("uai", _scheme.finals('y', "h"));
    assertEquals("uai", _scheme.finals('y', "zh"));
    assertEquals("uai", _scheme.finals('y', "ch"));
    assertEquals("uai", _scheme.finals('y', "sh"));
    assertEquals("uai", _scheme.finals('y', ""));
  }

  @Test
  public void test_final_z_ei()
  {
    // Z → "ei"
    assertEquals("ei", _scheme.finals('z', "g"));
    assertEquals("ei", _scheme.finals('z', "b"));
    assertEquals("ei", _scheme.finals('z', ""));
  }

  @Test
  public void test_final_semicolon_ing()
  {
    // ; → "ing"
    assertEquals("ing", _scheme.finals(';', "x"));
    assertEquals("ing", _scheme.finals(';', "y"));
    assertEquals("ing", _scheme.finals(';', "m"));
    assertEquals("ing", _scheme.finals(';', "d"));
    assertEquals("ing", _scheme.finals(';', "j"));
    assertEquals("ing", _scheme.finals(';', "l"));
    assertEquals("ing", _scheme.finals(';', "b"));
    assertEquals("ing", _scheme.finals(';', "p"));
    assertEquals("ing", _scheme.finals(';', "q"));
    assertEquals("ing", _scheme.finals(';', "t"));
    assertEquals("ing", _scheme.finals(';', "n"));
  }

  // --- Complete syllable tests (initial + final) ---

  @Test
  public void test_common_syllables()
  {
    assertEquals("ni", _scheme.initial('n') + _scheme.finals('i', "n"));
    assertEquals("hao", _scheme.initial('h') + _scheme.finals('k', "h"));
    assertEquals("zhi", _scheme.initial('v') + _scheme.finals('i', "zh"));
    assertEquals("chi", _scheme.initial('i') + _scheme.finals('i', "ch"));
    assertEquals("shi", _scheme.initial('u') + _scheme.finals('i', "sh"));
    assertEquals("zhong", _scheme.initial('v') + _scheme.finals('s', "zh"));
    assertEquals("guo", _scheme.initial('g') + _scheme.finals('o', "g"));
    assertEquals("da", _scheme.initial('d') + _scheme.finals('a', "d"));
    assertEquals("de", _scheme.initial('d') + _scheme.finals('e', "d"));
    assertEquals("le", _scheme.initial('l') + _scheme.finals('e', "l"));
    assertEquals("zai", _scheme.initial('z') + _scheme.finals('l', "z"));
    assertEquals("gei", _scheme.initial('g') + _scheme.finals('z', "g"));
    // 我 wo = w + o (the O key expands to the written final "o" after w)
    assertEquals("wo", _scheme.initial('w') + _scheme.finals('o', "w"));
    assertEquals("bo", _scheme.initial('b') + _scheme.finals('o', "b"));
    assertEquals("fo", _scheme.initial('f') + _scheme.finals('o', "f"));
    // 哟 yo = y + o
    assertEquals("yo", _scheme.initial('y') + _scheme.finals('o', "y"));
  }

  @Test
  public void test_ing_syllables()
  {
    // 'ing' via ';' key
    assertEquals("xing", _scheme.initial('x') + _scheme.finals(';', "x"));
    assertEquals("ying", _scheme.initial('y') + _scheme.finals(';', "y"));
    assertEquals("ming", _scheme.initial('m') + _scheme.finals(';', "m"));
    assertEquals("ding", _scheme.initial('d') + _scheme.finals(';', "d"));
    assertEquals("jing", _scheme.initial('j') + _scheme.finals(';', "j"));
    assertEquals("ling", _scheme.initial('l') + _scheme.finals(';', "l"));
    assertEquals("bing", _scheme.initial('b') + _scheme.finals(';', "b"));
    assertEquals("ping", _scheme.initial('p') + _scheme.finals(';', "p"));
    assertEquals("qing", _scheme.initial('q') + _scheme.finals(';', "q"));
    assertEquals("ting", _scheme.initial('t') + _scheme.finals(';', "t"));
    assertEquals("ning", _scheme.initial('n') + _scheme.finals(';', "n"));
  }

  @Test
  public void test_iang_uang_syllables()
  {
    // iang/uang via D key (after j,q,x,n,l → iang; otherwise → uang)
    assertEquals("jiang", _scheme.initial('j') + _scheme.finals('d', "j"));
    assertEquals("qiang", _scheme.initial('q') + _scheme.finals('d', "q"));
    assertEquals("xiang", _scheme.initial('x') + _scheme.finals('d', "x"));
    assertEquals("liang", _scheme.initial('l') + _scheme.finals('d', "l"));
    assertEquals("niang", _scheme.initial('n') + _scheme.finals('d', "n"));
    assertEquals("guang", _scheme.initial('g') + _scheme.finals('d', "g"));
    assertEquals("huang", _scheme.initial('h') + _scheme.finals('d', "h"));
    assertEquals("zhuang", _scheme.initial('v') + _scheme.finals('d', "zh"));
    assertEquals("shuang", _scheme.initial('u') + _scheme.finals('d', "sh"));
    assertEquals("chuang", _scheme.initial('i') + _scheme.finals('d', "ch"));
  }

  @Test
  public void test_ia_ua_syllables()
  {
    // ia/ua via W key (after j,q,x,l,d → ia; otherwise → ua)
    assertEquals("jia", _scheme.initial('j') + _scheme.finals('w', "j"));
    assertEquals("qia", _scheme.initial('q') + _scheme.finals('w', "q"));
    assertEquals("xia", _scheme.initial('x') + _scheme.finals('w', "x"));
    assertEquals("lia", _scheme.initial('l') + _scheme.finals('w', "l"));
    assertEquals("dia", _scheme.initial('d') + _scheme.finals('w', "d"));
    assertEquals("hua", _scheme.initial('h') + _scheme.finals('w', "h"));
    assertEquals("gua", _scheme.initial('g') + _scheme.finals('w', "g"));
    assertEquals("kua", _scheme.initial('k') + _scheme.finals('w', "k"));
    assertEquals("zhua", _scheme.initial('v') + _scheme.finals('w', "zh"));
    assertEquals("chua", _scheme.initial('i') + _scheme.finals('w', "ch"));
    assertEquals("shua", _scheme.initial('u') + _scheme.finals('w', "sh"));
    assertEquals("rua", _scheme.initial('r') + _scheme.finals('w', "r"));
  }

  @Test
  public void test_iong_syllables()
  {
    // iong via S key after j,q,x; yong = y + ong
    assertEquals("jiong", _scheme.initial('j') + _scheme.finals('s', "j"));
    assertEquals("qiong", _scheme.initial('q') + _scheme.finals('s', "q"));
    assertEquals("xiong", _scheme.initial('x') + _scheme.finals('s', "x"));
    assertEquals("yong", _scheme.initial('y') + _scheme.finals('s', "y"));
    assertEquals("dong", _scheme.initial('d') + _scheme.finals('s', "d"));
    assertEquals("song", _scheme.initial('s') + _scheme.finals('s', "s"));
  }

  @Test
  public void test_ue_syllables()
  {
    // üe via T key: written "ue" after j,q,x,y; "ve" after n,l
    assertEquals("jue", _scheme.initial('j') + _scheme.finals('t', "j"));
    assertEquals("que", _scheme.initial('q') + _scheme.finals('t', "q"));
    assertEquals("xue", _scheme.initial('x') + _scheme.finals('t', "x"));
    assertEquals("yue", _scheme.initial('y') + _scheme.finals('t', "y"));
    assertEquals("nve", _scheme.initial('n') + _scheme.finals('t', "n"));
    assertEquals("lve", _scheme.initial('l') + _scheme.finals('t', "l"));
    // The same via the V key's üe reading
    assertEquals("jue", _scheme.initial('j') + _scheme.finals('v', "j"));
    assertEquals("yue", _scheme.initial('y') + _scheme.finals('v', "y"));
    assertEquals("nve", _scheme.initial('n') + _scheme.finals('v', "n"));
    assertEquals("lve", _scheme.initial('l') + _scheme.finals('v', "l"));
  }

  @Test
  public void test_iu_syllables()
  {
    // iu via Q key
    assertEquals("niu", _scheme.initial('n') + _scheme.finals('q', "n"));
    assertEquals("liu", _scheme.initial('l') + _scheme.finals('q', "l"));
    assertEquals("jiu", _scheme.initial('j') + _scheme.finals('q', "j"));
    assertEquals("qiu", _scheme.initial('q') + _scheme.finals('q', "q"));
    assertEquals("xiu", _scheme.initial('x') + _scheme.finals('q', "x"));
  }

  @Test
  public void test_iao_syllables()
  {
    // iao via C key; yao is written y + ao → Y + K, not Y + C
    assertEquals("jiao", _scheme.initial('j') + _scheme.finals('c', "j"));
    assertEquals("qiao", _scheme.initial('q') + _scheme.finals('c', "q"));
    assertEquals("xiao", _scheme.initial('x') + _scheme.finals('c', "x"));
    assertEquals("miao", _scheme.initial('m') + _scheme.finals('c', "m"));
    assertEquals("liao", _scheme.initial('l') + _scheme.finals('c', "l"));
    assertEquals("biao", _scheme.initial('b') + _scheme.finals('c', "b"));
    assertEquals("tiao", _scheme.initial('t') + _scheme.finals('c', "t"));
    assertEquals("niao", _scheme.initial('n') + _scheme.finals('c', "n"));
    assertEquals("yao", _scheme.initial('y') + _scheme.finals('k', "y"));
  }

  @Test
  public void test_ian_syllables()
  {
    // ian via M key; yan is written y + an → Y + J
    assertEquals("jian", _scheme.initial('j') + _scheme.finals('m', "j"));
    assertEquals("qian", _scheme.initial('q') + _scheme.finals('m', "q"));
    assertEquals("xian", _scheme.initial('x') + _scheme.finals('m', "x"));
    assertEquals("mian", _scheme.initial('m') + _scheme.finals('m', "m"));
    assertEquals("lian", _scheme.initial('l') + _scheme.finals('m', "l"));
    assertEquals("bian", _scheme.initial('b') + _scheme.finals('m', "b"));
    assertEquals("pian", _scheme.initial('p') + _scheme.finals('m', "p"));
    assertEquals("dian", _scheme.initial('d') + _scheme.finals('m', "d"));
    assertEquals("tian", _scheme.initial('t') + _scheme.finals('m', "t"));
    assertEquals("nian", _scheme.initial('n') + _scheme.finals('m', "n"));
    assertEquals("yan", _scheme.initial('y') + _scheme.finals('j', "y"));
  }

  @Test
  public void test_in_syllables()
  {
    // in via N key; yin is written y + in → Y + N
    assertEquals("jin", _scheme.initial('j') + _scheme.finals('n', "j"));
    assertEquals("qin", _scheme.initial('q') + _scheme.finals('n', "q"));
    assertEquals("xin", _scheme.initial('x') + _scheme.finals('n', "x"));
    assertEquals("lin", _scheme.initial('l') + _scheme.finals('n', "l"));
    assertEquals("bin", _scheme.initial('b') + _scheme.finals('n', "b"));
    assertEquals("pin", _scheme.initial('p') + _scheme.finals('n', "p"));
    assertEquals("min", _scheme.initial('m') + _scheme.finals('n', "m"));
    assertEquals("nin", _scheme.initial('n') + _scheme.finals('n', "n"));
    assertEquals("yin", _scheme.initial('y') + _scheme.finals('n', "y"));
  }

  @Test
  public void test_un_syllables()
  {
    // un via P key (also the ün of jun qun xun yun); wen is written
    // w + en → W + F, yun is written y + un → Y + P
    assertEquals("dun", _scheme.initial('d') + _scheme.finals('p', "d"));
    assertEquals("tun", _scheme.initial('t') + _scheme.finals('p', "t"));
    assertEquals("lun", _scheme.initial('l') + _scheme.finals('p', "l"));
    assertEquals("zhun", _scheme.initial('v') + _scheme.finals('p', "zh"));
    assertEquals("cun", _scheme.initial('c') + _scheme.finals('p', "c"));
    assertEquals("sun", _scheme.initial('s') + _scheme.finals('p', "s"));
    assertEquals("jun", _scheme.initial('j') + _scheme.finals('p', "j"));
    assertEquals("qun", _scheme.initial('q') + _scheme.finals('p', "q"));
    assertEquals("xun", _scheme.initial('x') + _scheme.finals('p', "x"));
    assertEquals("yun", _scheme.initial('y') + _scheme.finals('p', "y"));
    assertEquals("wen", _scheme.initial('w') + _scheme.finals('f', "w"));
  }

  @Test
  public void test_v_syllables()
  {
    // ü after n,l via the Y key (女 = n+y, 绿 = l+y); the V key's ü
    // reading after n,l is the üe of nve/lve
    assertEquals("nv", _scheme.initial('n') + _scheme.finals('y', "n"));
    assertEquals("lv", _scheme.initial('l') + _scheme.finals('y', "l"));
    // ü after j,q,x,y written "u": via the U key (canonical) and the
    // Y key (jy → ju, yy → yu)
    assertEquals("ju", _scheme.initial('j') + _scheme.finals('u', "j"));
    assertEquals("qu", _scheme.initial('q') + _scheme.finals('u', "q"));
    assertEquals("xu", _scheme.initial('x') + _scheme.finals('u', "x"));
    assertEquals("yu", _scheme.initial('y') + _scheme.finals('u', "y"));
    assertEquals("ju", _scheme.initial('j') + _scheme.finals('y', "j"));
    assertEquals("yu", _scheme.initial('y') + _scheme.finals('y', "y"));
  }

  @Test
  public void test_ui_syllables()
  {
    // ui via V key (not after n,l)
    assertEquals("hui", _scheme.initial('h') + _scheme.finals('v', "h"));
    assertEquals("zhui", _scheme.initial('v') + _scheme.finals('v', "zh"));
    assertEquals("chui", _scheme.initial('i') + _scheme.finals('v', "ch"));
    assertEquals("shui", _scheme.initial('u') + _scheme.finals('v', "sh"));
    assertEquals("dui", _scheme.initial('d') + _scheme.finals('v', "d"));
    assertEquals("tui", _scheme.initial('t') + _scheme.finals('v', "t"));
    assertEquals("rui", _scheme.initial('r') + _scheme.finals('v', "r"));
    assertEquals("zui", _scheme.initial('z') + _scheme.finals('v', "z"));
    assertEquals("cui", _scheme.initial('c') + _scheme.finals('v', "c"));
    assertEquals("sui", _scheme.initial('s') + _scheme.finals('v', "s"));
    assertEquals("gui", _scheme.initial('g') + _scheme.finals('v', "g"));
    assertEquals("kui", _scheme.initial('k') + _scheme.finals('v', "k"));
    // wei is written w + ei → W + Z
    assertEquals("wei", _scheme.initial('w') + _scheme.finals('z', "w"));
  }

  @Test
  public void test_uai_syllables()
  {
    // uai via Y key; wai is written w + ai → W + L
    assertEquals("kuai", _scheme.initial('k') + _scheme.finals('y', "k"));
    assertEquals("guai", _scheme.initial('g') + _scheme.finals('y', "g"));
    assertEquals("huai", _scheme.initial('h') + _scheme.finals('y', "h"));
    assertEquals("zhuai", _scheme.initial('v') + _scheme.finals('y', "zh"));
    assertEquals("chuai", _scheme.initial('i') + _scheme.finals('y', "ch"));
    assertEquals("shuai", _scheme.initial('u') + _scheme.finals('y', "sh"));
    assertEquals("wai", _scheme.initial('w') + _scheme.finals('l', "w"));
  }

  @Test
  public void test_w_glide_syllables()
  {
    // Syllables written with w: w initial + the simple final
    assertEquals("wa", _scheme.initial('w') + _scheme.finals('a', "w"));
    assertEquals("wo", _scheme.initial('w') + _scheme.finals('o', "w"));
    assertEquals("wu", _scheme.initial('w') + _scheme.finals('u', "w"));
    assertEquals("wan", _scheme.initial('w') + _scheme.finals('j', "w"));
    assertEquals("wang", _scheme.initial('w') + _scheme.finals('h', "w"));
    assertEquals("wai", _scheme.initial('w') + _scheme.finals('l', "w"));
    assertEquals("wei", _scheme.initial('w') + _scheme.finals('z', "w"));
    assertEquals("wen", _scheme.initial('w') + _scheme.finals('f', "w"));
    assertEquals("weng", _scheme.initial('w') + _scheme.finals('g', "w"));
  }

  @Test
  public void test_zero_initial_syllables()
  {
    // Zero-initial syllables via the O key (and A/E)
    assertEquals("a", _scheme.initial('o') + _scheme.finals('a', ""));
    assertEquals("o", _scheme.initial('o') + _scheme.finals('o', ""));
    assertEquals("e", _scheme.initial('o') + _scheme.finals('e', ""));
    assertEquals("ai", _scheme.initial('o') + _scheme.finals('l', ""));
    assertEquals("ei", _scheme.initial('o') + _scheme.finals('z', ""));
    assertEquals("ao", _scheme.initial('o') + _scheme.finals('k', ""));
    assertEquals("an", _scheme.initial('o') + _scheme.finals('j', ""));
    assertEquals("en", _scheme.initial('o') + _scheme.finals('f', ""));
    assertEquals("ang", _scheme.initial('o') + _scheme.finals('h', ""));
    assertEquals("eng", _scheme.initial('o') + _scheme.finals('g', ""));
    assertEquals("er", _scheme.initial('o') + _scheme.finals('r', ""));
    assertEquals("ou", _scheme.initial('o') + _scheme.finals('b', ""));
    // The same via the A/E zero-initial keys
    assertEquals("a", _scheme.initial('a') + _scheme.finals('a', ""));
    assertEquals("e", _scheme.initial('e') + _scheme.finals('e', ""));
    assertEquals("ai", _scheme.initial('a') + _scheme.finals('l', ""));
    assertEquals("en", _scheme.initial('e') + _scheme.finals('f', ""));
  }

  // --- Full coverage ---

  /**
   * Every pinyin syllable in the built-in dictionary (single-character
   * entries; interjections without initials/finals like 哼 hng excluded,
   * and 咯 "lo" which the standard scheme expands to luo like every
   * mainstream double pinyin) must be typeable with some key pair.
   */
  private static final String ALL_SYLLABLES =
    "a e o ai an ao ba bi bo bu ca ce ci cu da de di du ei en er " +
    "fa fo fu ga ge gu ha he hu ji ju ka ke ku la le li lu lv ma " +
    "me mi mo mu na ne ni nu nv ou pa pi po pu qi qu re ri ru sa " +
    "se si su ta te ti tu wa wo wu xi xu ya ye yi yo yu za ze zi " +
    "zu ang bai ban bao bei ben bie bin cai can cao cen cha che " +
    "chi chu cou cui cun cuo dai dan dao dei den dia die diu dou " +
    "dui dun duo eng fan fei fen fou gai gan gao gei gen gou gua " +
    "gui gun guo hai han hao hei hen hou hua hui hun huo jia jie " +
    "jin jiu jue jun kai kan kao kei ken kou kua kui kun kuo lai " +
    "lan lao lei len lia lie lin liu lou lun luo lve mai man mao " +
    "mei men mie min miu mou nai nan nao nei nen nie nin niu nou " +
    "nun nuo nve pai pan pao pei pen pie pin pou qia qie qin qiu " +
    "que qun ran rao ren rou rua rui run ruo sai san sao sen sha " +
    "she shi shu sou sui sun suo tai tan tao tei tie tou tui tun " +
    "tuo wai wan wei wen xia xie xin xiu xue xun yan yao yin you " +
    "yue yun zai zan zao zei zen zha zhe zhi zhu zou zui zun zuo " +
    "bang beng bian biao bing cang ceng chai chan chao chen chou " +
    "chua chui chun chuo cong cuan dang deng dian diao ding dong " +
    "duan fang feng fiao gang geng gong guai guan hang heng hong " +
    "huai huan jian jiao jing juan kang keng kong kuai kuan lang " +
    "leng lian liao ling long luan mang meng mian miao ming nang " +
    "neng nian niao ning nong nuan pang peng pian piao ping qian " +
    "qiao qing quan rang reng rong ruan sang seng shai shan shao " +
    "shei shen shou shua shui shun shuo song suan tang teng tian " +
    "tiao ting tong tuan wang weng xian xiao xing xuan yang ying " +
    "yong yuan zang zeng zhai zhan zhao zhei zhen zhou zhua zhui " +
    "zhun zhuo zong zuan chang cheng chong chuai chuan guang huang " +
    "jiang jiong kuang liang niang qiang qiong shang sheng shuai " +
    "shuan xiang xiong zhang zheng zhong zhuai zhuan chuang shuang " +
    "zhuang ";

  @Test
  public void test_every_dictionary_syllable_is_typeable()
  {
    Set<String> produced = new HashSet<String>();
    String keys = "abcdefghijklmnopqrstuvwxyz;";
    for (char k1 : keys.toCharArray())
    {
      String init = _scheme.initial(k1);
      if (init == null) continue; // ';' is never an initial
      for (char k2 : keys.toCharArray())
      {
        String fin = _scheme.finals(k2, init);
        if (fin != null) produced.add(init + fin);
      }
    }
    for (String syl : ALL_SYLLABLES.trim().split(" "))
      assertTrue("syllable not typeable: " + syl,
          produced.contains(syl));
  }

  @Test
  public void test_scheme_name() { assertEquals("Microsoft", _scheme.name()); }
}
