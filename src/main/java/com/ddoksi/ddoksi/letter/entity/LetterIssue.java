package com.ddoksi.ddoksi.letter.entity;

import com.ddoksi.ddoksi.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 레터 호차 (월 1회).
 *
 * issueMonth 는 해당 월의 1일을 저장한다 (예: 2026-09-01).
 * DB 의 UNIQUE 제약이 같은 달에 두 번 발행되는 것을 막는다.
 */
@Getter
@Entity
@Table(name = "letter_issue")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LetterIssue extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issue_month", nullable = false, unique = true)
    private LocalDate issueMonth;

    @Column(name = "subject", nullable = false, columnDefinition = "text")
    private String subject;

    /**
     * 발송 시점에 확정된 본문.
     * 나중에 법안 데이터가 바뀌어도 "실제로 보낸 내용" 은 그대로 남아야 하므로
     * 렌더링 결과를 저장한다.
     */
    @Column(name = "body_html", columnDefinition = "text")
    private String bodyHtml;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LetterIssueStatus status;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Builder
    private LetterIssue(LocalDate issueMonth, String subject, String bodyHtml) {
        this.issueMonth = issueMonth;
        this.subject = subject;
        this.bodyHtml = bodyHtml;
        this.status = LetterIssueStatus.DRAFT;
    }

    /** 발송을 시작한다. 중간에 죽어도 이 상태가 남아 재개 지점을 알 수 있다. */
    public void startSending() {
        this.status = LetterIssueStatus.SENDING;
        this.publishedAt = Instant.now();
    }

    public void completeSending() {
        this.status = LetterIssueStatus.SENT;
    }
}
