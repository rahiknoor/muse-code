#!/usr/bin/env python3
"""Authenticated Hugging Face Space clients for the free video pipeline.

Rahik's explicit consent (2026-09-29, chat): attach his Hugging Face login to
OFFICIAL-VENDOR Spaces only, to raise the free ZeroGPU quota for every video
in the pipeline. Third-party Spaces stay anonymous.

Security notes:
- This module NEVER sees the raw token. It fetches an `hsurr:...` surrogate
  from authd; the egress proxy (Sentinel) swaps the surrogate for the real
  credential on approved outbound requests. The surrogate string is not the
  secret and is never written to disk.
- Do NOT add third-party Spaces to OFFICIAL_SPACES without Rahik's explicit
  say-so for that specific host.
"""
import sys

sys.path.insert(0, "/opt/hatch/skills/skill-creator/bin")
from dynamic_credentials import dynamic_credential_entry  # noqa: E402

from gradio_client import Client  # noqa: E402

CRED = "custom.huggingface"

# Official-vendor Space repo IDs approved by Rahik 2026-09-29.
OFFICIAL_SPACES = {
    "black-forest-labs/FLUX.1-dev",
    "black-forest-labs/FLUX.2-klein-4B",
    "Lightricks/ltx-video-distilled",
    "microsoft/TRELLIS.2",
    "Qwen/Qwen-Image",
}

_surrogate = None


def get_surrogate() -> str:
    """Return the hsurr:... surrogate for the HF credential (memory only)."""
    global _surrogate
    if _surrogate is None:
        entry = dynamic_credential_entry(CRED)
        _surrogate = str(entry["surrogate"]).strip()
        assert _surrogate.startswith("hsurr:"), "not a surrogate"
    return _surrogate


def space_client(space_id: str, **kwargs) -> Client:
    """gradio_client Client for a Space; authenticated iff official-vendor.

    Passes hf_token=<surrogate> for Spaces in OFFICIAL_SPACES so the call is
    attributed to Rahik's HF account quota. Everything else stays anonymous.
    """
    if space_id in OFFICIAL_SPACES:
        return Client(space_id, token=get_surrogate(), **kwargs)
    return Client(space_id, **kwargs)


if __name__ == "__main__":
    # Self-test: fetch the surrogate and confirm it looks right.
    # Never prints the value itself.
    s = get_surrogate()
    print("surrogate fetched: yes")
    print("format ok:", s.startswith("hsurr:"))
    print("official spaces:", len(OFFICIAL_SPACES))
