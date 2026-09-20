package com.ddoksi.ddoksi.bill.repository;

import com.ddoksi.ddoksi.bill.entity.AnalysisBatchItem;
import com.ddoksi.ddoksi.bill.entity.AnalysisBatchItemId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisBatchItemRepository
        extends JpaRepository<AnalysisBatchItem, AnalysisBatchItemId> {

    /** 한 배치에 담아 보낸 법안 전부. 수거 단계에서 {@code billId → sourceHash} 표를 만든다. */
    @Query("select i from AnalysisBatchItem i where i.id.batchId = :batchId")
    List<AnalysisBatchItem> findByBatchId(@Param("batchId") Long batchId);
}
