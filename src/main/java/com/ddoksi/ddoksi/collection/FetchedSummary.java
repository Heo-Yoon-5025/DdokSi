package com.ddoksi.ddoksi.collection;

/**
 * API 에서 받아온 법안 한 건의 본문. 저장 단계로 넘기기 위한 운반용 값이다.
 *
 * <p>엔티티를 그대로 들고 다니지 않는 이유: 조회 트랜잭션과 저장 트랜잭션이 나뉘어 있어
 * 그 사이에 엔티티가 준영속 상태가 된다. 식별자만 넘기고 저장 시점에 다시 붙이는 편이 안전하다.
 *
 * @param billId          우리 DB 의 법안 id
 * @param billNo          의안번호 (로그 추적용)
 * @param summary         제안이유 및 주요내용. API 가 빈 본문을 주는 경우가 있어 null 일 수 있다.
 * @param externalBillId  응답에 담겨 온 국회 의안 ID. 대조 검증에 쓴다.
 * @param found           의안번호로 데이터를 찾았는지. false 면 INFO-200 이었다는 뜻이다.
 */
public record FetchedSummary(Long billId, String billNo, String summary,
                             String externalBillId, boolean found) {

    public static FetchedSummary notFound(Long billId, String billNo) {
        return new FetchedSummary(billId, billNo, null, null, false);
    }
}
