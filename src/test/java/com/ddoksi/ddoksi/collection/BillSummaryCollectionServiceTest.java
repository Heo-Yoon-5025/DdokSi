package com.ddoksi.ddoksi.collection;

import static org.assertj.core.api.Assertions.assertThat;

import com.ddoksi.ddoksi.bill.entity.Bill;
import com.ddoksi.ddoksi.bill.entity.BillSummary;
import com.ddoksi.ddoksi.bill.repository.BillRepository;
import com.ddoksi.ddoksi.bill.repository.BillSummaryRepository;
import com.ddoksi.ddoksi.collection.api.AssemblyApiProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;

/**
 * 제안이유 수집 통합 테스트 — 실제 국회 API 를 호출하고 실제 DB 에 커밋한다.
 *
 * <p>{@link BillCollectionServiceTest} 와 같은 이유로 @Transactional 을 붙이지 않는다.
 * 묶음 단위 커밋이 이 배치의 설계 핵심이라, 테스트를 트랜잭션으로 감싸면 검증하려는 동작이 사라진다.
 *
 * <p>법안 한 건당 API 를 한 번씩 부르므로 표본을 작게 잡는다.
 */
@SpringBootTest
class BillSummaryCollectionServiceTest {

    /** 표본 크기. 늘리면 그만큼 API 호출이 늘어난다. */
    private static final int SAMPLE = 15;

    @Autowired private BillSummaryCollectionService summaryService;
    @Autowired private BillSummaryPersister persister;
    @Autowired private BillSummaryRepository summaryRepository;
    @Autowired private BillRepository billRepository;
    @Autowired private AssemblyApiProperties properties;

    @BeforeEach
    void requireData() {
        Assumptions.assumeTrue(properties.hasKey(), "인증키가 없어 건너뜁니다 (.env 확인)");
        Assumptions.assumeTrue(billRepository.count() > 0,
                "수집된 법안이 없어 건너뜁니다 (먼저 목록 백필을 실행하세요)");
    }

    @Test
    @DisplayName("본문 없는 법안을 채우고, 채운 법안은 다시 대상이 되지 않는다")
    void fillsMissingSummariesAndExcludesThemAfterward() {
        Assumptions.assumeTrue(summaryRepository.countBillsWithoutSummary() >= SAMPLE,
                "본문 없는 법안이 표본 수보다 적어 건너뜁니다");

        long before = summaryRepository.count();

        SummaryResult result = summaryService.collectMissingSummaries(SAMPLE);

        // 저장된 행 수와 집계가 어긋나면 어느 한쪽이 거짓말을 하고 있는 것이다
        assertThat(summaryRepository.count() - before).isEqualTo(result.inserted());
        assertThat(result.inserted()).isPositive();
        assertThat(result.skipped()).isZero();

        // 대부분은 본문이 실제로 채워져야 한다. 빈 본문이 섞이는 것은 정상이지만
        // 전부 비어 있다면 응답 필드를 잘못 읽고 있다는 신호다.
        assertThat(result.emptyContent()).isLessThan(result.inserted());
    }

    @Test
    @DisplayName("같은 법안을 다시 저장해도 행이 늘지 않는다 - 멱등성")
    void staysIdempotentOnRepeatedPersist() {
        // 이미 본문이 있는 법안을 골라 같은 내용을 다시 저장해 본다.
        // API 를 다시 부르지 않고 저장 단계만 검증하므로 호출을 낭비하지 않는다.
        List<BillSummary> existing = summaryRepository.findAllWithBill(Limit.of(5));
        Assumptions.assumeTrue(!existing.isEmpty(),
                "저장된 본문이 없어 건너뜁니다 (앞의 테스트를 먼저 실행하세요)");

        List<FetchedSummary> replay = new ArrayList<>();
        for (BillSummary s : existing) {
            Bill bill = s.getBill();
            replay.add(new FetchedSummary(
                    bill.getId(), bill.getBillNo(), s.getSummary(), s.getExternalBillId(), true));
        }

        long before = summaryRepository.count();
        SummaryResult result = persister.persistChunk(replay);

        // 내용이 같으므로 신규도 갱신도 없어야 한다
        assertThat(result.inserted()).isZero();
        assertThat(result.updated()).isZero();
        assertThat(result.unchanged()).isEqualTo(replay.size());
        assertThat(summaryRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("응답의 의안 ID 가 다르면 저장하지 않고 건너뛴다")
    void skipsWhenExternalBillIdMismatches() {
        List<Bill> candidates = summaryRepository.findBillsWithoutSummary(0L, Limit.of(1));
        Assumptions.assumeTrue(!candidates.isEmpty(), "본문 없는 법안이 없어 건너뜁니다");

        Bill target = candidates.get(0);
        long before = summaryRepository.count();

        // 엉뚱한 법안의 본문이 돌아온 상황을 흉내 낸다
        SummaryResult result = persister.persistChunk(List.of(new FetchedSummary(
                target.getId(), target.getBillNo(), "다른 법안의 본문", "PRC_전혀다른의안ID", true)));

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.inserted()).isZero();
        assertThat(summaryRepository.count()).isEqualTo(before);

        // 잘못된 본문이 붙지 않았는지 직접 확인한다
        Optional<BillSummary> stored = summaryRepository.findByBill(target);
        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 의안번호는 오류가 아니라 데이터 없음으로 처리된다")
    void treatsMissingDataAsNotFound() {
        SummaryResult result = persister.persistChunk(
                List.of(FetchedSummary.notFound(-1L, "9999999")));

        assertThat(result.notFound()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(result.inserted()).isZero();
    }
}
