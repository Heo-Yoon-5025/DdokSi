package com.ddoksi.ddoksi.collection.entity;

import com.ddoksi.ddoksi.common.entity.BaseCreatedAtEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 배치 실행 이력.
 *
 * "레터에 법안이 왜 하나도 없었지?" 를 나중에 추적할 수 있는 유일한 단서다.
 * cursorValue 는 증분 수집 위치를 기억해 매번 전체를 긁지 않게 한다.
 */
@Getter
@Entity
@Table(name = "collection_run")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectionRun extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_name", nullable = false, length = 100)
    private String jobName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CollectionRunStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "target_count", nullable = false)
    private int targetCount;

    @Column(name = "success_count", nullable = false)
    private int successCount;

    @Column(name = "fail_count", nullable = false)
    private int failCount;

    /** 다음 실행이 이어받을 수집 위치 (예: 마지막으로 처리한 제안일) */
    @Column(name = "cursor_value", length = 100)
    private String cursorValue;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Builder
    private CollectionRun(String jobName, Instant startedAt) {
        this.jobName = jobName;
        this.startedAt = startedAt != null ? startedAt : Instant.now();
        this.status = CollectionRunStatus.RUNNING;
    }

    /** 개별 건 처리 결과를 누적한다. 건별 실패는 배치를 중단시키지 않고 여기에 집계된다. */
    public void recordProgress(int target, int success, int fail) {
        this.targetCount = target;
        this.successCount = success;
        this.failCount = fail;
    }

    /** 배치를 정상 종료 처리한다. */
    public void complete(String cursorValue) {
        this.status = CollectionRunStatus.SUCCESS;
        this.cursorValue = cursorValue;
        this.finishedAt = Instant.now();
    }

    /** 전체성 실패로 배치를 중단 처리한다. */
    public void fail(String errorMessage) {
        this.status = CollectionRunStatus.FAILED;
        this.errorMessage = errorMessage;
        this.finishedAt = Instant.now();
    }
}
