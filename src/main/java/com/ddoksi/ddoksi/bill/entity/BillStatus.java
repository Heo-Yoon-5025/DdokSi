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
            // "대안반영" 은 국회 용어를 그대로 옮긴 것이라 일반 독자에게 뜻이 전달되지 않는다.
            // 무슨 일이 일어났는지는 description() 이 설명하고, 배지에는 읽히는 말을 쓴다.
            case MERGED -> "통합 처리";
            case DISCARDED -> "폐기";
            case UNKNOWN -> "확인필요";
        };
    }

    /**
     * 라벨 아래에 붙는 한 문장 설명.
     *
     * <p>{@link #label()} 이 배지에 들어갈 두세 글자라면 이쪽은 그 상태가 무엇을 뜻하는지
     * 풀어 쓴 문장이다. 특히 MERGED 는 라벨만으로는 아무것도 전달되지 않는다 —
     * 법안이 끝났는지, 내용이 살아남았는지, 실패인지 아닌지가 전부 라벨 밖에 있다.
     *
     * <p>label() 과 같은 이유로 여기 한 곳에서 정의한다. 앱과 레터가 각자 문장을 만들면
     * 같은 상태를 서로 다르게 설명하게 된다.
     *
     * <p>DISCARDED 는 철회와 폐기가 한 상태에 묶여 있어 이 문장만으로는 정확하지 않다.
     * 국회 원문으로 갈라주는 {@link com.ddoksi.ddoksi.bill.entity.Bill#statusDescription()}
     * 를 쓴다.
     */
    public String description() {
        return switch (this) {
            case PENDING -> "국회에서 아직 심사 중인 법안입니다.";
            case PASSED -> "본회의를 통과했습니다.";
            // 처리된 법안 중 가장 큰 비중(전체의 20.9%, PASSED 의 여섯 배)이라
            // 폐기로도 통과로도 읽히면 안 된다.
            // "대안이 본회의를 통과했다" 까지는 쓰지 않는다 — 위원장 제안 대안은
            // 우리 수집 범위(의원 발의 법률안) 밖이라 그 결말을 우리가 알지 못한다.
            case MERGED -> "비슷한 법안들과 하나로 합쳐졌습니다. "
                    + "이 법안 번호로는 더 진행되지 않지만, 내용은 살아 있습니다.";
            case DISCARDED -> "심사 결과 더 진행되지 않습니다.";
            case UNKNOWN -> "상태를 확인할 수 없습니다.";
        };
    }
}
