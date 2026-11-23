# 코드 지도

어느 디렉터리에 무엇이 있고, 어떤 요건이 어디까지 구현돼 있는가.

> 설계 판단의 **이유**는 저장소 루트의 `CLAUDE.md`, DB 스키마는 `docs/database.md` 에 있다.
> 이 문서는 **위치**를 다룬다.

| | |
|---|---|
| Java | 78 파일 |
| 앱 (ts/tsx) | 12 파일 |
| 마이그레이션 | 7 (V1–V7) |
| 테이블 | 13 |
| 테스트 | 88 (14 파일) |
| 엔드포인트 | 3 |

---

## 1. 디렉터리 지도

패키지는 **계층이 아니라 도메인으로** 나뉜다. controller/service/repository 로 쪼개면 변경
하나가 먼 폴더 네 곳에 흩어지는데, 이 프로젝트는 도메인 경계가 뚜렷해서 도메인 우선이
관련 코드를 모아 준다.

```
com.ddoksi.ddoksi
├── config/          Spring 설정 (JPA Auditing)                    1
├── common/          도메인에 속하지 않는 공통물                     5
│   ├── entity/        BaseTimeEntity · BaseCreatedAtEntity
│   ├── dto/           PageResponse
│   └── web/           예외 처리 · CORS
├── collection/      국회 API 수집                                 20
│   ├── api/           HTTP 와 닿는 유일한 자리
│   ├── entity/        CollectionRun · BillRaw
│   └── repository/
├── bill/            법안 도메인 — 가장 큰 패키지                   43
│   ├── analysis/      AI 분석 (동기 + 배치)                       16
│   ├── entity/        Bill · BillAnalysis · AnalysisBatch · ...   11
│   ├── repository/    Specification 포함                           8
│   └── dto/           조회 응답                                     4
├── subscription/    구독자 — 엔티티만                              4
│   └── entity/
└── letter/          월간 레터 — 엔티티만                           4
    └── entity/
```

> `subscription/` 과 `letter/` 에는 `entity/` 하위 디렉터리밖에 없다. 테이블과 매핑까지만
> 있고 service·repository·controller 가 한 줄도 없다는 뜻이다. **어디까지 왔는지가 폴더
> 목록에 그대로 드러나는 것**이 도메인 우선 구조의 부수 효과다.

---

## 2. 요건별 구현 현황

| 요건 | 상태 | 구현 위치 |
|---|---|---|
| 국회에서 법안 목록을 가져온다 | ✅ 완료 | `collection/BillCollectionService` |
| 법안 본문(제안이유)을 가져온다 | ✅ 완료 | `collection/BillSummaryCollectionService` |
| 처리결과를 우리 상태로 분류한다 | ✅ 완료 | `bill/BillStatusMapper` |
| 상태 변화를 이력으로 남긴다 | ✅ 완료 | `collection/BillPagePersister` |
| AI 로 법안 해설을 만든다 | ✅ 완료 | `bill/analysis/` (동기 · 배치) |
| 법안을 목록·상세로 조회한다 | ✅ 완료 | `bill/BillController` · `BillQueryService` |
| 개편된 위원회 이름을 묶는다 | ✅ 완료 | `bill/CommitteeNameResolver` |
| 앱에서 목록·상세를 본다 | ✅ 완료 | `app/src/app/` (Expo Router) |
| 이메일로 구독을 신청한다 | ⬜ 미구현 | `subscription/entity/` 만 존재 |
| 더블 옵트인으로 인증한다 | ⬜ 미구현 | `Subscriber` 에 컬럼만 |
| 이번 달 레터를 편성한다 | ⬜ 미구현 | `letter/entity/` 만 존재 |
| 레터를 발송한다 (AWS SES) | ⬜ 미구현 | 의존성도 없음 |
| 수신거부를 처리한다 | ⬜ 미구현 | 토큰 컬럼만 |
| 서버에 배포한다 | ⬜ 미착수 | — |

**경계가 선명하다.** "모으고 · 분석하고 · 보여주는" 쪽은 전부 되고, "구독받아 보내는" 쪽은
전부 테이블뿐이다.

---

## 3. collection — 국회 API 수집

### `collection/api/`

| 파일 | 역할 |
|---|---|
| `AssemblyApiClient` | HTTP 호출. 응답 봉투(`head`/`row`)를 벗기고 `RESULT.CODE` 를 검사한다 — 이 API 는 **오류도 HTTP 200** 으로 오므로 상태 코드만으로는 실패를 못 가린다 |
| `AssemblyApiProperties` | 키·URL·타임아웃·페이지 크기·호출 간격. **User-Agent 가 설정에 있는 이유**는 WAF 가 일부 UA 를 400 으로 막기 때문이다 |
| `AssemblyPage` | 한 페이지 응답 |
| `AssemblyApiException` | 재시도 가능 여부를 구분해 담는다 (타임아웃·5xx 는 재시도, 잘못된 키·차단된 UA 는 즉시 중단) |

### `collection/` — 법안 목록 배치

| 파일 | 역할 |
|---|---|
| `BillCollectionService` | 페이지를 순회하는 본체. 실행 이력을 `collection_run` 에 남긴다 |
| `BillPagePersister` | **페이지당 한 트랜잭션.** 195면을 한 트랜잭션에 묶으면 커넥션을 내내 붙들고 늦은 실패가 전부를 되돌린다 |
| `BillRowMapper` | API 행 → `Bill` 엔티티 |
| `PageResult` | 페이지 처리 결과 집계 |
| `BillCollectionScheduler` | 매일 03:30. **기본 비활성** |
| `BillBackfillRunner` | 기동 시 전량 수집. **기본 비활성** |

**핵심 성질은 멱등성.** 상태가 실제로 바뀐 경우에만 이력 행을 쓰고 원본 페이로드도 신규·변경
건만 저장한다 — 안 그러면 실행마다 19,447행이 쌓인다.

### `collection/` — 제안이유 배치

| 파일 | 역할 |
|---|---|
| `BillSummaryCollectionService` | 본체. **의안번호당 1호출**이라 목록 배치와 분리돼 있다 (합치면 2분짜리 작업이 몇 시간이 된다) |
| `BillSummaryPersister` | 100건당 커밋. 응답의 `BILL_ID` 를 우리 것과 대조해 어긋나면 건너뛴다 |
| `FetchedSummary` | 가져온 본문 한 건 |
| `SummaryResult` | 결과 집계 (신규 / 빈 본문 / 데이터없음 / 건너뜀) |
| `BillSummaryBackfillRunner` | 기동 시 전량. **기본 비활성** |

**핵심 성질은 재개 가능성.** 행이 있는 법안은 대상에서 빠지고, 커서(`afterId`)로 항상
앞으로만 나아간다 — "앞에서부터 N건"이면 저장에 실패하는 한 건 때문에 제자리를 맴돈다.

---

## 4. bill/analysis — AI 분석

16개 파일로 가장 큰 하위 패키지. 같은 일을 하는 경로가 둘(동기·배치)이고, 둘이 갈라지지
않게 묶어 둔 지점들이 있다.

### 프롬프트와 어휘

| 파일 | 역할 |
|---|---|
| `BillAnalysisPrompt` | **프롬프트 · 출력 스키마 · 입력 해시가 한 곳에.** 셋은 함께 바뀌는 것들이라 흩어두면 `prompt_version` 과 어긋난다. 캐시되는 고정 프리픽스(`SYSTEM`)에 법안별 정보가 한 글자라도 섞이면 캐시가 조용히 깨진다 |
| `BillTopic` | 주제 태그 고정 어휘 16개 + `sanitize()`. 원천에 주제 분류가 없어 우리가 만들어야 한다 |

### SDK 와 닿는 자리

| 파일 | 역할 |
|---|---|
| `AnthropicClientConfig` | 클라이언트 빈. **키가 없어도 기동은 성공**해야 한다 — 분석은 기본 비활성이고 조회·수집은 Claude 없이 돈다 |
| `ClaudeAnalysisClient` | 동기 호출. 응답 해석(`parse`·`extractText`)과 `outputConfig()` 를 배치와 **공유**한다 |
| `ClaudeBatchAnalysisClient` | 배치 제출 · 폴링 · 결과 스트리밍. 토큰 사용량을 집계해 **캐시읽기가 0이면 경고**한다 (캐시 실패는 조용하고 청구서만 두 배가 된다) |

### 오케스트레이션과 저장

| 파일 | 역할 |
|---|---|
| `BillAnalysisService` | 동기 경로. 연속 실패 5건이면 중단 — 인증 오류·크레딧 소진은 건별로 보이지만 실제로는 전체성 실패다 |
| `BillAnalysisBatchService` | 배치 경로. **미수거 배치 수거 → 새 배치 제출** 순서를 강제한다 |
| `AnalysisBatchRecorder` | 제출 직후 **별도 트랜잭션으로 즉시 커밋.** 오케스트레이션에 트랜잭션을 걸면 배치가 끝날 때까지(최대 24시간) 열려 있게 된다 |
| `BillAnalysisPersister` | 저장. `UNIQUE (bill_id, prompt_version)` 때문에 재시도는 INSERT 가 아니라 UPDATE 다 |
| `PendingAnalysis` | 배치에 실어 보낼 한 건 (billId · sourceHash · userMessage) |
| `AnalysisResult` | 모델이 돌려준 다섯 항목 |
| `AnalyzedBill` | 한 건의 시도 결과 — 성공·실패를 한 타입에 담는다 (실패도 행으로 남겨야 재시도 대상이 된다) |
| `AnalysisChunkResult` | 묶음 집계 (신규/재시도/재생성/변화없음/실패) |

### 진입점

| 파일 | 역할 |
|---|---|
| `BillAnalysisScheduler` | 매일 04:30 (수집 뒤). **기본 비활성** |
| `BillAnalysisBackfillRunner` | 동기 백필. 소량 검증용 |
| `BillAnalysisBatchRunner` | 배치 백필. 전량은 이쪽 |

셋 다 기본 비활성이다. 다른 배치들과 같은 이유에 하나가 더 붙는다 — **호출 한 건이 곧
과금**이라 모르고 켜두면 기동만으로 청구서가 생긴다.

### 값을 지키는 규칙 둘 (테스트로 고정)

1. **미수거 배치 수거 → 새 배치 제출 순서, 배치는 한 번에 하나만.**
   대상 조회는 "분석 행이 없는 법안"을 고르는데, 제출 중인 법안에도 아직 분석 행이 없다 —
   **"제출 중"과 "미착수"가 구별되지 않는다.**
2. **매칭은 위치가 아니라 `custom_id` 로만.**
   결과는 보낸 순서로 오지 않는다. 위치로 맞추면 분석이 엉뚱한 법안에 붙는데 예외도 안 나고
   값도 다 차 있어서, 읽어보기 전엔 아무도 모른다.

---

## 5. bill — 조회 · 엔티티 · 리포지토리

### `bill/` (최상위)

| 파일 | 역할 |
|---|---|
| `BillController` | `GET /api/bills` · `/{id}` · `/committees`. 인증 없음 — 공개 데이터다 |
| `BillQueryService` | 읽기 전용. 조회가 읽을 분석 버전(`active-prompt-version`)이 생성 버전과 분리돼 있어 v2 백필 중에도 화면은 안 흔들린다 |
| `BillStatusMapper` | 국회 처리결과 → `BillStatus`. **분류가 사는 유일한 자리** — 다른 데서 또 분류하면 두 기준이 생긴다 |
| `CommitteeNameResolver` | 개편 전후 위원회 이름 통합. 옛 이름을 입력해도 동작하고, 별칭 체인은 종점까지 따라가며 경고한다 |

### `bill/repository/`

| 파일 | 역할 |
|---|---|
| `BillRepository` | 조회 + 배치용 커서 쿼리(`findBillsNeedingAnalysis`) |
| `BillSpecifications` | **동적 필터.** `(:param is null or col = :param)` 은 실제로 깨졌다 — null 바인드를 PostgreSQL 이 `bytea` 로 추론해 `lower(bytea)` 오류가 났고, 인덱스도 못 탄다 |
| `BillSummaryRepository` | 본문. 배치용 대량 조회 포함 |
| `BillAnalysisRepository` | 버전별 분석 조회 · 남은 건수 |
| `BillStatusHistoryRepository` | 상태 이력 |
| `AnalysisBatchRepository` / `AnalysisBatchItemRepository` | 배치 이력 |
| `CommitteeAliasRepository` | 별칭 (3행이라 통째로 읽는다) |

### `bill/entity/` · `dto/`

엔티티: `Bill` · `BillSummary` · `BillStatusHistory` · `BillAnalysis` · `BillStatus` ·
`AnalysisStatus` · `AnalysisBatch` · `AnalysisBatchItem(+Id)` · `AnalysisBatchStatus` ·
`CommitteeAlias`

**엔티티 규약**

- Lombok `@Data`·`@Setter` 를 쓰지 않는다 — 둘 다 JPA 영속성 컨텍스트와 싸운다
- 생성자는 private + `@Builder`, 기본 생성자는 `PROTECTED` (JPA 가 요구하지만 애플리케이션
  코드가 부를 일은 없다)
- 상태 변경은 의도를 드러내는 이름의 메서드로 한다 — `verify()` · `markSent()` · `complete()`
- 모든 시각은 `java.time.Instant`, JSONB 는 `@JdbcTypeCode(SqlTypes.JSON)`

DTO 넷(`BillSummaryResponse` · `BillDetailResponse` · `BillAnalysisResponse` ·
`BillStatusChangeResponse`)은 응답 계약이다. 상세는 **원문과 분석을 따로** 내려준다 —
앱이 원문을 접고 요약을 기본 노출할 수 있게.

---

## 6. common · config

| 파일 | 역할 |
|---|---|
| `common/dto/PageResponse` | 페이지 응답. Spring 의 `Page` 를 그대로 쓰지 않는 이유는 그 JSON 모양이 버전마다 바뀌어 **프레임워크 내부 클래스가 앱의 계약이 되기** 때문이다 |
| `common/entity/BaseTimeEntity`<br>`BaseCreatedAtEntity` | 생성·수정 시각 Auditing 공통 상위 |
| `common/web/ApiExceptionHandler` | `ResponseEntityExceptionHandler` 를 **상속**한다. `Exception` 하나로 받으면 Spring 의 올바른 매핑을 덮어써서 `status=NOPE` 가 400 대신 500 이 됐다 |
| `common/web/WebCorsConfig` | 허용 출처를 설정에서 읽는다. **와일드카드는 쓰지 않는다** |
| `config/JpaAuditingConfig` | Auditing 활성화 |

---

## 7. 설정 키

비밀값은 gitignore 된 `.env` 에서 온다 (`spring.config.import=optional:file:./.env`).
**배치는 전부 기본 비활성이다.**

### 수집

| 키 | 기본값 | 비고 |
|---|---|---|
| `assembly.api.key` | `${ASSEMBLY_API_KEY}` | .env |
| `assembly.api.user-agent` | `ddoksi-collector/1.0` | **비우면 WAF 가 400 으로 막는다** |
| `assembly.api.request-delay` | `300ms` | 공공 API 라 보수적으로 |
| `assembly.api.page-size` | `100` | |
| `ddoksi.collection.assembly-age` | `22` | 대상 국회 대수 |
| `ddoksi.collection.scheduled.enabled` | `false` | 목록 수집 03:30 |
| `ddoksi.collection.scheduled.summary-cron` | `0 0 4 * * *` | 본문 수집 04:00 |
| `ddoksi.collection.scheduled.summary-max-bills` | `500` | 정기 1회 상한 |
| `ddoksi.collection.backfill.enabled`<br>`…summary-backfill.enabled` | `false` | 기동 시 전량 |

### 분석 — 켜면 돈이 나간다

| 키 | 기본값 | 비고 |
|---|---|---|
| `ddoksi.analysis.api-key` | `${ANTHROPIC_API_KEY}` | 비어도 기동은 성공 |
| `ddoksi.analysis.model` | `claude-opus-5` | |
| `ddoksi.analysis.prompt-version` | `v1` | 생성할 버전 |
| `ddoksi.analysis.active-prompt-version` | `v1` | 조회가 읽을 버전 — **나눠 둔 것이 전환 통제 장치** |
| `ddoksi.analysis.scheduled.enabled` | `false` | 04:30, 1회 200건 |
| `ddoksi.analysis.backfill.max-bills` | `100` | 동기. 상한을 빼면 전량이 호출된다 |
| `ddoksi.analysis.batch-backfill.max-bills` | `100` | 배치. `0` 이면 **수거만** |
| `ddoksi.analysis.batch.chunk-size` | `2000` | 배치당 건수 (한도는 크기가 먼저 걸린다) |
| `ddoksi.analysis.batch.poll-interval-seconds` | `60` | |
| `ddoksi.analysis.batch.poll-timeout-minutes` | `360` | 넘기면 `SUBMITTED` 로 두고 종료 |

### 기타

| 키 | 기본값 |
|---|---|
| `ddoksi.cors.allowed-origins` | `http://localhost:8081,http://localhost:19006` |
| `spring.jpa.hibernate.ddl-auto` | `validate` |
| `spring.flyway.baseline-on-migrate` | `false` |

---

## 8. 테스트 지도

88개 / 14파일. 무엇을 **고정**하는지로 읽는 게 빠르다.

| 파일 | 고정하는 것 |
|---|---|
| `EntityPersistenceTest` | JSONB 왕복 · enum 변환 · UUID · 복합키 · Auditing. `ddl-auto=validate` 는 컬럼 존재만 보므로 이것들은 실제로 저장해봐야 안다 |
| `BillStatusMapperTest` | 처리결과 → 상태 매핑 |
| `BillControllerTest` | 상태 코드(400/405/415)와 **상태 라벨 문구**. 라벨을 못 박아 둬서 앱과 서버가 갈라지는 걸 잡아낸 적이 있다 |
| `CommitteeNameAliasTest` | 옛 이름으로도 조회되는지 · 특별위원회가 안 묶이는지 · 상태 필터와 함께 써도 유지되는지 |
| `BillAnalysisBatchServiceTest` | **이중 과금 차단**(미수거 배치가 있으면 제출 안 함) · **custom_id 매칭**(역순으로 돌려줘도 제 법안에 붙는지) |
| `BillAnalysisPersisterTest` | UNIQUE 제약 아래 재시도 경로 — FAILED 행이 영구히 남지 않는지 |
| `BillTopicTest` / `BillStatusDescriptionTest` | 어휘 강제 · 상태 설명문 |
| `BillCollectionServiceTest`<br>`BillSummaryCollectionServiceTest`<br>`AssemblyApiClientTest` | 수집 경로. **국회 API 를 실제로 호출한다** |
| `support/DatabaseCleaner` | 테스트 간 정리. **JDBC URL 에 `ddoksi_test` 가 없으면 실행을 거부**한다 — 개발 DB 의 19,447건을 지우는 사고를 막는 장치 |
| `support/BillFixtures` | 픽스처. 의안번호가 **실제 공개 데이터**인 이유는 제안이유 테스트가 그 번호로 라이브 API 를 호출하기 때문이다 — 지어낸 번호면 전부 `INFO-200` 이 되어 저장 경로를 한 줄도 안 지난다 |

**실행 조건:** 로컬 PostgreSQL + 국회 API 키.

```bash
set -a && . ./.env && set +a
./gradlew test
```

---

## 9. app — 모바일

Expo SDK 57 · React Native 0.86 · Expo Router.

```
app/
├── app.json            scheme: ddoksi · web.output: static · typedRoutes
└── src/
    ├── app/            라우트 — 파일 경로가 곧 URL
    │   ├── _layout.tsx     Stack + 공통 헤더
    │   ├── index.tsx       /
    │   └── bills/[id].tsx  /bills/1107
    ├── screens/        HomeScreen · BillDetailScreen
    ├── components/     BillCard · FilterTabs · StatusBadge
    ├── api/bills.ts    HTTP 계층 — 화면은 여기만 부른다
    ├── config/api.ts   API 주소 (EXPO_PUBLIC_API_BASE_URL)
    ├── types/bill.ts   서버 응답 스펙과 1:1
    └── theme.ts        색 · 간격 · 상태별 배지 색
```

- **라우트 뿌리가 `src/app` 인 이유**: Expo 프로젝트 폴더 자체가 `app` 이라 기본값이면
  `app/app` 이 된다. SDK 57 은 `src` 디렉터리가 있으면 `src/app` 을 자동 인식한다.
- **라우트 파일과 화면 구현을 나눴다.** 라우트는 "어느 경로에 무엇이 오는가"만 정하고,
  화면은 라우팅을 몰라도 되게 했다.
- **상세 화면은 제안이유 원문을 접고 AI 요약을 기본 노출한다.** 원문은 중앙값 499자, 최대
  1만자가 넘는 법률 문체라 펼쳐 두면 읽을 사람이 없다. 다만 지우지 않는다 — 요약이 틀렸을 때
  기댈 곳이 없으면 검증 불가능한 말만 하는 서비스가 된다.
- 분석 항목이 비면 항목째 건너뛰고, 분석이 아예 없으면 안내 문구를 띄운다. **셋 중 무엇이
  없어도 화면은 그려진다.**

> `app/AGENTS.md` 에 "Expo 는 바뀌었으니 SDK 57 문서를 읽고 코드를 쓰라"고 적혀 있다.
> 따를 값어치가 있다 — 위의 `src/app` 동작은 추측이 아니라 그 문서에서 확인한 것이다.

### 실기기 실행

```bash
cd app
EXPO_PUBLIC_API_BASE_URL=http://<맥의 LAN IP>:8080 npx expo start
```

- 맥에 인터페이스가 여럿이면(유선 + Wi-Fi) **기본 경로로 나가는 쪽의 IP** 를 써야 한다.
  아니면 응답이 다른 인터페이스로 나가 폰에서 안 닿는다.
- iOS 는 **로컬 네트워크 권한**이 필요하다. 거부돼 있으면 Expo Go 가 "오프라인"이라고만 한다.
- Expo Go 와 CLI 가 **같은 계정으로 둘 다 로그인**돼 있어야 한다.
- 폰 브라우저로 열면 CORS 에 막힌다(Expo Go 는 무관) — 필요하면 기동 시
  `--ddoksi.cors.allowed-origins` 에 해당 출처를 추가한다.

---

## 10. 아직 없는 것

### `subscription/` — 엔티티 4개

`Subscriber` · `SubscriberStatus` · `SubscriptionCommittee` · `SubscriptionCommitteeId`

필요한 것: 구독 신청 엔드포인트, 더블 옵트인 인증 메일, 수신거부 처리.

### `letter/` — 엔티티 4개

`LetterIssue` · `LetterIssueStatus` · `LetterDelivery` · `DeliveryStatus`

필요한 것: 편성 로직(이번 달 어떤 법안을 담을지), HTML 템플릿, AWS SES 연동, 발송 배치.

> **편성 로직은 지금도 설계·구현할 수 있다.** 분석 데이터가 120건뿐이라 전량 발송은 못 하지만
> 로직 자체는 돈이 들지 않고, 만들어 보면 "출력 항목이 레터에 충분한가"가 드러난다.
> 그 판단을 전량 실행 **전에** 하는 것과 후에 하는 것의 차이는 2만원과 27만원이다.
