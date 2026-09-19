package com.ddoksi.ddoksi.bill.analysis;

/**
 * 법안 한 건의 분석 시도 결과. 호출 단계와 저장 단계 사이를 오가는 값이다.
 *
 * <p>성공과 실패를 한 타입에 담는 이유는 저장 단계가 둘을 같은 흐름에서 처리해야 하기
 * 때문이다. 실패도 행으로 남겨야 다음 실행이 재시도 대상으로 집어낼 수 있다.
 *
 * @param billId       대상 법안 id
 * @param sourceHash   이번 호출에 사용한 입력의 해시. 재생성 판정 기준이 된다
 * @param model        실제로 호출한 모델 id
 * @param result       성공 시 결과, 실패 시 null
 * @param errorMessage 실패 사유, 성공 시 null
 */
public record AnalyzedBill(
        Long billId,
        String sourceHash,
        String model,
        AnalysisResult result,
        String errorMessage
) {
    public static AnalyzedBill success(Long billId, String sourceHash, String model,
                                       AnalysisResult result) {
        return new AnalyzedBill(billId, sourceHash, model, result, null);
    }

    public static AnalyzedBill failure(Long billId, String sourceHash, String model,
                                       String errorMessage) {
        return new AnalyzedBill(billId, sourceHash, model, null, errorMessage);
    }

    public boolean isSuccess() {
        return result != null;
    }
}
