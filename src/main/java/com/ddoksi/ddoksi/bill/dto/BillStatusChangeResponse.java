package com.ddoksi.ddoksi.bill.dto;

import com.ddoksi.ddoksi.bill.entity.BillStatusHistory;
import java.time.Instant;

/**
 * 상태 변경 이력 한 건.
 *
 * fromStatus 가 null 이면 최초 수집 시점의 기록이다.
 */
public record BillStatusChangeResponse(
        String fromStatusLabel,
        String toStatusLabel,
        String fromProcResultRaw,
        String toProcResultRaw,
        Instant changedAt
) {
    public static BillStatusChangeResponse from(BillStatusHistory history) {
        return new BillStatusChangeResponse(
                history.getFromStatus() != null ? history.getFromStatus().label() : null,
                history.getToStatus().label(),
                history.getFromProcResultRaw(),
                history.getToProcResultRaw(),
                history.getChangedAt());
    }
}
