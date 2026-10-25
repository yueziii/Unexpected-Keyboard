#!/usr/bin/env python3
"""
Generate the built-in pinyin dictionary for Unexpected Keyboard.

Output: assets/pinyin_dict.txt
Format: pinyin<TAB>word<TAB>freq — no tones, syllables joined, ü written as v.

Corpora (clone into --data-dir; see download_pinyin_data.sh):
  mozillazg/pinyin-data         per-character pinyin
  mozillazg/phrase-pinyin-data  per-word annotated pinyin (resolves polyphones)
  rime/rime-luna-pinyin         word list with pinyin (traditional script)
  rime/rime-essay               word frequencies (large balanced corpus)
  fxsjy/jieba                   word frequencies (very large, web scale)
  thunlp/THUOCL                 domain-specific high-frequency words (the
                                mainstream head of each industry list is
                                force-included, see DOMAIN_WORDS)
  BYVoid/OpenCC                 traditional → simplified conversion
  pwxcoo/chinese-xinhua         成语 (with annotated pinyin) + 歇后语
  chinese-poetry/chinese-poetry 蒙学 anthologies (唐诗三百首/千家诗/增广贤文)
                                → famous-line segments, converted to simplified

Extra curated word lists live in scripts/data/extra/ (versioned in the repo):
  buzzwords.txt    网络热词, plus latin abbreviation pairs (yyds -> 永远的神)
  proverbs.txt     俗语谚语
  famous_lines.txt 诗词名句 beyond the anthologies
They are force-included at a tiered frequency (see *_FLOOR). Lines starting
with "@abbrev" in the output map a latin abbreviation to a word for the
abbreviated-pinyin (简拼) index built by gen_pinyin_bin.py.

Frequencies from the different corpora are merged on a common scale using a
logarithmic normalization (see normalize_freq), so words seen by several
corpora get the maximum of their normalized scores and single characters do
not automatically outrank common multi-character words.
"""
import argparse, codecs, json, math, os, re, sys, unicodedata

FREQ_SCALE = 1000000
DEFAULT_MULTI_LIMIT = 90000

# Industry vocabulary (THUOCL): force-include the top words of every domain
# list regardless of the multi-char limit, so the mainstream vocabulary of
# each field (IT, medicine, law, finance...) ships with the built-in
# dictionary. DOMAIN_WORDS caps how deep each list goes, DOMAIN_MIN_FREQ
# drops words seen only once or twice (extraction noise). Forced words rank
# at DOMAIN_FLOOR when their corpus frequency is lower: above the long-tail
# band that the multi-char cut leaves behind (freq ≈ 120), below everyday
# words, so they surface in the candidate list and take part in sentence
# decoding without displacing common words.
DOMAIN_WORDS = 6000
DOMAIN_MIN_FREQ = 3
DOMAIN_FLOOR = 1000

# Tiered floors for the force-included categories. Buzzwords must surface
# high in the candidate list; idioms only need to outrank the long tail,
# because the decoder penalizes single-character lattice nodes, so an idiom
# span always beats a same-length chain of common characters. Poem lines
# and proverbs behave like idioms.
BUZZ_FLOOR = 5000
IDIOM_FLOOR = 1000
PHRASE_FLOOR = 800
# Long phrases (poem lines, proverbs, xiehouyu) may be up to this many
# characters; ordinary corpus words keep the 6-character cap.
CATEGORY_MAX_LEN = 12
# Poetry segments shorter than this are mostly fragments already covered by
# ordinary dictionary words.
MIN_POETRY_LEN = 4

def strip_tones(p):
    n = unicodedata.normalize('NFD', p)
    # ü is 'u' + combining diaeresis after NFD; map it to 'v' before the
    # diacritics are stripped, so 绿 is indexed as "lv" (and 女 as "nv"),
    # matching what users type.
    n = n.replace('u\u0308', 'v')
    n = ''.join(c for c in n if unicodedata.category(c) != 'Mn')
    return n.lower()

def is_han(word):
    return all(0x4e00 <= ord(c) <= 0x9fff for c in word)

def normalize_freq(freq, max_freq):
    """Linearly normalize a raw frequency onto [0, FREQ_SCALE]. A linear
    scale preserves the orders of magnitude between common and rare words
    (log-compression made nearly all words look equally frequent, which
    broke sentence disambiguation). Words seen by several corpora get the
    maximum of their normalized scores."""
    if freq <= 0:
        return 0
    return int(round(FREQ_SCALE * float(freq) / max_freq))

def load_opencc(path):
    d = {}
    with codecs.open(path, 'r', 'utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            parts = line.split('\t')
            if len(parts) >= 2:
                d[parts[0]] = parts[1].split(' ')[0]
    return d

class Corpora:
    def __init__(self, data_dir):
        self.data_dir = data_dir

    def path(self, repo, rel):
        p = os.path.join(self.data_dir, repo, rel)
        if not os.path.exists(p):
            sys.exit(f"Missing corpus file: {p}\n"
                     f"Run download_pinyin_data.sh to fetch the corpora.")
        return p

    # ---- OpenCC: traditional → simplified ----
    def opencc(self):
        ts_chars = load_opencc(self.path('OpenCC',
            'data/dictionary/TSCharacters.txt'))
        ts_phrases = load_opencc(self.path('OpenCC',
            'data/dictionary/TSPhrases.txt'))
        def trad_to_simp(text):
            for trad, simp in ts_phrases.items():
                text = text.replace(trad, simp)
            return ''.join(ts_chars.get(c, c) for c in text)
        return trad_to_simp

    # ---- pinyin-data: per-character pinyin ----
    def char_pinyin(self):
        char_pinyin = {}
        with codecs.open(self.path('pinyin-data', 'pinyin.txt'), 'r', 'utf-8') as f:
            for line in f:
                line = line.strip()
                if line.startswith('#') or not line:
                    continue
                m = re.match(r'U\+([0-9A-Fa-f]+):\s*(.+?)\s*#\s*(.+)', line)
                if m:
                    cp = int(m.group(1), 16)
                    if 0x4e00 <= cp <= 0x9fff:
                        pinyins = []
                        for p in m.group(2).split(','):
                            p = strip_tones(p.strip())
                            if p and p not in pinyins:
                                pinyins.append(p)
                        char_pinyin[chr(cp)] = pinyins
        return char_pinyin

    def standard_chars(self):
        """The 8105 characters of the 通用规范汉字表 (2013): the simplified
        characters people actually type. Traditional variants and rare
        characters (錫, 浠) share pinyins with common ones but must not
        outrank them in the candidate list."""
        chars = set()
        with codecs.open(self.path('pinyin-data', 'kMandarin_8105.txt'),
                         'r', 'utf-8') as f:
            for line in f:
                m = re.match(r'U\+([0-9A-Fa-f]+):', line)
                if m:
                    cp = int(m.group(1), 16)
                    if 0x3400 <= cp <= 0x9fff:
                        chars.add(chr(cp))
        return chars

    # ---- phrase-pinyin-data: annotated word pinyin ----
    def phrase_pinyin(self, trad_to_simp):
        phrase = {}
        with codecs.open(self.path('phrase-pinyin-data', 'large_pinyin.txt'),
                         'r', 'utf-8') as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith('#') or ':' not in line:
                    continue
                word, pys = line.split(':', 1)
                word = trad_to_simp(word.strip())
                if not (2 <= len(word) <= 6) or not is_han(word):
                    continue
                syllables = []
                for p in pys.split():
                    p = strip_tones(p)
                    if not p:
                        syllables = None
                        break
                    syllables.append(p)
                if syllables and len(syllables) == len(word):
                    phrase.setdefault(word, set()).add(''.join(syllables))
        return phrase

    # ---- rime-luna-pinyin: word pinyin (traditional script) ----
    def luna_pinyin(self, trad_to_simp):
        word_pinyin_trad = {}
        with codecs.open(self.path('rime-luna-pinyin', 'luna_pinyin.dict.yaml'),
                         'r', 'utf-8') as f:
            started = False
            for line in f:
                line = line.rstrip('\n')
                if line == '...':
                    started = True
                    continue
                if not started or not line.strip() or line.startswith('#'):
                    continue
                parts = line.split('\t')
                if len(parts) >= 2:
                    word = parts[0]
                    pinyin = parts[1].strip()
                    if not (2 <= len(word) <= 6) or not is_han(word):
                        continue
                    py = ''.join(strip_tones(p) for p in pinyin.split())
                    if py:
                        word_pinyin_trad.setdefault(word, set()).add(py)
        return word_pinyin_trad

    # ---- chinese-xinhua: idioms with annotated pinyin ----
    def idioms(self):
        """[(word, pinyin_or_None)] — pinyin from the corpus when it has one
        syllable per character, None to fall back to per-character reading."""
        out = []
        with open(self.path('chinese-xinhua', 'data/idiom.json'),
                  encoding='utf-8') as f:
            for e in json.load(f):
                word = e.get('word', '')
                if not (2 <= len(word) <= CATEGORY_MAX_LEN) or not is_han(word):
                    continue
                py = None
                syls = [strip_tones(p) for p in e.get('pinyin', '').split()]
                if (len(syls) == len(word) and all(syls)
                        and all('a' <= c <= 'z' for s in syls for c in s)):
                    py = ''.join(syls)
                out.append((word, py))
        return out

    # ---- chinese-xinhua: xiehouyu riddles ----
    def xiehouyu(self):
        """Front parts of 歇后语 (the part people type), punctuation
        stripped. The punchlines are ordinary words mostly covered by the
        frequency corpora, so they are not repeated here."""
        out = set()
        with open(self.path('chinese-xinhua', 'data/xiehouyu.json'),
                  encoding='utf-8') as f:
            for e in json.load(f):
                for part in re.split(r'[，。！？；、,.!?;:]',
                                     e.get('riddle', '')):
                    part = part.strip()
                    if (MIN_POETRY_LEN <= len(part) <= CATEGORY_MAX_LEN
                            and is_han(part)):
                        out.add(part)
        return sorted(out)

    # ---- chinese-poetry 蒙学 anthologies: famous-line segments ----
    def poetry_lines(self, trad_to_simp):
        """Simplified segments (4..12 chars) of 唐诗三百首, 千家诗 and
        增广贤文 lines, split on punctuation. 增广贤文 is the classic proverb
        anthology, so it doubles as a 俗语 source."""
        out = set()
        for rel in ('tangshisanbaishou.json', 'qianjiashi.json',
                    'zengguangxianwen.json'):
            with open(self.path('chinese-poetry', '蒙学/' + rel),
                      encoding='utf-8') as f:
                doc = json.load(f)
            for para in poetry_paragraphs(doc):
                for part in re.split(r'[，。！？；、,.!?;:]', para):
                    part = trad_to_simp(part.strip())
                    if (MIN_POETRY_LEN <= len(part) <= CATEGORY_MAX_LEN
                            and is_han(part)):
                        out.add(part)
        return sorted(out)

    # ---- curated extra word lists (versioned in the repo) ----
    def extra_lists(self):
        """{filename: (words, [(abbrev, word)])} from scripts/data/extra/."""
        extra_dir = os.path.join(self.data_dir, 'extra')
        out = {}
        for name in sorted(os.listdir(extra_dir)):
            if not name.endswith('.txt'):
                continue
            words, abbrs = [], []
            with open(os.path.join(extra_dir, name), encoding='utf-8') as f:
                for line in f:
                    line = line.strip()
                    if not line or line.startswith('#'):
                        continue
                    if '\t' in line:
                        abbrev, word = line.split('\t', 1)
                        abbrev, word = abbrev.strip(), word.strip()
                        if abbrev and word:
                            abbrs.append((abbrev, word))
                    else:
                        words.append(line)
            out[name] = (words, abbrs)
        return out

    # ---- word frequencies from three corpora ----
    def thuocl_lists(self):
        """THUOCL domain word lists: [(domain, [(word, freq)]) ...] with each
        list sorted by descending frequency. Plain open() is required here:
        THUOCL_diming.txt uses CR-only line endings and a BOM, which
        codecs.open (no universal newlines) mis-parses into a single line,
        and universal newline translation plus utf-8-sig handle both."""
        lists = []
        thuocl_dir = os.path.join(self.data_dir, 'THUOCL', 'data')
        if not os.path.isdir(thuocl_dir):
            return lists
        for name in sorted(os.listdir(thuocl_dir)):
            if not name.endswith('.txt'):
                continue
            freqs = {}
            with open(os.path.join(thuocl_dir, name), 'r',
                      encoding='utf-8-sig') as f:
                for line in f:
                    parts = line.strip().split('\t')
                    if len(parts) < 2:
                        continue
                    word = parts[0].strip()
                    try:
                        freq = int(parts[1].strip())
                    except ValueError:
                        continue
                    if not (2 <= len(word) <= 6) or not is_han(word):
                        continue
                    if word not in freqs or freq > freqs[word]:
                        freqs[word] = freq
            lists.append((name[len('THUOCL_'):-len('.txt')],
                          sorted(freqs.items(), key=lambda x: -x[1])))
        return lists

    def word_freqs(self, trad_to_simp):
        essay_freq = {}
        with codecs.open(self.path('rime-essay', 'essay.txt'), 'r', 'utf-8') as f:
            for line in f:
                line = line.rstrip('\n')
                if not line:
                    continue
                parts = line.split('\t')
                if len(parts) < 2:
                    continue
                word = parts[0]
                try:
                    freq = int(parts[1])
                except ValueError:
                    continue
                if not (1 <= len(word) <= 6) or not is_han(word):
                    continue
                simp = trad_to_simp(word)
                if simp not in essay_freq or freq > essay_freq[simp]:
                    essay_freq[simp] = freq

        jieba_freq = {}
        jieba_geo_chars = set()
        with codecs.open(self.path('jieba', 'jieba/dict.txt'), 'r', 'utf-8') as f:
            for line in f:
                parts = line.strip().split(' ')
                if len(parts) < 2:
                    continue
                word = parts[0]
                try:
                    freq = int(parts[1])
                except ValueError:
                    continue
                if not (1 <= len(word) <= 6) or not is_han(word):
                    continue
                if word not in jieba_freq or freq > jieba_freq[word]:
                    jieba_freq[word] = freq
                if len(word) == 1 and len(parts) >= 3 and parts[2] == 'g':
                    # Geographic names (浠 from 浠水县 gazetteers): chars
                    # that are almost never typed standalone but carry huge
                    # raw counts. They must not rank high as single-char
                    # candidates.
                    jieba_geo_chars.add(word)

        thuocl_freq = {}
        for _domain, words in self.thuocl_lists():
            for word, freq in words:
                if word not in thuocl_freq or freq > thuocl_freq[word]:
                    thuocl_freq[word] = freq

        return essay_freq, jieba_freq, thuocl_freq, jieba_geo_chars

def poetry_paragraphs(doc):
    """All paragraph strings of a 蒙学 JSON document, whatever its nesting
    (唐诗三百首/千家诗 group by verse type, 增广贤文 by chapter)."""
    paras = []
    def walk(node):
        if isinstance(node, dict):
            if 'paragraphs' in node:
                # Some anthologies nest objects inside paragraphs; only the
                # plain strings are poem lines.
                paras.extend(p for p in node['paragraphs']
                             if isinstance(p, str))
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)
    walk(doc.get('content'))
    return paras

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    repo_dir = os.path.dirname(script_dir)
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--data-dir', default=os.path.join(script_dir, 'data'),
                    help='Directory containing the cloned corpora')
    ap.add_argument('--output',
                    default=os.path.join(script_dir, 'data', 'pinyin_dict.txt'),
                    help='Intermediate text dictionary, consumed by gen_pinyin_bin.py')
    ap.add_argument('--multi-limit', type=int, default=DEFAULT_MULTI_LIMIT,
                    help='Maximum number of multi-character word entries')
    ap.add_argument('--domain-words', type=int, default=DOMAIN_WORDS,
                    help='Top words per THUOCL domain list to force-include '
                         'regardless of the multi-char limit (0 disables)')
    args = ap.parse_args()

    corpora = Corpora(args.data_dir)
    trad_to_simp = corpora.opencc()
    char_pinyin = corpora.char_pinyin()
    standard = corpora.standard_chars()
    phrase_pinyin = corpora.phrase_pinyin(trad_to_simp)
    luna_pinyin = corpora.luna_pinyin(trad_to_simp)
    essay_freq, jieba_freq, thuocl_freq, jieba_geo_chars = \
        corpora.word_freqs(trad_to_simp)

    # Map from simplified words back to their traditional spellings, used to
    # look words up in the luna-pinyin word list (traditional script).
    simp_to_trad = {}
    with codecs.open(corpora.path('rime-essay', 'essay.txt'), 'r', 'utf-8') as f:
        for line in f:
            word = line.split('\t', 1)[0].rstrip('\n')
            if word and is_han(word):
                simp_to_trad.setdefault(trad_to_simp(word), set()).add(word)

    # Merge the frequency corpora on a common normalized scale. A word gets
    # the maximum of its normalized scores so that being frequent in any
    # corpus keeps it competitive.
    maxes = {}
    for name, d in (('essay', essay_freq), ('jieba', jieba_freq),
                    ('thuocl', thuocl_freq)):
        maxes[name] = max(d.values()) if d else 1
    def merged_freq(word):
        return max(
            normalize_freq(essay_freq.get(word, 0), maxes['essay']),
            normalize_freq(jieba_freq.get(word, 0), maxes['jieba']),
            normalize_freq(thuocl_freq.get(word, 0), maxes['thuocl']),
        )

    # Frequency for single characters. THUOCL is a set of domain word
    # lists; its place-name list gives obscure geographic characters
    # (浠, 亳, 邳) frequencies far above their real typing rate, so it is
    # excluded for characters. Words keep the full merge.
    def merged_freq_char(word):
        jieba = 0 if word in jieba_geo_chars else jieba_freq.get(word, 0)
        return max(
            normalize_freq(essay_freq.get(word, 0), maxes['essay']),
            normalize_freq(jieba, maxes['jieba']),
        )

    # simp_to_trad is built above, before this function is ever called.
    def get_word_pinyins(word):
        pinyins = set()
        if word in phrase_pinyin:
            pinyins.update(phrase_pinyin[word])
        if not pinyins:
            for tw in simp_to_trad.get(word, set()):
                if tw in luna_pinyin:
                    pinyins.update(luna_pinyin[tw])
        if not pinyins:
            # Compose from the first reading of each character; only when
            # every character of the word has a reading.
            if all(c in char_pinyin and char_pinyin[c] for c in word):
                pinyins.add(''.join(char_pinyin[c][0] for c in word))
        return pinyins

    entries = {}  # (pinyin, word) → freq

    # Single characters: every reading of every character. pinyin-data
    # orders readings most-common-first; later readings (historical or
    # literary, e.g. 是 tí, 她 jiě) must not compete with the reading people
    # actually type, so they get a heavily reduced frequency. Common
    # polyphones (了 le/liao, 行 xing/hang) keep a usable tail entry.
    # Characters outside the 通用规范汉字表 (traditional variants, rare
    # characters) are demoted so they never outrank standard ones.
    for char, pinyins in char_pinyin.items():
        freq = merged_freq_char(char)
        if freq == 0:
            freq = 500  # unseen but valid characters stay at the tail
        if char not in standard:
            freq = max(freq // 10, 50)
        for idx, py in enumerate(pinyins):
            tail = 100 if char in standard else 10
            f = freq if idx == 0 else max(freq // 200, tail)
            key = (py, char)
            if key not in entries or f > entries[key]:
                entries[key] = f

    # Multi-character words: the most frequent ones from the merged corpus.
    multi_words = set(essay_freq) | set(jieba_freq) | set(thuocl_freq)
    multi_words = [(w, merged_freq(w)) for w in multi_words if len(w) > 1]
    multi_words.sort(key=lambda x: -x[1])
    multi_words = multi_words[:args.multi_limit]

    missing = 0
    for word, freq in multi_words:
        pinyins = get_word_pinyins(word)
        if not pinyins:
            missing += 1
            continue
        for py in pinyins:
            key = (py, word)
            if key not in entries or freq > entries[key]:
                entries[key] = freq

    # Industry vocabulary: the head of every THUOCL domain list ships in the
    # built-in dictionary. These words lose the global frequency competition
    # against general words (THUOCL counts are corpus-local and most of each
    # list normalizes below the multi-char cut), so they are added here
    # explicitly, at DOMAIN_FLOOR when their natural frequency is lower.
    domain_added = 0
    if args.domain_words > 0:
        selected = set(w for w, _ in multi_words)
        for domain, words in corpora.thuocl_lists():
            for word, raw_freq in words[:args.domain_words]:
                if raw_freq < DOMAIN_MIN_FREQ or word in selected:
                    continue
                pinyins = get_word_pinyins(word)
                if not pinyins:
                    missing += 1
                    continue
                freq = max(merged_freq(word), DOMAIN_FLOOR)
                for py in pinyins:
                    key = (py, word)
                    if key not in entries or freq > entries[key]:
                        entries[key] = freq
                selected.add(word)
                domain_added += 1

    # ---- force-included categories: idioms, poem lines, proverbs,
    # xiehouyu and curated extra lists ----
    # These words are longer than the 6-character corpus cap and mostly
    # absent from the frequency corpora, so they are added explicitly at a
    # tiered floor. Long phrases surface through their exact full pinyin
    # (where a single-span lattice node beats any multi-word path), so the
    # floor only needs to keep them above the long tail.
    category_counts = {}
    abbrev_pairs = []  # (latin abbrev, word) for the 简拼 index

    def add_category(name, words, floor, pinyin_of=None):
        added = 0
        for word in words:
            if not (1 <= len(word) <= CATEGORY_MAX_LEN) or not is_han(word):
                continue
            pinyins = pinyin_of(word) if pinyin_of else get_word_pinyins(word)
            if not pinyins:
                continue
            freq = max(merged_freq(word), floor)
            for py in pinyins:
                key = (py, word)
                if key not in entries or freq > entries[key]:
                    entries[key] = freq
            added += 1
        category_counts[name] = added

    # 成语: the corpus pinyin is authoritative for polyphones inside idioms;
    # entries without a usable one fall back to the generic word pinyin.
    idioms = corpora.idioms()
    idiom_pinyin = {w: py for w, py in idioms if py}
    add_category('idioms',
                 [w for w, _ in idioms],
                 IDIOM_FLOOR,
                 lambda w: {idiom_pinyin[w]} if w in idiom_pinyin
                           else get_word_pinyins(w))

    add_category('poetry', corpora.poetry_lines(trad_to_simp), PHRASE_FLOOR)
    add_category('xiehouyu', corpora.xiehouyu(), PHRASE_FLOOR)

    extra = corpora.extra_lists()
    for fname, (words, abbrs) in extra.items():
        # Buzzwords rank high; proverbs and famous lines are lookup-only.
        floor = BUZZ_FLOOR if 'buzzword' in fname else PHRASE_FLOOR
        add_category(fname, words, floor)
        for abbrev, word in abbrs:
            # The mapped word must itself be a dictionary word for the
            # abbrev index to resolve it; add it if the lists did not.
            if not any(w == word for w in words):
                add_category(fname + '-abbrev-target', [word], floor)
            abbrev_pairs.append((abbrev, word))

    sorted_entries = sorted(entries.items(), key=lambda x: -x[1])

    with codecs.open(args.output, 'w', 'utf-8') as f:
        f.write("# Pinyin dictionary for Unexpected Keyboard\n")
        f.write("# Generated by scripts/gen_pinyin_dict.py from: pinyin-data, "
                "phrase-pinyin-data, rime-luna-pinyin, rime-essay, jieba, "
                "THUOCL, OpenCC, chinese-xinhua, chinese-poetry\n")
        f.write("# Format: pinyin<TAB>word<TAB>freq\n")
        f.write("# All pinyin without tones; syllables joined; ü = v\n")
        f.write("# Lines '@abbrev<TAB>letters<TAB>word' map a latin "
                "abbreviation to a word for the 简拼 index\n")
        for (py, word), freq in sorted_entries:
            f.write(f"{py}\t{word}\t{freq}\n")
        known = {w for (_, w), _ in sorted_entries}
        for abbrev, word in abbrev_pairs:
            if word in known:
                f.write(f"@abbrev\t{abbrev}\t{word}\n")

    size = os.path.getsize(args.output)
    single = sum(1 for (py, w), _ in sorted_entries if len(w) == 1)
    multi = sum(1 for (py, w), _ in sorted_entries if len(w) > 1)
    uchars = len(set(w for (py, w), _ in sorted_entries if len(w) == 1))
    print(f"Multi-char words missing pinyin: {missing}", file=sys.stderr)
    print(f"Domain words force-included: {domain_added}", file=sys.stderr)
    for name, count in category_counts.items():
        print(f"Category {name}: {count}", file=sys.stderr)
    print(f"Abbreviation pairs: {len(abbrev_pairs)}", file=sys.stderr)
    print(f"Output: {args.output}", file=sys.stderr)
    print(f"Total entries: {len(sorted_entries)}", file=sys.stderr)
    print(f"File size: {size} bytes ({size/1024:.0f} KB)", file=sys.stderr)
    print(f"  Single char entries: {single} ({uchars} unique chars)", file=sys.stderr)
    print(f"  Multi-char entries: {multi}", file=sys.stderr)

    test = {'你好': 'nihao', '中国': 'zhongguo', '谢谢': 'xiexie',
            '再见': 'zaijian', '什么': 'shenme', '怎么': 'zenme',
            '现在': 'xianzai', '今天': 'jintian', '钢琴': 'gangqin',
            '电脑': 'diannao', '北京': 'beijing', '手机': 'shouji',
            # categories
            '一心一意': 'yixinyiyi', '胸有成竹': 'xiongyouchengzhu',
            '床前明月光': 'chuangqianmingyueguang',
            '锄禾日当午': 'chuheridangwu',
            '显眼包': 'xianyanbao', '遥遥领先': 'yaoyaolingxian',
            '三天打鱼两天晒网': 'santiandayuliangtianshaiwang'}
    print("Verification:", file=sys.stderr)
    found = {(py, w) for (py, w), _ in sorted_entries}
    for w, expected in test.items():
        ok = (expected, w) in found
        print(f"  {w} ({expected}): {'✓' if ok else '✗'}", file=sys.stderr)

if __name__ == '__main__':
    main()
