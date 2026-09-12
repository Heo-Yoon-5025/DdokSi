#!/usr/bin/env bash
#
# 국회 Open API 응답 점검 스크립트.
#
# 목적: 인증키를 받은 직후, 또는 국회가 스펙을 바꿨다고 의심될 때
#       실제 응답 필드와 처리결과 문자열을 눈으로 확인한다.
#       이 결과가 있어야 bill.status 매핑을 추측이 아닌 규칙으로 확정할 수 있다.
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

# User-Agent 는 실제 수집 클라이언트(AssemblyApiClient)와 같은 값을 쓴다.
#
# 앞단 WAF 가 일부 UA 를 HTTP 400 "Bad Request." 로 막는다. 실측 결과 막히는 것은
# curl/* 과 빈/누락 UA 뿐이고, 자체 UA 는 그대로 통과한다. 즉 브라우저를 사칭할
# 이유가 없다. 사칭하면 국회 쪽에서 크롤러로 보고 차단할 위험만 커지고,
# 프로브와 운영 코드가 서로 다른 정체로 요청해 "스크립트는 되는데 앱은 막히는"
# 상황을 만들 수 있다. 우리 정체를 밝혀 API 운영자가 연락할 수 있게 둔다.
UA="ddoksi-collector/1.0 (+https://github.com/Heo-Yoon-5025/DdokSi)"

# 응답 본문에서 특정 필드의 첫 값을 꺼낸다. 뒤따르는 호출에 넘길 값을 얻는 용도다.
first_field() {
  python3 - "$1" "$2" <<'PY'
import json, sys
try:
    d = json.load(open(sys.argv[1], encoding='utf-8'))
except Exception:
    sys.exit(0)
for val in d.values():
    if not isinstance(val, list):
        continue
    for block in val:
        if isinstance(block, dict) and block.get('row'):
            print(block['row'][0].get(sys.argv[2], '') or '')
            sys.exit(0)
PY
}

# API 한 건을 호출하고 첫 행의 필드 구성을 보여준다.
#   $1 표시 이름  $2 API 코드명  $3 추가 쿼리스트링(선택)
call() {
  local name="$1" api="$2" extra="${3:-}"
  local url="${BASE}/${api}?KEY=${ASSEMBLY_API_KEY}&Type=json&pIndex=1&pSize=5${extra}"
  local file="${OUT}/${name}.json"

  printf '\n=== %s (%s) ===\n' "$name" "$api"
  local code
  code=$(curl -s --max-time 30 -A "$UA" "$url" -o "$file" -w '%{http_code}')
  echo "HTTP $code -> $file"

  # HTTP 400 은 API 오류가 아니라 앞단 WAF 차단이다. 이걸 API 장애로 오해하면
  # 멀쩡한 서비스를 붙잡고 원인을 엉뚱한 곳에서 찾게 된다. 재시도해도 소용없다.
  if [[ "$code" == "400" ]]; then
    echo "  ⚠️  WAF 차단으로 보입니다 (본문: $(head -c 40 "$file"))."
    echo "      User-Agent 설정을 확인하세요. 재시도해도 통과하지 않습니다."
    return
  fi

  # 오류도 HTTP 200 으로 온다. 상태 코드가 아니라 본문의 RESULT.CODE 를 봐야 한다.
  if grep -q '"RESULT"' "$file" && ! grep -q '"row"' "$file"; then
    echo "  ⚠️  오류 응답:"
    python3 -c "import json;print('   ',json.load(open('$file',encoding='utf-8'))['RESULT'])" 2>/dev/null \
      || head -c 200 "$file"
    return
  fi

  # 정상 응답이면 첫 행의 필드명을 뽑아 보여준다
  python3 - "$file" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding='utf-8'))
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

# 대수(AGE)로 거를 수 있는 것은 발의법률안뿐이다.
# BILLRCP 에 ERACO 를 넘기면 어떤 값을 줘도 INFO-200 이 되므로 넘기지 않는다.
call "발의법률안"   "nzmimeepazxkubdpn" "&AGE=22"
call "의안접수목록" "BILLRCP"
call "진행중입법예고" "nknalejkafmvgzmpt"

# 제안이유 API 는 목록을 주지 않는다. BILL_NO 가 필수라 법안을 하나 정해서 불러야 한다.
# 번호를 코드에 박지 않고 앞선 응답에서 꺼내 쓴다 — 박아두면 그 법안이 사라졌을 때
# 프로브가 API 장애처럼 보이는 실패를 낸다.
BILL_NO="$(first_field "${OUT}/발의법률안.json" BILL_NO)"
if [[ -n "$BILL_NO" ]]; then
  call "제안이유" "BPMBILLSUMMARY" "&BILL_NO=${BILL_NO}"
  echo "      (의안번호 ${BILL_NO} 기준)"
else
  echo
  echo "=== 제안이유 (BPMBILLSUMMARY) ==="
  echo "  건너뜀: 발의법률안 응답에서 BILL_NO 를 얻지 못했습니다."
fi

echo
echo "=== 처리결과(PROC_RESULT) 문자열 수집 ==="
echo "상태 매핑 규칙을 정하려면 실제로 어떤 값들이 오는지 알아야 한다."
echo "지금 매핑: PENDING / PASSED / MERGED / DISCARDED / UNKNOWN (BillStatusMapper)"
python3 - "$OUT" <<'PY'
import json, glob, os, sys, collections
counter = collections.Counter()
for path in glob.glob(os.path.join(sys.argv[1], '*.json')):
    try:
        d = json.load(open(path, encoding='utf-8'))
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
