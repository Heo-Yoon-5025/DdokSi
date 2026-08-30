package com.ddoksi.ddoksi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화.
 *
 * 엔티티의 createdAt / updatedAt 을 애플리케이션이 자동으로 채운다.
 * DB 컬럼에도 DEFAULT now() 가 걸려 있지만, 그것은 JPA 를 거치지 않는 직접 INSERT
 * (배치의 벌크 삽입 등)를 위한 안전망이다.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
