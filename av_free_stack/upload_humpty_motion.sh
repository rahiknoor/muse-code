#!/bin/bash
# Upload Humpty motion videos to Dell ComfyUI input via chunked scp (tunnel can't handle large files)
SSH_OPTS="-o BatchMode=yes -o ConnectTimeout=30 -o StrictHostKeyChecking=yes -o UserKnownHostsFile=/home/hatch/.ssh/known_hosts -i /home/hatch/.ssh/laptop_muse_ed25519 -o ProxyCommand=python3 /home/hatch/workspace/bin/ssh_via_tunnel.py %h %p"
REMOTE="saqib@100.98.44.74"
DESTDIR="D:\\ComfyUI\\ComfyUI_windows_portable\\ComfyUI\\input"
CHUNKDIR="$DESTDIR\\chunks"
cd /home/hatch/workspace/av_free_stack || exit 1

declare -A FILES=(
  [s2]="media-generation-humpty-2d-scene2-video-0-ba1c75a6-5f93-4693-b4be-b5ac5f0aa845.mp4:humpty_scene2_motion.mp4"
  [s3]="media-generation-humpty-2d-scene3-video-0-e49ab7b8-49ce-441b-8d86-ab1548872c9e.mp4:humpty_scene3_motion.mp4"
  [sr]="media-generation-humpty-2d-rescue-video-0-cc2603bb-028c-42f9-a61c-f1bf056b4d0f.mp4:humpty_rescue_motion.mp4"
  [s4]="media-generation-humpty-2d-scene4-video-0-d9267df2-0ca1-42b5-b6e1-c80e514bec52.mp4:humpty_scene4_motion.mp4"
)

for key in s2 s3 sr s4; do
  IFS=: read src dst <<< "${FILES[$key]}"
  echo "=== $key: $src -> $dst"
  rm -rf humpty_upload_chunks && mkdir -p humpty_upload_chunks
  split -b 400k "$src" "humpty_upload_chunks/${key}_"
  # upload chunks with retries
  for c in humpty_upload_chunks/${key}_*; do
    base=$(basename "$c")
    for attempt in 1 2 3 4 5 6; do
      if scp $SSH_OPTS "$c" "$REMOTE:$CHUNKDIR\\$base" 2>/dev/null; then break; fi
      sleep 3
    done
  done
  # verify no zero-byte chunks, re-upload offenders
  for i in 1 2 3; do
    zeros=$(ssh $SSH_OPTS "$REMOTE" "dir $CHUNKDIR" 2>/dev/null | grep -E "${key}_" | awk '$4==0 {print $5}')
    [ -z "$zeros" ] && break
    for z in $zeros; do
      for attempt in 1 2 3 4 5; do
        if scp $SSH_OPTS "humpty_upload_chunks/$z" "$REMOTE:$CHUNKDIR\\$z" 2>/dev/null; then break; fi
        sleep 3
      done
    done
  done
  # reassemble
  CHUNKS=$(cd humpty_upload_chunks && ls ${key}_* | sed "s|^|$CHUNKDIR\\\\|" | paste -sd+)
  ssh $SSH_OPTS "$REMOTE" "copy /b $CHUNKS $DESTDIR\\$dst" 2>&1 | tail -1
  # verify size
  rsize=$(ssh $SSH_OPTS "$REMOTE" "dir $DESTDIR\\$dst" 2>/dev/null | grep "$dst" | awk '{print $4}' | tr -d ',')
  lsize=$(stat -c%s "$src")
  echo "remote=$rsize local=$lsize"
  if [ "$rsize" = "$lsize" ]; then echo "VERIFY OK: $dst"; else echo "VERIFY FAIL: $dst"; fi
done
# cleanup chunks on Dell
ssh $SSH_OPTS "$REMOTE" "del /q $CHUNKDIR\\s* 2>nul & echo CLEANUP_DONE"
echo "ALL DONE"
