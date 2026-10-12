#!/usr/bin/env python3
"""Text-to-image via Cloudflare Workers AI REST API.

Usage:
  cf_text_to_image.py --discover-account
  cf_text_to_image.py --account <id> --model @cf/black-forest-labs/flux-2-klein-4b \
      --prompt "..." --out out.png [--width 1024 --height 1024 --seed 7 --steps 8]

Auth goes through the authd surrogate exchange (never a raw key in chat/env/files).
"""
import argparse
import base64
import json
import sys
import urllib.request

sys.path.insert(0, "/opt/hatch/skills/skill-creator/bin")
from dynamic_credentials import (
    add_surrogate_to_request,
    read_json_response,
    read_response_body,
)

CRED = "custom.cloudflare-workers-ai"
HOSTS = ["api.cloudflare.com"]
BASE = "https://api.cloudflare.com/client/v4"


def authed_request(url, data=None, method="GET"):
    req = urllib.request.Request(url, data=data, method=method)
    add_surrogate_to_request(req, CRED, allowed_hosts=HOSTS)
    return req


def discover_account():
    req = authed_request(f"{BASE}/accounts")
    with urllib.request.urlopen(req, timeout=30) as resp:
        body = read_json_response(resp)
    accounts = body.get("result", [])
    if not accounts:
        raise SystemExit("no Cloudflare accounts visible to this token")
    for a in accounts:
        print(a["id"], a.get("name", ""))
    return accounts[0]["id"]


def encode_multipart(fields):
    """Build a multipart/form-data body from {name: str value}."""
    import secrets
    boundary = "----MuseForm" + secrets.token_hex(12)
    chunks = []
    for name, value in fields.items():
        chunks.append(f"--{boundary}\r\n".encode())
        chunks.append(
            f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode())
        chunks.append(str(value).encode())
        chunks.append(b"\r\n")
    chunks.append(f"--{boundary}--\r\n".encode())
    return b"".join(chunks), boundary


def text_to_image(account, model, prompt, out, width, height, seed, steps):
    url = f"{BASE}/accounts/{account}/ai/run/{model}"
    if "flux-2" in model:
        # FLUX.2 family requires multipart/form-data on Workers AI, even for
        # prompt-only calls. klein-4b steps are fixed at 4 (distilled).
        fields = {"prompt": prompt, "width": width, "height": height}
        if seed is not None:
            fields["seed"] = seed
        body, boundary = encode_multipart(fields)
        req = authed_request(url, data=body, method="POST")
        req.add_header(
            "Content-Type", f"multipart/form-data; boundary={boundary}")
    else:
        payload = {
            "prompt": prompt,
            "width": width,
            "height": height,
            "seed": seed,
        }
        if steps:
            payload["num_steps"] = steps
        data = json.dumps(payload).encode()
        req = authed_request(url, data=data, method="POST")
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=300) as resp:
        ctype = resp.headers.get("Content-Type", "")
        raw = read_response_body(resp)
    # Workers AI returns either raw image bytes or JSON with result.image
    # as a base64 string (FLUX.2 family uses the latter).
    img_bytes = None
    if raw[:4] in (b"\xff\xd8\xff\xe0", b"\xff\xd8\xff\xe1", b"\x89PNG"):
        img_bytes = raw
    else:
        try:
            payload = json.loads(raw.decode("utf-8", "replace"))
        except Exception:
            payload = None
        if isinstance(payload, dict):
            result = payload.get("result") or {}
            b64 = result.get("image") if isinstance(result, dict) else None
            if b64:
                img_bytes = base64.b64decode(b64)
            elif not payload.get("success", True):
                print(json.dumps(payload, indent=2)[:2000])
    if img_bytes is None:
        raise SystemExit("Workers AI did not return an image")
    with open(out, "wb") as f:
        f.write(img_bytes)
    print(f"saved {out} ({len(img_bytes)} bytes, {ctype})")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--discover-account", action="store_true")
    ap.add_argument("--account")
    ap.add_argument("--model", default="@cf/black-forest-labs/flux-2-klein-4b")
    ap.add_argument("--prompt")
    ap.add_argument("--out")
    ap.add_argument("--width", type=int, default=1024)
    ap.add_argument("--height", type=int, default=1024)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--steps", type=int, default=0)
    args = ap.parse_args()

    if args.discover_account:
        discover_account()
        return
    if not (args.account and args.prompt and args.out):
        ap.error("--account, --prompt and --out are required")
    text_to_image(args.account, args.model, args.prompt, args.out,
                  args.width, args.height, args.seed, args.steps or None)


if __name__ == "__main__":
    main()
