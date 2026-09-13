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
  thunlp/THUOCL                 domain-specific high-frequency words
  BYVoid/OpenCC                 traditional → simplified conversion

Frequencies from the different corpora are merged on a common scale using a
logarithmic normalization (see normalize_freq), so words seen by several
corpora get the maximum of their normalized scores and single characters do
not automatically outrank common multi-character words.
"""
import argparse, codecs, math, os, re, sys, unicodedata

FREQ_SCALE = 1000000
DEFAULT_MULTI_LIMIT = 90000

def strip_tones(p):
    n = unicodedata.normalize('NFD', p)
    n = ''.join(c for c in n if unicodedata.category(c) != 'Mn')
    return n.lower().replace('ü', 'v')

def is_han(word):
    return all(0x4e00 <= ord(c) <= 0x9fff for c in word)

def normalize_freq(freq, max_freq):
    """Log-normalize a raw frequency onto [0, FREQ_SCALE]. The logarithm
    compresses the huge dynamic range so that words from corpora with
    different scales can be compared, while preserving the ordering."""
    if freq <= 0:
        return 0
    return int(round(FREQ_SCALE * math.log1p(freq) / math.log1p(max_freq)))

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

    # ---- word frequencies from three corpora ----
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

        thuocl_freq = {}
        thuocl_dir = os.path.join(self.data_dir, 'THUOCL', 'data')
        if os.path.isdir(thuocl_dir):
            for name in os.listdir(thuocl_dir):
                if not name.endswith('.txt'):
                    continue
                with codecs.open(os.path.join(thuocl_dir, name), 'r', 'utf-8') as f:
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
                        if word not in thuocl_freq or freq > thuocl_freq[word]:
                            thuocl_freq[word] = freq

        return essay_freq, jieba_freq, thuocl_freq

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    repo_dir = os.path.dirname(script_dir)
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--data-dir', default=os.path.join(script_dir, 'data'),
                    help='Directory containing the cloned corpora')
    ap.add_argument('--output', default=os.path.join(repo_dir, 'assets', 'pinyin_dict.txt'))
    ap.add_argument('--multi-limit', type=int, default=DEFAULT_MULTI_LIMIT,
                    help='Maximum number of multi-character word entries')
    args = ap.parse_args()

    corpora = Corpora(args.data_dir)
    trad_to_simp = corpora.opencc()
    char_pinyin = corpora.char_pinyin()
    phrase_pinyin = corpora.phrase_pinyin(trad_to_simp)
    luna_pinyin = corpora.luna_pinyin(trad_to_simp)
    essay_freq, jieba_freq, thuocl_freq = corpora.word_freqs(trad_to_simp)

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

    # Single characters: every reading of every character.
    for char, pinyins in char_pinyin.items():
        freq = merged_freq(char)
        if freq == 0:
            freq = 500  # unseen but valid characters stay at the tail
        for py in pinyins:
            key = (py, char)
            if key not in entries or freq > entries[key]:
                entries[key] = freq

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

    sorted_entries = sorted(entries.items(), key=lambda x: -x[1])

    with codecs.open(args.output, 'w', 'utf-8') as f:
        f.write("# Pinyin dictionary for Unexpected Keyboard\n")
        f.write("# Generated by scripts/gen_pinyin_dict.py from: pinyin-data, "
                "phrase-pinyin-data, rime-luna-pinyin, rime-essay, jieba, "
                "THUOCL, OpenCC\n")
        f.write("# Format: pinyin<TAB>word<TAB>freq\n")
        f.write("# All pinyin without tones; syllables joined; ü = v\n")
        for (py, word), freq in sorted_entries:
            f.write(f"{py}\t{word}\t{freq}\n")

    size = os.path.getsize(args.output)
    single = sum(1 for (py, w), _ in sorted_entries if len(w) == 1)
    multi = sum(1 for (py, w), _ in sorted_entries if len(w) > 1)
    uchars = len(set(w for (py, w), _ in sorted_entries if len(w) == 1))
    print(f"Multi-char words missing pinyin: {missing}", file=sys.stderr)
    print(f"Output: {args.output}", file=sys.stderr)
    print(f"Total entries: {len(sorted_entries)}", file=sys.stderr)
    print(f"File size: {size} bytes ({size/1024:.0f} KB)", file=sys.stderr)
    print(f"  Single char entries: {single} ({uchars} unique chars)", file=sys.stderr)
    print(f"  Multi-char entries: {multi}", file=sys.stderr)

    test = {'你好': 'nihao', '中国': 'zhongguo', '谢谢': 'xiexie',
            '再见': 'zaijian', '什么': 'shenme', '怎么': 'zenme',
            '现在': 'xianzai', '今天': 'jintian', '钢琴': 'gangqin',
            '电脑': 'diannao', '北京': 'beijing', '手机': 'shouji'}
    print("Verification:", file=sys.stderr)
    found = {(py, w) for (py, w), _ in sorted_entries}
    for w, expected in test.items():
        ok = (expected, w) in found
        print(f"  {w} ({expected}): {'✓' if ok else '✗'}", file=sys.stderr)

if __name__ == '__main__':
    main()
