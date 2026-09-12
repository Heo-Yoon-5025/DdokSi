package com.ddoksi.ddoksi.bill.dto;

import com.ddoksi.ddoksi.bill.entity.Bill;
import com.ddoksi.ddoksi.bill.entity.BillStatus;
import java.time.LocalDate;

/**
 * 목록용 법안 요약.
 *
 * 엔티티를 그대로 내보내지 않는다. 지연 로딩 프록시가 직렬화에서 터지고,
 * DB 컬럼 변경이 곧바로 앱이 깨지는 API 변경이 되어버린다.
 *
 * @param status      로직용 상태 값 (앱이 필터/분기에 쓴다)
 * @param statusLabel 화면 표시용 한글 라벨 (라벨 정의가 앱과 갈라지지 않게 서버가 내려준다)
 * @param summary     AI 요약. 분석 파이프라인이 아직 없어 현재는 항상 null 이다.
 */
public record BillSummaryResponse(
        Long id,
        String billNo,
        String title,
        BillStatus status,
        String statusLabel,
        String committeeName,
        String proposerSummary,
        LocalDate proposedDate,
        String summary
) {
    public static BillSummaryResponse from(Bill bill) {
        return new BillSummaryResponse(
                bill.getId(),
                bill.getBillNo(),
                bill.getTitle(),
                bill.getStatus(),
                bill.getStatus().label(),
                bill.getCommitteeName(),
                bill.getProposerSummary(),
                bill.getProposedDate(),
                null);
    }
}
