package com.ddoksi.ddoksi.bill.analysis;

/**
 * 묶음 저장 결과 집계.
 *
 * @param inserted    새로 만든 행
 * @param retried     지난 실행에서 실패했던 행을 다시 채운 것
 * @param regenerated 원문이 바뀌어 다시 생성한 것
 * @param unchanged   이미 성공했고 입력도 그대로여서 건드리지 않은 것
 * @param failed      이번에도 실패로 기록된 것
 */
public record AnalysisChunkResult(
        int inserted,
        int retried,
        int regenerated,
        int unchanged,
        int failed
) {
    public static AnalysisChunkResult empty() {
        return new AnalysisChunkResult(0, 0, 0, 0, 0);
    }

    public AnalysisChunkResult plus(AnalysisChunkResult other) {
        return new AnalysisChunkResult(
                inserted + other.inserted,
                retried + other.retried,
                regenerated + other.regenerated,
                unchanged + other.unchanged,
                failed + other.failed);
    }

    /** 실제로 모델을 부른 건수. 비용과 직결되므로 따로 센다. */
    public int called() {
        return inserted + retried + regenerated;
    }
}
