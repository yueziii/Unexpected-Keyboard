#!/usr/bin/env python3
"""
Compile the pinyin engine data into compact binary assets:

  assets/py_dict.bin    words + single characters indexed by pinyin
  assets/py_bigram.bin  word bigram counts for sentence decoding

Both files are memory-mapped by the IME (PinyinData.java) and binary
searched, so no full parse is needed at load time.

Dictionary source: scripts/data/pinyin_dict.txt (produced by
gen_pinyin_dict.py, which merges mozillazg/pinyin-data, phrase-pinyin-data,
rime-luna-pinyin, rime-essay, jieba, THUOCL, chinese-xinhua and
chinese-poetry). Format: pinyin<TAB>word<TAB>freq, plus optional
'@abbrev<TAB>letters<TAB>word' lines mapping a latin abbreviation to a word
(e.g. yyds -> 永远的神).

Bigram source: a word-segmented corpus (People's Daily 1998-01,
scripts/data/ChineseCorpus199801.txt, GBK encoded, token/tag lines).
Adjacent token pairs where both tokens are dictionary words are counted.
The IME adds a transition bonus for observed pairs when converting whole
sentences, which is what disambiguates e.g. 这是/只是, 天气/添气.

Binary formats (big-endian, see PinyinData.java):

py_dict.bin (PYD1, version 1 — still read by older builds):
    "PYD1" int32 version=1 int32 n_entries
    entries sorted by (pinyin bytes, freq desc, word bytes):
        uint8 py_len, pinyin bytes (ascii),
        uint16 word_len, word bytes (utf8),
        int32 freq, int32 word_id
    int32 n_distinct_pinyin, int32[] first-entry offset per distinct pinyin
        (offsets relative to the start of the entry region)
    int32 n_words, word table sorted by word bytes:
        uint16 word_len, word bytes (utf8)          # word_id = table index

py_dict.bin (PYD2, version 2 — adds the abbreviated-pinyin 简拼 index):
    same header magic/version/n_entries and entry region as PYD1;
    int32 n_distinct_pinyin, int32[] pinyin offsets (as above)
    int32 n_words, word table sorted by word bytes:
        uint16 word_len, word bytes (utf8), uint32 max_freq
                                                              # word_id = index
    int32 n_abbrev_keys, int32[] first-key offset per distinct abbrev
        (offsets relative to the start of the abbrev key region)
    abbrev key region, keys sorted by abbrev bytes:
        uint8 key_len, abbrev bytes (ascii), uint8 n_ids,
        n_ids × int32 word_id (sorted by descending word max_freq, capped)

    The abbrev index maps the initials of every multi-character word (one
    entry per distinct syllabification, e.g. 西安 under both "xa" (xi'an)
    and "x" (xian)) plus the explicit @abbrev pairs, so typing "nh" or
    "yyds" surfaces 你好 / 永远的神.

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

MAX_WORD_LEN = 12
MIN_BIGRAM_COUNT = 4         # below this, co-occurrence is noise
MAX_PAIRS = 600000          # ~6 MB asset
# Abbrev (简拼) index limits. Short words keep every distinct initials
# variant (ambiguous splits like 西安 = xian/xi'an matter); long phrases
# only the canonical ones, or poem lines and xiehouyu would blow the index
# up with combinatorial variants nobody types.
ABBREV_MAX_VARIANTS_SHORT = 8   # for words up to 4 characters
ABBREV_MAX_VARIANTS_LONG = 2    # for longer words
ABBREV_MAX_IDS = 64             # words kept per initials key (top by freq)

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

def _syllables():
    global SYLLABLES
    if SYLLABLES is None:
        SYLLABLES = load_syllables()
    return SYLLABLES

def pinyin_alignment_ok(pinyin, word):
    """Whether [pinyin] can be split into exactly len(word) valid syllables
    (dynamic programming, so ambiguous splits like fang'an/fan'gan both
    pass). Rejects entries whose pinyin cannot align with the word."""
    if len(pinyin) > 6 * len(word) or len(pinyin) < len(word):
        return False
    reach = [False] * (len(pinyin) + 1)
    reach[0] = True
    for i in range(len(pinyin)):
        if not reach[i]:
            continue
        for j in range(i + 1, min(len(pinyin), i + 6) + 1):
            if reach[j]:
                continue
            if pinyin[i:j] in _syllables():
                reach[j] = True
    return reach[len(pinyin)]

def syllabification_initials(pinyin, limit):
    """Up to [limit] distinct initials strings of the valid syllable splits
    of [pinyin] (e.g. "xian" -> {"x", "xa"}). DFS with pruning; longest
    syllables first so the canonical split is found first."""
    syls = _syllables()
    out = set()
    n = len(pinyin)
    def dfs(pos, initials):
        if len(out) >= limit:
            return
        if pos == n:
            out.add(initials)
            return
        for j in range(min(n, pos + 6), pos, -1):
            s = pinyin[pos:j]
            if s in syls:
                dfs(j, initials + s[0])
                if len(out) >= limit:
                    return
    dfs(0, '')
    return out

def load_dict():
    """Load ({(pinyin, word): freq}, {abbrev: word}) from the merged
    dictionary."""
    entries = {}
    abbrevs = {}
    with open(DICT_TXT, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            if line.startswith('@abbrev'):
                parts = line.split('\t')
                if len(parts) == 3:
                    abbrevs[parts[1]] = parts[2]
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
    return entries, abbrevs

def build_abbrev_index(entries, word_id):
    """{initials: [word_id, ...]} for every multi-character word: the
    initials of each distinct syllabification of each of its pinyins. The
    explicit @abbrev pairs are merged by the caller."""
    index = collections.defaultdict(list)
    pinyins_by_word = collections.defaultdict(set)
    for (py, word), _ in entries.items():
        if len(word) >= 2:
            pinyins_by_word[word].add(py)
    for word, pinyins in pinyins_by_word.items():
        wid = word_id[word]
        limit = (ABBREV_MAX_VARIANTS_SHORT if len(word) <= 4
                 else ABBREV_MAX_VARIANTS_LONG)
        initials_set = set()
        # Sorted iteration: which pinyins fit under the variant cap must
        # not depend on set order (PYTHONHASHSEED) for reproducible builds.
        for py in sorted(pinyins):
            initials_set |= syllabification_initials(py, limit)
            if len(initials_set) >= limit:
                break
        for initials in initials_set:
            index[initials].append(wid)
    return index

def write_dict_bin(entries, abbrevs):
    """entries: {(pinyin, word): freq}; abbrevs: {letters: word}.
    Writes the PYD2 format (word table with frequencies + abbrev index)."""
    words = sorted(set(w for (_, w) in entries),
                   key=lambda w: w.encode('utf-8'))
    word_id = {w: i for i, w in enumerate(words)}
    word_freq = [0] * len(words)
    for (py, word), freq in entries.items():
        wid = word_id[word]
        if freq > word_freq[wid]:
            word_freq[wid] = freq

    # Abbrev index: initials of every multi-char word (per distinct
    # syllabification) + explicit abbreviation pairs (deduplicated: a pair
    # like pyq->朋友圈 usually matches the word's own initials).
    index = build_abbrev_index(entries, word_id)
    abbrev_dropped = 0
    for letters, word in sorted(abbrevs.items()):
        if word in word_id:
            ids = index.setdefault(letters, [])
            wid = word_id[word]
            if wid not in ids:
                ids.append(wid)
        else:
            abbrev_dropped += 1
    for initials in index:
        ids = index[initials]
        # Total order (freq desc, then word id = word byte order): the
        # output must not depend on dict/set iteration order (and thus on
        # PYTHONHASHSEED), so builds are byte-for-byte reproducible.
        ids.sort(key=lambda w: (-word_freq[w], w))
        del ids[ABBREV_MAX_IDS:]

    sorted_entries = sorted(entries.items(),
                            key=lambda kv: (kv[0][0].encode('ascii'),
                                            -kv[1], kv[0][1].encode('utf-8')))
    body = bytearray()
    offsets = []
    prev_py = None
    for (py, word), freq in sorted_entries:
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

    keys = sorted(index.keys(), key=lambda s: s.encode('ascii'))
    key_region = bytearray()
    key_offsets = []
    for k in keys:
        key_offsets.append(len(key_region))
        ids = index[k]
        key_region += struct.pack('>B', len(k))
        key_region += k.encode('ascii')
        key_region += struct.pack('>B', len(ids))
        for wid in ids:
            key_region += struct.pack('>i', wid)

    out = bytearray()
    out += struct.pack('>4sI', b'PYD2', 2)
    out += struct.pack('>I', len(entries))
    out += body
    out += struct.pack('>I', len(offsets))
    for off in offsets:
        out += struct.pack('>I', off)
    out += struct.pack('>I', len(words))
    for w, freq in zip(words, word_freq):
        wb = w.encode('utf-8')
        out += struct.pack('>H', len(wb))
        out += wb
        out += struct.pack('>I', freq)
    out += struct.pack('>I', len(keys))
    for off in key_offsets:
        out += struct.pack('>I', off)
    out += key_region
    with open(OUT_DICT, 'wb') as f:
        f.write(out)
    if abbrev_dropped:
        print('@abbrev targets missing from the dictionary: %d' %
              abbrev_dropped)
    return words, keys

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
    entries, abbrevs = load_dict()
    print('dict entries: %d' % len(entries))
    words, keys = write_dict_bin(entries, abbrevs)
    print('unique words: %d, abbrev keys: %d -> %s (%.1f MB)' % (
        len(words), len(keys), OUT_DICT, os.path.getsize(OUT_DICT) / 1e6))
    if not os.path.exists(CORPUS):
        print('Corpus missing (%s), skipping bigram.' % CORPUS)
        return
    pairs, n_tokens = count_bigrams(load_word_ids(words))
    n = write_bigram_bin(words, pairs)
    print('corpus tokens: %d, bigram pairs kept: %d -> %s (%.1f MB)' % (
        n_tokens, n, OUT_BIGRAM, os.path.getsize(OUT_BIGRAM) / 1e6))

if __name__ == '__main__':
    main()
