package com.ddoksi.ddoksi.bill.dto;

import com.ddoksi.ddoksi.bill.entity.Bill;
import com.ddoksi.ddoksi.bill.entity.BillStatus;
import java.time.LocalDate;
import java.util.List;

/**
 * 상세용 법안 정보.
 *
 * @param procResultRaw 국회가 내려준 처리결과 원문.
 *                      우리 분류(status)와 함께 노출해 사용자가 원 표현을 확인할 수 있게 한다.
 * @param detailUrl     국회 공식 페이지. AI 요약이 틀렸을 때 원문을 확인할 경로이므로 항상 함께 내보낸다.
 * @param statusHistory 상태 변경 이력 (최신순)
 */
public record BillDetailResponse(
        Long id,
        String billNo,
        Short assemblyAge,
        String title,
        BillStatus status,
        String statusLabel,
        String procResultRaw,
        String committeeName,
        String proposerSummary,
        String rstProposer,
        LocalDate proposedDate,
        LocalDate procDate,
        String detailUrl,
        List<BillStatusChangeResponse> statusHistory
) {
    public static BillDetailResponse of(Bill bill, List<BillStatusChangeResponse> history) {
        return new BillDetailResponse(
                bill.getId(),
                bill.getBillNo(),
                bill.getAssemblyAge(),
                bill.getTitle(),
                bill.getStatus(),
                bill.getStatus().label(),
                bill.getProcResultRaw(),
                bill.getCommitteeName(),
                bill.getProposerSummary(),
                bill.getRstProposer(),
                bill.getProposedDate(),
                bill.getProcDate(),
                bill.getDetailUrl(),
                history);
    }
}
