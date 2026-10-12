#!/bin/bash
# ComfyUI laptop sync - push/pull between this VM and Rahik's Dell G7
# Usage: comfy-sync.sh push [file...]  - push files to laptop
#        comfy-sync.sh pull [file...]  - pull files from laptop
#        comfy-sync.sh push-workflows   - push all workflow JSONs
#        comfy-sync.sh pull-outputs     - pull new outputs from ComfyUI

LAPTOP="muse@100.98.44.74"
KEY="~/.ssh/laptop_muse_ed25519"
PROXY_CMD="$HOME/workspace/bin/ts-connect.py %h %p"
export TUNNEL_PROXY="${HTTPS_PROXY%:*}:3130"
SSH="ssh -o BatchMode=yes -o ConnectTimeout=30 -o StrictHostKeyChecking=yes -i $KEY -o ProxyCommand=\"$PROXY_CMD\" $LAPTOP"
SCP="scp -o BatchMode=yes -o ConnectTimeout=30 -o StrictHostKeyChecking=yes -i $KEY -o ProxyCommand=\"$PROXY_CMD\""

COMFY_BASE="D:/ComfyUI/ComfyUI_windows_portable/ComfyUI"
LOCAL_BASE="$HOME/workspace/av_free_stack"

case "$1" in
  push-workflows)
    echo "Pushing workflows to laptop..."
    for f in $LOCAL_BASE/*workflow*.json $LOCAL_BASE/comfyui_*.json; do
      [ -f "$f" ] && eval $SCP "$f" "${LAPTOP}:D:/comfyui_workflows/" && echo "  Pushed $(basename $f)"
    done
    ;;
  pull-outputs)
    echo "Pulling new outputs from laptop..."
    eval $SSH "\"powershell -Command \\\"Get-ChildItem ${COMFY_BASE}/output/*.png | Sort-Object LastWriteTime -Descending | Select-Object -First 10 | ForEach-Object { \$_.Name }\\\"\""
    ;;
  push)
    shift
    for f in "$@"; do
      eval $SCP "$f" "${LAPTOP}:D:/comfyui_inbox/" && echo "Pushed $f"
    done
    ;;
  pull)
    shift
    for f in "$@"; do
      eval $SCP "${LAPTOP}:\"$f\"" "$LOCAL_BASE/" && echo "Pulled $f"
    done
    ;;
  *)
    echo "Usage: $0 {push-workflows|pull-outputs|push <files>|pull <remote-paths>}"
    ;;
esac
