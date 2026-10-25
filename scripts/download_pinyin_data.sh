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

# chinese-poetry is huge; sparse-checkout only the 蒙学 folder (tangshi
# sanbaishou, zengguangxianwen, qianjiashi) needed for famous-line entries.
if [ -d "chinese-poetry" ]; then
  echo "Already present: chinese-poetry"
else
  echo "Cloning chinese-poetry (sparse: 蒙学)"
  git clone --depth 1 --filter=blob:none --sparse \
    https://github.com/chinese-poetry/chinese-poetry
  (cd chinese-poetry && git sparse-checkout set 蒙学)
fi
