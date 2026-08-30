package com.ddoksi.ddoksi.bill.entity;

/** AI 분석 생성 결과 상태. */
public enum AnalysisStatus {
    SUCCESS,
    /** 생성 실패. 분석이 없어도 법안 기본 정보는 계속 노출되어야 하므로 실패도 기록으로 남긴다. */
    FAILED
}
