package com.ddoksi.ddoksi.collection.entity;

/** 배치 실행 상태. */
public enum CollectionRunStatus {
    /** 실행 중 */
    RUNNING,
    /** 정상 종료 (개별 건 실패가 있어도 배치 자체가 완주했으면 SUCCESS) */
    SUCCESS,
    /** 전체성 실패로 중단됨 (인증 실패, API 전면 장애 등) */
    FAILED
}
