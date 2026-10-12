#!/bin/bash
# Sync the Atlas Obsidian vault (~/workspace/atlas) to Rahik's Dell G7:
#   - D:\Atlas                       (his desktop Obsidian vault)
#   - %USERPROFILE%\iCloudDrive\Obsidian\Atlas  (feeds his iPhone via iCloud; skipped when iCloud for Windows isn't set up)
# Best-effort: exits quietly (code 0) when the Dell is unreachable.
# Excludes .git (never ship repo metadata); includes .obsidian (graph colors live there).
# NOTE: tar extraction never deletes — files removed from the vault linger at
# destinations until cleaned. iCloud propagates whatever lands there.
set -u
VAULT="$HOME/workspace/atlas"
KEY="$HOME/.ssh/laptop_muse_ed25519"
HOST="saqib@100.98.44.74"
export TUNNEL_PROXY="${HTTPS_PROXY%:*}:3130"
# NOTE (2026-10-05): ssh resolves home to /root, not $HOME, so the known_hosts
# file must be named explicitly or host-key checks fail (BatchMode). See TOOLS.md.
SSH_OPTS=(
  -o BatchMode=yes
  -o ConnectTimeout=15
  -o StrictHostKeyChecking=accept-new
  -o UserKnownHostsFile=/home/hatch/.ssh/known_hosts
  -o ProxyCommand="$HOME/workspace/bin/ts-connect.py %h %p"
)

dell_ssh() {
  timeout 60 ssh "${SSH_OPTS[@]}" -i "$KEY" "$HOST" "$@"
}
dell_scp() {
  timeout 120 scp "${SSH_OPTS[@]}" -i "$KEY" "$@"
}

# Probe reachability first; quiet exit when the Dell is offline/asleep.
if ! dell_ssh "echo ok" 2>/dev/null | grep -q ok; then
  echo "dell unreachable, skipping"
  exit 0
fi

# Pack the vault (no .git) and ship it over.
tar -czf /tmp/atlas_sync.tgz --exclude=.git -C "$VAULT" . || exit 1
dell_scp /tmp/atlas_sync.tgz "$HOST:D:/atlas_sync.tgz" || exit 1
dell_ssh "powershell -NoProfile -Command \"New-Item -ItemType Directory -Force D:\\Atlas | Out-Null; tar -xzf D:\\atlas_sync.tgz -C D:\\Atlas; \$icloudDir = Join-Path \$env:USERPROFILE 'iCloudDrive'; if (Test-Path \$icloudDir) { \$v = Join-Path \$icloudDir 'Obsidian\\Atlas'; New-Item -ItemType Directory -Force \$v | Out-Null; tar -xzf D:\\atlas_sync.tgz -C \$v; Write-Output 'icloud updated' } else { Write-Output 'icloud not set up, skipped' }; Remove-Item D:\\atlas_sync.tgz -Force\"" || exit 1
rm -f /tmp/atlas_sync.tgz
echo "atlas synced to D:\\Atlas"
