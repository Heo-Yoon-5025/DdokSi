package com.ddoksi.ddoksi.bill.analysis;

/**
 * 배치에 실어 보낼 법안 한 건.
 *
 * <p>{@link BillAnalysisPrompt} 가 만들어 낸 것(사용자 메시지, 입력 해시)을 그대로 들고 있다.
 * 배치 클라이언트가 프롬프트 구성을 다시 하지 않게 하려는 것이다 — 동기 경로와 배치 경로가
 * 서로 다른 입력을 보내면 같은 법안인데 결과가 달라지고, 그 차이는 조용히 생긴다.
 *
 * @param billId      대상 법안 id. 배치의 {@code custom_id} 로 쓴다
 * @param sourceHash  제출 시점 입력의 해시. 결과를 저장할 때 이 값을 그대로 쓴다
 * @param userMessage 법안별로 달라지는 부분. 시스템 프롬프트는 클라이언트가 붙인다
 */
public record PendingAnalysis(Long billId, String sourceHash, String userMessage) {
}
