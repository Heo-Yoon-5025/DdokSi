package com.ddoksi.ddoksi.bill.repository;

import com.ddoksi.ddoksi.bill.entity.Bill;
import com.ddoksi.ddoksi.bill.entity.BillAnalysis;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BillAnalysisRepository extends JpaRepository<BillAnalysis, Long> {

    /**
     * 한 법안의 특정 버전 분석.
     *
     * <p>{@code UNIQUE (bill_id, prompt_version)} 이라 결과는 최대 한 건이다.
     * 저장 경로(신규/재시도/재생성)를 가르는 판단이 전부 이 조회에서 시작된다.
     */
    Optional<BillAnalysis> findByBillAndPromptVersion(Bill bill, String promptVersion);

    /** 배치 진행 상황 로그용. 아직 성공하지 못한 법안이 얼마나 남았는지 센다. */
    @Query("""
            select count(b) from Bill b
              join BillSummary s on s.bill = b
            where s.summary is not null and s.summary <> ''
              and not exists (
                  select 1 from BillAnalysis a
                  where a.bill = b
                    and a.promptVersion = :version
                    and a.status = com.ddoksi.ddoksi.bill.entity.AnalysisStatus.SUCCESS)
            """)
    long countBillsNeedingAnalysis(@Param("version") String version);
}
