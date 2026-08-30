package com.ddoksi.ddoksi.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 생성 시각만 갖는 엔티티의 공통 상위 클래스.
 *
 * 이력성 테이블(한 번 쌓이면 수정하지 않는 데이터)이 여기에 해당한다.
 *
 * 시각 타입으로 Instant 를 쓰는 이유: DB 컬럼이 timestamptz 이므로
 * "타임존이 붙은 절대 시각" 을 그대로 표현하는 타입이 맞다.
 * LocalDateTime 을 쓰면 타임존 정보가 사라져 서버 TZ 에 따라 의미가 달라진다.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseCreatedAtEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
