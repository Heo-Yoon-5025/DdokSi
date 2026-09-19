package com.ddoksi.ddoksi.bill.entity;

import com.ddoksi.ddoksi.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 정규화된 법안 (현재 상태).
 *
 * PK 는 대리키(id)이고 국회 식별자(externalBillId)에는 UNIQUE 만 걸려 있다.
 * 외부 시스템이 주는 식별자를 PK 로 삼으면 그쪽 사정에 우리 FK 전체가 끌려다닌다.
 */
@Getter
@Entity
@Table(name = "bill")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Bill extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 국회 BILL_ID. 수집 시 중복 판정의 기준이 된다. */
    @Column(name = "external_bill_id", nullable = false, length = 50, unique = true)
    private String externalBillId;

    /** 의안번호 (BILL_NO) */
    @Column(name = "bill_no", length = 20)
    private String billNo;

    /** 국회 대수 (예: 22) */
    @Column(name = "assembly_age")
    private Short assemblyAge;

    @Column(name = "title", nullable = false, columnDefinition = "text")
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BillStatus status;

    /**
     * 국회가 내려준 처리결과 문자열 원문.
     * 위 status 매핑이 틀렸다고 판명돼도 이 값이 있으면 재수집 없이 DB 안에서 다시 분류할 수 있다.
     */
    @Column(name = "proc_result_raw", length = 200)
    private String procResultRaw;

    @Column(name = "proposer_kind", length = 30)
    private String proposerKind;

    /** "홍길동의원 등 12인" 같은 표시용 요약 문자열. 정확한 분류에는 쓰지 않는다. */
    @Column(name = "proposer_summary", columnDefinition = "text")
    private String proposerSummary;

    /** 대표발의자 */
    @Column(name = "rst_proposer", columnDefinition = "text")
    private String rstProposer;

    @Column(name = "committee_name", length = 100)
    private String committeeName;

    @Column(name = "committee_id", length = 50)
    private String committeeId;

    @Column(name = "proposed_date")
    private LocalDate proposedDate;

    @Column(name = "proc_date")
    private LocalDate procDate;

    /** 국회 원문 페이지 링크. AI 요약이 틀렸을 때 사용자가 직접 확인할 경로다. */
    @Column(name = "detail_url", columnDefinition = "text")
    private String detailUrl;

    /**
     * 아직 쓰임새를 모르는 나머지 응답 필드.
     * 인증키를 받아 실제 응답을 확인한 뒤, 필요한 것만 컬럼으로 승격시킨다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> extra = new HashMap<>();

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Builder
    private Bill(String externalBillId, String billNo, Short assemblyAge, String title,
                 BillStatus status, String procResultRaw, String proposerKind,
                 String proposerSummary, String rstProposer, String committeeName,
                 String committeeId, LocalDate proposedDate, LocalDate procDate,
                 String detailUrl, Map<String, Object> extra) {
        this.externalBillId = externalBillId;
        this.billNo = billNo;
        this.assemblyAge = assemblyAge;
        this.title = title;
        this.status = status != null ? status : BillStatus.UNKNOWN;
        this.procResultRaw = procResultRaw;
        this.proposerKind = proposerKind;
        this.proposerSummary = proposerSummary;
        this.rstProposer = rstProposer;
        this.committeeName = committeeName;
        this.committeeId = committeeId;
        this.proposedDate = proposedDate;
        this.procDate = procDate;
        this.detailUrl = detailUrl;
        this.extra = extra != null ? extra : new HashMap<>();

        Instant now = Instant.now();
        this.firstSeenAt = now;
        this.lastSeenAt = now;
    }

    /**
     * 수집 배치가 같은 법안을 다시 만났을 때 최신 값으로 갱신한다.
     *
     * 상태 변경 이력(BillStatusHistory)은 여기서 만들지 않는다.
     * 이 메서드는 "값을 덮어쓰는" 책임만 지고, 변경 감지와 이력 기록은 서비스 계층이 담당한다.
     * 그래야 이력을 남길지 말지를 호출하는 쪽이 결정할 수 있다.
     */
    public void refreshFrom(Bill latest) {
        this.billNo = latest.billNo;
        this.assemblyAge = latest.assemblyAge;
        this.title = latest.title;
        this.status = latest.status;
        this.procResultRaw = latest.procResultRaw;
        this.proposerKind = latest.proposerKind;
        this.proposerSummary = latest.proposerSummary;
        this.rstProposer = latest.rstProposer;
        this.committeeName = latest.committeeName;
        this.committeeId = latest.committeeId;
        this.proposedDate = latest.proposedDate;
        this.procDate = latest.procDate;
        this.detailUrl = latest.detailUrl;
        this.extra = latest.extra;
        this.lastSeenAt = Instant.now();
    }

    /** 이번 수집에서 값 변화 없이 다시 등장했을 때, 살아있음만 갱신한다. */
    public void touch() {
        this.lastSeenAt = Instant.now();
    }

    /** 상태가 실제로 바뀌었는지 판정한다. 이력을 남길지 결정하는 기준이다. */
    public boolean hasStatusChanged(BillStatus newStatus, String newProcResultRaw) {
        if (this.status != newStatus) {
            return true;
        }
        // 우리 분류가 같아도 국회 원문 문자열이 달라졌다면 의미 있는 변화로 본다
        // (예: UNKNOWN 안에서 "심사중" -> "체계자구심사")
        return this.procResultRaw == null
                ? newProcResultRaw != null
                : !this.procResultRaw.equals(newProcResultRaw);
    }

    /** 국회가 "철회" 로 내려주는 원문 값. DISCARDED 안에서 폐기와 갈라내는 기준이다. */
    private static final String PROC_RESULT_WITHDRAWN = "철회";

    /**
     * 상태 설명문. 대부분 {@link BillStatus#description()} 을 그대로 쓰고
     * DISCARDED 만 국회 원문으로 갈라진다.
     *
     * <p>철회(166건)와 폐기(4건)가 같은 상태에 묶여 있는데 둘은 의미가 다르다.
     * 철회는 발의한 의원이 스스로 거둬들인 것이고, 폐기는 심사 끝에 버려진 것이다.
     * "심사 결과 더 진행되지 않습니다" 를 철회에 붙이면 사실과 다르다.
     *
     * <p>상태 enum 을 늘리는 대신 원문으로 갈라낸다. 새 상태를 만들면 마이그레이션과
     * 앱 필터 변경이 따라오는데, 표시 문구 하나 때문에 치를 값이 아니다.
     * 이 판정에 필요한 procResultRaw 를 enum 은 모르므로 엔티티가 맡는다.
     */
    public String statusDescription() {
        if (status == BillStatus.DISCARDED && PROC_RESULT_WITHDRAWN.equals(procResultRaw)) {
            return "발의한 의원이 스스로 거두어들였습니다.";
        }
        return status.description();
    }
}
