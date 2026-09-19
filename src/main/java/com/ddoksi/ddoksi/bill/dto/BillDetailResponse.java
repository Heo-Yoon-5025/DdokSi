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
 * @param statusDescription 상태를 풀어 쓴 한 문장. 라벨만으로는 MERGED 를 설명할 수 없어
 *                          서버가 함께 내려준다. 앱과 레터가 각자 문장을 만들면 갈라진다.
 * @param billText      제안이유 및 주요내용 원문. 국회가 본문을 올리지 않은 41건은 null 이다.
 *                      AI 요약이 없거나 미덥지 않을 때 독자가 기댈 곳이므로 항상 함께 내보낸다.
 * @param analysis      AI 분석 결과. 아직 생성 전이거나 실패한 법안은 null 이다.
 *                      null 일 때 앱은 원문만 보여주면 된다 — 분석 유무가 화면을 막지 않는다.
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
        String statusDescription,
        String billText,
        BillAnalysisResponse analysis,
        List<BillStatusChangeResponse> statusHistory
) {
    public static BillDetailResponse of(Bill bill, List<BillStatusChangeResponse> history,
                                        String billText, BillAnalysisResponse analysis) {
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
                bill.statusDescription(),
                billText,
                analysis,
                history);
    }
}
