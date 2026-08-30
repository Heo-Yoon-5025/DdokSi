package com.ddoksi.ddoksi.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import org.springframework.data.annotation.LastModifiedDate;

/**
 * 생성 시각과 수정 시각을 모두 갖는 엔티티의 공통 상위 클래스.
 * 값이 갱신되는 테이블이 여기에 해당한다.
 */
@Getter
@MappedSuperclass
public abstract class BaseTimeEntity extends BaseCreatedAtEntity {

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
