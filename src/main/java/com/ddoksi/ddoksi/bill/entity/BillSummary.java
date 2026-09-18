package com.ddoksi.ddoksi.bill.entity;

import com.ddoksi.ddoksi.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 법률안 제안이유 및 주요내용.
 *
 * <p>출처는 BPMBILLSUMMARY API 이고, 법안 한 건당 한 번씩 호출해 받아온다.
 * 발의법률안 목록 API 가 주는 24개 필드는 전부 메타데이터라 본문이 없었고,
 * 앱 상세 화면과 AI 분석이 모두 이 텍스트를 입력으로 쓴다.
 *
 * <p>{@code bill} 테이블에 컬럼으로 붙이지 않고 별도 엔티티로 둔 이유는 V4 마이그레이션
 * 주석에 적어두었다. 요약하면 목록 조회 성능과 배치 재개 가능성 때문이다.
 */
@Getter
@Entity
@Table(name = "bill_summary")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillSummary extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 지연 로딩으로 둔다. 본문만 필요한 조회에서 법안 전체를 끌고 오지 않기 위해서다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private Bill bill;

    /**
     * 제안이유 및 주요내용 본문.
     *
     * <p>null 을 허용한다. API 가 정상 응답(INFO-000)으로 내려주면서도 SUMMARY 만 비어 있는
     * 법안이 실제로 존재한다. 그런 건도 행을 남겨야 "조회했지만 내용이 없었다" 로 기록되어
     * 다음 배치에서 같은 건을 다시 호출하지 않는다.
     */
    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    /** 응답에 담겨 온 국회 의안 ID. 엉뚱한 법안을 받아오지 않았는지 확인하는 데 쓴다. */
    @Column(name = "external_bill_id", length = 50)
    private String externalBillId;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Builder
    private BillSummary(Bill bill, String summary, String externalBillId) {
        this.bill = bill;
        this.summary = summary;
        this.externalBillId = externalBillId;
        this.fetchedAt = Instant.now();
    }

    /** 본문이 실제로 채워져 있는지. API 가 빈 본문을 정상 응답으로 주기 때문에 필요하다. */
    public boolean hasContent() {
        return summary != null && !summary.isBlank();
    }

    /**
     * 재수집 결과로 본문을 갱신한다.
     *
     * <p>내용이 같으면 아무것도 하지 않고 {@code false} 를 돌려준다.
     * 변화가 없는데도 매번 UPDATE 를 날리면 updated_at 만 계속 바뀌어
     * "언제 실제로 내용이 바뀌었는지" 를 알 수 없게 된다.
     *
     * @return 실제로 내용이 바뀌었으면 true
     */
    public boolean updateContent(String newSummary) {
        this.fetchedAt = Instant.now();
        if (java.util.Objects.equals(this.summary, newSummary)) {
            return false;
        }
        this.summary = newSummary;
        return true;
    }
}
