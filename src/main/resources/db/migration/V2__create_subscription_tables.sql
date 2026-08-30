-- =============================================================================
-- V2. 구독자 / 레터 발송 스키마
--
-- 이메일 발송은 되돌릴 수 없다. 잘못 보낸 메일은 회수할 수 없고 곧바로 구독 해지로 이어진다.
-- 그래서 중복 발송과 무단 구독을 애플리케이션 코드의 주의가 아니라
-- DB 제약으로 막는 것을 원칙으로 한다.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- subscriber : 레터 구독자
--
-- status 에 PENDING 을 둔 이유:
-- 이메일 확인 절차 없이 바로 ACTIVE 로 만들면, 남의 주소를 입력해서 원치 않는 메일을
-- 받게 만들 수 있다. 확인 메일을 보내고 클릭한 경우에만 ACTIVE 로 전환한다.
--
-- unsubscribe_token 이 필요한 이유:
-- 수신거부 링크는 로그인 없이 눌려야 한다. 구독자 id 를 그대로 링크에 쓰면
-- 숫자만 바꿔서 남을 해지시킬 수 있으므로, 추측 불가능한 토큰을 따로 둔다.
-- -----------------------------------------------------------------------------
CREATE TABLE subscriber (
    id                BIGSERIAL    PRIMARY KEY,
    email             VARCHAR(255) NOT NULL,
    -- PENDING(확인 대기) / ACTIVE(구독중) / UNSUBSCRIBED(해지)
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    unsubscribe_token UUID         NOT NULL DEFAULT gen_random_uuid(),

    -- 이메일 확인을 마친 시각
    verified_at       TIMESTAMPTZ,
    subscribed_at     TIMESTAMPTZ,
    unsubscribed_at   TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_subscriber_status CHECK (status IN ('PENDING', 'ACTIVE', 'UNSUBSCRIBED'))
);

-- 이메일은 대소문자를 구분하지 않고 유일해야 한다.
-- citext 확장을 쓰지 않고 함수 인덱스로 처리해 확장 의존성을 만들지 않는다.
CREATE UNIQUE INDEX uq_subscriber_email ON subscriber (lower(email));
CREATE UNIQUE INDEX uq_subscriber_unsubscribe_token ON subscriber (unsubscribe_token);
-- 발송 대상 조회: ACTIVE 인 구독자만
CREATE INDEX idx_subscriber_status ON subscriber (status);


-- -----------------------------------------------------------------------------
-- subscription_committee : 구독자별 관심 상임위 필터
--
-- 행이 하나도 없으면 "전체 수신" 으로 해석한다.
-- 별도 컬럼으로 전체 여부를 두지 않는 이유는 두 값이 어긋날 여지를 만들지 않기 위해서다.
-- -----------------------------------------------------------------------------
CREATE TABLE subscription_committee (
    subscriber_id  BIGINT       NOT NULL REFERENCES subscriber (id) ON DELETE CASCADE,
    committee_name VARCHAR(100) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    PRIMARY KEY (subscriber_id, committee_name)
);


-- -----------------------------------------------------------------------------
-- letter_issue : 레터 호차 (월 1회)
--
-- issue_month 는 해당 월의 1일을 저장한다 (예: 2026-09-01).
-- UNIQUE 제약으로 같은 달에 두 번 발행되는 것을 막는다.
-- -----------------------------------------------------------------------------
CREATE TABLE letter_issue (
    id           BIGSERIAL   PRIMARY KEY,
    issue_month  DATE        NOT NULL,
    subject      TEXT        NOT NULL,
    -- 발송 시점에 확정된 본문. 나중에 법안 데이터가 바뀌어도
    -- "실제로 보낸 내용" 은 그대로 남아야 하므로 렌더링 결과를 저장한다.
    body_html    TEXT,
    -- DRAFT(작성중) / SENDING(발송중) / SENT(발송완료)
    status       VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    published_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_letter_issue_month UNIQUE (issue_month),
    CONSTRAINT ck_letter_issue_status CHECK (status IN ('DRAFT', 'SENDING', 'SENT'))
);


-- -----------------------------------------------------------------------------
-- letter_delivery : 구독자별 발송 결과
--
-- (호차, 구독자) UNIQUE 제약이 이 테이블의 핵심이다.
-- 발송 배치가 중간에 죽어서 재실행되면 이미 받은 사람에게 또 보내는 사고가 나는데,
-- 애플리케이션 코드로 조심하는 것보다 DB 제약으로 막는 편이 확실하다.
--
-- provider_message_id 는 SES 가 반환하는 식별자다.
-- 반송(bounce)이나 스팸 신고가 들어왔을 때 어느 발송 건인지 역추적하는 데 쓴다.
-- -----------------------------------------------------------------------------
CREATE TABLE letter_delivery (
    id                  BIGSERIAL    PRIMARY KEY,
    letter_issue_id     BIGINT       NOT NULL REFERENCES letter_issue (id) ON DELETE CASCADE,
    subscriber_id       BIGINT       NOT NULL REFERENCES subscriber (id) ON DELETE CASCADE,
    -- PENDING(대기) / SENT(발송성공) / FAILED(발송실패) / BOUNCED(반송)
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    provider_message_id VARCHAR(200),
    sent_at             TIMESTAMPTZ,
    error_message       TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_letter_delivery UNIQUE (letter_issue_id, subscriber_id),
    CONSTRAINT ck_letter_delivery_status CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'BOUNCED'))
);

-- 재발송 대상(실패 건) 조회
CREATE INDEX idx_letter_delivery_issue_status ON letter_delivery (letter_issue_id, status);
