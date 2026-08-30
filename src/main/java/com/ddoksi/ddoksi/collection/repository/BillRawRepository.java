package com.ddoksi.ddoksi.collection.repository;

import com.ddoksi.ddoksi.collection.entity.BillRaw;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillRawRepository extends JpaRepository<BillRaw, Long> {

    /**
     * 해당 법안의 가장 최근 수집 원문을 찾는다.
     * 저장된 해시와 비교해 내용이 그대로면 재파싱을 건너뛰기 위한 것이다.
     */
    Optional<BillRaw> findFirstByExternalBillIdOrderByCollectedAtDesc(String externalBillId);
}
