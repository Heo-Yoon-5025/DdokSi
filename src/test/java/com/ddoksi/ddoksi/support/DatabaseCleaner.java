package com.ddoksi.ddoksi.support;

import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 테스트 사이에 DB 를 비운다.
 *
 * <p>수집 배치 테스트는 묶음 단위 커밋이 설계의 핵심이라 트랜잭션 롤백으로 감쌀 수 없다.
 * 그래서 테스트가 남긴 데이터를 다음 테스트가 보지 않도록 여기서 직접 지운다.
 * 이것이 없으면 실행을 거듭할수록 데이터가 쌓여 "몇 건이 나와야 한다" 는 검증이 무너진다.
 *
 * <p><b>운영 DB 를 지우는 사고를 막는 것이 이 클래스의 가장 중요한 책임이다.</b>
 * 접속 URL 이 테스트 DB 가 아니면 무조건 예외를 던지고 아무것도 지우지 않는다.
 * 설정 실수 하나로 수집해 둔 1만9천 건이 날아가는 일은 되돌릴 수 없다.
 */
@Component
public class DatabaseCleaner {

    /** 이 문자열이 접속 URL 에 없으면 삭제를 거부한다. */
    private static final String REQUIRED_DB_MARKER = "ddoksi_test";

    /**
     * 지울 테이블. 순서는 신경 쓰지 않아도 된다 — CASCADE 로 함께 지우고,
     * 한 문장으로 TRUNCATE 하므로 외래키 순서 문제가 생기지 않는다.
     */
    private static final String TABLES = String.join(", ",
            "bill_summary", "bill_status_history", "bill_analysis",
            "analysis_batch_item", "analysis_batch",
            "bill_raw", "bill", "collection_run",
            "letter_delivery", "letter_issue",
            "subscription_committee", "subscriber");

    private final EntityManager entityManager;
    private final DataSource dataSource;

    public DatabaseCleaner(EntityManager entityManager, DataSource dataSource) {
        this.entityManager = entityManager;
        this.dataSource = dataSource;
    }

    /** 모든 도메인 테이블을 비우고 시퀀스를 1 부터 다시 시작한다. */
    @Transactional
    public void clean() {
        verifyTargetIsTestDatabase();
        // RESTART IDENTITY 를 함께 주어 테스트마다 id 가 같은 값에서 시작하게 한다.
        entityManager.createNativeQuery(
                "TRUNCATE TABLE " + TABLES + " RESTART IDENTITY CASCADE").executeUpdate();
    }

    /** 접속 대상이 테스트 DB 인지 확인한다. 아니면 즉시 중단한다. */
    private void verifyTargetIsTestDatabase() {
        String url;
        try (Connection connection = dataSource.getConnection()) {
            url = connection.getMetaData().getURL();
        } catch (SQLException e) {
            throw new IllegalStateException("접속 대상을 확인할 수 없어 삭제를 중단합니다", e);
        }

        if (url == null || !url.contains(REQUIRED_DB_MARKER)) {
            throw new IllegalStateException(
                    "테스트 DB 가 아니므로 삭제를 거부합니다. url=" + url
                            + " (기대: '" + REQUIRED_DB_MARKER + "' 를 포함하는 주소)"
                            + " — @ActiveProfiles(\"test\") 가 빠졌는지 확인하세요.");
        }
    }
}
