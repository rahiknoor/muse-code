#!/usr/bin/env bash
# Auto-label sweep for Gmail (cron: auto-label-video-project-emails).
# - Searches each sender/subject group for UNLABELED matches, applies the label.
# - Archives (removes INBOX) only matches that are READ, never purchase/important.
# - Never creates Gmail filters. Never touches rahiknoor81@gmail.com mail except
#   humpty/twinkle subject matches (per body exception).
# Run sequentially: Gmail uses a weighted per-account request budget.
set -uo pipefail
cd "$HOME" || exit 1
G=(hatch_gws_cli gmail)

rate_limited=0

glist() { "${G[@]}" users messages list --params "$1" 2>/dev/null; }
gget() { # $1=id $2=header-name
  "${G[@]}" users messages get --params \
    "$(jq -nc --arg id "$1" --arg h "$2" '{userId:"me",id:$id,format:"metadata",metadataHeaders:[$h]}')" 2>/dev/null
}

check_limit() { # $1=raw-output; returns 0 if rate limited
  echo "$1" | grep -q 'connector_rate_limited'
}

# collect_ids <query> -> newline list of message ids (up to 3 pages of 100)
collect_ids() {
  local query="$1" token="" page=0 out p
  while [ "$page" -lt 3 ]; do
    if [ -z "$token" ]; then
      p=$(jq -nc --arg q "$query" '{userId:"me",q:$q,maxResults:100}')
    else
      p=$(jq -nc --arg q "$query" --arg t "$token" '{userId:"me",q:$q,maxResults:100,pageToken:$t}')
    fi
    out=$(glist "$p") || { echo "LIST_ERROR"; return 1; }
    if check_limit "$out"; then echo "RATE_LIMITED"; return 2; fi
    echo "$out" | jq -r '.messages // [] | .[].id'
    token=$(echo "$out" | jq -r '.nextPageToken // empty')
    [ -z "$token" ] && break
    page=$((page+1))
  done
}

# Subjects that must never be archived (purchase receipts / important / security)
EXCL='order|receipt|confirmation|purchase|shipped|delivering|delivered|payment|invoice|transaction|refund|unusual activity|suspicious|locked|verify it.s you|password changed'
excluded_subject() { # $1=subject -> 0 if excluded from archiving
  echo "$1" | grep -qiE "$EXCL"
}

bmod() { # $1=add-json $2=remove-json $3...=ids
  local body ids_json
  ids_json=$(printf '%s\n' "$@" | tail -n +3 | jq -R . | jq -s .)
  [ "$(echo "$ids_json" | jq 'length')" = "0" ] && return 0
  body=$(jq -nc --argjson ids "$ids_json" --argjson a "$1" --argjson r "$2" \
    '{ids:$ids,addLabelIds:$a,removeLabelIds:$r}')
  local out
  out=$("${G[@]}" users messages batchModify --params '{"userId":"me"}' --json "$body" 2>/dev/null) || { echo "BMOD_ERROR"; return 1; }
  if check_limit "$out"; then echo "RATE_LIMITED"; return 2; fi
  echo "OK"
}

echo "=== group sweep ==="
printf '%s\n' \
'Label_7|Finance/SoFi|{from:m.sofi.org from:newsletter.sofi.com} -label:"Finance/SoFi" -from:rahiknoor81@gmail.com' \
'Label_8|Finance/Robinhood|from:noreply@robinhood.com -label:"Finance/Robinhood" -from:rahiknoor81@gmail.com' \
'Label_9|Finance/Chase|from:no.reply.alerts@chase.com -label:"Finance/Chase" -from:rahiknoor81@gmail.com' \
'Label_10|Finance/Discover|from:discover.com -label:"Finance/Discover" -from:rahiknoor81@gmail.com' \
'Label_11|Finance/Cash App|from:square.com -label:"Finance/Cash App" -from:rahiknoor81@gmail.com' \
'Label_12|Job Search|{from:ccsend.com from:hospitalityonline.com from:jobs2web.com from:cbpcareers} -label:"Job Search" -from:rahiknoor81@gmail.com' \
'Label_14|Shopping/Macy'"'"'s|{from:macys.com from:em.macys.com from:emails.macys.com from:notify.macys.com} -label:"Shopping/Macy'"'"'s" -from:rahiknoor81@gmail.com' \
'Label_15|Shopping/AliExpress|from:aliexpress.com -label:"Shopping/AliExpress" -from:rahiknoor81@gmail.com' \
'Label_16|Shopping/eBay|from:ebay.com -label:"Shopping/eBay" -from:rahiknoor81@gmail.com' \
'Label_17|Shopping/Amazon|from:business.amazon.com -label:"Shopping/Amazon" -from:rahiknoor81@gmail.com' \
'Label_18|Shopping/Mercari|from:mercari -label:"Shopping/Mercari" -from:rahiknoor81@gmail.com' \
'Label_20|Investing/Jeremy Lefebvre|from:financialeducationjeremy.com -label:"Investing/Jeremy Lefebvre" -from:rahiknoor81@gmail.com' \
'Label_21|Investing/WhaleWisdom|from:whalewisdom.com -label:"Investing/WhaleWisdom" -from:rahiknoor81@gmail.com' \
'Label_22|Investing/Yahoo Finance|from:finance.comms.yahoo.net -label:"Investing/Yahoo Finance" -from:rahiknoor81@gmail.com' \
'Label_23|GitHub|from:noreply@github.com -label:"GitHub" -from:rahiknoor81@gmail.com' \
'Label_24|Google|from:google.com -label:"Google" -from:rahiknoor81@gmail.com' \
'Label_4|Nursery Rhymes/Humpty Dumpty|subject:"humpty dumpty" -label:"Nursery Rhymes/Humpty Dumpty"' \
'Label_5|Nursery Rhymes/Twinkle Twinkle Little Star|subject:twinkle -label:"Nursery Rhymes/Twinkle Twinkle Little Star"' \
| while IFS='|' read -r label_id label_name query; do
  ids_raw=$(collect_ids "$query"); rc=$?
  if [ "$rc" = "2" ] || [ "$ids_raw" = "RATE_LIMITED" ]; then echo "RATE_LIMITED_STOP|$label_name"; exit 10; fi
  if [ "$rc" = "1" ] || [ "$ids_raw" = "LIST_ERROR" ]; then echo "LIST_FAIL|$label_name"; continue; fi
  mapfile -t ids < <(printf '%s\n' "$ids_raw" | sed '/^$/d')
  if [ "${#ids[@]}" = "0" ]; then echo "GROUP|$label_id|$label_name|0|0|0|0"; continue; fi

  declare -a all_ids=() archive_ids=()
  unread=0; excl=0
  for id in "${ids[@]}"; do
    meta=$(gget "$id" "Subject") || { echo "GET_FAIL|$label_name|$id"; continue; }
    if check_limit "$meta"; then echo "RATE_LIMITED_STOP|$label_name"; exit 10; fi
    lbls=$(echo "$meta" | jq -r '.labelIds // [] | join(",")')
    subj=$(echo "$meta" | jq -r '.payload.headers[]? | select(.name=="Subject") | .value // empty' | head -c 300)
    all_ids+=("$id")
    if [[ ",$lbls," == *",UNREAD,"* ]]; then unread=$((unread+1)); continue; fi
    if [[ ",$lbls," != *",INBOX,"* ]]; then continue; fi
    if excluded_subject "$subj"; then excl=$((excl+1)); continue; fi
    archive_ids+=("$id")
  done

  add_json=$(jq -nc --arg l "$label_id" '[$l]')
  r1=$(bmod "$add_json" '[]' "${all_ids[@]}") || echo "BMOD_ADD_FAIL|$label_name"
  if [ "${#archive_ids[@]}" -gt 0 ]; then
    r2=$(bmod '[]' '["INBOX"]' "${archive_ids[@]}") || echo "BMOD_RM_FAIL|$label_name"
  fi
  echo "GROUP|$label_id|$label_name|${#all_ids[@]}|${#archive_ids[@]}|$unread|$excl"
done
rc_all=$?
[ "$rc_all" = "10" ] && { echo "ABORTED_RATE_LIMITED"; exit 10; }

echo "=== unknown senders (inbox, 30d, sample 100) ==="
inbox_raw=$(collect_ids "in:inbox newer_than:30d") || inbox_raw=""
mapfile -t inbox_ids < <(printf '%s\n' "$inbox_raw" | sed '/^$/d' | head -100)
declare -A domcount=()
for id in "${inbox_ids[@]}"; do
  meta=$(gget "$id" "From") || continue
  if check_limit "$meta"; then echo "ABORTED_RATE_LIMITED"; exit 10; fi
  from=$(echo "$meta" | jq -r '.payload.headers[]? | select(.name=="From") | .value // empty' | head -1)
  dom=$(echo "$from" | grep -oiE '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}' | tail -1 | cut -d@ -f2 | tr 'A-Z' 'a-z' || true)
  [ -z "$dom" ] && continue
  domcount["$dom"]=$(( ${domcount["$dom"]:-0} + 1 ))
done
MAP='sofi.com sofi.org robinhood.com chase.com discover.com square.com ccsend.com hospitalityonline.com jobs2web.com cbpcareers macys.com aliexpress.com ebay.com amazon.com mercari financialeducationjeremy.com whalewisdom.com yahoo.net github.com google.com gmail.com'
for dom in "${!domcount[@]}"; do
  n=${domcount[$dom]}
  [ "$n" -lt 3 ] && continue
  skip=0
  for tok in $MAP; do [[ "$dom" == *"$tok"* ]] && skip=1 && break; done
  [ "$skip" = "1" ] && continue
  echo "UNKNOWN|$n|$dom"
done
echo "SAMPLED|${#inbox_ids[@]}"
echo "DONE"
