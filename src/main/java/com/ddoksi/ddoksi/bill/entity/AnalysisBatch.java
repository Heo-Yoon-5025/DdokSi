package com.ddoksi.ddoksi.bill.entity;

import com.ddoksi.ddoksi.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Claude Batch API 에 제출한 분석 묶음 하나.
 *
 * <p><b>이 엔티티의 존재 이유는 이중 과금 방지다.</b> 배치는 제출하는 순간 과금이 확정되고
 * 결과는 최대 24시간 뒤에 나온다. 그 사이 앱이 재시작되면 {@code providerBatchId} 가
 * 메모리에서 사라지고, 이미 지불한 결과를 찾아갈 길이 없어진다. 다음 실행은 같은 법안을
 * 다시 제출하고 값은 두 번 나간다.
 *
 * <p>동기 호출 경로에는 이런 기록이 필요 없었다. 응답을 받는 순간 저장까지 끝나므로
 * 프로세스가 죽어도 잃는 것은 진행 중이던 호출 한 건뿐이다.
 */
@Getter
@Entity
@Table(name = "analysis_batch")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisBatch extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 이 배치를 제출한 실행의 {@code collection_run.id}.
     *
     * <p>연관관계로 매핑하지 않고 id 값만 들고 있는다. 이 테이블에서 실행 정보를 타고 들어갈
     * 일이 없고, 반대로 실행이 지워져도 배치 기록은 남아야 하기 때문이다 (FK 는 SET NULL).
     */
    @Column(name = "run_id")
    private Long runId;

    /** Anthropic 이 발급한 배치 id ({@code msgbatch_...}). 결과를 찾아가는 유일한 열쇠다. */
    @Column(name = "provider_batch_id", nullable = false, length = 100)
    private String providerBatchId;

    @Column(name = "prompt_version", nullable = false, length = 20)
    private String promptVersion;

    @Column(name = "model", nullable = false, length = 50)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AnalysisBatchStatus status;

    /** 제출한 요청 수. 수거 결과와 대조해 돌아오지 않은 건을 계산하는 기준이 된다. */
    @Column(name = "request_count", nullable = false)
    private int requestCount;

    @Column(name = "succeeded_count", nullable = false)
    private int succeededCount;

    @Column(name = "errored_count", nullable = false)
    private int erroredCount;

    /**
     * 보냈는데 결과에 나타나지 않은 건수.
     *
     * <p>취소·만료된 요청이 여기 잡힌다. 0 이 아니어도 배치는 정상 종료로 본다 — 그 법안들은
     * {@code bill_analysis} 에 행이 없으므로 다음 실행의 대상 조회에 저절로 다시 걸린다.
     * 다만 조용히 넘어가면 안 되는 신호라 수치로 남긴다.
     */
    @Column(name = "missing_count", nullable = false)
    private int missingCount;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "collected_at")
    private Instant collectedAt;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Builder
    private AnalysisBatch(Long runId, String providerBatchId, String promptVersion,
                          String model, int requestCount) {
        this.runId = runId;
        this.providerBatchId = providerBatchId;
        this.promptVersion = promptVersion;
        this.model = model;
        this.requestCount = requestCount;
        this.status = AnalysisBatchStatus.SUBMITTED;
        this.submittedAt = Instant.now();
    }

    /** 결과를 모두 수거하고 반영했다. */
    public void markCollected(int succeeded, int errored, int missing) {
        this.status = AnalysisBatchStatus.COLLECTED;
        this.succeededCount = succeeded;
        this.erroredCount = errored;
        this.missingCount = missing;
        this.collectedAt = Instant.now();
    }

    /** 이 배치는 더 기다려도 소용이 없다. 대상 법안은 다음 실행이 다시 집어간다. */
    public void markFailed(String errorMessage) {
        this.status = AnalysisBatchStatus.FAILED;
        this.errorMessage = errorMessage;
        this.collectedAt = Instant.now();
    }

    public boolean isPending() {
        return status == AnalysisBatchStatus.SUBMITTED;
    }
}
