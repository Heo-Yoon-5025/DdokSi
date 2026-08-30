package com.ddoksi.ddoksi.bill.repository;

import com.ddoksi.ddoksi.bill.entity.BillStatusHistory;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillStatusHistoryRepository extends JpaRepository<BillStatusHistory, Long> {

    /**
     * 특정 기간에 상태가 바뀐 이력을 조회한다.
     * 월간 레터의 "이번 달에 이렇게 바뀌었습니다" 섹션이 여기서 나온다.
     */
    List<BillStatusHistory> findByChangedAtBetweenOrderByChangedAtDesc(Instant from, Instant to);
}
