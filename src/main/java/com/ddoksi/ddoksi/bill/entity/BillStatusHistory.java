package com.ddoksi.ddoksi.bill.entity;

import com.ddoksi.ddoksi.collection.entity.CollectionRun;
import com.ddoksi.ddoksi.common.entity.BaseCreatedAtEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 법안 상태 변경 이력.
 *
 * 이 서비스의 핵심 엔티티다.
 * 월간 레터의 "이번 달 통과된 법안" 과 앱의 관심 법안 푸시 알림이 모두 여기서 나온다.
 *
 * 멱등성 유지를 위해 "실제로 값이 바뀐 경우에만" 생성한다.
 * 배치를 두 번 돌렸다고 같은 변경이 두 건 쌓이면 안 된다. (Bill.hasStatusChanged 로 판정)
 */
@Getter
@Entity
@Table(name = "bill_status_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillStatusHistory extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private Bill bill;

    /** 최초 수집 시점에는 이전 상태가 없으므로 null 을 허용한다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private BillStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private BillStatus toStatus;

    @Column(name = "from_proc_result_raw", length = 200)
    private String fromProcResultRaw;

    @Column(name = "to_proc_result_raw", length = 200)
    private String toProcResultRaw;

    /** 변경을 "감지한" 시각. 국회에서 실제로 바뀐 시각과는 다를 수 있다. */
    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "detected_by_run_id")
    private CollectionRun detectedByRun;

    @Builder
    private BillStatusHistory(Bill bill, BillStatus fromStatus, BillStatus toStatus,
                              String fromProcResultRaw, String toProcResultRaw,
                              CollectionRun detectedByRun) {
        this.bill = bill;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.fromProcResultRaw = fromProcResultRaw;
        this.toProcResultRaw = toProcResultRaw;
        this.detectedByRun = detectedByRun;
        this.changedAt = Instant.now();
    }

    /** 최초 수집 시 기록하는 이력. 이전 상태가 없다. */
    public static BillStatusHistory initial(Bill bill, CollectionRun run) {
        return BillStatusHistory.builder()
                .bill(bill)
                .toStatus(bill.getStatus())
                .toProcResultRaw(bill.getProcResultRaw())
                .detectedByRun(run)
                .build();
    }
}
