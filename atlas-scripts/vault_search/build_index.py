#!/usr/bin/env python3
"""Build the vault search index: chunk every Atlas markdown note, embed chunks.

Reads:  ~/workspace/atlas/**/*.md  (skips .obsidian/)
Writes: ~/workspace/atlas-scripts/vault_search/index.json
        [{"path","title","chunk","text","embedding"} ...]

Chunking: strip frontmatter, split on blank lines, pack paragraphs into
~600-char chunks, each prefixed with the note title for retrieval context.
Rebuild the index after vault content changes (it is a search cache, not
vault content — the vault itself is never written).
"""
import json
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
VAULT = Path.home() / "workspace" / "atlas"
INDEX = HERE / "index.json"
CHUNK_CHARS = 600


def strip_frontmatter(text: str) -> str:
    if text.startswith("---"):
        end = text.find("\n---", 3)
        if end != -1:
            return text[end + 4 :]
    return text


def clean(text: str) -> str:
    text = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", text)  # images
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)  # links -> label
    text = re.sub(r"[#>*`_~|]", " ", text)  # markdown noise
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def chunks_for(title: str, body: str):
    paras = [p.strip() for p in body.split("\n\n") if p.strip()]
    chunks, cur = [], ""
    for p in paras:
        p = clean(p)
        if not p:
            continue
        if cur and len(cur) + 1 + len(p) > CHUNK_CHARS:
            chunks.append(cur)
            cur = ""
        cur = (cur + " " + p).strip() if cur else p
    if cur:
        chunks.append(cur)
    return [f"{title}. {c}" for c in chunks] or [title]


def main() -> None:
    notes = sorted(
        p for p in VAULT.rglob("*.md") if ".obsidian" not in p.parts
    )
    print(f"indexing {len(notes)} notes", file=sys.stderr)
    records, payloads = [], []
    for note in notes:
        title = note.stem
        body = strip_frontmatter(note.read_text(encoding="utf-8", errors="replace"))
        for i, ch in enumerate(chunks_for(title, body)):
            rid = f"{note.relative_to(VAULT)}#{i}"
            records.append(
                {
                    "id": rid,
                    "path": str(note.relative_to(VAULT)),
                    "title": title,
                    "chunk": i,
                    "text": ch[:300],
                }
            )
            payloads.append({"id": rid, "text": ch})
    print(f"embedding {len(payloads)} chunks", file=sys.stderr)
    proc = subprocess.run(
        [str(HERE / ".venv" / "bin" / "python"), str(HERE / "embed.py"), "--batch"],
        input="\n".join(json.dumps(p) for p in payloads),
        capture_output=True,
        text=True,
        check=True,
    )
    vecs = {}
    for line in proc.stdout.splitlines():
        rec = json.loads(line)
        vecs[rec["id"]] = rec["embedding"]
    for r in records:
        r["embedding"] = vecs[r["id"]]
        del r["id"]
    INDEX.write_text(json.dumps(records), encoding="utf-8")
    print(f"wrote {INDEX} ({len(records)} chunks)", file=sys.stderr)


if __name__ == "__main__":
    main()
