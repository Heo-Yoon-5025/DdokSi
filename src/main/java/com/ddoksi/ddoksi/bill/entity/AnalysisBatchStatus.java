package com.ddoksi.ddoksi.bill.entity;

/**
 * Batch API 제출 건의 수명주기.
 *
 * <p>상태가 셋뿐인 것은 의도적이다. Anthropic 쪽 진행 상태(in_progress / canceling / ended)를
 * 그대로 옮겨 적지 않는다 — 그 값은 물어보면 언제든 알 수 있는 남의 상태이고, 우리가 기억해야
 * 하는 것은 "이 배치의 결과를 우리 DB 에 반영했는가" 하나다. 남의 상태를 복제해 두면 둘이
 * 어긋났을 때 어느 쪽이 진실인지 알 수 없게 된다.
 */
public enum AnalysisBatchStatus {

    /** 제출은 끝났고 결과는 아직 수거하지 않았다. 재시작 후 이어받아야 할 유일한 상태다. */
    SUBMITTED,

    /** 결과를 모두 수거해 {@code bill_analysis} 에 반영했다. */
    COLLECTED,

    /** 제출이 실패했거나 결과를 끝내 가져올 수 없게 됐다. 대상 법안은 다음 실행이 다시 집어간다. */
    FAILED
}
