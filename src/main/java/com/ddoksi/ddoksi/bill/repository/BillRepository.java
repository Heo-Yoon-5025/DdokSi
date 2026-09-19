package com.ddoksi.ddoksi.bill.repository;

import com.ddoksi.ddoksi.bill.entity.Bill;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * AI 분석이 필요한 법안을 id 순서로 가져온다.
     *
     * <p><b>왜 not exists 가 아니라 left join 인가.</b> "분석 행이 없는 법안" 만 고르면
     * 지난 실행에서 실패해 FAILED 로 남은 행이 '있음' 으로 잡혀 재시도 대상에서
     * 영원히 빠진다. 예외도 안 나고 로그도 안 남아서, 그 법안은 조용히 분석 없이 방치된다.
     * 그래서 행의 존재가 아니라 <b>상태</b>를 본다.
     *
     * <p>원문이 바뀌어 재생성이 필요한 경우는 여기서 거르지 않는다. 판정 기준인
     * source_hash 는 법안마다 값이 달라 SQL 한 문장으로 비교할 수 없다.
     * 1차로 넓게 가져온 뒤 {@code BillAnalysis.isStale()} 로 코드에서 가린다.
     *
     * <p>제안이유가 없거나 빈 법안(41건)은 제외한다. 입력이 없으면 분석할 것도 없고,
     * 국회가 나중에 본문을 올리면 조건이 저절로 참이 되어 다음 실행에 잡힌다.
     *
     * <p>afterId 커서를 쓰는 이유는 제안이유 수집기와 같다 — 항상 "앞에서부터 N건" 을 읽으면
     * 저장에 실패하는 한 건 때문에 배치가 같은 자리를 맴돈다.
     *
     * @param version 대상 프롬프트 버전
     * @param afterId 이 id 보다 큰 법안만. 처음에는 0 을 넘긴다
     */
    @Query("""
            select b from Bill b
              join BillSummary s on s.bill = b
              left join BillAnalysis a on a.bill = b and a.promptVersion = :version
            where b.id > :afterId
              and s.summary is not null and s.summary <> ''
              and (a.id is null or a.status = com.ddoksi.ddoksi.bill.entity.AnalysisStatus.FAILED)
            order by b.id
            """)
    List<Bill> findBillsNeedingAnalysis(@Param("version") String version,
                                        @Param("afterId") Long afterId,
                                        Limit limit);
}
