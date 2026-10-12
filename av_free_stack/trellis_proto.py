#!/usr/bin/env python3
"""TRELLIS.2 feasibility prototype: image -> 3D GLB via microsoft/TRELLIS.2 Space."""
import os
import re
import subprocess
import sys
import time
import traceback
import urllib.request

os.environ.pop("no_proxy", None)
os.environ.pop("NO_PROXY", None)

from gradio_client import Client, handle_file
from hf_auth import space_client

WORK = os.path.expanduser("~/workspace/av_free_stack")
REF = os.environ.get("BAKEOFF_REF", os.path.join(WORK, "humpty_v2_ref.png"))
OUT_GLB = os.path.join(WORK, "humpty_v2_trellis.glb")
OUT_HTML = os.path.join(WORK, "humpty_v2_trellis_preview.html")


def _is_auth_error(exc):
    """True for credential failures (401/invalid token), never for quota."""
    msg = str(exc).lower()
    if "quota" in msg:
        return False
    return ("401" in msg or "unauthorized" in msg or "invalid token" in msg
            or "invalid_token" in msg or "bad credentials" in msg
            or "authentication" in msg)


def _make_client(authed):
    if authed:
        return space_client("microsoft/TRELLIS.2",
                            httpx_kwargs={"timeout": 1200})
    return Client("microsoft/TRELLIS.2", httpx_kwargs={"timeout": 1200})


def _parse_quota_wait(exc):
    """Seconds until the ZeroGPU pool refills, from 'Try again in H:MM:SS'.

    Returns None when the error is not quota-shaped or the wait is
    unparseable. Capped at 16h so a garbage parse can't sleep forever.
    """
    import re
    m = re.search(r"try again in (\d+):(\d\d):(\d\d)",
                  str(exc).lower())
    if not m:
        return None
    secs = (int(m.group(1)) * 3600 + int(m.group(2)) * 60
            + int(m.group(3)))
    return min(secs, 16 * 3600)


def _is_session_dead(exc):
    """True when the server's 3D session is gone — only a regenerate helps."""
    msg = str(exc).lower()
    return ("no session" in msg or "session" in msg and "expired" in msg
            or "has no attribute" in msg or "typeerror" in msg)


def main():
    if not os.path.exists(REF):
        print(f"MISSING {REF}", file=sys.stderr)
        sys.exit(2)
    if os.path.exists(OUT_GLB):
        print(f"cached -> {OUT_GLB}", flush=True)
        return
    # Official Microsoft Space -> authenticated via Rahik's HF login first;
    # on auth errors (401/invalid token only) fall back to anonymous.
    #
    # Pipeline lessons 2026-09-30 (proven by repeated runs):
    # - /image_to_3d takes ONE arg: the image wrapped in handle_file()
    #   (gradio_client 2.7.1 does not auto-upload a raw path string; the
    #   server then fails ImageData validation). Remaining params keep Space
    #   defaults. The full /start_session -> /preprocess_image -> /get_seed
    #   chain is NOT needed and its rgba+explicit-params /image_to_3d variant
    #   fails server-side; the direct call works.
    # - /extract_glb MUST run on the SAME client right after /image_to_3d
    #   (a fresh client has no session state -> server TypeError).
    # - Both /image_to_3d and /extract_glb burn ~120s ZeroGPU each; retries
    #   cover quota exhaustion AND transient "GPU task aborted".
    # - The Space sometimes returns an OPAQUE Gradio AppError ("upstream app
    #   raised an exception", show_error=False) on /image_to_3d — observed
    #   2026-09-30 ~10:20 EDT after a successful auth + preprocess, i.e. not
    #   a prompt/ref problem. Retried (cap 4, then fail) so one bad server
    #   moment doesn't kill the whole run, but we don't burn quota on a
    #   genuinely deterministic server failure either.
    # - 2026-09-30 ~10:35 EDT QUOTA TRAP (killed a run mid-flight): the old
    #   loop regenerated /image_to_3d on every retry. Each successful
    #   regeneration burns ~120s, so /extract_glb (another ~120s) kept
    #   hitting quota with ~172s left in the pool — the retries were eating
    #   the very quota the extract needed. New structure: generate ONCE per
    #   cycle, then retry /extract_glb on the same session WITHOUT
    #   regenerating. Regeneration happens only when the session is dead or
    #   extract retries exhaust (max 3 cycles). Quota waits honor the
    #   server's "Try again in H:MM:SS" instead of a blind 90s.
    authed = True
    c = None
    opaque_hits = 0
    for cycle in range(1, 4):
        if c is None:
            c = _make_client(authed)
            print(f"trellis: using "
                  f"{'AUTHENTICATED' if authed else 'ANONYMOUS'} client",
                  flush=True)
        # ---- Phase 1: image -> 3D (burns ~120s ZeroGPU) ----
        gen_ok = False
        for g in range(1, 8):
            try:
                print(f"trellis: /image_to_3d (cycle {cycle}, try {g})",
                      flush=True)
                res = c.predict(handle_file(REF), api_name="/image_to_3d")
                html = res if isinstance(res, str) else str(res)
                with open(OUT_HTML, "w") as f:
                    f.write(html)
                print(f"preview html saved ({len(html)} chars)", flush=True)
                gen_ok = True
                break
            except Exception as exc:  # noqa: BLE001
                if _is_auth_error(exc) and authed:
                    print(f"trellis: auth error -> anonymous fallback "
                          f"({exc})", flush=True)
                    authed = False
                    c = None
                    break  # restart cycle with anonymous client
                if not _transient_pause(exc, "image_to_3d"):
                    if _opaque_allowed(exc, opaque_hits):
                        opaque_hits += 1
                        continue
                    traceback.print_exc()
                    sys.exit(1)
        if not gen_ok:
            if c is None:
                continue  # anonymous fallback restarted the cycle
            print("trellis: /image_to_3d retries exhausted", file=sys.stderr)
            sys.exit(1)
        # ---- Phase 2: extract on SAME session, no regeneration ----
        for x in range(1, 9):
            try:
                print(f"trellis: /extract_glb (cycle {cycle}, try {x})",
                      flush=True)
                glb_res = c.predict(300000, 2048, api_name="/extract_glb")
                _save_glb(glb_res)
                if os.path.exists(OUT_GLB):
                    return
                print("trellis: extract returned no GLB; retrying same "
                      "session", flush=True)
                time.sleep(90)
            except Exception as exc:  # noqa: BLE001
                if _is_session_dead(exc):
                    print(f"trellis: session dead ({str(exc)[:80]}); "
                          f"regenerating", flush=True)
                    c = None
                    break
                if not _transient_pause(exc, "extract_glb"):
                    if _opaque_allowed(exc, opaque_hits):
                        opaque_hits += 1
                        continue
                    traceback.print_exc()
                    sys.exit(1)
        else:
            print("trellis: extract retries exhausted; regenerating "
                  f"(cycle {cycle}/3)", flush=True)
            c = None
            continue
        # session died -> regenerate next cycle (c already None)
    print("FAILED: extract never produced a GLB after 3 cycles",
          file=sys.stderr)
    sys.exit(1)


def _transient_pause(exc, phase):
    """Sleep through a quota/transient failure. True = caller should retry.

    Quota waits use the server's own 'Try again in H:MM:SS' (min 90s so we
    don't hammer); other transient failures ('aborted') get a flat 90s.
    Returns False when the error is not transient.
    """
    msg = str(exc).lower()
    quota = "quota" in msg
    transient = quota or "aborted" in msg
    if not transient:
        return False
    wait = _parse_quota_wait(exc) if quota else None
    wait = max(wait or 0, 90)
    print(f"trellis: {phase} transient ({str(exc)[:80]}), waiting "
          f"{wait}s", flush=True)
    time.sleep(wait)
    return True


def _opaque_allowed(exc, hits):
    """Opaque show_error=False server errors: retry a few times, then fail.

    Cap is 4 total across the run — a deterministic server failure must not
    burn quota the way blind retries did on 2026-09-30.
    """
    msg = str(exc).lower()
    if ("gradio app has raised an exception" not in msg
            and "show_error=true" not in msg):
        return False
    if hits >= 4:
        return False
    print(f"trellis: opaque server error ({str(exc)[:80]}), retrying "
          f"({hits + 1}/4)", flush=True)
    time.sleep(90)
    return True


def _save_glb(glb_res):
    """Copy the GLB out of the /extract_glb result (Model3d + Downloadbutton)."""
    candidates = []
    items = glb_res if isinstance(glb_res, (list, tuple)) else [glb_res]
    for item in items:
        if isinstance(item, dict):
            for key in ("path", "url"):
                if item.get(key):
                    candidates.append(item[key])
        elif isinstance(item, str) and item:
            candidates.append(item)
    for cand in candidates:
        try:
            if os.path.exists(cand):
                subprocess.run(["cp", cand, OUT_GLB], check=True)
            else:
                urllib.request.urlretrieve(cand, OUT_GLB)
            print(f"saved -> {OUT_GLB}", flush=True)
            return
        except Exception as exc:  # noqa: BLE001
            print(f"glb candidate failed ({cand[:80]}): {exc}", flush=True)
    print("NO usable GLB in /extract_glb result; preview html kept",
          flush=True)


if __name__ == "__main__":
    main()
