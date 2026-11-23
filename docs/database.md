# 데이터베이스 설계

PostgreSQL 17 · 13개 테이블 · 마이그레이션 V1–V7

> 스키마의 주인은 **Flyway** 다. Hibernate 는 `ddl-auto=validate` 로 검증만 하고 테이블을
> 만들거나 바꾸지 않는다. **이미 적용된 마이그레이션은 절대 수정하지 않는다** — Flyway 가
> 체크섬을 비교해 기동을 거부한다. 새 버전 파일을 추가하는 것이 유일한 변경 방법이다.
>
> 설계 판단의 배경은 저장소 루트의 `CLAUDE.md`, 코드 위치는 `docs/code-map.md` 에 있다.

---

## 1. 전체 구조

네 도메인으로 나뉘고, 도메인 사이를 잇는 외래키는 많지 않다.

```
[수집]                      [법안]
collection_run ─┬─────────▶ bill ◀─┬── bill_summary        (1:1)
                │            │     ├── bill_status_history (1:N)
                └─▶ bill_raw │     ├── bill_analysis       (1:N, 버전별)
                             │     └── analysis_batch_item (N:M 연결)
                             │
                analysis_batch ─────┘
                committee_alias      (독립 참조 데이터)

[구독]                              [레터]
subscriber ─┬── subscription_committee
            └──────────────────────── letter_delivery ──▶ letter_issue
```

### 설계 원칙 네 가지

**1. 원본과 해석을 분리한다.**
`bill_raw` 가 API 응답 원문을, `bill` 이 우리가 해석한 값을 담는다. 파싱 버그가 나중에
발견돼도 원문이 있으면 재처리로 복구된다. 같은 이유로 `bill.status`(우리 분류)와
`bill.proc_result_raw`(국회 원문)를 둘 다 저장한다 — 재수집 없이 재분류할 수 있다.

**2. 행의 유무가 상태를 표현한다.**
`bill_summary` 에 행이 있으면 "본문을 받아봤다", 없으면 "아직 안 받았다"다. 본문이 비어
있어도 행은 남긴다 — 그래야 "받아봤는데 비었다"와 구별되고, 배치가 같은 건을 영원히
재호출하지 않는다.

**3. UNIQUE 로 중복 작업을 막는다.**
`bill_analysis` 의 `(bill_id, prompt_version)`, `letter_delivery` 의 `(issue, subscriber)`,
`analysis_batch` 의 `provider_batch_id`. 전부 **배치가 두 번 돌아도 같은 일을 두 번 하지
않게** 하는 장치다. 특히 분석과 발송은 두 번 하면 돈이 두 번 나가거나 같은 메일이 두 번 간다.

**4. 시각은 전부 `timestamptz`.**
`LocalDateTime` 을 쓰면 시간대가 사라져 서버 TZ 가 바뀔 때 의미가 달라진다. JSONB 는
`@JdbcTypeCode(SqlTypes.JSON)` 으로 매핑한다.

---

## 2. 수집 도메인

### `collection_run` — 모든 배치의 실행 이력

| 컬럼 | 타입 | 제약 | 용도 |
|---|---|---|---|
| `id` | bigserial | PK | |
| `job_name` | varchar(100) | NOT NULL | `member-bill-collect` · `bill-summary-collect` · `bill-analysis` · `bill-analysis-batch` |
| `status` | varchar(20) | NOT NULL | `RUNNING` / `SUCCESS` / `FAILED` |
| `started_at` | timestamptz | NOT NULL, now() | |
| `finished_at` | timestamptz | | 끝나지 않았으면 NULL — 죽은 배치를 찾는 단서 |
| `target_count` | int | NOT NULL, 0 | 처리 대상 건수 |
| `success_count` | int | NOT NULL, 0 | |
| `fail_count` | int | NOT NULL, 0 | 건별 실패는 배치를 중단시키지 않고 여기 쌓인다 |
| `cursor_value` | varchar(100) | | **다음 실행이 이어받을 위치.** 매번 전체를 긁지 않게 한다 |
| `error_message` | text | | 전체성 실패 사유 |
| `created_at` | timestamptz | NOT NULL, now() | |

인덱스: `(job_name, started_at DESC)` — "이 배치의 최근 실행"이 주 조회 경로다.

> **왜 필요한가.** "레터에 법안이 왜 하나도 없었지?"를 나중에 추적할 유일한 단서다.
> 배치가 조용히 실패했는지, 아예 안 돌았는지, 0건을 처리하고 성공했는지가 여기서만 갈린다.

### `bill_raw` — API 응답 원문

| 컬럼 | 타입 | 제약 | 용도 |
|---|---|---|---|
| `id` | bigserial | PK | |
| `collection_run_id` | bigint | FK → collection_run | 어느 실행이 가져왔는지 |
| `source_api` | varchar(50) | NOT NULL | `nzmimeepazxkubdpn` 등 — 수집원이 늘어날 때를 대비 |
| `external_bill_id` | varchar(50) | | 국회 의안 ID |
| `payload` | jsonb | NOT NULL | **응답 원문 그대로** |
| `payload_hash` | varchar(64) | NOT NULL | 내용이 바뀌었는지 판정. 같으면 저장하지 않는다 |
| `collected_at` | timestamptz | NOT NULL, now() | |

인덱스: `external_bill_id`, `collected_at DESC`

> **신규·변경 건만 저장한다.** 매 실행마다 전부 저장하면 19,447행이 실행마다 쌓인다.
> `payload_hash` 가 그 판정 기준이다.

---

## 3. 법안 도메인

### `bill` — 정규화된 법안 (19,447행)

| 컬럼 | 타입 | 제약 | 용도 |
|---|---|---|---|
| `id` | bigserial | PK | **id 1 이 가장 최근 법안**이다 — 수집기가 API 를 최신순으로 읽었다 |
| `external_bill_id` | varchar(50) | NOT NULL, UNIQUE | 국회 의안 ID (`PRC_...`). **우리가 법안을 식별하는 키** |
| `bill_no` | varchar(20) | | 의안번호. 제안이유 API 가 이걸로 조회한다 |
| `assembly_age` | smallint | | 국회 대수 (현재 전부 22) |
| `title` | text | NOT NULL | 법안명. 중앙값 16자, 최대 99자 |
| `status` | varchar(20) | NOT NULL, `'UNKNOWN'`, CHECK | `PENDING` / `PASSED` / `MERGED` / `DISCARDED` / `UNKNOWN` |
| `proc_result_raw` | varchar(200) | | **국회 원문 그대로** (`대안반영폐기` 등) |
| `proposer_kind` | varchar(30) | | 의원/위원장/정부/의장. **현재 전부 NULL** — 수집원이 의원 발의 하나뿐이라 값이 상수가 된다 |
| `proposer_summary` | text | | `황운하의원 등 14인` 같은 표시용 문자열. **파싱 대상이 아니다** |
| `rst_proposer` | text | | 대표발의자 |
| `committee_name` | varchar(100) | | 소관위원회. **개편 전후로 갈라져 있다** → `committee_alias` 참고. 72건은 NULL(회부 전) |
| `committee_id` | varchar(50) | | 위원회 코드 |
| `proposed_date` | date | | 제안일. 앱 목록의 정렬 기준 |
| `proc_date` | date | | 처리일. PENDING 이면 NULL |
| `detail_url` | text | | 국회 공식 페이지. **AI 요약이 틀렸을 때 기댈 곳**이라 항상 내보낸다 |
| `extra` | jsonb | NOT NULL, `{}` | 컬럼으로 안 올린 원천 필드. 위원회 단계 결과(`CMT_PROC_RESULT_CD`)가 여기 있어 **조회·인덱싱이 안 된다** |
| `first_seen_at` | timestamptz | NOT NULL, now() | 처음 수집된 시각 |
| `last_seen_at` | timestamptz | NOT NULL, now() | 마지막으로 응답에 나타난 시각 |

인덱스: `external_bill_id`(UNIQUE) · `(status, proposed_date DESC)` · `proposed_date DESC` · `committee_name`

**상태 분포 (19,447건 실측)**

| 상태 | 뜻 | 건수 | 비율 |
|---|---|---|---|
| `PENDING` | 아직 처리되지 않음 | 14,562 | 74.9% |
| `MERGED` | 위원회 대안에 내용이 반영되고 폐기 | 4,063 | 20.9% |
| `PASSED` | 원안·수정 가결 | 652 | 3.4% |
| `DISCARDED` | 임기만료·철회·부결 | 170 | 0.9% |
| `UNKNOWN` | 분류 실패 | **0** | — |

> **`MERGED` 가 독립 상태인 이유.** `대안반영폐기` 는 법안이 형식적으로 폐기되면서 내용은
> 위원회 대안에 흡수돼 법이 되는 경우다. "통과"라고 하면 사실이 아니고 "폐기"라고 하면
> 노력이 헛된 것처럼 읽힌다. 게다가 드문 경우가 아니라 **PASSED 의 여섯 배**다.

### `bill_summary` — 제안이유 및 주요내용 (19,447행)

| 컬럼 | 타입 | 제약 | 용도 |
|---|---|---|---|
| `id` | bigserial | PK | |
| `bill_id` | bigint | NOT NULL, FK, **UNIQUE** | 법안 하나에 본문은 하나 |
| `summary` | text | | 본문. **NULL 허용** — API 가 정상 응답으로 빈 본문을 주는 법안이 41건 있다 |
| `external_bill_id` | varchar(50) | | 응답에 담겨 온 의안 ID. 우리 것과 대조해 엉뚱한 법안을 가져왔는지 확인 |
| `fetched_at` | timestamptz | NOT NULL, now() | |

> **왜 `bill` 에 컬럼으로 안 붙였나.** 세 가지다. ① 목록 조회가 계속 훑는 테이블이
> 두꺼워진다 ② 1만9천 건 UPDATE 는 MVCC 상 행을 전부 새로 쓰고 인덱스 4개도 갱신한다
> ③ **행의 유무가 "받았는지"를 표현**해 중단 후 재개를 가능하게 한다.
>
> 본문 길이 실측(19,406건): 중앙값 499자, 평균 633자, **최대 10,349자**. 길이 상한을
> 가정하면 안 된다.

### `bill_status_history` — 상태 변경 이력 (19,447행)

| 컬럼 | 타입 | 제약 | 용도 |
|---|---|---|---|
| `id` | bigserial | PK | |
| `bill_id` | bigint | NOT NULL, FK | |
| `from_status` | varchar(20) | | **NULL 이면 최초 수집 기록** |
| `to_status` | varchar(20) | NOT NULL | |
| `from_proc_result_raw` | varchar(200) | | |
| `to_proc_result_raw` | varchar(200) | | 분류가 같아도 원문이 바뀌면 변경으로 본다 |
| `changed_at` | timestamptz | NOT NULL, now() | |
| `detected_by_run_id` | bigint | FK → collection_run | 어느 실행이 감지했는지 |

인덱스: `(bill_id, changed_at DESC)` · `changed_at DESC`

> **뉴스레터와 푸시 알림의 근거가 될 테이블.** "이번 달에 통과된 법안"을 여기서 뽑는다.
> 현재는 법안당 1행뿐이다 — 마지막 수집(2026-10-05) 이후 상태 변화가 감지된 적이 없다.
>
> **값이 실제로 바뀐 경우에만 행을 쓴다.** 안 그러면 실행마다 19,447행이 쌓인다.

### `bill_analysis` — Claude 분석 결과 (120행)

| 컬럼 | 타입 | 제약 | 용도 |
|---|---|---|---|
| `id` | bigserial | PK | |
| `bill_id` | bigint | NOT NULL, FK | |
| `prompt_version` | varchar(20) | NOT NULL | `v1`. 프롬프트가 바뀌면 올린다 |
| `model` | varchar(50) | NOT NULL | `claude-opus-5`. 품질 문제 추적용 |
| `source_hash` | varchar(64) | NOT NULL | SHA-256(제목 + 위원회 + 본문). **재생성 판정 기준** |
| `hook` | text | | 40자. SNS 첫 줄 / 카드뉴스 표지 (V5) |
| `summary` | text | | 150자. 제도를 무엇에서 무엇으로 바꾸는가 |
| `example` | text | | 120자. 누구의 어떤 상황과 닿는가 |
| `background` | text | | 150자. 왜 지금 나왔는가 |
| `topics` | jsonb | NOT NULL, `[]` | 고정 어휘 16개 중 0~2개 (V5). **GIN 인덱스** |
| `pros` | jsonb | NOT NULL, `[]` | **의도적으로 비워둠** (아래 참고) |
| `cons` | jsonb | NOT NULL, `[]` | **의도적으로 비워둠** |
| `status` | varchar(20) | NOT NULL, CHECK | `SUCCESS` / `FAILED` |
| `error_message` | text | | 실패 사유 |
| `generated_at` | timestamptz | NOT NULL, now() | |

제약: **UNIQUE (bill_id, prompt_version)** · 인덱스: `bill_id` · `topics`(GIN)

> **본문 항목이 전부 NULL 허용인 것이 중요하다.** "근거가 없으면 비워라"를 스키마가
> 금지하면 모델은 빈칸을 만들 수 없어 **뭔가를 지어낸다.**
>
> **`pros`/`cons` 를 채우지 않는 이유.** 입력인 제안이유는 발의자의 설득 문서라 반대
> 근거가 구조적으로 존재하지 않는다. 요구하면 모델이 반드시 만들어내고, 19,406건은 사람이
> 검수할 수 없다 — 정치 콘텐츠에서 치명적이다. 대안으로 검토한 회의록은 PDF이고 회의
> 단위이며 커버리지가 43%(PENDING 은 25%)라 부적합했다. 컬럼은 근거 있는 출처가 생길 때를
> 위해 남겨둔다.
>
> **UNIQUE 제약이 저장 로직을 결정한다.** 같은 법안·같은 버전으로 두 번째 행을 만들 수
> 없으므로 재시도는 INSERT 가 아니라 UPDATE 다. 그렇다고 "행이 있으면 건너뛴다"로 하면
> FAILED 가 영구히 남아 그 법안은 조용히 방치된다. **존재가 아니라 상태와 해시를 함께 본다.**

### `analysis_batch` — Batch API 제출 이력 (1행)

| 컬럼 | 타입 | 제약 | 용도 |
|---|---|---|---|
| `id` | bigserial | PK | |
| `run_id` | bigint | FK → collection_run (ON DELETE SET NULL) | 어느 실행이 제출했는지 |
| `provider_batch_id` | varchar(100) | NOT NULL, **UNIQUE** | Anthropic 배치 id (`msgbatch_...`). **결과를 찾아가는 유일한 열쇠** |
| `prompt_version` | varchar(20) | NOT NULL | |
| `model` | varchar(50) | NOT NULL | |
| `status` | varchar(20) | NOT NULL, CHECK | `SUBMITTED` / `COLLECTED` / `FAILED` |
| `request_count` | int | NOT NULL | 제출 건수. 수거 결과와 대조해 누락을 계산 |
| `succeeded_count` | int | NOT NULL, 0 | |
| `errored_count` | int | NOT NULL, 0 | |
| `missing_count` | int | NOT NULL, 0 | 보냈는데 결과에 없는 건 (취소·만료) |
| `submitted_at` | timestamptz | NOT NULL, now() | |
| `collected_at` | timestamptz | | |
| `error_message` | text | | |

인덱스: `(status, submitted_at)` — 기동 시 "미수거 배치"를 찾는 경로

> **이 테이블은 이중 과금 방지 장치다.** 배치는 제출하는 순간 과금이 확정되고 결과는 최대
> 24시간 뒤에 온다. 그 사이 배치 id 가 메모리에만 있으면 앱 재시작과 함께 사라지고, 이미
> 지불한 결과를 찾아갈 방법이 없어진다. 다음 실행은 같은 법안을 다시 제출한다.
> 동기 호출에는 없던 실패 방식이다.
>
> **Anthropic 쪽 진행 상태는 복제하지 않는다.** 물어보면 알 수 있는 남의 상태이고, 우리가
> 기억해야 하는 것은 "결과를 우리 DB 에 반영했는가" 하나다. 복제해두면 둘이 어긋났을 때
> 어느 쪽이 진실인지 알 수 없다.

### `analysis_batch_item` — 배치에 담아 보낸 법안 (20행)

| 컬럼 | 타입 | 제약 | 용도 |
|---|---|---|---|
| `batch_id` | bigint | NOT NULL, FK, **PK 일부** | |
| `bill_id` | bigint | NOT NULL, FK, **PK 일부** | `custom_id` 로 이 값을 쓴다 |
| `source_hash` | varchar(64) | NOT NULL | **제출 시점의 입력 해시** |

> **왜 따로 두는가.** 두 가지다.
>
> ① **해시를 제출 시점에 얼려야 한다.** 수거할 때 다시 계산하면 그 사이 제목이나
> 위원회명이 바뀐 경우 *보내지도 않은 입력*의 해시를 저장하게 되고, 다음 실행이 그것을
> "최신"으로 오판해 재생성 대상에서 뺀다. 위원회 개편으로 이름이 실제로 바뀐 전례가 있다.
>
> ② **응답에는 돌아온 것만 담긴다.** 무엇을 보냈는지 기록이 없으면 누락을 알아챌 수 없다.
>
> 복합 PK 가 한 배치에 같은 법안이 두 번 들어가는 것도 막는다 — 그러면 결과 매칭이 모호해진다.

### `committee_alias` — 위원회 이름 별칭 (3행)

| 컬럼 | 타입 | 제약 | 용도 |
|---|---|---|---|
| `alias_name` | varchar(100) | **PK** | 기록에 남은 옛 이름 |
| `canonical_name` | varchar(100) | NOT NULL | 지금 쓰는 정식 이름 |
| `note` | text | | 묶은 근거. 몇 년 뒤 "왜 묶였지?"가 반드시 나온다 |
| `created_at` | timestamptz | NOT NULL, now() | |

제약: `CHECK (alias_name <> canonical_name)` · 인덱스: `canonical_name`(역방향 조회)

**현재 3쌍**

| 옛 이름 | 건수 | 정식 이름 | 건수 |
|---|---|---|---|
| 기획재정위원회 | 439 | 재정경제기획위원회 | 1,432 |
| 환경노동위원회 | 145 | 기후에너지환경노동위원회 | 1,763 |
| 여성가족위원회 | 38 | 성평등가족위원회 | 219 |

> **판별 규칙은 "상임위원회 중 PENDING 이 0건".** 심사가 끝난 법안은 당시 이름이 기록에
> 얼어붙고 열려 있는 법안만 새 이름으로 옮겨갔기 때문이다. 다만 **"PENDING 0건"만으로는
> 부족하다** — 특별위원회 5개도 0건인데 그쪽은 이름이 바뀐 게 아니라 활동 기한이 끝난
> 한시 조직이다. 느슨한 규칙으로 구현하면 특위가 엉뚱한 상임위에 묶인다.
>
> **`bill.committee_name` 은 덮어쓰지 않는다.** 국회가 내려준 사실이고, 수집 배치가 같은
> 값을 다시 내려주므로 매번 되돌려야 한다. 조회 시점에 묶는다.
>
> **체인(A→B, B→C)은 금지.** `canonical_name` 은 반드시 최종 이름이어야 한다. DB 로는
> 자기참조까지만 막을 수 있어 해석기가 종점까지 따라가며 체인을 발견하면 경고한다.
> 개편이 또 일어나면 **기존 행의 `canonical_name` 도 함께 갱신**해야 한다.

---

## 4. 구독 도메인 — 테이블만 존재

### `subscriber` (0행)

| 컬럼 | 타입 | 제약 | 용도 |
|---|---|---|---|
| `id` | bigserial | PK | |
| `email` | varchar(255) | NOT NULL | |
| `status` | varchar(20) | NOT NULL, `'PENDING'`, CHECK | `PENDING` / `ACTIVE` / `UNSUBSCRIBED` |
| `unsubscribe_token` | uuid | NOT NULL, `gen_random_uuid()` | **수신거부 링크가 추측 가능하면 안 된다** |
| `verified_at` | timestamptz | | 더블 옵트인 인증 시각 |
| `subscribed_at` | timestamptz | | |
| `unsubscribed_at` | timestamptz | | 행을 지우지 않고 시각을 남긴다 — 재구독 이력이 필요하다 |

인덱스: `status` — 발송 대상(`ACTIVE`)을 고르는 경로

### `subscription_committee` (0행)

| 컬럼 | 타입 | 제약 | 용도 |
|---|---|---|---|
| `subscriber_id` | bigint | NOT NULL, FK, **PK 일부** | |
| `committee_name` | varchar(100) | NOT NULL, **PK 일부** | |
| `created_at` | timestamptz | NOT NULL, now() | |

> **행이 하나도 없으면 "전체 수신"으로 해석한다.** 전체 여부를 별도 컬럼으로 두지 않는
> 이유는 두 값이 어긋날 여지를 만들지 않기 위해서다.
>
> ⚠️ 여기 저장되는 이름은 **정식 이름**이어야 한다. 옛 이름이 저장되면 `committee_alias`
> 가 풀어주긴 하지만, 애초에 UI 가 정식 이름만 보여주도록 `toCanonicalNames()` 를 거친다.

---

## 5. 레터 도메인 — 테이블만 존재

### `letter_issue` (0행)

| 컬럼 | 타입 | 제약 | 용도 |
|---|---|---|---|
| `id` | bigserial | PK | |
| `issue_month` | date | NOT NULL, **UNIQUE** | 발행 월. 한 달에 한 호 |
| `subject` | text | NOT NULL | 메일 제목 |
| `body_html` | text | | 렌더링된 본문 |
| `status` | varchar(20) | NOT NULL, `'DRAFT'`, CHECK | `DRAFT` / `SENDING` / `SENT` |
| `published_at` | timestamptz | | |

### `letter_delivery` (0행)

| 컬럼 | 타입 | 제약 | 용도 |
|---|---|---|---|
| `id` | bigserial | PK | |
| `letter_issue_id` | bigint | NOT NULL, FK | |
| `subscriber_id` | bigint | NOT NULL, FK | |
| `status` | varchar(20) | NOT NULL, `'PENDING'`, CHECK | `PENDING` / `SENT` / `FAILED` / `BOUNCED` |
| `provider_message_id` | varchar(200) | | SES 메시지 ID. 바운스 추적에 필요 |
| `sent_at` | timestamptz | | |
| `error_message` | text | | |

제약: **UNIQUE (letter_issue_id, subscriber_id)** · 인덱스: `(letter_issue_id, status)`

> **UNIQUE 가 이중 발송을 막는다.** 발송 배치가 중간에 죽어 다시 돌아도 같은 사람에게 같은
> 호가 두 번 가지 않는다. 분석의 `(bill_id, prompt_version)` 과 같은 발상이다 —
> **되돌릴 수 없는 작업은 제약으로 막는다.**

---

## 6. 마이그레이션 이력

| 버전 | 내용 | 배경 |
|---|---|---|
| **V1** | `collection_run` · `bill_raw` · `bill` · `bill_status_history` · `bill_analysis` | 최초 스키마 |
| **V2** | `subscriber` · `subscription_committee` · `letter_issue` · `letter_delivery` | 구독·레터 |
| **V3** | `bill.status` 에 `MERGED` 추가 | 실데이터에서 `대안반영폐기` 가 `PASSED` 의 6배로 나왔다 |
| **V4** | `bill_summary` | 목록 API 에 본문이 없어 별도 API·별도 테이블이 필요했다 |
| **V5** | `bill_analysis.hook` · `topics` + GIN 인덱스 | **전량 생성 전에** 넣었다 — 나중에 추가하면 전량 재생성이라 13배 비싸진다 |
| **V6** | `analysis_batch` · `analysis_batch_item` | Batch API 의 이중 과금을 막기 위해 |
| **V7** | `committee_alias` + 3쌍 시드 | 개편으로 갈라진 위원회 이름 통합 |

> **V5 의 판단이 비용 구조를 보여준다.** 항목 하나를 전량 생성 *전에* 추가하면 약 2만원,
> *후에* 추가하면 전량 재생성이라 약 27만원이다. 13배 차이라 이 테이블에서는
> **"쓸지 안 쓸지 애매하면 지금 넣는다"** 가 맞는 판단이 된다.

---

## 7. 로컬 환경

```bash
# 개발 DB
createdb -O ddoksi ddoksi
# 테스트 DB (최초 1회, 스키마는 Flyway 가 만든다)
createdb -O ddoksi ddoksi_test
```

접속 정보는 `DB_URL` · `DB_USERNAME` · `DB_PASSWORD` 환경변수로 주입하고,
`application.properties` 에는 로컬 기본값만 둔다.

> **테스트는 `ddoksi_test` 를 쓴다.** 수집 배치는 묶음 단위 커밋이 설계의 핵심이라 롤백으로
> 감쌀 수 없고, 그 결과물이 실수집분과 섞이면 나중에 버그인지 테스트 찌꺼기인지 구분할 수
> 없게 된다. `DatabaseCleaner` 는 **JDBC URL 에 `ddoksi_test` 가 없으면 실행을 거부**한다 —
> `@ActiveProfiles("test")` 를 빠뜨리면 조용히 개발 DB 를 지우는 대신 시끄럽게 실패한다.

### 왜 PostgreSQL 인가

- 개인 프로젝트에 비용 부담이 없다
- AWS RDS 프리 티어가 지원한다 — 홈서버에서 클라우드로 옮길 때 **엔진은 그대로**이고
  돌아가는 위치만 바뀐다
- 네이티브 JSONB. 원문 페이로드와 AI 분석 출력처럼 반정형 데이터가 많다

업무에서 쓰는 Oracle 은 **의도적으로 쓰지 않는다.** 나중에 PostgreSQL → Oracle 로 옮기려면
SQL 을 다시 쓰고 라이선스 비용도 져야 한다.
