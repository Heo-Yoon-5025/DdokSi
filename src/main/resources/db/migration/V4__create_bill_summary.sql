-- =============================================================================
-- V4. bill_summary : 법률안 제안이유 및 주요내용
--
-- 배경:
-- 지금까지 쓰던 발의법률안 API(nzmimeepazxkubdpn)는 24개 필드 전부가 메타데이터이고
-- 법안 내용 텍스트가 없다. 그래서 앱 상세 화면에 보여줄 본문도, AI 분석의 입력도 없었다.
-- 이 테이블이 그 빈칸을 채운다.
--
-- 출처: BPMBILLSUMMARY API (2026-09-13 실호출로 확인)
--   - BILL_NO 가 필수 인자다. 인자를 빼면 ERROR-300 이므로 페이징으로 한 번에 받을 수 없고,
--     법안 한 건당 한 번씩 호출해야 한다.
--   - 응답 필드는 BILL_NO / BILL_NAME / BILL_ID / SUMMARY / AGE 다섯 개뿐이다.
--   - SUMMARY 길이 실측(표본 40건): 중앙값 480자, 평균 662자, 최대 3,149자.
--
-- bill 에 컬럼을 붙이지 않고 테이블을 나눈 이유:
--   1. bill 은 피드 목록 조회가 계속 훑는 테이블이다. 평균 662자 TEXT 를 붙이면
--      행이 두꺼워져 목록 조회가 같이 느려진다.
--   2. 1만9천 건 UPDATE 는 MVCC 특성상 행을 전부 새로 쓰고 인덱스 4개도 함께 갱신한다.
--      별도 테이블이면 INSERT 만 하면 된다.
--   3. "아직 안 받아봤다" 와 "받아봤는데 내용이 없다" 를 구분해야 배치를 중단 후
--      이어서 돌릴 수 있다. 행의 유무로 그 구분이 자연스럽게 생긴다.
-- =============================================================================

CREATE TABLE bill_summary (
    id         BIGSERIAL   PRIMARY KEY,
    bill_id    BIGINT      NOT NULL REFERENCES bill (id) ON DELETE CASCADE,

    -- 제안이유 및 주요내용 본문.
    -- NULL 을 허용한다. API 가 SUMMARY:null 을 정상(INFO-000)으로 내려주는 법안이 실제로 있다
    -- (예: 의안번호 2208675). 이 경우에도 행은 남겨야 "조회했으나 내용이 없음" 으로 기록되고
    -- 다음 실행에서 같은 건을 또 호출하지 않는다.
    summary    TEXT,

    -- 응답에 담겨 온 국회 의안 ID. bill.external_bill_id 와 대조해
    -- 의안번호로 엉뚱한 법안을 가져오지 않았는지 확인하는 용도다.
    external_bill_id VARCHAR(50),

    -- 이 내용을 API 에서 받아온 시각. 나중에 재수집 대상을 고를 때 기준이 된다.
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 법안 하나에 본문은 하나다. 배치를 두 번 돌려도 행이 늘지 않게 막는다.
    CONSTRAINT uq_bill_summary_bill UNIQUE (bill_id)
);

-- 수집 배치가 "아직 본문이 없는 법안" 을 고를 때 쓰는 조인 경로.
-- UNIQUE 제약이 이미 인덱스를 만들어 주므로 별도 인덱스는 두지 않는다.
