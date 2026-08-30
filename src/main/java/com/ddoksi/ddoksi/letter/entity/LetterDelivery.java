package com.ddoksi.ddoksi.letter.entity;

import com.ddoksi.ddoksi.common.entity.BaseTimeEntity;
import com.ddoksi.ddoksi.subscription.entity.Subscriber;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 구독자별 발송 결과.
 *
 * (호차, 구독자) UNIQUE 제약이 이 엔티티의 핵심이다.
 * 발송 배치가 중간에 죽어서 재실행되면 이미 받은 사람에게 또 보내는 사고가 나는데,
 * 애플리케이션 코드로 조심하는 것보다 DB 제약으로 막는 편이 확실하다.
 */
@Getter
@Entity
@Table(
        name = "letter_delivery",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_letter_delivery",
                columnNames = {"letter_issue_id", "subscriber_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LetterDelivery extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "letter_issue_id", nullable = false)
    private LetterIssue letterIssue;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscriber_id", nullable = false)
    private Subscriber subscriber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DeliveryStatus status;

    /** SES 가 반환하는 식별자. 반송이나 스팸 신고가 들어왔을 때 역추적에 쓴다. */
    @Column(name = "provider_message_id", length = 200)
    private String providerMessageId;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Builder
    private LetterDelivery(LetterIssue letterIssue, Subscriber subscriber) {
        this.letterIssue = letterIssue;
        this.subscriber = subscriber;
        this.status = DeliveryStatus.PENDING;
    }

    public void markSent(String providerMessageId) {
        this.status = DeliveryStatus.SENT;
        this.providerMessageId = providerMessageId;
        this.sentAt = Instant.now();
        this.errorMessage = null;
    }

    /** 재시도 대상이 되는 실패. */
    public void markFailed(String errorMessage) {
        this.status = DeliveryStatus.FAILED;
        this.errorMessage = errorMessage;
    }

    /** 반송. 재시도하지 않는다 — 유효하지 않은 주소로 계속 보내면 발신 평판이 나빠진다. */
    public void markBounced(String errorMessage) {
        this.status = DeliveryStatus.BOUNCED;
        this.errorMessage = errorMessage;
    }
}
