package juloo.keyboard2;

import juloo.keyboard2.pinyin.PinyinSchemes;
import juloo.keyboard2.pinyin.PinyinSchemes.Scheme;
import java.util.List;
import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;

/**
 * Tests for the Microsoft Double Pinyin scheme (微软双拼).
 *
 * Standard Microsoft double pinyin key mapping:
 *
 * Initial (声母):
 *   b→b  c→c  d→d  f→f  g→g  h→h  j→j  k→k  l→l  m→m  n→n  p→p
 *   q→q  r→r  s→s  t→t  w→w  x→x  y→y  z→z
 *   v→zh  i→ch  u→sh  o→""(零声母)  a→""(零声母)  e→""(零声母)
 *
 * Final (韵母):
 *   a→a   b→ou  c→iao  d→iang/uang  e→e   f→en  g→eng  h→ang
 *   i→i   j→an  k→ao   l→ai    m→ian n→in  o→o/uo  p→un/ün
 *   q→iu  r→uan/er s→ong/iong t→ue(üe) u→u  v→ui  w→ia/ua  x→ie
 *   y→uai/ü  z→ei  ;→ing
 *
 * Context rules:
 *   d: "iang" after j,q,x,n,l; "uang" otherwise
 *   w: "ia" after labials (b,p,m,f); "ua" otherwise
 *   s: "ong" normally; "iong" after zero-initial
 *   r: "uan" normally; "er" after zero-initial
 *   o: "o" after labials (b,p,m,f) and zero-initial; "uo" otherwise
 *   v: "ui" normally; "ü"(written "v") after n,l
 *   t: "ue"(üe) — always "ue" (üe is represented as "ue")
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
    // O → "o" after labials (b,p,m,f) and zero-initial; "uo" otherwise
    assertEquals("o", _scheme.finals('o', "b"));
    assertEquals("o", _scheme.finals('o', "p"));
    assertEquals("o", _scheme.finals('o', "m"));
    assertEquals("o", _scheme.finals('o', "f"));
    assertEquals("o", _scheme.finals('o', ""));
    assertEquals("uo", _scheme.finals('o', "d"));
    assertEquals("uo", _scheme.finals('o', "zh"));
    assertEquals("uo", _scheme.finals('o', "g"));
  }

  @Test
  public void test_final_p_un()
  {
    // P → "un"
    assertEquals("un", _scheme.finals('p', "b"));
    assertEquals("un", _scheme.finals('p', "zh"));
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
    assertEquals("er", _scheme.finals('r', ""));
  }

  @Test
  public void test_final_s_ong_vs_iong()
  {
    // S → "ong" normally; "iong" after zero-initial
    assertEquals("ong", _scheme.finals('s', "b"));
    assertEquals("ong", _scheme.finals('s', "zh"));
    assertEquals("iong", _scheme.finals('s', ""));
  }

  @Test
  public void test_final_t_ue()
  {
    // T → "ue"(üe)
    assertEquals("ue", _scheme.finals('t', "j"));
    assertEquals("ue", _scheme.finals('t', "q"));
    assertEquals("ue", _scheme.finals('t', "x"));
    assertEquals("ue", _scheme.finals('t', "n"));
    assertEquals("ue", _scheme.finals('t', "l"));
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
  public void test_final_v_ui_vs_v()
  {
    // V → "ui" normally; "ü"(written "v") after n,l
    assertEquals("ui", _scheme.finals('v', "b"));
    assertEquals("ui", _scheme.finals('v', "zh"));
    assertEquals("ui", _scheme.finals('v', "ch"));
    assertEquals("v", _scheme.finals('v', "n"));
    assertEquals("v", _scheme.finals('v', "l"));
  }

  @Test
  public void test_final_w_ia_vs_ua()
  {
    // W → "ia" after j,q,x (palatals); "ua" otherwise
    assertEquals("ia", _scheme.finals('w', "j"));
    assertEquals("ia", _scheme.finals('w', "q"));
    assertEquals("ia", _scheme.finals('w', "x"));
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
  public void test_final_y_uai()
  {
    // Y → "uai"
    assertEquals("uai", _scheme.finals('y', "k"));
    assertEquals("uai", _scheme.finals('y', "b"));
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
  public void test_final_ee_ing()
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
    assertEquals("er", _scheme.initial('o') + _scheme.finals('r', ""));
    assertEquals("an", _scheme.initial('o') + _scheme.finals('j', ""));
    assertEquals("en", _scheme.initial('o') + _scheme.finals('f', ""));
    assertEquals("ou", _scheme.initial('o') + _scheme.finals('b', ""));
    assertEquals("a", _scheme.initial('o') + _scheme.finals('a', ""));
    assertEquals("ai", _scheme.initial('o') + _scheme.finals('l', ""));
    assertEquals("ao", _scheme.initial('o') + _scheme.finals('k', ""));
    assertEquals("ang", _scheme.initial('o') + _scheme.finals('h', ""));
    assertEquals("eng", _scheme.initial('o') + _scheme.finals('g', ""));
    assertEquals("ei", _scheme.initial('o') + _scheme.finals('z', ""));
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
    // ia/ua via W key (after labials → ia; otherwise → ua)
    assertEquals("jia", _scheme.initial('j') + _scheme.finals('w', "j"));
    assertEquals("qia", _scheme.initial('q') + _scheme.finals('w', "q"));
    assertEquals("xia", _scheme.initial('x') + _scheme.finals('w', "x"));
    assertEquals("hua", _scheme.initial('h') + _scheme.finals('w', "h"));
    assertEquals("shua", _scheme.initial('u') + _scheme.finals('w', "sh"));
  }

  @Test
  public void test_ue_syllables()
  {
    // üe via T key (represented as "ue")
    assertEquals("jue", _scheme.initial('j') + _scheme.finals('t', "j"));
    assertEquals("que", _scheme.initial('q') + _scheme.finals('t', "q"));
    assertEquals("xue", _scheme.initial('x') + _scheme.finals('t', "x"));
    assertEquals("nue", _scheme.initial('n') + _scheme.finals('t', "n"));
    assertEquals("lue", _scheme.initial('l') + _scheme.finals('t', "l"));
    assertEquals("yue", _scheme.initial('y') + _scheme.finals('t', "y"));
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
    // iao via C key
    assertEquals("jiao", _scheme.initial('j') + _scheme.finals('c', "j"));
    assertEquals("qiao", _scheme.initial('q') + _scheme.finals('c', "q"));
    assertEquals("xiao", _scheme.initial('x') + _scheme.finals('c', "x"));
    assertEquals("miao", _scheme.initial('m') + _scheme.finals('c', "m"));
    assertEquals("liao", _scheme.initial('l') + _scheme.finals('c', "l"));
    assertEquals("biao", _scheme.initial('b') + _scheme.finals('c', "b"));
    assertEquals("tiao", _scheme.initial('t') + _scheme.finals('c', "t"));
    assertEquals("niao", _scheme.initial('n') + _scheme.finals('c', "n"));
  }

  @Test
  public void test_ian_syllables()
  {
    // ian via M key
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
  }

  @Test
  public void test_in_syllables()
  {
    // in via N key
    assertEquals("jin", _scheme.initial('j') + _scheme.finals('n', "j"));
    assertEquals("qin", _scheme.initial('q') + _scheme.finals('n', "q"));
    assertEquals("xin", _scheme.initial('x') + _scheme.finals('n', "x"));
    assertEquals("lin", _scheme.initial('l') + _scheme.finals('n', "l"));
    assertEquals("bin", _scheme.initial('b') + _scheme.finals('n', "b"));
    assertEquals("pin", _scheme.initial('p') + _scheme.finals('n', "p"));
    assertEquals("min", _scheme.initial('m') + _scheme.finals('n', "m"));
    assertEquals("nin", _scheme.initial('n') + _scheme.finals('n', "n"));
    // Zero-initial "in" (音/yin) encoded as O+N; internally "in"
    assertEquals("in", _scheme.initial('o') + _scheme.finals('n', ""));
  }

  @Test
  public void test_un_syllables()
  {
    // un via P key
    assertEquals("dun", _scheme.initial('d') + _scheme.finals('p', "d"));
    assertEquals("tun", _scheme.initial('t') + _scheme.finals('p', "t"));
    assertEquals("lun", _scheme.initial('l') + _scheme.finals('p', "l"));
    assertEquals("zhun", _scheme.initial('v') + _scheme.finals('p', "zh"));
    assertEquals("cun", _scheme.initial('c') + _scheme.finals('p', "c"));
    assertEquals("sun", _scheme.initial('s') + _scheme.finals('p', "s"));
    // Zero-initial "un" (温/wen) encoded as O+P; internally "un"
    assertEquals("un", _scheme.initial('o') + _scheme.finals('p', ""));
  }

  @Test
  public void test_lv_syllables()
  {
    // ü after n,l via V key (represented as "v")
    assertEquals("lv", _scheme.initial('l') + _scheme.finals('v', "l"));
    assertEquals("nv", _scheme.initial('n') + _scheme.finals('v', "n"));
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
  }

  @Test
  public void test_uai_syllables()
  {
    // uai via Y key
    assertEquals("kuai", _scheme.initial('k') + _scheme.finals('y', "k"));
    assertEquals("guai", _scheme.initial('g') + _scheme.finals('y', "g"));
    assertEquals("huai", _scheme.initial('h') + _scheme.finals('y', "h"));
    assertEquals("zhuai", _scheme.initial('v') + _scheme.finals('y', "zh"));
    assertEquals("chuai", _scheme.initial('i') + _scheme.finals('y', "ch"));
    assertEquals("shuai", _scheme.initial('u') + _scheme.finals('y', "sh"));
    // Zero-initial "uai" (歪/wai) encoded as O+Y; internally "uai"
    assertEquals("uai", _scheme.initial('o') + _scheme.finals('y', ""));
  }

  @Test
  public void test_scheme_name() { assertEquals("Microsoft", _scheme.name()); }
}
