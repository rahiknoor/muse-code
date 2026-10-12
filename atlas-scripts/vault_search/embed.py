#!/usr/bin/env python3
"""Embed text with the local Xenova MiniLM ONNX model.

Usage:
    embed.py "some text"            # print JSON array (one embedding) to stdout
    embed.py --batch               # read JSON lines {"id":..,"text":..} on stdin,
                                   # write {"id":..,"embedding":[...]} lines to stdout

Mean-pooled, L2-normalized embeddings (384 dims). Logs go to stderr;
stdout is pure JSON. CPU only.
"""
import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from transformers import AutoTokenizer

HERE = Path(__file__).resolve().parent
MODEL_DIR = HERE / "models" / "all-MiniLM-L6-v2"

_tokenizer = None
_session = None


def _load():
    global _tokenizer, _session
    if _session is None:
        _tokenizer = AutoTokenizer.from_pretrained(str(MODEL_DIR), local_files_only=True)
        _session = ort.InferenceSession(
            str(MODEL_DIR / "model.onnx"),
            providers=["CPUExecutionProvider"],
        )
    return _tokenizer, _session


def embed(texts):
    tok, sess = _load()
    enc = tok(texts, padding=True, truncation=True, max_length=256, return_tensors="np")
    inputs = {i.name: enc[i.name] for i in sess.get_inputs()}
    last_hidden = sess.run(None, inputs)[0]  # (batch, seq, 384)
    mask = enc["attention_mask"][..., None].astype(np.float32)
    summed = (last_hidden * mask).sum(axis=1)
    counts = np.clip(mask.sum(axis=1), 1e-9, None)
    vecs = summed / counts
    norms = np.linalg.norm(vecs, axis=1, keepdims=True).clip(1e-12)
    return (vecs / norms).tolist()


def main() -> None:
    args = sys.argv[1:]
    if args and args[0] == "--batch":
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            rec = json.loads(line)
            vec = embed([rec["text"]])[0]
            print(json.dumps({"id": rec.get("id"), "embedding": vec}))
    elif args:
        vec = embed([" ".join(args)])[0]
        print(json.dumps(vec))
    else:
        print("usage: embed.py \"text\" | embed.py --batch", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
