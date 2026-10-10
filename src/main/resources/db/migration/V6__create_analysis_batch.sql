-- =============================================================================
-- V6. analysis_batch / analysis_batch_item : Claude Batch API 제출 이력
--
-- 배경:
-- 지금까지 분석은 동기 호출 하나뿐이었다. 파일럿 100건 실측(2026-09-20, runId 50)으로
-- 건당 5.62초가 확인됐고, 남은 19,306건을 그대로 돌리면 30시간 + 약 34만원이다.
-- Batch API 는 모든 토큰이 50% 할인이라 같은 작업이 약 17만원으로 내려간다.
--
-- 왜 테이블이 필요한가 — 이것이 동기 경로와 배치 경로의 결정적 차이다:
--   동기 호출은 응답을 받는 순간 저장까지 끝난다. 프로세스가 죽으면 그 호출 한 건만 잃는다.
--   배치는 제출하는 순간 과금이 확정되고 결과는 최대 24시간 뒤에 나온다. 그 사이의
--   batch_id 가 메모리에만 있으면 앱이 재시작되는 순간 이미 지불한 결과를 찾아갈 방법이
--   사라지고, 다음 실행은 같은 법안을 다시 제출한다 — 곧 이중 과금이다.
--   collection_run 이 수집 배치에 하는 역할을 분석 배치에도 해주는 것이 이 테이블이다.
--
-- 왜 item 을 따로 두는가 (batch 행에 JSONB 로 말아 넣지 않고):
--   1. source_hash 는 제출 시점의 입력으로 계산해야 한다. 결과를 수거할 때 다시 계산하면
--      그 사이 법안 제목이나 위원회명이 바뀐 경우(실제로 위원회 개편이 있었다) 보내지도
--      않은 입력의 해시를 저장하게 되고, 다음 실행은 그것을 "최신" 으로 오판한다.
--   2. 응답에는 성공한 custom_id 만 담겨 올 수 있다. 무엇을 보냈는지 기록이 없으면
--      돌아오지 않은 건을 알아챌 수 없다.
-- =============================================================================

CREATE TABLE analysis_batch (
    id                BIGSERIAL   PRIMARY KEY,

    -- 이 배치를 제출한 실행. collection_run 과 이어 붙여 "언제 돌린 배치인가" 를 추적한다.
    run_id            BIGINT      REFERENCES collection_run (id) ON DELETE SET NULL,

    -- Anthropic 이 발급한 배치 id (msgbatch_...). 재시작 후 결과를 찾아가는 유일한 열쇠라
    -- UNIQUE 를 건다. 같은 배치를 두 번 수거해 두 번 저장하는 일을 막는 것이기도 하다.
    provider_batch_id VARCHAR(100) NOT NULL,

    prompt_version    VARCHAR(20) NOT NULL,
    model             VARCHAR(50) NOT NULL,

    -- SUBMITTED  제출 완료, 결과 대기 중
    -- COLLECTED  결과를 모두 수거해 bill_analysis 에 반영함
    -- FAILED     제출 자체가 실패했거나 수거가 불가능해진 배치
    status            VARCHAR(20) NOT NULL,

    request_count     INT         NOT NULL,
    succeeded_count   INT         NOT NULL DEFAULT 0,
    errored_count     INT         NOT NULL DEFAULT 0,
    -- 보냈는데 결과에 나타나지 않은 건수. 0 이 아니면 그 법안들은 다음 실행이 다시 집어간다.
    missing_count     INT         NOT NULL DEFAULT 0,

    submitted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    collected_at      TIMESTAMPTZ,
    error_message     TEXT,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_analysis_batch_provider UNIQUE (provider_batch_id),
    CONSTRAINT ck_analysis_batch_status CHECK (status IN ('SUBMITTED', 'COLLECTED', 'FAILED'))
);

-- 기동 시 "아직 수거 안 된 배치" 를 찾는 경로. 이 조회가 이중 과금을 막는 장치다.
CREATE INDEX idx_analysis_batch_status ON analysis_batch (status, submitted_at);

CREATE TABLE analysis_batch_item (
    batch_id    BIGINT      NOT NULL REFERENCES analysis_batch (id) ON DELETE CASCADE,
    bill_id     BIGINT      NOT NULL REFERENCES bill (id) ON DELETE CASCADE,

    -- 제출 시점 입력(제목 + 위원회 + 제안이유)의 해시. 수거할 때 다시 계산하지 않고
    -- 이 값을 그대로 bill_analysis.source_hash 에 넣는다. 위 주석의 1번이 그 이유다.
    source_hash VARCHAR(64) NOT NULL,

    -- custom_id 는 bill_id 를 문자열로 쓴다. 한 배치에 같은 법안이 두 번 들어가면
    -- 결과 매칭이 모호해지므로 복합 PK 로 막는다.
    PRIMARY KEY (batch_id, bill_id)
);
