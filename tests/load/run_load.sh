#!/usr/bin/env bash
# Запуск одного нагрузочного профиля с мониторингом ресурсов.
#   RESULTS_DIR=~/lfx-results/load MYSQL_CLI="mysql -uroot -proot luckfox_db" ./run_load.sh <profile>
# Результаты: raw-<profile>.csv.gz (все метрики k6), summary-<profile>.json, monitor-<profile>.csv, meta-<profile>.json
set -u
PROFILE="$1"
NAME="${2:-$1}"   # имя файлов результатов (один профиль можно прогнать в разных условиях)
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${RESULTS_DIR:-$HERE/results}"
API_URL="${API_URL:-http://localhost:8080/api}"
mkdir -p "$OUT"

count() { curl -s "$API_URL/$1?size=1${2:-}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["totalElements"])' 2>/dev/null; }
before="{\"components\": $(count components), \"images\": $(count images)}"

python3 "$HERE/monitor.py" "$OUT/monitor-$NAME.csv" &
MON=$!
START=$(date +%s)
K6_CSV_TIME_FORMAT=unix_milli k6 run --quiet --no-color \
  -e PROFILE="$PROFILE" -e API_URL="$API_URL" -e WEB_URL="${WEB_URL:-http://localhost:5173}" \
  -e SUMMARY="$OUT/summary-$NAME.json" \
  --out "csv=$OUT/raw-$NAME.csv.gz" "$HERE/scenarios.js" > "$OUT/k6-$NAME.log" 2>&1
RC=$?
END=$(date +%s)
sleep 2
kill "$MON" 2>/dev/null; wait "$MON" 2>/dev/null
after="{\"components\": $(count components), \"images\": $(count images), \"queued\": $(count images '&state=QUEUED'), \"building\": $(count images '&state=BUILDING')}"
cat > "$OUT/meta-$NAME.json" <<EOF
{"profile": "$PROFILE", "name": "$NAME", "start": $START, "end": $END, "k6_exit": $RC, "before": $before, "after": $after}
EOF
echo "$NAME: k6 exit $RC, $((END-START)) s"
tail -n 3 "$OUT/k6-$NAME.log"
