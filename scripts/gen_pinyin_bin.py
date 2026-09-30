#!/usr/bin/env python3
"""
Compile the pinyin engine data into compact binary assets:

  assets/py_dict.bin    words + single characters indexed by pinyin
  assets/py_bigram.bin  word bigram counts for sentence decoding

Both files are memory-mapped by the IME (PinyinData.java) and binary
searched, so no full parse is needed at load time.

Dictionary source: assets/pinyin_dict.txt (produced by gen_pinyin_dict.py,
which merges mozillazg/pinyin-data, phrase-pinyin-data, rime-luna-pinyin,
rime-essay, jieba and THUOCL). Format: pinyin<TAB>word<TAB>freq.

Bigram source: a word-segmented corpus (People's Daily 1998-01,
scripts/data/ChineseCorpus199801.txt, GBK encoded, token/tag lines).
Adjacent token pairs where both tokens are dictionary words are counted.
The IME adds a transition bonus for observed pairs when converting whole
sentences, which is what disambiguates e.g. 这是/只是, 天气/添气.

Binary formats (big-endian, see PinyinData.java):

py_dict.bin:
    "PYD1" int32 version=1 int32 n_entries
    entries sorted by (pinyin bytes, freq desc, word bytes):
        uint8 py_len, pinyin bytes (ascii),
        uint16 word_len, word bytes (utf8),
        int32 freq, int32 word_id
    int32 n_distinct_pinyin, int32[] first-entry offset per distinct pinyin
        (offsets relative to the start of the entry region)
    int32 n_words, word table sorted by word bytes:
        uint16 word_len, word bytes (utf8)          # word_id = table index

py_bigram.bin:
    "PYB1" int32 version=1 int32 n_words int32 n_pairs
    pairs sorted by (prev_id, cur_id):
        int32 prev_id int32 cur_id uint16 count (saturating)
    int32 n_distinct_prev, int32[] first-pair offset per distinct prev
        (offsets relative to the start of the pair region)
"""
import codecs
import collections
import os
import struct
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DICT_TXT = os.path.join(REPO, 'scripts', 'data', 'pinyin_dict.txt')
CORPUS = os.path.join(REPO, 'scripts', 'data', 'ChineseCorpus199801.txt')
OUT_DICT = os.path.join(REPO, 'assets', 'py_dict.bin')
OUT_BIGRAM = os.path.join(REPO, 'assets', 'py_bigram.bin')

MAX_WORD_LEN = 8
MIN_BIGRAM_COUNT = 4         # below this, co-occurrence is noise
MAX_PAIRS = 600000          # ~6 MB asset

def is_han(word):
    return all(0x3400 <= ord(c) <= 0x9fff for c in word)

def load_syllables():
    """The standard toneless Mandarin syllables, mirroring
    PinyinSyllables.java; used to validate entry pinyins."""
    syls = set()
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           'syllables.txt')) as f:
        for line in f:
            for s in line.split():
                syls.add(s)
    return syls

SYLLABLES = None  # lazy

def pinyin_alignment_ok(pinyin, word):
    """Whether [pinyin] can be split into exactly len(word) valid syllables
    (dynamic programming, so ambiguous splits like fang'an/fan'gan both
    pass). Rejects entries whose pinyin cannot align with the word."""
    global SYLLABLES
    if SYLLABLES is None:
        SYLLABLES = load_syllables()
    n = len(pinyin)
    if n > 6 * len(word) or n < len(word):
        return False
    reach = [False] * (n + 1)
    reach[0] = True
    for i in range(n):
        if not reach[i]:
            continue
        for j in range(i + 1, min(n, i + 6) + 1):
            if reach[j]:
                continue
            if pinyin[i:j] in SYLLABLES:
                reach[j] = True
    return reach[n]

def load_dict():
    """Load (pinyin, word, freq) entries from the merged dictionary."""
    entries = {}
    with codecs.open(DICT_TXT, 'r', 'utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            parts = line.split('\t')
            if len(parts) < 2:
                parts = line.split()
            if len(parts) < 2:
                continue
            py, word = parts[0], parts[1]
            try:
                freq = int(parts[2]) if len(parts) >= 3 else 100
            except ValueError:
                freq = 100
            if not (1 <= len(word) <= MAX_WORD_LEN):
                continue
            if not is_han(word):
                continue
            if not py or not all('a' <= c <= 'z' for c in py):
                continue
            if not pinyin_alignment_ok(py, word):
                continue
            key = (py, word)
            if key not in entries or freq > entries[key]:
                entries[key] = freq
    if not entries:
        sys.exit('No entries parsed from %s' % DICT_TXT)
    return entries

def write_dict_bin(entries):
    """entries: {(pinyin, word): freq}"""
    words = sorted(set(w for (_, w) in entries),
                   key=lambda w: w.encode('utf-8'))
    word_id = {w: i for i, w in enumerate(words)}

    entries = sorted(entries.items(),
                     key=lambda kv: (kv[0][0].encode('ascii'),
                                     -kv[1], kv[0][1].encode('utf-8')))
    body = bytearray()
    offsets = []
    prev_py = None
    for (py, word), freq in entries:
        if py != prev_py:
            offsets.append(len(body))
            prev_py = py
        wbytes = word.encode('utf-8')
        body += struct.pack('>B', len(py))
        body += py.encode('ascii')
        body += struct.pack('>H', len(wbytes))
        body += wbytes
        body += struct.pack('>i', freq)
        body += struct.pack('>i', word_id[word])

    out = bytearray()
    out += struct.pack('>4sI', b'PYD1', 1)
    out += struct.pack('>I', len(entries))
    out += body
    out += struct.pack('>I', len(offsets))
    for off in offsets:
        out += struct.pack('>I', off)
    out += struct.pack('>I', len(words))
    for w in words:
        wb = w.encode('utf-8')
        out += struct.pack('>H', len(wb))
        out += wb
    with open(OUT_DICT, 'wb') as f:
        f.write(out)
    return words

def load_word_ids(words):
    word_id = {w: i for i, w in enumerate(words)}
    return word_id

def count_bigrams(word_id):
    """Count adjacent word pairs in the segmented corpus."""
    pairs = collections.Counter()
    n_tokens = 0
    with codecs.open(CORPUS, 'r', 'gbk', errors='ignore') as f:
        for line in f:
            prev = None
            for tok in line.split():
                if '/' not in tok:
                    continue
                w = tok.rsplit('/', 1)[0]
                if not (1 <= len(w) <= MAX_WORD_LEN) or not is_han(w):
                    prev = None
                    continue
                wid = word_id.get(w)
                if wid is None:
                    prev = None
                    continue
                n_tokens += 1
                if prev is not None:
                    pairs[(prev, wid)] += 1
                prev = wid
    return pairs, n_tokens

def write_bigram_bin(words, pairs):
    kept = [(p, c) for p, c in pairs.items() if c >= MIN_BIGRAM_COUNT]
    kept.sort(key=lambda pc: (-pc[1], pc[0]))
    if len(kept) > MAX_PAIRS:
        kept = kept[:MAX_PAIRS]
    kept.sort(key=lambda pc: pc[0])

    body = bytearray()
    offsets = []
    prev_prev = None
    for (prev, cur), count in kept:
        if prev != prev_prev:
            offsets.append(len(body))
            prev_prev = prev
        body += struct.pack('>IIH', prev, cur, min(count, 0xffff))

    out = bytearray()
    out += struct.pack('>4sIII', b'PYB1', 1, len(words), len(kept))
    out += body
    out += struct.pack('>I', len(offsets))
    for off in offsets:
        out += struct.pack('>I', off)
    with open(OUT_BIGRAM, 'wb') as f:
        f.write(out)
    return len(kept)

def main():
    entries = load_dict()
    print('dict entries: %d' % len(entries))
    words = write_dict_bin(entries)
    print('unique words: %d -> %s (%.1f MB)' % (
        len(words), OUT_DICT, os.path.getsize(OUT_DICT) / 1e6))
    if not os.path.exists(CORPUS):
        print('Corpus missing (%s), skipping bigram.' % CORPUS)
        return
    pairs, n_tokens = count_bigrams(load_word_ids(words))
    n = write_bigram_bin(words, pairs)
    print('corpus tokens: %d, bigram pairs kept: %d -> %s (%.1f MB)' % (
        n_tokens, n, OUT_BIGRAM, os.path.getsize(OUT_BIGRAM) / 1e6))

if __name__ == '__main__':
    main()
