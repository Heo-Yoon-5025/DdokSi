package com.ddoksi.ddoksi.bill.repository;

import com.ddoksi.ddoksi.bill.entity.AnalysisBatch;
import com.ddoksi.ddoksi.bill.entity.AnalysisBatchStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisBatchRepository extends JpaRepository<AnalysisBatch, Long> {

    /**
     * 아직 수거하지 않은 배치를 제출 순서대로.
     *
     * <p>배치 실행의 첫 동작이 이 조회다. 새 배치를 제출하기 전에 먼저 이것을 비워야
     * 이미 값을 치른 결과를 버리고 같은 법안을 다시 제출하는 일이 생기지 않는다.
     */
    List<AnalysisBatch> findByStatusOrderBySubmittedAtAsc(AnalysisBatchStatus status);

    /** 같은 배치를 두 번 기록하지 않았는지 확인하는 용도. */
    Optional<AnalysisBatch> findByProviderBatchId(String providerBatchId);
}
