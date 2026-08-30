-- =============================================================================
-- V1. 법안 수집/저장 스키마
--
-- 설계 원칙
--  1) 원문과 가공 결과를 분리한다. 파싱 로직에 버그가 있어도 원문이 남아 있으면
--     재수집 없이 재처리로 복구된다.
--  2) 상태 "변경 이력"을 별도 테이블에 쌓는다. 현재 상태만 덮어쓰면
--     "이번 달에 무엇이 바뀌었는가"를 답할 수 없고, 그것이 이 서비스의 핵심이다.
--  3) 국회 API 응답 스펙이 아직 검증되지 않았으므로, 확신 있는 필드만 컬럼으로 뽑고
--     나머지는 JSONB 로 흡수한다.
--
-- 모든 시각 컬럼은 timestamptz 를 쓴다. timestamp(타임존 없음)를 쓰면
-- 서버/컨테이너의 TZ 설정이 바뀔 때(예: AWS 는 기본 UTC) 기존 데이터의 의미가 어긋난다.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- collection_run : 배치 실행 이력
--
-- "레터에 법안이 왜 하나도 없었지?" 를 나중에 추적할 수 있는 유일한 단서다.
-- cursor_value 는 증분 수집 위치를 기억해서 매번 전체를 긁지 않게 한다.
-- -----------------------------------------------------------------------------
CREATE TABLE collection_run (
    id            BIGSERIAL    PRIMARY KEY,
    job_name      VARCHAR(100) NOT NULL,
    -- RUNNING / SUCCESS / FAILED
    status        VARCHAR(20)  NOT NULL,
    started_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    -- 처리 대상 건수와 그중 성공/실패 건수. 개별 건 실패는 배치를 중단시키지 않고 여기에 집계된다.
    target_count  INTEGER      NOT NULL DEFAULT 0,
    success_count INTEGER      NOT NULL DEFAULT 0,
    fail_count    INTEGER      NOT NULL DEFAULT 0,
    -- 다음 실행이 이어받을 수집 위치 (예: 마지막으로 처리한 제안일)
    cursor_value  VARCHAR(100),
    error_message TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_collection_run_job_started ON collection_run (job_name, started_at DESC);


-- -----------------------------------------------------------------------------
-- bill_raw : 국회 API 응답 원문
--
-- 파싱하기 전 상태 그대로 보관한다. 외부 API 스펙은 예고 없이 바뀌고,
-- 그때 "우리가 실제로 무엇을 받았는지" 를 볼 수 있어야 원인을 찾을 수 있다.
-- -----------------------------------------------------------------------------
CREATE TABLE bill_raw (
    id                BIGSERIAL   PRIMARY KEY,
    collection_run_id BIGINT      REFERENCES collection_run (id),
    -- 어느 API 에서 온 응답인지 (예: nzmimeepazxkubdpn, ALLBILL)
    source_api        VARCHAR(50) NOT NULL,
    -- 응답에서 뽑아낸 국회 의안 ID. 파싱 실패 시 NULL 일 수 있으므로 제약을 걸지 않는다.
    external_bill_id  VARCHAR(50),
    -- 응답 본문 원문
    payload           JSONB       NOT NULL,
    -- 직전 수집분과 내용이 같은지 판정해 불필요한 재파싱을 건너뛰기 위한 해시
    payload_hash      VARCHAR(64) NOT NULL,
    collected_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_bill_raw_external_id ON bill_raw (external_bill_id);
CREATE INDEX idx_bill_raw_collected_at ON bill_raw (collected_at DESC);


-- -----------------------------------------------------------------------------
-- bill : 정규화된 법안 (현재 상태)
--
-- PK 를 대리키(id)로 두고 국회 식별자(external_bill_id)에는 UNIQUE 만 건다.
-- 외부 시스템이 주는 식별자를 PK 로 삼으면 그쪽 사정에 우리 FK 전체가 끌려다닌다.
-- -----------------------------------------------------------------------------
CREATE TABLE bill (
    id               BIGSERIAL    PRIMARY KEY,
    -- 국회 BILL_ID. 수집 시 중복 판정의 기준이 된다.
    external_bill_id VARCHAR(50)  NOT NULL,
    -- 의안번호 (BILL_NO)
    bill_no          VARCHAR(20),
    -- 국회 대수 (예: 22)
    assembly_age     SMALLINT,
    title            TEXT         NOT NULL,

    -- 우리 서비스가 쓰는 정규화된 상태.
    -- 매핑 규칙이 아직 검증되지 않았으므로, 분류하지 못한 값은 UNKNOWN 으로 받는다.
    status           VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
    -- 국회가 내려준 처리결과 문자열 원문. 위 status 매핑이 틀렸다고 판명돼도
    -- 이 값이 있으면 재수집 없이 DB 안에서 다시 분류할 수 있다.
    proc_result_raw  VARCHAR(200),

    -- 발의자 정보. PROPOSER 는 "홍길동의원 등 12인" 같은 표시용 요약 문자열이므로
    -- 정확한 분류가 필요하면 대표발의자(RST_PROPOSER)를 따로 봐야 한다.
    proposer_kind    VARCHAR(30),
    proposer_summary TEXT,
    rst_proposer     TEXT,

    committee_name   VARCHAR(100),
    committee_id     VARCHAR(50),

    proposed_date    DATE,
    proc_date        DATE,
    -- 국회 원문 페이지 링크. AI 요약이 틀렸을 때 사용자가 직접 확인할 경로다.
    detail_url       TEXT,

    -- 아직 쓰임새를 모르는 나머지 응답 필드를 그대로 담아둔다.
    -- 인증키를 받아 실제 응답을 확인한 뒤, 필요한 것만 컬럼으로 승격시킨다.
    extra            JSONB        NOT NULL DEFAULT '{}'::jsonb,

    -- 최초로 수집된 시각 / 마지막으로 응답에 등장한 시각
    first_seen_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_bill_external_id UNIQUE (external_bill_id),
    CONSTRAINT ck_bill_status CHECK (status IN ('PENDING', 'PASSED', 'DISCARDED', 'UNKNOWN'))
);

-- 메인 피드: 상태별로 최신 제안일 순 조회
CREATE INDEX idx_bill_status_proposed ON bill (status, proposed_date DESC);
-- 상임위 필터
CREATE INDEX idx_bill_committee ON bill (committee_name);
-- 전체 피드 정렬
CREATE INDEX idx_bill_proposed_date ON bill (proposed_date DESC);


-- -----------------------------------------------------------------------------
-- bill_status_history : 상태 변경 이력
--
-- 이 서비스의 핵심 테이블이다.
-- 월간 레터의 "이번 달 통과된 법안" 과 앱의 관심 법안 푸시 알림이 모두 여기서 나온다.
--
-- 국회 API 가 변경 이력을 직접 주지 않으면, 배치가 스냅샷을 비교해 여기에 직접 쌓는다.
-- 멱등성 유지를 위해 "실제로 값이 바뀐 경우에만" INSERT 한다.
-- 배치를 두 번 돌렸다고 같은 변경이 두 줄 생기면 안 된다.
-- -----------------------------------------------------------------------------
CREATE TABLE bill_status_history (
    id                   BIGSERIAL   PRIMARY KEY,
    bill_id              BIGINT      NOT NULL REFERENCES bill (id) ON DELETE CASCADE,
    -- 최초 수집 시점에는 이전 상태가 없으므로 NULL 을 허용한다.
    from_status          VARCHAR(20),
    to_status            VARCHAR(20) NOT NULL,
    -- 상태와 마찬가지로 국회 원문 문자열도 남겨 재분류 가능성을 확보한다.
    from_proc_result_raw VARCHAR(200),
    to_proc_result_raw   VARCHAR(200),
    -- 변경을 "감지한" 시각. 국회에서 실제로 바뀐 시각과는 다를 수 있다.
    changed_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    detected_by_run_id   BIGINT      REFERENCES collection_run (id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 월간 집계: "이번 달에 상태가 바뀐 법안"
CREATE INDEX idx_bill_status_history_changed ON bill_status_history (changed_at DESC);
-- 법안 하나의 이력 조회
CREATE INDEX idx_bill_status_history_bill ON bill_status_history (bill_id, changed_at DESC);


-- -----------------------------------------------------------------------------
-- bill_analysis : Claude API 분석 결과 캐시
--
-- AI 호출은 곧 비용이므로 재생성하지 않는 것이 기본이다.
-- 다만 "이미 있으면 무조건 건너뛰기" 로는 법안 원문이 수정된 경우를 처리할 수 없어서
-- 입력 원문의 해시(source_hash)를 함께 저장해 바뀐 것만 골라낸다.
--
-- prompt_version 을 함께 저장하는 이유: 프롬프트를 개선했을 때 구버전으로 생성된 것만
-- 재생성할 수 있어야 한다. 버전 정보가 없으면 전량 재생성밖에 방법이 없고 그것은 비용이다.
-- -----------------------------------------------------------------------------
CREATE TABLE bill_analysis (
    id             BIGSERIAL   PRIMARY KEY,
    bill_id        BIGINT      NOT NULL REFERENCES bill (id) ON DELETE CASCADE,
    prompt_version VARCHAR(20) NOT NULL,
    -- 생성에 사용한 모델 식별자
    model          VARCHAR(50) NOT NULL,
    -- 분석 입력(법안 원문 + 제안이유)의 해시. 원문이 바뀌었는지 판단하는 기준.
    source_hash    VARCHAR(64) NOT NULL,

    -- 생성 결과. 근거가 없으면 억지로 채우지 않고 비워두므로 NULL 을 허용한다.
    summary        TEXT,
    example        TEXT,
    background     TEXT,
    -- 찬성/반대 논거는 여러 개가 나오므로 배열로 담는다. 한쪽만 채우지 않는다.
    pros           JSONB       NOT NULL DEFAULT '[]'::jsonb,
    cons           JSONB       NOT NULL DEFAULT '[]'::jsonb,

    -- SUCCESS / FAILED. 분석 실패가 법안 기본 정보 노출을 막아서는 안 되므로
    -- 실패도 기록으로 남기고 서비스는 계속 동작한다.
    status         VARCHAR(20) NOT NULL,
    error_message  TEXT,

    generated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 같은 법안 + 같은 프롬프트 버전으로 두 번 생성하지 않는다 (중복 과금 방지)
    CONSTRAINT uq_bill_analysis UNIQUE (bill_id, prompt_version),
    CONSTRAINT ck_bill_analysis_status CHECK (status IN ('SUCCESS', 'FAILED'))
);

CREATE INDEX idx_bill_analysis_bill ON bill_analysis (bill_id);
