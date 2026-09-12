package com.ddoksi.ddoksi.bill.repository;

import com.ddoksi.ddoksi.bill.entity.Bill;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/**
 * 목록 조회는 {@link JpaSpecificationExecutor#findAll} 과 {@link BillSpecifications} 를 조합해 쓴다.
 * 필터가 선택적이라 조건을 동적으로 구성해야 하고, 그래야 인덱스도 제대로 활용된다.
 */
public interface BillRepository extends JpaRepository<Bill, Long>, JpaSpecificationExecutor<Bill> {

    /** 수집 시 이미 저장된 법안인지 판정하는 기준. */
    Optional<Bill> findByExternalBillId(String externalBillId);

    /**
     * 여러 건을 한 번에 조회한다.
     *
     * 수집 배치는 한 페이지(수백 건)를 처리하므로, 건별로 findByExternalBillId 를 부르면
     * 쿼리가 건수만큼 나간다. 페이지 단위로 미리 가져와 메모리에서 대조한다.
     */
    List<Bill> findAllByExternalBillIdIn(List<String> externalBillIds);

    /** 필터 UI 에 채울 상임위 목록. 법안이 실제로 존재하는 위원회만 내려준다. */
    @Query("select distinct b.committeeName from Bill b "
            + "where b.committeeName is not null order by b.committeeName")
    List<String> findDistinctCommitteeNames();
}
