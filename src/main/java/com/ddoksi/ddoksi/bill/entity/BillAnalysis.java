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

    /**
     * SNS 첫 줄 / 카드뉴스 표지 / 레터 소제목으로 그대로 쓰는 한 문장.
     *
     * 법안명이 아니라 내용 핵심어를 담는다 — 법안명은 최대 99자라 40자 상한 안에
     * 법안명과 서술을 함께 넣을 수 없는 경우가 296건 있다.
     */
    @Column(name = "hook", columnDefinition = "text")
    private String hook;

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

    /**
     * 주제 태그. {@link com.ddoksi.ddoksi.bill.analysis.BillTopic} 의 고정 어휘에서만 온다.
     *
     * 국회 원천에 주제 분류가 없어 우리가 만드는 값이다. 레터 편집과 앱 필터가 이걸로 묶는다.
     * 어느 어휘에도 맞지 않으면 빈 배열이다 — '기타' 를 두면 그것이 최다 태그가 된다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "topics", nullable = false, columnDefinition = "jsonb")
    private List<String> topics = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AnalysisStatus status;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Builder
    private BillAnalysis(Bill bill, String promptVersion, String model, String sourceHash,
                         String hook, String summary, String example, String background,
                         List<String> pros, List<String> cons, List<String> topics,
                         AnalysisStatus status, String errorMessage) {
        this.bill = bill;
        this.promptVersion = promptVersion;
        this.model = model;
        this.sourceHash = sourceHash;
        this.hook = hook;
        this.summary = summary;
        this.example = example;
        this.background = background;
        this.pros = pros != null ? pros : new ArrayList<>();
        this.cons = cons != null ? cons : new ArrayList<>();
        this.topics = topics != null ? topics : new ArrayList<>();
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

    /**
     * 재시도 또는 재생성 결과로 내용을 덮어쓴다.
     *
     * <p><b>왜 새 행을 만들지 않는가.</b> {@code UNIQUE (bill_id, prompt_version)} 때문에
     * 같은 법안·같은 프롬프트 버전으로 두 번째 행을 만들 수 없다. 재시도 경로는 INSERT 가
     * 아니라 반드시 이쪽이다.
     *
     * <p>세터를 두지 않고 이 메서드 하나로 좁힌 이유는, 갱신이 언제 어떤 이유로 일어나는지를
     * 호출부에서 읽히게 하기 위해서다. 엔티티 규약대로 의도를 이름에 담는다.
     */
    public void replaceWith(String hook, String summary, String example, String background,
                            List<String> topics, String sourceHash, String model) {
        this.hook = hook;
        this.summary = summary;
        this.example = example;
        this.background = background;
        this.topics = topics != null ? topics : new ArrayList<>();
        this.sourceHash = sourceHash;
        this.model = model;
        this.status = AnalysisStatus.SUCCESS;
        // 지난 실패 흔적을 남기지 않는다. 성공한 행에 옛 오류 메시지가 붙어 있으면
        // 나중에 로그를 볼 때 무엇이 현재 사실인지 알 수 없다.
        this.errorMessage = null;
        this.generatedAt = Instant.now();
    }

    /**
     * 재시도했는데 또 실패한 경우. 사유만 갱신하고 기존 생성 결과는 건드리지 않는다.
     *
     * <p>이전에 성공한 적이 있는 행이 원문 변경으로 재생성되다 실패하면, 옛 내용이라도
     * 남아 있는 편이 빈 화면보다 낫다. 상태가 FAILED 라 조회 API 는 내보내지 않지만
     * 데이터는 보존된다.
     */
    public void markFailed(String errorMessage) {
        this.status = AnalysisStatus.FAILED;
        this.errorMessage = errorMessage;
        this.generatedAt = Instant.now();
    }

    /** 분석 입력이 바뀌었는지 판정한다. 재생성 여부를 결정하는 기준이다. */
    public boolean isStale(String currentSourceHash) {
        return !this.sourceHash.equals(currentSourceHash);
    }
}
