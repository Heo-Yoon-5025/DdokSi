package com.ddoksi.ddoksi.collection;

/**
 * 제안이유 수집 결과 집계.
 *
 * @param inserted     본문을 새로 저장한 법안
 * @param updated      이미 있던 본문이 실제로 바뀌어 갱신한 법안
 * @param unchanged    다시 받아봤지만 내용이 같았던 법안
 * @param emptyContent 저장은 했으나 본문이 비어 있던 법안.
 *                     inserted/updated 와 별개로 세는 부분 집계다 — API 가 정상 응답으로
 *                     빈 본문을 주는 경우가 실제로 있어, 그 규모를 따로 봐야 한다.
 * @param notFound     의안번호로 조회했으나 데이터가 없던 법안 (INFO-200)
 * @param skipped      오류로 건너뛴 법안
 */
public record SummaryResult(int inserted, int updated, int unchanged,
                            int emptyContent, int notFound, int skipped) {

    /** 본문을 실제로 저장한 건수. emptyContent 는 inserted 안에 포함되므로 더하지 않는다. */
    public int processed() {
        return inserted + updated + unchanged;
    }

    public SummaryResult plus(SummaryResult other) {
        return new SummaryResult(
                inserted + other.inserted,
                updated + other.updated,
                unchanged + other.unchanged,
                emptyContent + other.emptyContent,
                notFound + other.notFound,
                skipped + other.skipped);
    }

    public static SummaryResult empty() {
        return new SummaryResult(0, 0, 0, 0, 0, 0);
    }
}
