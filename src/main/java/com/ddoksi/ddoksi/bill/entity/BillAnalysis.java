package com.ddoksi.ddoksi.bill.entity;

import com.ddoksi.ddoksi.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Claude API 분석 결과 캐시.
 *
 * AI 호출은 곧 비용이므로 재생성하지 않는 것이 기본이다.
 * 다만 "이미 있으면 무조건 건너뛰기" 로는 법안 원문이 수정된 경우를 처리할 수 없어서
 * 입력 원문의 해시(sourceHash)를 함께 저장해 바뀐 것만 골라낸다.
 *
 * promptVersion 을 함께 저장하는 이유: 프롬프트를 개선했을 때 구버전으로 생성된 것만
 * 재생성할 수 있어야 한다. 버전 정보가 없으면 전량 재생성밖에 방법이 없고 그것은 비용이다.
 */
@Getter
@Entity
@Table(name = "bill_analysis")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillAnalysis extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private Bill bill;

    @Column(name = "prompt_version", nullable = false, length = 20)
    private String promptVersion;

    @Column(name = "model", nullable = false, length = 50)
    private String model;

    /** 분석 입력(법안 원문 + 제안이유)의 해시. 원문이 바뀌었는지 판단하는 기준. */
    @Column(name = "source_hash", nullable = false, length = 64)
    private String sourceHash;

    /** 생성 결과. 원문에 근거가 없으면 억지로 채우지 않으므로 null 을 허용한다. */
    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "example", columnDefinition = "text")
    private String example;

    @Column(name = "background", columnDefinition = "text")
    private String background;

    /** 찬성 논거. 한쪽만 채우지 않는다 — 정치적 중립성의 최소 조건이다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pros", nullable = false, columnDefinition = "jsonb")
    private List<String> pros = new ArrayList<>();

    /** 반대 논거 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cons", nullable = false, columnDefinition = "jsonb")
    private List<String> cons = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AnalysisStatus status;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Builder
    private BillAnalysis(Bill bill, String promptVersion, String model, String sourceHash,
                         String summary, String example, String background,
                         List<String> pros, List<String> cons,
                         AnalysisStatus status, String errorMessage) {
        this.bill = bill;
        this.promptVersion = promptVersion;
        this.model = model;
        this.sourceHash = sourceHash;
        this.summary = summary;
        this.example = example;
        this.background = background;
        this.pros = pros != null ? pros : new ArrayList<>();
        this.cons = cons != null ? cons : new ArrayList<>();
        this.status = status;
        this.errorMessage = errorMessage;
        this.generatedAt = Instant.now();
    }

    /** 생성 실패를 기록한다. 실패해도 법안 기본 정보 노출은 막지 않는다. */
    public static BillAnalysis failed(Bill bill, String promptVersion, String model,
                                      String sourceHash, String errorMessage) {
        return BillAnalysis.builder()
                .bill(bill)
                .promptVersion(promptVersion)
                .model(model)
                .sourceHash(sourceHash)
                .status(AnalysisStatus.FAILED)
                .errorMessage(errorMessage)
                .build();
    }

    /** 분석 입력이 바뀌었는지 판정한다. 재생성 여부를 결정하는 기준이다. */
    public boolean isStale(String currentSourceHash) {
        return !this.sourceHash.equals(currentSourceHash);
    }
}
