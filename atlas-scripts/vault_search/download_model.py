#!/usr/bin/env python3
"""Download the Xenova MiniLM ONNX model + tokenizer into vault_search/models/.

Model: xenova/all-MiniLM-L6-v2 (ONNX, ~80MB, CPU-only). One-time download.
"""
import sys
from pathlib import Path

from huggingface_hub import snapshot_download

HERE = Path(__file__).resolve().parent
DEST = HERE / "models" / "all-MiniLM-L6-v2"

ALLOW = [
    "config.json",
    "onnx/model_quantized.onnx",  # int8-quantized MiniLM (~23MB); retrieval
    "tokenizer.json",              # quality is on par with fp32 for search
    "tokenizer_config.json",
    "special_tokens_map.json",
]


def main() -> None:
    if (DEST / "model.onnx").exists() and (DEST / "tokenizer.json").exists():
        print(f"model already present at {DEST}", file=sys.stderr)
        return
    DEST.mkdir(parents=True, exist_ok=True)
    snapshot_download(
        repo_id="xenova/all-MiniLM-L6-v2",
        local_dir=str(DEST),
        allow_patterns=ALLOW,
    )
    # Xenova keeps the weights under onnx/; promote to the top level for embed.py.
    nested = DEST / "onnx" / "model_quantized.onnx"
    if nested.exists() and not (DEST / "model.onnx").exists():
        nested.rename(DEST / "model.onnx")
    size_mb = sum(p.stat().st_size for p in DEST.rglob("*") if p.is_file()) / 1e6
    print(f"downloaded to {DEST} ({size_mb:.1f} MB)", file=sys.stderr)


if __name__ == "__main__":
    main()
