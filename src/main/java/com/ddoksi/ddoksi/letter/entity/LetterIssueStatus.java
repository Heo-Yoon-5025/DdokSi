package com.ddoksi.ddoksi.letter.entity;

/** 레터 호차 상태. */
public enum LetterIssueStatus {
    /** 작성중 — 아직 발송하지 않음 */
    DRAFT,
    /** 발송중 — 일부 구독자에게 이미 나갔을 수 있다 */
    SENDING,
    /** 발송 완료 */
    SENT
}
