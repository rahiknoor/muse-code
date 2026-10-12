#!/bin/bash
# Auto-resume loop for remaining ComfyUI model downloads.
# Each dl_*.bat is idempotent (curl -C - resume). The tunnel proxy kills SSH
# sessions every ~1-3 min, so this loop relaunches dead batches until done.
export TUNNEL_PROXY="${HTTPS_PROXY%:*}:3130"
KEY="$HOME/.ssh/laptop_muse_ed25519"
HOST="muse@100.98.44.74"
PC="/home/hatch/workspace/bin/ts-connect.py"
SSHOPTS=(-o "ProxyCommand=$PC %h %p" -o StrictHostKeyChecking=no -o ConnectTimeout=30 -i "$KEY")

for round in $(seq 1 40); do
  all_done=true
  for b in b c d; do
    marker=$(ssh "${SSHOPTS[@]}" "$HOST" "findstr finished D:\\dl_$b.log" 2>/dev/null | tr -d '\r')
    case "$marker" in
      *finished*) continue ;;
    esac
    all_done=false
    timeout 400 ssh "${SSHOPTS[@]}" "$HOST" "D:\\dl_$b.bat" >/dev/null 2>&1 || true
  done
  if $all_done; then echo "ALL-DOWNLOADS-COMPLETE round=$round"; break; fi
  sleep 3
done
