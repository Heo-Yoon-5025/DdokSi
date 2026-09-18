# ddoksi

A subscription service that collects bill (법안) information from the Korean National
Assembly, enriches it with AI-generated summaries and analysis, and delivers it through
two channels: a **monthly email newsletter** and a **mobile app**.

---

## Tech Stack

### Backend

| Layer | Choice | Version |
|---|---|---|
| Language | Java | 21 (toolchain) |
| Runtime (local) | Homebrew OpenJDK | 21.0.10 |
| Framework | Spring Boot | 4.1.1 |
| Build tool | Gradle (wrapper) | 9.7.1 |
| Web layer | Spring MVC (`spring-boot-starter-webmvc`) | managed by Boot |
| Persistence | Spring Data JPA / Hibernate | managed by Boot |
| Validation | Jakarta Bean Validation (`spring-boot-starter-validation`) | managed by Boot |
| Boilerplate reduction | Lombok | managed by Boot |
| Dependency management | `io.spring.dependency-management` plugin | 1.1.7 |

> **Spring Boot 4 note:** starter artifact names differ from Boot 2/3. This project uses
> `spring-boot-starter-webmvc` (not `spring-boot-starter-web`), and test starters are split
> per-slice (`spring-boot-starter-webmvc-test`, `spring-boot-starter-data-jpa-test`) instead
> of the single `spring-boot-starter-test`. Most online examples target Boot 3 — verify
> artifact names against Boot 4 before adding a dependency.

### Package Structure

Packages are organized **by domain**, not by layer. A layer-first split
(`controller` / `service` / `repository`) would scatter one change across four distant
folders; this project has clear domain boundaries, so domain-first keeps related code together.

```
com.ddoksi.ddoksi
├── config/          Spring configuration (JPA auditing, ...)
├── common/entity/   BaseCreatedAtEntity, BaseTimeEntity
├── collection/      Batch runs and raw API payloads
├── bill/            Bills, status history, AI analysis
├── subscription/    Subscribers and their committee filters
└── letter/          Newsletter issues and per-recipient delivery
```

Inside each domain, sub-packages are added as needed (`entity`, then `repository`,
`service`, `controller`). Only `entity` exists so far.

### Entity Conventions

- Entities never use Lombok `@Data` or `@Setter`. `@Data` drags in setters and
  `@EqualsAndHashCode`, both of which fight JPA's persistence context.
- Constructors are private and reached through `@Builder`; the no-arg constructor is
  `PROTECTED` because JPA requires one but application code should not call it.
- State changes go through named methods that express intent (`verify()`, `markSent()`,
  `complete()`), not setters.
- All instants are `java.time.Instant`, matching the `timestamptz` columns. `LocalDateTime`
  would drop the zone and change meaning when the server TZ changes.
- JSONB columns use `@JdbcTypeCode(SqlTypes.JSON)`. Raw API payloads are kept as `String`
  so the original text survives untouched; structured JSON uses `Map` / `List`.

### Database

| Layer | Choice | Version |
|---|---|---|
| RDBMS | PostgreSQL | 17.11 (local, Homebrew `postgresql@17`) |
| JDBC driver | `org.postgresql:postgresql` (runtime scope) | 42.7.13 |
| Schema migration | Flyway (`spring-boot-starter-flyway` + `flyway-database-postgresql`) | 12.4.0 |

Flyway owns the schema; Hibernate is set to `ddl-auto=validate` and never creates or alters
tables. Migrations live in `src/main/resources/db/migration` as `V<n>__<description>.sql`
and are applied on application startup. **Never edit a migration that has already been
applied** — Flyway checksums them and refuses to start. Add a new versioned file instead.

Local database: `ddoksi` / role `ddoksi`. Connection settings come from `DB_URL`,
`DB_USERNAME`, `DB_PASSWORD` environment variables, with local-only defaults in
`application.properties`.

**Why PostgreSQL:**
- Free, with no cost concerns for a personal project
- Supported by the AWS RDS free tier — the planned move from home server to cloud keeps the
  same database engine and only changes where it runs
- Native JSONB support, which suits semi-structured data such as raw bill text and AI analysis output

Oracle is explicitly **not** used here, despite being the stack used at work. Migrating
PostgreSQL → Oracle later would mean rewriting SQL and taking on licensing cost.

### Mobile

| Layer | Choice |
|---|---|
| Framework | React Native |
| Toolchain | Expo |
| Test target | Physical device via Expo Go (Xcode / Android Studio only if needed later) |

### AI

| Layer | Choice |
|---|---|
| Provider | Anthropic Claude API |
| Purpose | Generate per-bill summaries, plain-language examples, pro/con arguments, and background context |

### Infrastructure

| Layer | Choice |
|---|---|
| Local DB | PostgreSQL installed natively via Homebrew (`brew services start postgresql@17`) — no container runtime is installed on this machine |
| IDE | IntelliJ IDEA |
| JDK management | Homebrew |
| Phase 1 deployment | Home server (Ubuntu Server) behind an nginx reverse proxy |
| Phase 2 deployment | AWS free tier (RDS for PostgreSQL, EC2 or Elastic Beanstalk for the app) |

### External Data Source

| Item | Detail |
|---|---|
| Source | 열린국회정보 Open API (`open.assembly.go.kr`) |
| Base URL | `https://open.assembly.go.kr/portal/openapi/<API name>` (verified) |
| Auth | `KEY` query parameter. Free, issued from My Page after login |
| Format | `Type=json` or `xml`; paged with `pIndex` / `pSize` |

**Verified by direct calls (2026-08-30):**

- **The WAF blocks some `User-Agent` values with `HTTP 400 Bad Request.`** (12 bytes of plain
  text) before the API is ever reached. Measured: `curl/*` and an empty/missing UA are blocked;
  `Java-http-client/*`, `python-requests/*`, a custom UA, and browser UAs all pass. So a browser
  UA is *not* required — do not impersonate one. This project sends
  `ddoksi-collector/1.0 (+https://github.com/Heo-Yoon-5025/DdokSi)` so the API operator can
  identify and contact us. A collector that gets this wrong looks like a total API outage.
  Note this arrives as an HTTP 400, so it must be treated as **non-retryable** — retrying a
  blocked UA never succeeds.
- `KEY=sample` does **not** work, despite the developer guide implying a sample default.
  It returns `{"RESULT":{"CODE":"ERROR-290","MESSAGE":"인증키가 유효하지 않습니다..."}}`.
- Errors come back as **HTTP 200** with a `RESULT.CODE` / `RESULT.MESSAGE` body. Status codes
  alone cannot be used to detect failure — the response body must be inspected.

### Verified API shapes (probed 2026-08-30 with a real key)

**`nzmimeepazxkubdpn` — 국회의원 발의법률안.** 19,058 rows for the 22nd Assembly (`AGE=22`),
sorted newest-first. Member-proposed bills only. 24 fields — the richest source:

| Field | Meaning |
|---|---|
| `BILL_ID` | Assembly bill id, e.g. `PRC_T2U6S0R8...` — our `external_bill_id` |
| `BILL_NO` / `BILL_NAME` | Bill number / title |
| `AGE` | Assembly term (22) |
| `PROPOSE_DT` | Proposal date |
| `PROC_RESULT` / `PROC_DT` | Final plenary result / date. **null while pending** |
| `COMMITTEE` / `COMMITTEE_ID` / `COMMITTEE_DT` | Standing committee |
| `CMT_PROC_RESULT_CD` / `CMT_PROC_DT` / `CMT_PRESENT_DT` | Committee-stage result |
| `LAW_PROC_RESULT_CD` / `LAW_PROC_DT` / `LAW_PRESENT_DT` / `LAW_SUBMIT_DT` | Legislation & Judiciary Committee stage |
| `PROPOSER` | Display string, e.g. "황운하의원 등 14인" — **not for parsing** |
| `RST_PROPOSER` / `PUBL_PROPOSER` | Lead / co-proposer names |
| `RST_MONA_CD` / `PUBL_MONA_CD` | Member codes (stable ids, better than names) |
| `DETAIL_LINK` / `MEMBER_LIST` | Assembly page / co-proposer popup |

**`BILLRCP` — 의안접수목록.** 134,696 rows across all terms. Covers every proposer type
(의원 93.9%, 위원장 4.7%, 정부 1.1%, 의장 0.3%) and every bill kind (법률안 97%, 결의안, 예산안 …),
but only 9 fields and **no committee information**. Field names differ from the API above:
`BILL_NM` (not `BILL_NAME`), `PROC_RSLT` (not `PROC_RESULT`), `PPSL_DT`, `PPSR_KIND`,
`ERACO`, `BILL_KIND`, `LINK_URL`.

**`BPMBILLSUMMARY` — 법률안 제안이유 및 주요내용.** The only source of bill *text*. Probed
2026-09-13 with a real key.

- **`BILL_NO` is mandatory.** Omitting it returns `ERROR-300 필수 값이 누락되어 있습니다`, and so
  does any attempt to page or filter by `AGE`. There is no list endpoint: one HTTP call per bill.
  Budget accordingly — 19,447 bills means 19,447 calls.
- Five fields only: `BILL_NO`, `BILL_NAME`, `BILL_ID`, `SUMMARY`, `AGE`. Same `head` / `row`
  envelope as every other API here, so `AssemblyApiClient.fetch()` handles it unchanged.
- `SUMMARY` length, measured over all 19,406 texts collected: median 499, mean 633,
  **max 10,349** characters. Stored as `TEXT`; no length cap is safe to assume — the earlier
  40-bill sample put the maximum at 3,149, and the real one is over three times that. The long
  tail is thin (58 bills over 3,000 characters, 0.3%) but it exists, so anything that slices this
  text for a prompt has to handle it.
- **A successful response can still carry no text.** `INFO-000` with `SUMMARY: null` happens for
  real bills (e.g. `BILL_NO` 2208675). Treat "fetched but empty" as a distinct outcome from
  "not fetched yet", or the batch re-calls the same bills forever.
- An unknown `BILL_NO` returns `INFO-200`, which is absence, not an error.
- **One 의안번호 can return more than one row.** `BILL_NO` 2221245 comes back with
  `list_total_count: 2`: same `BILL_NAME` and `AGE`, different `BILL_ID`, and the first row's
  `SUMMARY` is empty while the second holds the real text. Taking `rows().get(0)` throws away
  text that was there. Match on `BILL_ID` against our `external_bill_id` instead — see
  `BillSummaryCollectionService.selectRow`. Measured at 1 in 100 bills, so roughly 200 across
  the full 19,447.
- The API page lists 요청제한횟수 as 제한없음.

> The response JSON is valid: newlines inside `SUMMARY` arrive correctly escaped as `\n`.
> An early probe seemed to show raw control characters, but that was `echo "$R" > file` in zsh —
> zsh's builtin `echo` expands backslash escapes without `-e` and corrupted the saved file.
> Use `printf '%s'` when capturing API responses in a shell. No Jackson leniency flag is needed.

> Passing `ERACO` as a filter returns `INFO-200 해당하는 데이터가 없습니다` for every value tried
> (`22`, `제22대`). Omitting it works. Filter by term in our own code, not via that parameter.

**Response envelope.** `{"<API name>": [{"head": [{"list_total_count": N}, {"RESULT": {...}}]}, {"row": [...]}]}`.
Result codes seen: `INFO-000` (success), `INFO-200` (no data), `ERROR-290` (invalid key).

### Actual `PROC_RESULT` values (complete set observed)

| Value | Count in sample | Meaning |
|---|---|---|
| *(null)* | largest | Still pending — not yet processed |
| `임기만료폐기` | 366 | Expired when the Assembly term ended |
| `대안반영폐기` | 217 | Discarded, but its content was folded into a committee alternative |
| `원안가결` | 63 | Passed as originally written |
| `수정가결` | 36 | Passed with amendments |
| `철회` | 11 | Withdrawn by the proposer |
| `폐기` | 5 | Discarded outright |
| `수정안반영폐기` | 3 | Like 대안반영폐기, folded into an amendment |
| `부결` | 1 | Voted down |

Committee-stage results (`CMT_PROC_RESULT_CD`) add `심사미료` and `회송`.

### Status mapping (decided, implemented in `BillStatusMapper`)

| `PROC_RESULT` | `BillStatus` |
|---|---|
| *(null / blank)* | `PENDING` |
| `원안가결`, `수정가결` | `PASSED` |
| `대안반영폐기`, `수정안반영폐기` | `MERGED` |
| `임기만료폐기`, `철회`, `폐기`, `부결` | `DISCARDED` |
| anything else | `UNKNOWN` |

**`MERGED` exists as its own status on purpose.** 대안반영폐기 means the bill was formally
discarded while its content was folded into a committee alternative that goes on to become
law. Calling that `PASSED` is factually wrong — this bill did not pass. Calling it
`DISCARDED` implies the effort came to nothing, which is also wrong. It is not a rare edge
case either: across 7,115 real rows it accounts for **19.0%**, nearly five times the share of
outright `PASSED` (3.9%).

Verified against all 7,115 rows fetched from the live API: **zero fall through to `UNKNOWN`**.
`BillStatusMapper` is the single place this mapping lives — do not classify status anywhere else.

Because unseen values can still appear (국회 may introduce new strings, and committee-stage
values such as `심사미료` / `회송` are not in the table), unmapped strings fall through to
`BillStatus.UNKNOWN` rather than break collection. `BillStatusMapper.isUnmapped()` lets the
collector log a warning when that happens.

`scripts/probe-assembly-api.sh` calls the candidate APIs and prints the field names of the
first row, plus every distinct `PROC_*` value found. Run it as soon as a key exists:

```
ASSEMBLY_API_KEY=<key> ./scripts/probe-assembly-api.sh
```

Its output is what turns `bill.status` mapping from a guess into a rule.

---

## Planned — Not Yet Added

These are part of the intended architecture but are **not** in the build or the codebase yet.
Do not assume they are available.

| Concern | Candidate | Status |
|---|---|---|
| Batch / scheduled collection | Spring Batch, or `@Scheduled` + services | Undecided |
| Containerization | Docker (OrbStack / Colima) for deployment | Deferred — nothing installed locally |
| Email delivery | AWS SES | Planned |
| HTTP client for the Open API | `RestClient` | Undecided |
| Operational endpoints | Spring Boot Actuator | Not added |
| Authentication / authorization | — | Undecided |
| Mobile state management & navigation | — | Undecided |

---

## Current Project State

As of **2026-09-19**:

| Item | State |
|---|---|
| Git | 16 commits on `main`, pushed to `Heo-Yoon-5025/DdokSi`. Work goes through PRs (#1–#6); merged branches are deleted |
| Backend | Flyway migrations, JPA entities, two collection batches, and a **read-only REST API** (`/api/bills`) |
| Database schema | **10 tables created; entity mappings verified by tests** (see below) |
| PostgreSQL | Running locally via `brew services` (`postgresql@17`) |
| Mobile app | Expo project in `app/`; main screen runs on the **real API** — list, status filter, keyword search, infinite scroll. **No detail screen and no navigation library yet** |
| National Assembly Open API | Key issued (in gitignored `.env`). **Integrated — full 22nd-Assembly backfill collected (19,447 bills)** |
| Bill text (제안이유) | **Full backfill done** (2026-09-19 01:21). Every one of the 19,447 bills has a `bill_summary` row; 19,406 carry text and 41 are genuinely empty |
| AI analysis | Not implemented. **No longer blocked** — its input (12.3M characters of bill text) is now collected. Cost has to be estimated before any full run |
| Tests | 56, all passing, none skipped (a local PostgreSQL and an API key are required) |

### REST API

`GET /api/bills` — paged list. Optional `status`, `committee`, `keyword`; `page`, `size` (max 100).
`GET /api/bills/{id}` — detail with status history. `GET /api/bills/committees` — filter options.
No authentication: this is public National Assembly data. Add auth only on subscription routes.

Three deliberate choices, each fixing a problem that actually occurred:

- **Filters use `Specification`, not one query with `(:param is null or col = :param)`.**
  That pattern broke outright — a null bind made PostgreSQL infer `bytea` and fail with
  `function lower(bytea) does not exist`. It also defeats indexes, since the planner cannot know
  which predicates apply. Specifications emit only the conditions actually requested.
- **`ApiExceptionHandler` extends `ResponseEntityExceptionHandler`.** A bare catch-all on
  `Exception` overwrote Spring's own correct mappings — `status=NOPE` returned 500 instead of 400.
  Extending it keeps the standard mappings (400 type mismatch, 405, 415) and adds ours on top.
  `BillControllerTest` pins the status codes so this cannot regress.
- **Responses use our own `PageResponse`, not Spring's `Page`.** `Page`'s JSON shape has changed
  across Spring versions, which would make an internal class the app's contract.

Status labels (`논의중`, `통과`, `대안반영`, `폐기`) come from `BillStatus.label()` and ship in the
response, so the app and the newsletter cannot drift into different wording for the same state.

CORS is limited to configured origins (`ddoksi.cors.allowed-origins`) — never a wildcard. Expo web
runs on 8081 and the API on 8080, so without it the browser blocks every request.

### Collection batch

`BillCollectionService` walks the member-bill API page by page. `BillPagePersister` commits
**one transaction per page** — wrapping the whole 195-page walk in one transaction would hold a
connection throughout and roll back everything on a late failure.

Idempotency is the batch's core property: a status history row is written only when the value
actually changed (`Bill.hasStatusChanged`), and raw payloads are stored only for new or changed
bills — otherwise every run would append ~19k identical rows to `bill_raw`.

Failure handling: per-row defects are logged and skipped; retryable failures (timeout, 5xx) retry
the same page up to 3 times with growing backoff; non-retryable ones (bad key, blocked UA — which
arrives as an HTTP 400) abort immediately.

Scheduling uses `@Scheduled`, not Spring Batch — the work is "page walk → upsert → status diff",
and Job/Step/Chunk plus its metadata tables buy little here; run history already lives in
`collection_run`. Wrap the service in a Step later if that changes.

Both entry points are **disabled by default** so a test run or local startup never pulls 19k rows:
- `ddoksi.collection.scheduled.enabled=true` — nightly cron
- `ddoksi.collection.backfill.enabled=true` — one-off full backfill at startup

**Measured on the full dataset (2026-09-12, 19,447 bills):** `PENDING` 74.9%, `MERGED` 20.9%,
`PASSED` 3.4%, `DISCARDED` 0.9%, and **`UNKNOWN` 0** — the status mapping has no gaps against
real data. `MERGED` outnumbers `PASSED` six to one, which is why it is its own status.
`임기만료폐기` and `부결` do not appear yet (the 22nd Assembly is still sitting) but are mapped.

### Summary collection batch (제안이유)

`BillSummaryCollectionService` fills `bill_summary`. It is a **separate batch** from the list
collector because the two have different shapes: the list API returns 100 bills per call, while
`BPMBILLSUMMARY` requires `BILL_NO` and returns exactly one. Folding it into the nightly list run
would turn a two-minute job into a multi-hour one.

**Resumability is the core property here**, the way idempotency is for the list collector. Bills
that already have a `bill_summary` row are excluded from the query, so an interrupted run simply
continues where it stopped. `findBillsWithoutSummary` takes an `afterId` cursor rather than always
reading "the first N without a summary" — otherwise one bill that fails to save would be retried
forever and the batch would never advance.

A row is written even when `SUMMARY` comes back null, because that is what distinguishes
"fetched, genuinely empty" from "not fetched yet". `INFO-200` (no such bill number) writes no row,
so the bill is retried on a later run — the Assembly may publish the text afterwards.

`BillSummaryPersister` commits one transaction per 100 bills and checks the response's `BILL_ID`
against `bill.external_bill_id`, skipping on mismatch. That guard matters because this API is keyed
by 의안번호 rather than the bill id we key on everywhere else.

`selectRow` picks the row whose `BILL_ID` matches ours **before** that guard runs, because the API
sometimes returns several rows for one 의안번호 (see above). When no row matches it hands over the
first one, so a genuinely wrong response still trips the mismatch guard — the fix must not weaken
it. This was found by running 100 bills before the full backfill; the batch reported `건너뜀=1`
and the log line was the only sign.

**Measured on the full run (2026-09-19, runId 48):** 19,348 bills fetched in 2h23m at 0.44s per
bill, `신규=19,348 / 빈 본문=41 / 데이터없음=0 / 건너뜀=0`, and **not one warning or error in the
whole log**. `데이터없음=0` means every bill our list collector knows about also has a 제안이유
record — the two APIs agree on the population. `건너뜀=0` is the multi-row fix holding across
19,348 calls; before it, the same population produced one skip per hundred bills.

Entry points, both disabled by default for the same reason as the list collector:
- `ddoksi.collection.summary-backfill.enabled=true` — one-off fill at startup, optionally capped
  with `ddoksi.collection.summary-backfill.max-bills=<n>`
- the nightly cron at `ddoksi.collection.scheduled.summary-cron` (04:00, after the 03:30 list run),
  capped at `summary-max-bills` per run so a backlog never stretches the nightly batch

### Schema (V1–V4)

| Table | Purpose |
|---|---|
| `collection_run` | Batch execution history + incremental collection cursor |
| `bill_raw` | Raw API responses (JSONB), kept so parsing bugs are recoverable by re-processing |
| `bill` | Normalized bill with its **current** status |
| `bill_summary` | 제안이유 및 주요내용 — the bill's text, one row per bill (V4) |
| `bill_status_history` | Status change history — the basis for the newsletter and push alerts |
| `bill_analysis` | Cached Claude output, keyed by `(bill_id, prompt_version)` |
| `subscriber` | Newsletter subscribers, with double opt-in status and an unsubscribe token |
| `subscription_committee` | Per-subscriber committee filter |
| `letter_issue` | Monthly newsletter issue |
| `letter_delivery` | Per-recipient send result, unique on `(issue, subscriber)` to prevent double sends |

`bill.status` is our own normalized value (`PENDING` / `PASSED` / `MERGED` / `DISCARDED` /
`UNKNOWN`), while `bill.proc_result_raw` keeps the National Assembly's original string. The
mapping has been verified against all 19,447 collected bills with zero `UNKNOWN`; unmappable
values still fall through to `UNKNOWN`, and the raw string allows re-classification later
without re-collecting. `MERGED` was added in V3 after the real data showed it outnumbers
`PASSED` six to one.

### Tests

`EntityPersistenceTest` saves and reloads every entity to verify JSONB round-trips, enum
conversion, UUID generation, the composite key, and auditing — things `ddl-auto=validate`
cannot catch, since it only checks that columns exist.

**Tests run against their own database, `ddoksi_test`, never the development database.** The
collection batches commit by design — per-page and per-chunk commits are the whole point, so
those tests cannot be wrapped in a rolled-back transaction. Earlier they committed into `ddoksi`
and their leftovers mixed with real collected rows, which would have made any later bug
impossible to attribute. `DatabaseCleaner` truncates between tests and **refuses to run unless
the JDBC URL names `ddoksi_test`**, so a missing `@ActiveProfiles("test")` fails loudly instead
of wiping 19,447 collected bills.

One-time setup, then Flyway builds the schema on first run:

```
createdb -O ddoksi ddoksi_test
```

**Tests seed their own fixtures** (`BillFixtures`, data in `src/test/resources/fixtures/bills.psv`)
rather than assuming a backfill has been run. They used to skip silently on an empty database,
which meant a green build proved nothing. The fixture bill numbers are **real** public Assembly
data on purpose: the 제안이유 tests call the live API with them, and invented numbers would return
`INFO-200` for every row and exercise none of the storage path.

**A local PostgreSQL must be running** (`brew services start postgresql@17`). Testcontainers is
still not set up — `docker` is only a CLI here with no daemon, so it would mean installing
OrbStack or Colima first. The fixtures carry over unchanged when that happens.

Treat anything in **Planned** above as a direction, not a decision. Confirm with the user
before introducing one of them into the build.
