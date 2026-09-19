package com.ddoksi.ddoksi.bill.dto;

import com.ddoksi.ddoksi.bill.entity.BillAnalysis;
import java.util.List;

/**
 * 상세 화면에 내보내는 AI 분석 결과.
 *
 * <p>각 항목은 서로를 참조하지 않고 독립적으로 읽히도록 생성된다. 앱 상세 화면에서는
 * 세로로 이어 보여주고, 레터와 카드뉴스에서는 항목 단위로 잘라 쓴다.
 *
 * <p>{@code pros} / {@code cons} 는 내보내지 않는다. v1 에서 생성하지 않기 때문이다 —
 * 입력인 제안이유가 발의자의 설득 문서라 반대 근거가 본문에 존재하지 않고,
 * 요구하면 모델이 만들어낸다. 컬럼은 남아 있으므로 근거 있는 출처가 생기면 채운다.
 *
 * @param generatedModel 어느 모델이 만든 결과인지. 품질 문제를 추적할 때 필요하다
 */
public record BillAnalysisResponse(
        String hook,
        String summary,
        String example,
        String background,
        List<String> topics,
        String generatedModel
) {
    public static BillAnalysisResponse from(BillAnalysis analysis) {
        return new BillAnalysisResponse(
                analysis.getHook(),
                analysis.getSummary(),
                analysis.getExample(),
                analysis.getBackground(),
                analysis.getTopics(),
                analysis.getModel());
    }
}
