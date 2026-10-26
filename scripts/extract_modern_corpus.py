#!/usr/bin/env python3
"""Extract modern-Chinese sentences for the bigram model.

Sources (both raw, unsegmented — segmentation happens in gen_pinyin_bin.py
with jieba; see download_pinyin_data.sh for how they are fetched):
  THUCNews (cnews.train.txt)   2005-2011 news articles, 10 categories
                               (体育/财经/科技/游戏/教育...). One article
                               per line, "label<TAB>text".
  zhwiki multistream head      Chinese Wikipedia dump, first bytes of the
                               multistream bz2 (independent bz2 streams, so
                               a ranged partial download decompresses
                               cleanly). Encyclopedic modern written
                               Chinese, wide technical vocabulary.
  LCCC (lccc_base_train)       Daily Chinese dialogue corpus. Colloquial
                               register that news/wiki lack (有空吗, 请你
                               吃饭); one dialogue per line as a JSON
                               array of turns. Each turn becomes its own
                               sentences: no bigram pairs across speakers.

Output: one sentence per line, punctuation-free Han-only sentences of
2..64 characters, UTF-8. The 1998 People's Daily corpus remains the
segmented seed in gen_pinyin_bin.py; this file adds the modern scale.
"""
import argparse
import bz2
import html
import os
import re
import sys

SCRIPTS = os.path.dirname(os.path.abspath(__file__))
MODERN_DIR = os.path.join(SCRIPTS, 'data', 'modern')
DEFAULT_CNEWS = os.path.join(MODERN_DIR, 'cnews.train.txt')
DEFAULT_WIKI = os.path.join(MODERN_DIR, 'zhwiki-head.xml.bz2')
DEFAULT_LCCC = os.path.join(MODERN_DIR, 'lccc_base_train.jsonl.gz')
DEFAULT_OUT = os.path.join(MODERN_DIR, 'modern_sentences.txt')

MIN_LEN = 2
MAX_LEN = 64
# Stop after this many characters of extracted text per source; the bigram
# counts saturate long before (the bin writer keeps at most 600k pairs).
MAX_CHARS_PER_SOURCE = 80_000_000

SENT_SPLIT = re.compile(r'[。！？；\n\r]')

# Wiki markup to drop before sentence splitting.
WIKI_COMMENT = re.compile(r'<!--.*?-->', re.S)
WIKI_REF = re.compile(r'<ref[^>]*/>|<ref[^>]*>.*?</ref>', re.S | re.I)
WIKI_TAG = re.compile(r'<[^>]+>')
WIKI_TEMPLATE = re.compile(r'\{\{[^{}]*\}\}', re.S)
WIKI_LINK = re.compile(r'\[\[(?:[^|\]]*\|)?([^\]]*)\]\]')
WIKI_HEAD = re.compile(r'^=+.*?=+\s*$', re.M)
WIKI_URL = re.compile(r'https?://\S+|\[.*?\]')

TEXT_OPEN = re.compile(r'<text[^>]*>')


HAN_CHAR = re.compile(r'[\u4e00-\u9fff]')


def sentences(text, out, seen_len):
    """Emit each sentence of [text] as one line. Non-Han characters stay:
    numbers and latin words must keep their positions so the segmentation
    stage (which drops non-Han tokens and breaks the pair chain there) sees
    the real word boundaries — stripping them here would forge fake
    collocations like 慢跑了+分钟 out of 慢跑了25分钟."""
    for sent in SENT_SPLIT.split(text):
        sent = sent.strip()
        han = len(HAN_CHAR.findall(sent))
        if MIN_LEN <= han <= MAX_LEN:
            out.write(sent + '\n')
            seen_len[0] += han


def extract_cnews(path, out):
    seen_len = [0]
    with open(path, encoding='utf-8', errors='ignore') as f:
        for line in f:
            if '\t' not in line:
                continue
            _label, text = line.split('\t', 1)
            # News articles carry a headline before the body; keep both.
            sentences(text, out, seen_len)
            if seen_len[0] >= MAX_CHARS_PER_SOURCE:
                print('cnews: reached the per-source cap', file=sys.stderr)
                break
    return seen_len[0]


def extract_lccc(path, out):
    """LCCC: one dialogue per line as a JSON array of turns; turns carry
    character-level spaces (你 去 那儿) to strip. Each turn's sentences are
    written as separate lines, so the bigram counter (which resets per
    line) never pairs words across speakers."""
    import gzip
    import json
    seen_len = [0]
    with gzip.open(path, 'rt', encoding='utf-8', errors='ignore') as f:
        for line in f:
            try:
                turns = json.loads(line)
            except ValueError:
                continue
            if not isinstance(turns, list):
                continue
            for turn in turns:
                if isinstance(turn, str):
                    sentences(turn.replace(' ', ''), out, seen_len)
            if seen_len[0] >= MAX_CHARS_PER_SOURCE:
                print('lccc: reached the per-source cap', file=sys.stderr)
                break
    return seen_len[0]


def wiki_text_chunks(path, out):
    """Stream-decompress the multistream dump and emit the sentences of
    <text> elements, until the cap is reached. A multistream dump is a
    concatenation of independent bz2 streams (one per 100 pages), so the
    ranged partial download decompresses cleanly; the last stream is
    truncated and reported by the decompressor as garbage, which ends the
    scan."""
    seen_len = [0]
    buf = ''
    pages = 0
    with open(path, 'rb') as f:
        decompressor = bz2.BZ2Decompressor()
        while True:
            chunk = f.read(1 << 20)
            if not chunk:
                break
            data = chunk
            while data:
                try:
                    xml = decompressor.decompress(data)
                except OSError:
                    data = b''  # truncated trailing stream; done
                    break
                if decompressor.eof:
                    # Finished one member stream; the rest belongs to the
                    # next member.
                    data = decompressor.unused_data
                    decompressor = bz2.BZ2Decompressor()
                else:
                    data = b''
                if not xml:
                    continue
                buf += xml.decode('utf-8', 'ignore')
                # Process complete <text ...>...</text> elements.
                while True:
                    m = TEXT_OPEN.search(buf)
                    if not m:
                        break
                    close = buf.find('</text>', m.end())
                    if close < 0:
                        # Element continues into the next chunk.
                        buf = buf[m.end():]
                        break
                    body = buf[m.end():close]
                    buf = buf[close + len('</text>'):]
                    pages += 1
                    sentences(clean_wiki(body), out, seen_len)
                if len(buf) > (1 << 20) and '<text' not in buf:
                    buf = buf[-4096:]
                if seen_len[0] >= MAX_CHARS_PER_SOURCE:
                    print('wiki: reached the per-source cap', file=sys.stderr)
                    return seen_len[0], pages
    return seen_len[0], pages


def clean_wiki(body):
    body = WIKI_COMMENT.sub(' ', body)
    body = WIKI_REF.sub(' ', body)
    body = WIKI_TAG.sub(' ', body)
    # Templates may nest; strip a few levels.
    for _ in range(3):
        body = WIKI_TEMPLATE.sub(' ', body)
    body = WIKI_LINK.sub(r'\1', body)
    body = WIKI_HEAD.sub(' ', body)
    body = WIKI_URL.sub(' ', body)
    return html.unescape(body)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--cnews', default=DEFAULT_CNEWS)
    ap.add_argument('--wiki', default=DEFAULT_WIKI)
    ap.add_argument('--lccc', default=DEFAULT_LCCC)
    ap.add_argument('--output', default=DEFAULT_OUT)
    args = ap.parse_args()

    total = 0
    with open(args.output, 'w', encoding='utf-8') as out:
        for name, path, fn in (
                ('THUCNews', args.cnews, extract_cnews),
                ('LCCC', args.lccc, extract_lccc),
                ('zhwiki', args.wiki, wiki_text_chunks)):
            if os.path.exists(path):
                n = fn(path, out)
                if isinstance(n, tuple):
                    n, pages = n
                    print(f'{name}: {n} chars from {pages} pages',
                          file=sys.stderr)
                else:
                    print(f'{name}: {n} chars', file=sys.stderr)
                total += n
            else:
                print(f'skip (missing): {path}', file=sys.stderr)
    print(f'{args.output}: {total} chars total, '
          f'{os.path.getsize(args.output) / 1e6:.1f} MB', file=sys.stderr)


if __name__ == '__main__':
    main()
