#!/bin/bash
# Mirror Muse's cloud workspace to the Desktop Muse folder on Rahik's Dell G7.
# Read-only for Rahik: he browses everything Muse makes, in the one folder he knows.
# Best-effort: exits quietly (code 0) when the Dell is unreachable.
# Excludes .git metadata plus dead weight nobody browses (models, venv, JDK).
set -u
SRC="$HOME/workspace"
KEY="$HOME/.ssh/laptop_muse_ed25519"
HOST="muse@100.98.44.74"
export TUNNEL_PROXY="${HTTPS_PROXY%:*}:3130"

dell_ssh() {
  timeout 90 ssh -o BatchMode=yes -o ConnectTimeout=15 \
    -o ProxyCommand="$HOME/workspace/bin/ts-connect.py %h %p" \
    -i "$KEY" "$HOST" "$@"
}
dell_scp() {
  timeout 600 scp -o BatchMode=yes -o ConnectTimeout=15 \
    -o ProxyCommand="$HOME/workspace/bin/ts-connect.py %h %p" \
    -i "$KEY" "$@"
}

# Probe reachability first; quiet exit when the Dell is offline/asleep.
if ! dell_ssh "echo ok" 2>/dev/null | grep -q ok; then
  echo "dell unreachable, skipping"
  exit 0
fi

# Pack the workspace (no .git, no dead weight) and ship it over.
# NOTE: stages under $HOME, not /tmp — /tmp is a 512MB tmpfs, far too small
# for the ~10GB tarball (failed 2026-10-03 with "No space left on device").
STAGE_LOCAL="$HOME/.workspace_mirror.tgz"
tar -czf "$STAGE_LOCAL" \
  --exclude=.git --exclude=models --exclude=venv --exclude=java/jdk-21 \
  --exclude=.harness \
  -C "$SRC" . || exit 1
STAGE="C:/Windows/Temp/workspace_mirror.tgz"
dell_scp "$STAGE_LOCAL" "$HOST:$STAGE" || exit 1

# Resolve his Windows profile Desktop (fall back to D:\Muse), extract, stamp README.
dell_ssh "powershell -NoProfile -Command \"\$p=(Get-ChildItem C:\\Users -Directory | Where-Object { \$_.Name -notin @('Public','Default','Default User','All Users','muse') } | Select-Object -First 1 -ExpandProperty Name); if (\$p) { \$d=\\\"C:\\Users\\\$p\\Desktop\\Muse\\\" } else { \$d='D:\\Muse' }; New-Item -ItemType Directory -Force \\\"\$d\\workspace-mirror\\\" | Out-Null; tar -xzf '$STAGE' -C \\\"\$d\\workspace-mirror\\\"; 'READ-ONLY mirror of Muse''s cloud workspace. Synced daily. Muse remains the writer; do not edit here.' | Out-File -Encoding utf8 \\\"\$d\\workspace-mirror\\README.txt\\\"; Remove-Item '$STAGE' -Force; Write-Output \\\"mirrored to \$d\\\"\"" || exit 1
rm -f "$STAGE_LOCAL"
echo "workspace mirror refreshed"
