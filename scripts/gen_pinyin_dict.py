#!/usr/bin/env python3
"""
Generate the final pinyin dictionary for Unexpected Keyboard.
Outputs to assets/pinyin_dict.txt
"""
import codecs, re, unicodedata, sys, os

# ============== Load OpenCC ==============
def load_opencc(path):
    d = {}
    with codecs.open(path, 'r', 'utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'): continue
            parts = line.split('\t')
            if len(parts) >= 2:
                d[parts[0]] = parts[1].split(' ')[0]
    return d

ts_chars = load_opencc('/tmp/OpenCC/data/dictionary/TSCharacters.txt')
ts_phrases = load_opencc('/tmp/OpenCC/data/dictionary/TSPhrases.txt')

def trad_to_simp(text):
    for trad, simp in ts_phrases.items():
        text = text.replace(trad, simp)
    return ''.join(ts_chars.get(c, c) for c in text)

# ============== Load char pinyin ==============
def strip_tones(p):
    n = unicodedata.normalize('NFD', p)
    n = ''.join(c for c in n if unicodedata.category(c) != 'Mn')
    return n.lower().replace('ü', 'v')

char_pinyin = {}
with codecs.open('/tmp/pinyin-data/pinyin.txt', 'r', 'utf-8') as f:
    for line in f:
        line = line.strip()
        if line.startswith('#') or not line: continue
        m = re.match(r'U\+([0-9A-Fa-f]+):\s*(.+?)\s*#\s*(.+)', line)
        if m:
            cp = int(m.group(1), 16)
            if 0x4e00 <= cp <= 0x9fff:
                pinyins = []
                for p in m.group(2).split(','):
                    p = p.strip()
                    no_tone = strip_tones(p)
                    if no_tone and no_tone not in pinyins:
                        pinyins.append(no_tone)
                char_pinyin[chr(cp)] = pinyins

# ============== Load luna_pinyin word pinyin ==============
word_pinyin_trad = {}
with codecs.open('/tmp/rime-luna-pinyin/luna_pinyin.dict.yaml', 'r', 'utf-8') as f:
    started = False
    for line in f:
        line = line.rstrip('\n')
        if line == '...': started = True; continue
        if not started or not line.strip() or line.startswith('#'): continue
        parts = line.split('\t')
        if len(parts) >= 2:
            word = parts[0]
            pinyin = parts[1].strip()
            if not (2 <= len(word) <= 6): continue
            if not all(0x4e00 <= ord(c) <= 0x9fff for c in word): continue
            py = ''.join(strip_tones(p) for p in pinyin.split())
            if py:
                word_pinyin_trad.setdefault(word, set()).add(py)

# ============== Load essay.txt ==============
simp_word_freq = {}
simp_to_trad = {}
with codecs.open('/tmp/rime-essay/essay.txt', 'r', 'utf-8') as f:
    for line in f:
        line = line.rstrip('\n')
        if not line: continue
        parts = line.split('\t')
        if len(parts) < 2: continue
        word = parts[0]
        try: freq = int(parts[1])
        except: freq = 0
        if not (1 <= len(word) <= 6): continue
        if not all(0x4e00 <= ord(c) <= 0x9fff for c in word): continue
        simp = trad_to_simp(word)
        if simp not in simp_word_freq or freq > simp_word_freq[simp]:
            simp_word_freq[simp] = freq
        simp_to_trad.setdefault(simp, set()).add(word)

# ============== Build pinyin for each word ==============
def compose_pinyin(word):
    result = ''
    for c in word:
        if c in char_pinyin and char_pinyin[c]:
            result += char_pinyin[c][0]
        else:
            return None
    return result or None

def get_word_pinyins(simp_word):
    pinyins = set()
    for tw in simp_to_trad.get(simp_word, set()):
        if tw in word_pinyin_trad:
            pinyins.update(word_pinyin_trad[tw])
    composed = compose_pinyin(simp_word)
    if composed:
        pinyins.add(composed)
    return pinyins

# ============== Generate entries ==============
entries = {}  # (pinyin, word) → freq

# Single chars (ALL)
for char, pinyins in char_pinyin.items():
    for py in pinyins:
        freq = simp_word_freq.get(char, 1)
        key = (py, char)
        if key not in entries or freq > entries[key]:
            entries[key] = freq

# Multi-char words (top 50000 by freq)
multi_words = [(w, f) for w, f in simp_word_freq.items() if len(w) > 1]
multi_words.sort(key=lambda x: -x[1])
MULTI_LIMIT = 50000

missing = 0
for word, freq in multi_words[:MULTI_LIMIT]:
    pinyins = get_word_pinyins(word)
    if not pinyins:
        missing += 1
        continue
    for py in pinyins:
        key = (py, word)
        if key not in entries or freq > entries[key]:
            entries[key] = freq

print(f"Multi-char words missing pinyin: {missing}", file=sys.stderr)
print(f"Total entries: {len(entries)}", file=sys.stderr)

# ============== Sort and write ==============
sorted_entries = sorted(entries.items(), key=lambda x: -x[1])

output_path = '/Users/rick/Code/unexpected-keyboard/assets/pinyin_dict.txt'
with codecs.open(output_path, 'w', 'utf-8') as f:
    f.write("# Pinyin dictionary for Unexpected Keyboard\n")
    f.write("# Generated from rime-essay, rime-luna-pinyin, pinyin-data, and OpenCC\n")
    f.write("# Format: pinyin<TAB>word<TAB>freq\n")
    f.write("# All pinyin without tones; syllables joined; ü = v\n")
    for (py, word), freq in sorted_entries:
        f.write(f"{py}\t{word}\t{freq}\n")

size = os.path.getsize(output_path)
single = sum(1 for (py,w),f in sorted_entries if len(w) == 1)
multi = sum(1 for (py,w),f in sorted_entries if len(w) > 1)
uchars = len(set(w for (py,w),f in sorted_entries if len(w) == 1))

print(f"\nOutput: {output_path}", file=sys.stderr)
print(f"Total entries: {len(sorted_entries)}", file=sys.stderr)
print(f"File size: {size} bytes ({size/1024:.0f} KB)", file=sys.stderr)
print(f"  Single char entries: {single} ({uchars} unique chars)", file=sys.stderr)
print(f"  Multi-char entries: {multi}", file=sys.stderr)

# Verify
test = {'你好':'nihao','中国':'zhongguo','谢谢':'xiexie','再见':'zaijian',
        '什么':'shenme','怎么':'zenme','现在':'xianzai','今天':'jintian',
        '凑':'cou','行':'xing','明':'ming','请':'qing','并':'bing'}
print("\nVerification:", file=sys.stderr)
for w, expected in test.items():
    found = any(py == expected and word == w for (py, word), f in sorted_entries)
    print(f"  {w} ({expected}): {'✓' if found else '✗'}", file=sys.stderr)
