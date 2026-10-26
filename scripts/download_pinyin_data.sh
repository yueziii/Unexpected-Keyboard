#!/bin/sh
# Clone the corpora needed by gen_pinyin_dict.py into scripts/data/.
# The corpora are gitignored: run this script once before generating the
# dictionary. Safe to re-run; existing directories are kept.
set -e
cd "$(dirname "$0")/data"
mkdir -p .
for repo in \
  https://github.com/mozillazg/pinyin-data \
  https://github.com/mozillazg/phrase-pinyin-data \
  https://github.com/rime/rime-luna-pinyin \
  https://github.com/rime/rime-essay \
  https://github.com/fxsjy/jieba \
  https://github.com/thunlp/THUOCL \
  https://github.com/BYVoid/OpenCC \
  https://github.com/pwxcoo/chinese-xinhua
do
  dir=$(basename "$repo")
  if [ -d "$dir" ]; then
    echo "Already present: $dir"
  else
    echo "Cloning $repo"
    git clone --depth 1 "$repo"
  fi
done

# chinese-poetry is huge; sparse-checkout only the folders whose segments
# become famous-line entries (Tang anthologies, classical prose, Song ci,
# the Shijing and the Chuci). When the git fetch fails (github.com blocked),
# fall back to raw.githubusercontent.com, which serves the same files.
# chinese-poetry is huge; sparse-checkout only the folders whose segments
# become famous-line entries (Tang anthologies, classical prose, Song ci,
# the Shijing and the Chuci). When the git fetch fails (github.com blocked),
# fall back to raw.githubusercontent.com, which serves the same files.
POETRY_DIRS="宋词 诗经 楚辞"
POETRY_FILES="宋词/宋词三百首.json 诗经/shijing.json 楚辞/chuci.json"

# Modern Chinese corpus for the bigram model: THUCNews (2005-2011 news)
# from the Hugging Face mirror and the head of the zhwiki multistream dump
# (independent bz2 members, so a ranged partial download decompresses
# cleanly). Both optional: gen_pinyin_bin.py falls back to the segmented
# 1998 corpus alone. Rebuild the sentence file with
# scripts/extract_modern_corpus.py after downloading.
MODERN_DIR="modern"
if [ ! -f "$MODERN_DIR/cnews.train.txt" ]; then
  echo "Fetching THUCNews (cnews.train.txt, 130 MB)"
  mkdir -p "$MODERN_DIR"
  curl -fsSL "https://hf-mirror.com/datasets/dirtycomputer/THUCNews/resolve/main/cnews.train.txt" \
    -o "$MODERN_DIR/cnews.train.txt" \
    || echo "THUCNews fetch failed; bigram will use the 1998 corpus only"
fi
if [ ! -f "$MODERN_DIR/zhwiki-head.xml.bz2" ]; then
  echo "Fetching zhwiki dump head (256 MB of the multistream dump)"
  mkdir -p "$MODERN_DIR"
  curl -fsSL --range 0-268435456 \
    "https://dumps.wikimedia.org/zhwiki/latest/zhwiki-latest-pages-articles-multistream.xml.bz2" \
    -o "$MODERN_DIR/zhwiki-head.xml.bz2" \
    || echo "zhwiki fetch failed; bigram will use the news corpus only"
fi
if [ ! -f "$MODERN_DIR/lccc_base_train.jsonl.gz" ]; then
  echo "Fetching LCCC daily dialogue corpus (370 MB)"
  mkdir -p "$MODERN_DIR"
  curl -fsSL "https://hf-mirror.com/datasets/silver/lccc/resolve/main/lccc_base_train.jsonl.gz" \
    -o "$MODERN_DIR/lccc_base_train.jsonl.gz" \
    || echo "LCCC fetch failed; bigram will lack the colloquial register"
fi
if [ -d "$MODERN_DIR" ] && [ ! -f "$MODERN_DIR/modern_sentences.txt" ]; then
  python3 "$(dirname "$0")/extract_modern_corpus.py"
fi
fetch_poetry_file() {
  f=$1
  if [ ! -f "chinese-poetry/$f" ]; then
    echo "Fetching $f via raw.githubusercontent.com"
    mkdir -p "chinese-poetry/$(dirname "$f")"
    encoded=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$f")
    curl -fsSL "https://raw.githubusercontent.com/chinese-poetry/chinese-poetry/master/$encoded" \
      -o "chinese-poetry/$f"
  fi
}
if [ -d "chinese-poetry" ]; then
  echo "Already present: chinese-poetry"
  (cd chinese-poetry && git sparse-checkout add $POETRY_DIRS) \
    && echo "Poetry collections present" \
    || for f in $POETRY_FILES; do fetch_poetry_file "$f"; done
else
  echo "Cloning chinese-poetry (sparse: 蒙学 + poetry collections)"
  git clone --depth 1 --filter=blob:none --sparse \
    https://github.com/chinese-poetry/chinese-poetry
  (cd chinese-poetry && git sparse-checkout set 蒙学 $POETRY_DIRS) \
    || for f in $POETRY_FILES; do fetch_poetry_file "$f"; done
fi
