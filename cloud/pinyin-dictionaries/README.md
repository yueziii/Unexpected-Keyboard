# Unexpected Keyboard — Pinyin Dictionaries

[Unexpected Keyboard](https://github.com/yueziii/Unexpected-Keyboard) 的拼音
云端数据仓库。

完整词库（29 万词条，含成语、诗词名句、俗语歇后语、行业词与网络热词）随
APK 内置发布，本仓库负责**发版节奏之外变化的数据**：

- **云端热词（`hotwords/` + manifest 的 `hotwords` 段）** — 每月从
  `wordlists/buzzwords.txt` 重建的小文本覆盖层（几 KB）。客户端在
  「设置 → 候选词 → 拼音热词更新」点按检查并下载；仓库不存在、无网络或
  无更新时直接使用内置热词，安装是尽力而为（best-effort）。
- **离线语音模型（`voice-models.json`）** — 离线语音模型的远程下载清单
  （见下文）。
- **词库整包（`packs/` + manifest 的 `packs` 段）** — 旧版客户端的整包
  下载渠道，保留但不再是主要更新方式，月度 workflow 不再重建。

**隐私：客户端只下载文件，不上传任何数据。**

## 目录结构

```
wordlists/            手工维护的精选词源（版本管理在这里）
  buzzwords.txt       网络热词 + 简拼缩写对（yyds → 永远的神）
  proverbs.txt        俗语谚语
  famous_lines.txt    诗词名句
hotwords/<date>/      生成的热词覆盖层（客户端下载物）
packs/<id>/<date>/    生成的词库整包（旧渠道，保留）
manifest.json         客户端下载清单（hotwords + packs + format_version）
voice-models.json     离线语音模型清单
```

## manifest.json 格式

```json
{
  "format_version": 2,
  "updated": "2026-10-25",
  "hotwords": {
    "path": "hotwords/2026-10-25/hotwords.tsv",
    "sha256": "…", "bytes": 7694, "count": 307, "date": "2026-10-25"
  },
  "packs": [
    {
      "id": "extended",
      "name": "扩展词库",
      "entries": 292758,
      "date": "2026-10-25",
      "dict":   {"path": "packs/extended/2026-10-25/py_dict.bin",
                 "sha256": "…", "bytes": 17900000},
      "bigram": {"path": "packs/extended/2026-10-25/py_bigram.bin",
                 "sha256": "…", "bytes": 300000}
    }
  ]
}
```

`path` 相对于本文件所在 URL。热词文件为 TSV：`pinyin<TAB>词<TAB>频次`，
`@abbrev<TAB>字母<TAB>词` 行是简拼缩写对，`#` 开头为注释；客户端按文件
顺序排名（靠前的热词排更前）。`packs` 的 `dict` 与 `bigram` 必须是同一
次编译产出的成对文件（客户端会校验两者词表一致）。

- `format_version: 1` — PYD1 词库格式（无简拼索引），旧客户端仍可安装；
- `format_version: 2` — PYD2 词库格式：词表带词频、附带简拼（声母）索引，
  含成语、诗词名句、俗语、歇后语与网络热词分类词库。

## 重建

脚本在主仓库：

```sh
git clone https://github.com/yueziii/Unexpected-Keyboard
cd Unexpected-Keyboard
scripts/download_pinyin_data.sh        # 克隆语料（约 1 GB，只需一次）
python3 scripts/make_pinyin_pack.py --hotwords-only   # 只重建热词
python3 scripts/make_pinyin_pack.py                   # 整包 + 热词
```

热词从 `wordlists/buzzwords.txt` 出发，经完整词库合并流程自动注音（所有
热词都被强制收录，必须能解析出拼音）。整包参数：`--multi-limit`（多字词
上限，默认 200000）、`--max-pairs`（词对模型上限，默认 1000000）、
`--pack-id` / `--name` / `--description`。

## 更新热词清单

编辑 `wordlists/buzzwords.txt`（每行一个词，或 `字母缩写<TAB>词`），推送
后等月度 workflow 重建；也可以手动跑 `make_pinyin_pack.py --hotwords-only`
并提交 `hotwords/` 与 `manifest.json`。客户端在设置里点按「拼音热词更新」
即可拉到，无需发版。

## 自动更新

`.github/workflows/build.yml` 每月 1 日自动重建热词文件并提交（清单的增
删改随语料注音进入覆盖层）。词库整包随 APK 发版更新，不走月度构建。

## 离线语音模型（voice-models.json）

`voice-models.json` 是输入法「设置 → 语音输入 → 离线语音模型」的下载清单。
清单本身已随 APK 内置（主仓库 `assets/voice-models.json`），**不推送本仓库也能用**；
本仓库的这份是可选的远程覆盖：推送后客户端会优先使用它，从而不发版就能新增模型。
两边内容应保持一致（以主仓库 assets 为准）。每个模型条目包含：

- `type`：`streaming_paraformer` 或 `streaming_zipformer`（识别引擎按此构造
  sherpa-onnx 配置）；
- `files`：每个文件的角色（encoder/decoder/joiner/tokens）、sha256 校验和
  与多个镜像 URL（hf-mirror.com 在前，huggingface.co 兜底）；
- `bytes`：所有文件字节数之和（主仓库有单元测试校验清单合法性）。

新增/更新模型：从 sherpa-onnx 的 HuggingFace 仓库下载 int8 模型文件，
`shasum -a 256` 计算校验和，把条目加入 `voice-models.json` 后推送到本仓库
的 `main` 分支即可，客户端无需发版。
