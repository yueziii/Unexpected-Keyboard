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

and manifest.json is rewritten with the new pack entry (same id replaces
the previous entry), including sha256 checksums that the keyboard verifies
before installing.

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
MANIFEST_VERSION = 1


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
            if line and not line.startswith('#'):
                n += 1
    return n


def build_pack(args, tmp):
    # 1. Merge the text dictionary with relaxed limits.
    dict_txt = os.path.join(tmp, 'pinyin_dict.txt')
    subprocess.run(
        [sys.executable, os.path.join(SCRIPTS, 'gen_pinyin_dict.py'),
         '--multi-limit', str(args.multi_limit), '--output', dict_txt],
        check=True)
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


def update_manifest(out_dir, pack):
    manifest_path = os.path.join(out_dir, 'manifest.json')
    if os.path.exists(manifest_path):
        with open(manifest_path, 'r', 'utf-8') as f:
            manifest = json.load(f)
        packs = [p for p in manifest.get('packs', [])
                 if p.get('id') != pack['id']]
    else:
        manifest = {'format_version': MANIFEST_VERSION}
        packs = []
    packs.append(pack)
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
                            '领域词，并加大词对模型')
    ap.add_argument('--multi-limit', type=int, default=200000,
                    help='Maximum number of multi-character word entries')
    ap.add_argument('--max-pairs', type=int, default=1000000,
                    help='Maximum number of bigram pairs')
    ap.add_argument('--date', default=datetime.date.today().isoformat())
    args = ap.parse_args()

    if not os.path.isdir(data_dir):
        sys.exit('Corpora missing: run scripts/download_pinyin_data.sh')
    if not os.path.exists(os.path.join(data_dir,
                                       'ChineseCorpus199801.txt')):
        sys.exit('Bigram corpus missing: run scripts/download_pinyin_data.sh')

    tmp = tempfile.mkdtemp(prefix='pinyin_pack_')
    try:
        dict_txt = build_pack(args, tmp)
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
                'bytes': os.path.getsize(os.path.join(tmp, 'py_dict.bin')),
            },
            'bigram': {
                'path': os.path.join('packs', args.pack_id, args.date,
                                     'py_bigram.bin'),
                'sha256': sha256_of(os.path.join(tmp, 'py_bigram.bin')),
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
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == '__main__':
    main()
