package com.ddoksi.ddoksi.bill;

import com.ddoksi.ddoksi.bill.entity.BillStatus;
import java.util.Map;

/**
 * 국회 API 의 처리결과 문자열을 우리 서비스의 {@link BillStatus} 로 변환한다.
 *
 * <p>매핑 규칙은 2026-08-30 에 실제 API 를 호출해 확인한 값을 근거로 한다.
 * 표본 7,000건에서 관측된 값은 아래 표가 전부다.
 *
 * <p><b>이 클래스가 유일한 매핑 지점이다.</b> 상태 분류 로직을 여기 말고 다른 곳에 두지 않는다.
 * 국회가 새로운 처리결과 문자열을 쓰기 시작하면 고칠 곳이 여기 하나여야 한다.
 *
 * <p>주의: API 마다 필드명이 다르다.
 * 발의법률안 API 는 {@code PROC_RESULT}, 의안접수목록(BILLRCP)은 {@code PROC_RSLT} 를 쓴다.
 * 값 자체는 같은 어휘를 공유하므로 이 매퍼는 양쪽 모두에 쓸 수 있다.
 */
public final class BillStatusMapper {

    /**
     * 처리결과 문자열 → 상태.
     *
     * 계류(미처리)는 값이 아예 null 로 오므로 이 표에 없다.
     */
    private static final Map<String, BillStatus> STATUS_BY_PROC_RESULT = Map.of(
            // 통과 — 본회의에서 가결됨
            "원안가결", BillStatus.PASSED,
            "수정가결", BillStatus.PASSED,

            // 대안반영 — 법안 자체는 폐기됐으나 내용이 대안/수정안에 흡수되어 법이 됨
            "대안반영폐기", BillStatus.MERGED,
            "수정안반영폐기", BillStatus.MERGED,

            // 폐기 — 내용이 어디에도 남지 않음
            "임기만료폐기", BillStatus.DISCARDED,
            "철회", BillStatus.DISCARDED,
            "폐기", BillStatus.DISCARDED,
            "부결", BillStatus.DISCARDED
    );

    private BillStatusMapper() {
    }

    /**
     * 처리결과 문자열을 상태로 변환한다.
     *
     * @param procResult 국회 API 의 PROC_RESULT / PROC_RSLT 값. 계류 중이면 null 이다.
     * @return 매핑된 상태. 값이 비어 있으면 PENDING, 표에 없는 값이면 UNKNOWN.
     */
    public static BillStatus from(String procResult) {
        // 계류 중인 법안은 처리결과 자체가 내려오지 않는다 (표본의 대다수가 여기 해당)
        if (procResult == null || procResult.isBlank()) {
            return BillStatus.PENDING;
        }

        // 앞뒤 공백은 제거하고 조회한다. 공공 API 응답에 공백이 섞여 오는 경우가 흔하다.
        BillStatus mapped = STATUS_BY_PROC_RESULT.get(procResult.strip());

        // 표에 없는 값은 임의로 추측하지 않는다.
        // 원문(Bill.procResultRaw)이 함께 저장되므로 나중에 규칙을 추가해 재분류할 수 있다.
        return mapped != null ? mapped : BillStatus.UNKNOWN;
    }

    /** 아직 분류 규칙이 없는 값인지 확인한다. 수집 배치가 경고 로그를 남길 때 쓴다. */
    public static boolean isUnmapped(String procResult) {
        return procResult != null
                && !procResult.isBlank()
                && !STATUS_BY_PROC_RESULT.containsKey(procResult.strip());
    }
}
