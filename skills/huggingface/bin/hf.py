#!/usr/bin/env python3
"""Call Hugging Face with the stored credential (via authd surrogate).

Usage: hf.py whoami
"""
import sys
import urllib.request

sys.path.insert(0, "/opt/hatch/skills/skill-creator/bin")
from dynamic_credentials import add_surrogate_to_request, read_json_response

CRED = "custom.huggingface"
HOSTS = ["huggingface.co"]


def whoami() -> None:
    req = urllib.request.Request("https://huggingface.co/api/whoami-v2")
    add_surrogate_to_request(req, CRED, allowed_hosts=HOSTS)
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = read_json_response(resp)
    print("name:", data.get("name"))
    print("email:", data.get("email"))


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "whoami":
        whoami()
    else:
        print("usage: hf.py whoami", file=sys.stderr)
        sys.exit(2)
