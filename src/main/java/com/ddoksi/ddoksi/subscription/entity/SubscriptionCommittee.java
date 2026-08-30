package com.ddoksi.ddoksi.subscription.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 구독자별 관심 상임위 필터.
 *
 * 행이 하나도 없으면 "전체 수신" 으로 해석한다.
 * 별도 컬럼으로 전체 여부를 두지 않는 이유는 두 값이 어긋날 여지를 만들지 않기 위해서다.
 *
 * 이 테이블은 생성 시각만 있고 수정되지 않으므로 BaseCreatedAtEntity 를 상속하지 않고
 * createdAt 을 직접 둔다. (복합키 엔티티라 Auditing 리스너 적용 범위를 단순하게 유지)
 */
@Getter
@Entity
@Table(name = "subscription_committee")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubscriptionCommittee {

    @EmbeddedId
    private SubscriptionCommitteeId id;

    /**
     * @MapsId 로 복합키의 subscriberId 부분과 이 연관관계를 같은 컬럼에 매핑한다.
     * 이렇게 하지 않으면 subscriber_id 컬럼이 두 번 매핑되어 오류가 난다.
     */
    @MapsId("subscriberId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscriber_id", nullable = false)
    private Subscriber subscriber;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public SubscriptionCommittee(Subscriber subscriber, String committeeName) {
        this.subscriber = subscriber;
        this.id = new SubscriptionCommitteeId(subscriber.getId(), committeeName);
        this.createdAt = Instant.now();
    }

    public String getCommitteeName() {
        return id.getCommitteeName();
    }
}
