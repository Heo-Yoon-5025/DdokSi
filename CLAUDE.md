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

- **A browser `User-Agent` header is required.** Without one the edge returns
  `HTTP 400 Bad Request.` (12 bytes of plain text) before the API is ever reached. With one,
  the API answers normally. This is a WAF rule, not an API error — any collector that omits
  the header will look like a total API outage. Always send a `User-Agent`.
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

As of **2026-08-30**:

| Item | State |
|---|---|
| Git | Repository initialized, **zero commits** |
| Backend | Flyway migrations + **JPA entities for all 9 tables**. No repositories, services, or controllers yet |
| Database schema | **9 tables created; entity mappings verified by tests** (see below) |
| PostgreSQL | Running locally via `brew services` (`postgresql@17`) |
| Mobile app | Expo project in `app/` with a working main screen — **mock data only** |
| National Assembly Open API | Key issued (in gitignored `.env`). **Response shape and all status values verified**; no collector code yet |
| AI analysis | Not implemented |

### Schema (V1, V2)

| Table | Purpose |
|---|---|
| `collection_run` | Batch execution history + incremental collection cursor |
| `bill_raw` | Raw API responses (JSONB), kept so parsing bugs are recoverable by re-processing |
| `bill` | Normalized bill with its **current** status |
| `bill_status_history` | Status change history — the basis for the newsletter and push alerts |
| `bill_analysis` | Cached Claude output, keyed by `(bill_id, prompt_version)` |
| `subscriber` | Newsletter subscribers, with double opt-in status and an unsubscribe token |
| `subscription_committee` | Per-subscriber committee filter |
| `letter_issue` | Monthly newsletter issue |
| `letter_delivery` | Per-recipient send result, unique on `(issue, subscriber)` to prevent double sends |

`bill.status` is our own normalized value (`PENDING` / `PASSED` / `DISCARDED` / `UNKNOWN`),
while `bill.proc_result_raw` keeps the National Assembly's original string. The mapping
between them is **not yet verified against the real API** — unmappable values become
`UNKNOWN`, and the raw string allows re-classification later without re-collecting.

### Tests

`EntityPersistenceTest` saves and reloads every entity to verify JSONB round-trips, enum
conversion, UUID generation, the composite key, and auditing — things `ddl-auto=validate`
cannot catch, since it only checks that columns exist.

**These tests require a local PostgreSQL to be running** (`brew services start postgresql@17`).
There is no Testcontainers setup yet because no container runtime is installed on this machine.

Treat anything in **Planned** above as a direction, not a decision. Confirm with the user
before introducing one of them into the build.
