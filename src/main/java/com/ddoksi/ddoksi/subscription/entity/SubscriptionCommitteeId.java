package com.ddoksi.ddoksi.subscription.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * subscription_committee 의 복합 기본키 (구독자 + 상임위명).
 *
 * 복합키 클래스는 값 객체이므로 equals/hashCode 가 반드시 필요하다.
 * JPA 가 영속성 컨텍스트에서 엔티티를 식별할 때 이 값을 쓰기 때문이다.
 */
@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubscriptionCommitteeId implements Serializable {

    @Column(name = "subscriber_id")
    private Long subscriberId;

    @Column(name = "committee_name", length = 100)
    private String committeeName;

    public SubscriptionCommitteeId(Long subscriberId, String committeeName) {
        this.subscriberId = subscriberId;
        this.committeeName = committeeName;
    }
}
