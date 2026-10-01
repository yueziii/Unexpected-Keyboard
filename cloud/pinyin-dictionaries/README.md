# Unexpected Keyboard — Pinyin Dictionaries

[Unexpected Keyboard](https://github.com/yueziii/Unexpected-Keyboard) 的拼音云端词库仓库。

键盘客户端在 **设置 → 候选词 → 拼音云端词库** 中列出本仓库 `manifest.json` 里
的词库包，用户选择后自动下载、校验（SHA-256）并立即生效；选择"内置词库"即删
除云端包回退内置词库。

**隐私：客户端只下载词库文件，不上传任何数据。**

## manifest.json 格式

```json
{
  "format_version": 1,
  "updated": "2026-10-01",
  "packs": [
    {
      "id": "extended",
      "name": "扩展词库",
      "description": "…",
      "entries": 213000,
      "date": "2026-10-01",
      "dict":   {"path": "packs/extended/2026-10-01/py_dict.bin",
                 "sha256": "…", "bytes": 7100000},
      "bigram": {"path": "packs/extended/2026-10-01/py_bigram.bin",
                 "sha256": "…", "bytes": 1000000}
    }
  ]
}
```

`path` 相对于本文件所在 URL；`dict` 与 `bigram` 必须是同一次编译产出的成对
文件（客户端会校验两者词表一致）。`format_version` 目前只支持 `1`。

## 重建词库包

脚本在主仓库：

```sh
git clone https://github.com/yueziii/Unexpected-Keyboard
cd Unexpected-Keyboard
scripts/download_pinyin_data.sh        # 克隆语料（约 1 GB，只需一次）
python3 scripts/make_pinyin_pack.py    # 生成 pack + 更新本仓库 manifest.json
```

常用参数：`--multi-limit`（多字词上限，默认 200000）、`--max-pairs`（词对
模型上限，默认 1000000）、`--pack-id` / `--name` / `--description`。

## 自动更新

`.github/workflows/build.yml` 每月 1 日自动重建各词库包并提交
（新的热词、新的领域词表都会随语料仓库更新而进入词库包）。
