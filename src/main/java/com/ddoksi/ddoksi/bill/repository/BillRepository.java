package com.ddoksi.ddoksi.bill.repository;

import com.ddoksi.ddoksi.bill.entity.Bill;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillRepository extends JpaRepository<Bill, Long> {

    /** 수집 시 이미 저장된 법안인지 판정하는 기준. */
    Optional<Bill> findByExternalBillId(String externalBillId);

    /**
     * 여러 건을 한 번에 조회한다.
     *
     * 수집 배치는 한 페이지(수백 건)를 처리하므로, 건별로 findByExternalBillId 를 부르면
     * 쿼리가 건수만큼 나간다. 페이지 단위로 미리 가져와 메모리에서 대조한다.
     */
    List<Bill> findAllByExternalBillIdIn(List<String> externalBillIds);
}
