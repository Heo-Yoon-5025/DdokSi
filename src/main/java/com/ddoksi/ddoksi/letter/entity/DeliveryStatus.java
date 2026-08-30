package com.ddoksi.ddoksi.letter.entity;

/** 구독자별 발송 상태. */
public enum DeliveryStatus {
    PENDING,
    SENT,
    /** 발송 실패 — 재시도 대상 */
    FAILED,
    /** 반송 — 주소가 유효하지 않다. 재시도하면 발신 평판만 나빠진다. */
    BOUNCED
}
