package com.ddoksi.ddoksi.bill.repository;

import com.ddoksi.ddoksi.bill.entity.Bill;
import com.ddoksi.ddoksi.bill.entity.BillSummary;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BillSummaryRepository extends JpaRepository<BillSummary, Long> {

    Optional<BillSummary> findByBill(Bill bill);

    /**
     * 아직 본문을 받아오지 않은 법안을 id 순서로 가져온다.
     *
     * <p><b>단순히 "본문 없는 법안 앞에서부터 N건" 을 반복해 읽지 않는 이유:</b>
     * 그렇게 하면 어떤 법안이 오류로 저장에 실패했을 때 다음 조회에서 또 맨 앞에 나타나
     * 배치가 같은 건을 무한히 재시도한다. 마지막으로 본 id 를 커서로 넘겨
     * 항상 앞으로만 나아가게 한다.
     *
     * @param afterId 이 id 보다 큰 법안만 대상으로 한다. 처음에는 0 을 넘긴다.
     */
    @Query("""
            select b from Bill b
            where b.id > :afterId
              and not exists (select 1 from BillSummary s where s.bill.id = b.id)
            order by b.id
            """)
    List<Bill> findBillsWithoutSummary(@Param("afterId") Long afterId, Limit limit);

    /**
     * 본문과 법안을 함께 읽는다.
     *
     * <p>{@code bill} 은 지연 로딩이라 트랜잭션 밖에서 꺼내면 초기화에 실패한다.
     * 둘 다 필요한 자리에서는 이 메서드로 한 번에 읽는다.
     *
     * <p><b>현재 테스트에서만 사용 중이다.</b> 요약 화면과 뉴스레터가 본문과 법안 정보를
     * 함께 읽어야 해서 운영 사용처가 생길 가능성이 높다고 보고 남겨둔다.
     * 핵심 기능이 붙은 뒤에도 운영에서 쓰이지 않으면 테스트 쪽으로 옮긴다.
     */
    @Query("select s from BillSummary s join fetch s.bill order by s.id")
    List<BillSummary> findAllWithBill(Limit limit);

    /**
     * 여러 법안의 본문을 한 번에 읽는다.
     *
     * <p>배치 분석은 한 번에 수천 건을 제출하므로 법안마다 {@link #findByBill} 을 부르면
     * 제출 준비에만 수천 번의 왕복이 생긴다. 엔티티가 아니라 {@code (법안 id, 본문)} 쌍만
     * 돌려주는 이유는 필요한 것이 그것뿐이고, 지연 로딩 프록시를 트랜잭션 밖으로
     * 들고 나가지 않기 위해서다.
     *
     * <p>본문이 비어 있는 법안은 여기서 걸러진다. 분석할 입력이 없으면 보낼 이유도 없다.
     */
    @Query("""
            select s.bill.id, s.summary from BillSummary s
            where s.bill.id in :billIds
              and s.summary is not null and s.summary <> ''
            """)
    List<Object[]> findSummaryTextsByBillIds(@Param("billIds") Collection<Long> billIds);

    /** 본문 수집이 얼마나 남았는지 로그와 운영 확인에 쓴다. */
    @Query("""
            select count(b) from Bill b
            where not exists (select 1 from BillSummary s where s.bill.id = b.id)
            """)
    long countBillsWithoutSummary();
}
