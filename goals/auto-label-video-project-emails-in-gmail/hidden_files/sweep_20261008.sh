#!/usr/bin/env bash
# Gmail auto-label sweep — 2026-10-08 run. Sequential, rate-limit friendly.
set -u
OUT=/tmp/label_sweep_20261008.txt
> "$OUT"

G='hatch_gws_cli gmail'

# labelId|labelName|from-brace-spec|extra-query (for subject-based)
declare -a LABEL_GROUPS=(
  'Label_7|Finance/SoFi|{m.sofi.org newsletter.sofi.com}|'
  'Label_8|Finance/Robinhood|{noreply@robinhood.com}|'
  'Label_9|Finance/Chase|{no.reply.alerts@chase.com}|'
  'Label_10|Finance/Discover|{discover.com}|'
  'Label_11|Finance/Cash App|{square.com}|'
  'Label_12|Job Search|{ccsend.com hospitalityonline.com jobs2web.com cbpcareers}|'
  "Label_14|Shopping/Macy's|{macys.com em.macys.com emails.macys.com notify.macys.com}|"
  'Label_15|Shopping/AliExpress|{aliexpress.com}|'
  'Label_16|Shopping/eBay|{ebay.com}|'
  'Label_17|Shopping/Amazon|{business.amazon.com}|'
  'Label_18|Shopping/Mercari|{mercari}|'
  'Label_20|Investing/Jeremy Lefebvre|{financialeducationjeremy.com}|'
  'Label_21|Investing/WhaleWisdom|{whalewisdom.com}|'
  'Label_22|Investing/Yahoo Finance|{finance.comms.yahoo.net}|'
  'Label_23|GitHub|{noreply@github.com}|'
  'Label_24|Google|{google.com}|'
  'Label_4|Nursery Rhymes/Humpty Dumpty||"humpty dumpty"'
  'Label_5|Nursery Rhymes/Twinkle Twinkle Little Star||subject:twinkle'
)

purchase_re='order|receipt|confirmation|purchase|shipped|delivering|delivered|payment|invoice|transaction|refund|unusual activity|suspicious|locked|verify it.s you|password changed'

for g in "${LABEL_GROUPS[@]}"; do
  IFS='|' read -r lid lname fromspec extra <<< "$g"
  if [ -n "$fromspec" ]; then
    base="from:$fromspec -label:\"$lname\" -from:rahiknoor81@gmail.com"
  else
    base="$extra -label:\"$lname\""
  fi
  for state in unread read; do
    q="$base is:$state"
    ids=$($G users messages list --params "{\"userId\":\"me\",\"q\":$(printf '%s' "$q" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'),\"maxResults\":100}" 2>/dev/null \
      | python3 -c 'import json,sys; d=json.load(sys.stdin); print(" ".join(m["id"] for m in d.get("messages",[])))')
    [ -z "$ids" ] && continue
    if [ "$state" = "unread" ]; then
      for id in $ids; do echo "LBL|$lid|$lname|$id|unread" >> "$OUT"; done
      idlist=$(printf '%s' "$ids" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read().split()))')
      $G users messages batchModify --params '{"userId":"me"}' --json "{\"ids\":$idlist,\"addLabelIds\":[\"$lid\"]}" >/dev/null 2>&1
    else
      # read: check subjects for purchase/important keywords before archiving
      archive_ids=""; skip_ids=""
      for id in $ids; do
        subj=$($G users messages get --params "{\"userId\":\"me\",\"id\":\"$id\",\"format\":\"metadata\",\"metadataHeaders\":[\"Subject\"]}" 2>/dev/null \
          | python3 -c 'import json,sys; d=json.load(sys.stdin); hs={h["name"]:h["value"] for h in d.get("payload",{}).get("headers",[])}; print(hs.get("Subject",""))')
        if printf '%s' "$subj" | grep -qiE "$purchase_re"; then
          echo "LBL|$lid|$lname|$id|read|kept_purchase_important|${subj:0:80}" >> "$OUT"
          skip_ids="$skip_ids $id"
        else
          echo "LBL|$lid|$lname|$id|read|archived|${subj:0:80}" >> "$OUT"
          archive_ids="$archive_ids $id"
        fi
      done
      for batch in "label:$ids" "archive:$archive_ids"; do
        kind="${batch%%:*}"; list="${batch#*:}"
        [ -z "$list" ] && continue
        ilist=$(printf '%s' "$list" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read().split()))')
        if [ "$kind" = "label" ]; then
          $G users messages batchModify --params '{"userId":"me"}' --json "{\"ids\":$ilist,\"addLabelIds\":[\"$lid\"]}" >/dev/null 2>&1
        else
          $G users messages batchModify --params '{"userId":"me"}' --json "{\"ids\":$ilist,\"addLabelIds\":[\"$lid\"],\"removeLabelIds\":[\"INBOX\"]}" >/dev/null 2>&1
        fi
      done
    fi
    sleep 2
  done
done

echo "SWEEP_DONE" >> "$OUT"
wc -l "$OUT"
