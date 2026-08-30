package com.ddoksi.ddoksi.subscription.entity;

/** 구독 상태. */
public enum SubscriberStatus {
    /**
     * 이메일 확인 대기.
     * 확인 절차 없이 바로 ACTIVE 로 만들면 남의 주소를 입력해 원치 않는 메일을 받게 만들 수 있다.
     */
    PENDING,
    /** 구독중 — 발송 대상 */
    ACTIVE,
    /** 해지 */
    UNSUBSCRIBED
}
