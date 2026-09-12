package com.ddoksi.ddoksi.bill.entity;

/**
 * 우리 서비스가 쓰는 정규화된 법안 상태.
 *
 * 매핑 규칙은 실제 API 응답(2026-08-30 확인)을 근거로 한다. BillStatusMapper 참고.
 * 관측되지 않은 값이 나중에 등장할 수 있으므로, 분류하지 못한 값은 UNKNOWN 으로 받고
 * 원문 문자열(Bill.procResultRaw)을 함께 보관해 나중에 재분류할 수 있게 한다.
 */
public enum BillStatus {
    /** 논의중 — 계류/심사중/상정 */
    PENDING,
    /** 통과 — 원안가결/수정가결 */
    PASSED,
    /**
     * 대안반영 — 이 법안은 폐기됐지만 내용이 위원회 대안에 흡수되어 법이 됐다.
     *
     * 처리된 법안 중 가장 큰 비중을 차지한다. PASSED 도 DISCARDED 도 사실을 왜곡하므로
     * 별도 상태로 둔다. (대안반영폐기 / 수정안반영폐기)
     */
    MERGED,
    /** 폐기 — 임기만료폐기/철회/폐기/부결 */
    DISCARDED,
    /** 아직 분류 규칙이 없는 처리결과 */
    UNKNOWN;

    /**
     * 화면에 노출할 한글 라벨.
     *
     * 앱과 레터가 각자 라벨을 정의하면 같은 상태를 다른 말로 부르게 된다.
     * 한국어 전용 서비스이므로 여기 한 곳에서 정하고 API 응답에 실어 보낸다.
     */
    public String label() {
        return switch (this) {
            case PENDING -> "논의중";
            case PASSED -> "통과";
            case MERGED -> "대안반영";
            case DISCARDED -> "폐기";
            case UNKNOWN -> "확인필요";
        };
    }
}
