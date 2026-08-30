package com.ddoksi.ddoksi.subscription.entity;

import com.ddoksi.ddoksi.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 레터 구독자.
 *
 * unsubscribeToken 이 필요한 이유:
 * 수신거부 링크는 로그인 없이 눌려야 한다. 구독자 id 를 그대로 링크에 쓰면
 * 숫자만 바꿔서 남을 해지시킬 수 있으므로, 추측 불가능한 토큰을 따로 둔다.
 */
@Getter
@Entity
@Table(name = "subscriber")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Subscriber extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** DB 에서는 lower(email) 로 유니크 인덱스가 걸려 있어 대소문자를 구분하지 않는다. */
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SubscriberStatus status;

    @Column(name = "unsubscribe_token", nullable = false, updatable = false)
    private UUID unsubscribeToken;

    /** 이메일 확인을 마친 시각 */
    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "subscribed_at")
    private Instant subscribedAt;

    @Column(name = "unsubscribed_at")
    private Instant unsubscribedAt;

    @Builder
    private Subscriber(String email) {
        this.email = email;
        this.status = SubscriberStatus.PENDING;
        // DB 에도 DEFAULT gen_random_uuid() 가 있지만, JPA 로 저장할 때는
        // INSERT 문에 값이 포함되므로 애플리케이션에서 직접 생성한다.
        this.unsubscribeToken = UUID.randomUUID();
    }

    /** 이메일 확인 완료 → 발송 대상이 된다. */
    public void verify() {
        this.status = SubscriberStatus.ACTIVE;
        Instant now = Instant.now();
        this.verifiedAt = now;
        this.subscribedAt = now;
    }

    public void unsubscribe() {
        this.status = SubscriberStatus.UNSUBSCRIBED;
        this.unsubscribedAt = Instant.now();
    }

    /** 해지했던 구독자가 다시 구독하는 경우. 토큰은 유지한다. */
    public void resubscribe() {
        this.status = SubscriberStatus.ACTIVE;
        this.subscribedAt = Instant.now();
        this.unsubscribedAt = null;
    }
}
