#!/usr/bin/env python3
"""
Build a downloadable pinyin dictionary pack for the cloud dictionary
repository (cloud/pinyin-dictionaries/), and update its manifest.json.

A pack is a matched pair of binaries in the same format as the bundled
assets (see gen_pinyin_bin.py):

  py_dict.bin    words + single characters indexed by pinyin
  py_bigram.bin  word bigram counts for sentence decoding

The default "extended" pack relaxes the limits of the built-in dictionary:
more multi-character words (--multi-limit) and a larger bigram model
(--max-pairs). The output goes to

  <out-dir>/packs/<pack-id>/<date>/py_dict.bin
  <out-dir>/packs/<pack-id>/<date>/py_bigram.bin

The same run also regenerates the cloud hotwords file from
wordlists/buzzwords.txt (unless --no-hotwords): a small text overlay with
the current trending words, published under

  <out-dir>/hotwords/<date>/hotwords.tsv

Both products are announced in manifest.json (the "packs" and "hotwords"
sections) with sha256 checksums that the keyboard verifies before
installing. The built-in dictionary carries the word lists too, so the
hotwords file is an optional freshness update: clients without it fall
back to the built-in list. --hotwords-only skips the pack binaries (the
monthly workflow only refreshes the hotwords).

The corpora must be cloned into scripts/data first:
  scripts/download_pinyin_data.sh
"""
import argparse
import datetime
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile

SCRIPTS = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(SCRIPTS)
MANIFEST_VERSION = 2


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 16), b''):
            h.update(chunk)
    return h.hexdigest()


def count_entries(dict_txt):
    n = 0
    with open(dict_txt, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            # @abbrev lines are 简拼 mappings, not dictionary entries.
            if line and not line.startswith('#') \
                    and not line.startswith('@'):
                n += 1
    return n


def build_pack(args, tmp):
    # 1. Merge the text dictionary with relaxed limits.
    dict_txt = os.path.join(tmp, 'pinyin_dict.txt')
    subprocess.run(
        [sys.executable, os.path.join(SCRIPTS, 'gen_pinyin_dict.py'),
         '--multi-limit', str(args.multi_limit), '--output', dict_txt],
        check=True)
    if not args.hotwords_only:
        # 2. Compile the binaries, with the generator's paths pointed at the
        #    pack instead of the bundled assets.
        sys.path.insert(0, SCRIPTS)
        import gen_pinyin_bin
        gen_pinyin_bin.DICT_TXT = dict_txt
        gen_pinyin_bin.CORPUS = os.path.join(SCRIPTS, 'data',
                                             'ChineseCorpus199801.txt')
        gen_pinyin_bin.OUT_DICT = os.path.join(tmp, 'py_dict.bin')
        gen_pinyin_bin.OUT_BIGRAM = os.path.join(tmp, 'py_bigram.bin')
        gen_pinyin_bin.MAX_PAIRS = args.max_pairs
        gen_pinyin_bin.main()
    return dict_txt


# ---- hotwords ------------------------------------------------------------

def read_buzzwords():
    """(words, [(abbrev, word)]) from wordlists/buzzwords.txt, in file
    order — the curated order is the ranking the client uses."""
    path = os.path.join(REPO, 'cloud', 'pinyin-dictionaries', 'wordlists',
                        'buzzwords.txt')
    words, abbrs = [], []
    with open(path, encoding='utf-8') as f:
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
    return words, abbrs


def emit_hotwords(dict_txt, out_dir, date):
    """Write the hotwords overlay file and update manifest.json.

    The overlay lists every buzzword with its generated pinyin and
    frequency, taken from the freshly merged dictionary (buzzwords are
    force-included there, so every entry must resolve). Lines are
    "pinyin\\tword\\tfreq" plus "@abbrev\\tletters\\tword"; '#' comments
    carry the format description. Returns the manifest entry."""
    words, abbrs = read_buzzwords()
    # Best (most frequent) pinyin per word from the merged dictionary.
    freq_of = {}
    with open(dict_txt, encoding='utf-8') as f:
        for line in f:
            if line.startswith('#') or line.startswith('@'):
                continue
            parts = line.rstrip('\n').split('\t')
            if len(parts) != 3:
                continue
            py, word, freq = parts
            try:
                freq = int(freq)
            except ValueError:
                continue
            if word not in freq_of or freq > freq_of[word][1]:
                freq_of[word] = (py, freq)
    lines = [
        '# Unexpected Keyboard hotwords (云端热词)',
        '# Generated by scripts/make_pinyin_pack.py from '
        'wordlists/buzzwords.txt',
        '# Format: pinyin<TAB>word<TAB>freq — no tones, syllables joined, '
        'ü written as v',
        "# Lines '@abbrev<TAB>letters<TAB>word' map a latin abbreviation "
        'to a word for the 简拼 index',
        '# date: %s' % date,
    ]
    missing = [w for w in words if w not in freq_of]
    if missing:
        sys.exit('Buzzwords missing from the generated dictionary (they '
                 'are force-included, this means a broken merge): %s'
                 % ', '.join(missing[:20]))
    for w in words:
        py, freq = freq_of[w]
        lines.append('%s\t%s\t%d' % (py, w, freq))
    for abbrev, w in abbrs:
        lines.append('@abbrev\t%s\t%s' % (abbrev, w))
    hw_dir = os.path.join(out_dir, 'hotwords', date)
    os.makedirs(hw_dir, exist_ok=True)
    out_path = os.path.join(hw_dir, 'hotwords.tsv')
    with open(out_path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    entry = {
        'path': os.path.join('hotwords', date, 'hotwords.tsv'),
        'sha256': sha256_of(out_path),
        'bytes': os.path.getsize(out_path),
        'count': len(words) + len(abbrs),
        'date': date,
    }
    update_manifest_hotwords(out_dir, entry)
    return entry


def update_manifest_hotwords(out_dir, entry):
    manifest_path = os.path.join(out_dir, 'manifest.json')
    manifest = {}
    if os.path.exists(manifest_path):
        with open(manifest_path, encoding='utf-8') as f:
            manifest = json.load(f)
    manifest['hotwords'] = entry
    with open(manifest_path, 'w', encoding='utf-8') as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write('\n')


def update_manifest(out_dir, pack):
    manifest_path = os.path.join(out_dir, 'manifest.json')
    if os.path.exists(manifest_path):
        with open(manifest_path, 'r', encoding='utf-8') as f:
            manifest = json.load(f)
        packs = [p for p in manifest.get('packs', [])
                 if p.get('id') != pack['id']]
    else:
        manifest = {'format_version': MANIFEST_VERSION}
        packs = []
    packs.append(pack)
    # The manifest format version follows the writer: a pack built by this
    # script version uses the current binary format.
    manifest['format_version'] = MANIFEST_VERSION
    manifest['updated'] = pack['date']
    manifest['packs'] = sorted(packs, key=lambda p: p.get('date', ''),
                               reverse=True)
    with open(manifest_path, 'w', encoding='utf-8') as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write('\n')


def main():
    data_dir = os.path.join(SCRIPTS, 'data')
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out-dir',
                    default=os.path.join(REPO, 'cloud',
                                         'pinyin-dictionaries'),
                    help='Cloud dictionary repository directory')
    ap.add_argument('--pack-id', default='extended')
    ap.add_argument('--name', default='扩展词库')
    ap.add_argument('--description',
                    default='在内置词库基础上放宽词频限制，收录更多低频词、'
                            '更深入的行业术语，以及成语、诗词名句、俗语歇后语'
                            '与网络热词等分类词库（PYD2 格式，支持简拼索引）')
    ap.add_argument('--multi-limit', type=int, default=200000,
                    help='Maximum number of multi-character word entries')
    ap.add_argument('--max-pairs', type=int, default=1000000,
                    help='Maximum number of bigram pairs')
    ap.add_argument('--no-hotwords', action='store_true',
                    help='Skip the hotwords overlay generation')
    ap.add_argument('--hotwords-only', action='store_true',
                    help='Skip the pack binaries; only regenerate the '
                         'hotwords file (the monthly workflow)')
    ap.add_argument('--date', default=datetime.date.today().isoformat())
    args = ap.parse_args()

    if not os.path.isdir(data_dir):
        sys.exit('Corpora missing: run scripts/download_pinyin_data.sh')
    if not args.hotwords_only and \
            not os.path.exists(os.path.join(data_dir,
                                            'ChineseCorpus199801.txt')):
        sys.exit('Bigram corpus missing: run scripts/download_pinyin_data.sh')

    tmp = tempfile.mkdtemp(prefix='pinyin_pack_')
    try:
        dict_txt = build_pack(args, tmp)
        if not args.hotwords_only:
            pack_dir = os.path.join(args.out_dir, 'packs', args.pack_id,
                                    args.date)
            os.makedirs(pack_dir, exist_ok=True)
            pack = {
                'id': args.pack_id,
                'name': args.name,
                'description': args.description,
                'entries': count_entries(dict_txt),
                'date': args.date,
                'dict': {
                    'path': os.path.join('packs', args.pack_id, args.date,
                                         'py_dict.bin'),
                    'sha256': sha256_of(os.path.join(tmp, 'py_dict.bin')),
                    'bytes': os.path.getsize(os.path.join(tmp,
                                                          'py_dict.bin')),
                },
                'bigram': {
                    'path': os.path.join('packs', args.pack_id, args.date,
                                         'py_bigram.bin'),
                    'sha256': sha256_of(os.path.join(tmp,
                                                     'py_bigram.bin')),
                    'bytes': os.path.getsize(os.path.join(tmp,
                                              'py_bigram.bin')),
                },
            }
            for name in ('py_dict.bin', 'py_bigram.bin'):
                shutil.move(os.path.join(tmp, name),
                            os.path.join(pack_dir, name))
            update_manifest(args.out_dir, pack)
            print('pack written to %s' % pack_dir)
            print(json.dumps(pack, ensure_ascii=False, indent=2))
        if not args.no_hotwords:
            hotwords = emit_hotwords(dict_txt, args.out_dir, args.date)
            print('hotwords written to %s' % os.path.join(
                args.out_dir, hotwords['path']))
            print(json.dumps(hotwords, ensure_ascii=False, indent=2))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == '__main__':
    main()
