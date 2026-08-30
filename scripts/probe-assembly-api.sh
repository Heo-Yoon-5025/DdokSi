#!/usr/bin/env bash
#
# 국회 Open API 응답 점검 스크립트.
#
# 목적: 인증키를 받은 직후, 실제 응답 필드와 처리결과 문자열을 확인한다.
# 이 결과를 보고 나서야 bill.status 매핑 규칙(PENDING/PASSED/DISCARDED)을 확정할 수 있다.
#
# 사용법:
#   ASSEMBLY_API_KEY=발급받은키 ./scripts/probe-assembly-api.sh
#
set -euo pipefail

if [[ -z "${ASSEMBLY_API_KEY:-}" ]]; then
  echo "오류: ASSEMBLY_API_KEY 환경변수가 필요합니다." >&2
  echo "사용법: ASSEMBLY_API_KEY=발급받은키 $0" >&2
  exit 1
fi

BASE="https://open.assembly.go.kr/portal/openapi"
OUT="${OUT_DIR:-./build/api-probe}"
mkdir -p "$OUT"

# ⚠️ User-Agent 를 보내지 않으면 앞단에서 HTTP 400 "Bad Request." 로 막힌다.
#    API 오류가 아니라 WAF 차단이므로, 실제 수집 코드에서도 반드시 설정해야 한다.
UA="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

call() {
  local name="$1" api="$2" extra="${3:-}"
  local url="${BASE}/${api}?KEY=${ASSEMBLY_API_KEY}&Type=json&pIndex=1&pSize=5${extra}"
  local file="${OUT}/${name}.json"

  printf '\n=== %s (%s) ===\n' "$name" "$api"
  local code
  code=$(curl -s --max-time 30 -A "$UA" -H "Referer: https://open.assembly.go.kr/" \
              "$url" -o "$file" -w '%{http_code}')
  echo "HTTP $code -> $file"

  # RESULT 만 있는 응답은 오류다 (예: ERROR-290 인증키 무효)
  if grep -q '"RESULT"' "$file" && ! grep -q '"row"' "$file"; then
    echo "  ⚠️  오류 응답:"
    python3 -c "import json,sys; d=json.load(open('$file')); print('   ', d['RESULT'])" 2>/dev/null \
      || head -c 200 "$file"
    return
  fi

  # 정상 응답이면 첫 행의 필드명을 뽑아 보여준다
  python3 - "$file" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
for key, val in d.items():
    if not isinstance(val, list):
        continue
    rows = [b for b in val if isinstance(b, dict) and 'row' in b]
    if not rows:
        continue
    first = rows[0]['row'][0]
    print(f"    최상위 키: {key}")
    print(f"    필드 {len(first)}개:")
    for k, v in first.items():
        preview = str(v)[:60] if v is not None else '(null)'
        print(f"      {k:<24} = {preview}")
PY
}

echo "국회 Open API 응답 점검 — 결과는 ${OUT} 에 저장됩니다."

call "발의법률안"   "nzmimeepazxkubdpn" "&AGE=22"
call "의안접수목록" "BILLRCP"
call "진행중입법예고" "nknalejkafmvgzmpt"

echo
echo "=== 처리결과(PROC_RESULT) 문자열 수집 ==="
echo "상태 매핑 규칙을 정하려면 실제로 어떤 값들이 오는지 알아야 한다."
python3 - "$OUT" <<'PY'
import json, glob, os, sys, collections
counter = collections.Counter()
for path in glob.glob(os.path.join(sys.argv[1], '*.json')):
    try:
        d = json.load(open(path))
    except Exception:
        continue
    for val in d.values():
        if not isinstance(val, list):
            continue
        for block in val:
            if not (isinstance(block, dict) and 'row' in block):
                continue
            for row in block['row']:
                for k, v in row.items():
                    if 'PROC' in k.upper() and 'DT' not in k.upper() and v:
                        counter[f"{k}={v}"] += 1
if counter:
    for k, n in counter.most_common():
        print(f"  {n:>3}  {k}")
else:
    print("  (처리결과 필드를 찾지 못했습니다 — pSize 를 늘려 다시 실행해 보세요)")
PY
