package com.ddoksi.ddoksi.bill.repository;

import com.ddoksi.ddoksi.bill.entity.Bill;
import com.ddoksi.ddoksi.bill.entity.BillStatus;
import org.springframework.data.jpa.domain.Specification;

/**
 * 법안 조회 조건.
 *
 * <p>{@code (:param is null or 컬럼 = :param)} 형태의 단일 쿼리를 쓰지 않는 이유가 두 가지 있다.
 *
 * <ol>
 *   <li><b>타입 추론 실패</b> — 파라미터가 null 이면 JDBC 가 타입 없는 NULL 을 보내고,
 *       PostgreSQL 이 {@code lower(bytea)} 처럼 엉뚱한 타입으로 추론해 실행 자체가 실패한다.
 *   <li><b>인덱스를 못 쓴다</b> — 조건이 실행 시점에야 정해지므로 플래너가
 *       {@code (status, proposed_date)} 인덱스를 활용할 수 없다.
 * </ol>
 *
 * <p>Specification 은 실제로 지정된 조건만 SQL 에 넣는다. 상태 필터만 준 요청은
 * {@code where status = ?} 만 나가므로 인덱스를 그대로 탄다.
 */
public final class BillSpecifications {

    private BillSpecifications() {
    }

    public static Specification<Bill> hasStatus(BillStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Bill> inCommittee(String committeeName) {
        return (root, query, cb) -> cb.equal(root.get("committeeName"), committeeName);
    }

    /**
     * 법안명 부분 일치.
     *
     * 앞뒤 와일드카드라 B-tree 인덱스를 타지 못한다. 현재 2만 건 규모에서는 문제되지 않지만,
     * 데이터가 커지면 pg_trgm + GIN 인덱스로 바꿔야 한다.
     */
    public static Specification<Bill> titleContains(String keyword) {
        return (root, query, cb) -> cb.like(
                cb.lower(root.get("title")),
                "%" + keyword.toLowerCase() + "%");
    }
}
