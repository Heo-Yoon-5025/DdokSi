package com.ddoksi.ddoksi.collection;

import static org.assertj.core.api.Assertions.assertThat;

import com.ddoksi.ddoksi.bill.entity.Bill;
import com.ddoksi.ddoksi.bill.entity.BillSummary;
import com.ddoksi.ddoksi.bill.repository.BillRepository;
import com.ddoksi.ddoksi.bill.repository.BillSummaryRepository;
import com.ddoksi.ddoksi.collection.api.AssemblyApiClient;
import com.ddoksi.ddoksi.collection.api.AssemblyApiProperties;
import com.ddoksi.ddoksi.collection.api.AssemblyPage;
import com.ddoksi.ddoksi.support.BillFixtures;
import com.ddoksi.ddoksi.support.DatabaseCleaner;
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
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * 제안이유 수집 통합 테스트 — 실제 국회 API 를 호출하고 테스트 DB 에 커밋한다.
 *
 * <p>{@link BillCollectionServiceTest} 와 같은 이유로 @Transactional 을 붙이지 않는다.
 * 묶음 단위 커밋이 이 배치의 설계 핵심이라, 트랜잭션으로 감싸면 검증하려는 동작이 사라진다.
 * 대신 매 테스트 시작 시 테스트 DB 를 비우고 픽스처를 다시 심는다.
 *
 * <p>픽스처의 의안번호는 실제 국회 데이터다. 지어낸 번호를 쓰면 응답이 전부
 * INFO-200(데이터 없음)이 되어 본문 저장 경로를 한 줄도 지나지 않는다.
 */
@ActiveProfiles("test")
@SpringBootTest
class BillSummaryCollectionServiceTest {

    /** 한 번에 받아올 표본 크기. 법안 1건당 API 1회이므로 작게 잡는다. */
    private static final int SAMPLE = 10;

    @Autowired private BillSummaryCollectionService summaryService;
    @Autowired private BillSummaryPersister persister;
    @Autowired private BillSummaryRepository summaryRepository;
    @Autowired private BillRepository billRepository;
    @Autowired private AssemblyApiClient apiClient;
    @Autowired private AssemblyApiProperties properties;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private BillFixtures fixtures;

    @BeforeEach
    void prepare() {
        Assumptions.assumeTrue(properties.hasKey(), "인증키가 없어 건너뜁니다 (.env 확인)");
        cleaner.clean();
        fixtures.seed();
    }

    @Test
    @DisplayName("본문 없는 법안을 채우고, 채운 법안은 다시 대상이 되지 않는다")
    void fillsMissingSummariesAndExcludesThemAfterward() {
        long targetsBefore = summaryRepository.countBillsWithoutSummary();
        assertThat(targetsBefore).isGreaterThanOrEqualTo(SAMPLE);

        SummaryResult result = summaryService.collectMissingSummaries(SAMPLE);

        // 표본 전부가 어떤 형태로든 판정되어야 한다 (저장했거나, 국회에 데이터가 없거나)
        assertThat(result.inserted() + result.notFound()).isEqualTo(SAMPLE);
        assertThat(result.inserted()).isPositive();
        assertThat(result.skipped()).isZero();

        // 저장된 행 수와 집계가 어긋나면 둘 중 하나가 거짓말을 하고 있는 것이다
        assertThat(summaryRepository.count()).isEqualTo(result.inserted());

        // 핵심: 채운 만큼 남은 대상이 줄어든다. 이 성질이 배치의 재개 가능성을 만든다.
        assertThat(summaryRepository.countBillsWithoutSummary())
                .isEqualTo(targetsBefore - result.inserted());
    }

    @Test
    @DisplayName("이어서 실행하면 앞서 채운 법안을 건너뛰고 다음 법안을 받는다")
    void resumesWithoutRefetching() {
        summaryService.collectMissingSummaries(SAMPLE);
        List<Long> firstRound = billIdsWithSummary();

        summaryService.collectMissingSummaries(SAMPLE);
        List<Long> secondRound = billIdsWithSummary();

        // 1회차에서 채운 법안은 그대로 남아 있고, 그 위에 새로 쌓인다
        assertThat(secondRound).containsAll(firstRound);
        assertThat(secondRound).hasSizeGreaterThan(firstRound.size());
        // 같은 법안에 행이 두 개 생기지 않는다
        assertThat(secondRound).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("같은 법안을 다시 저장해도 행이 늘지 않는다 - 멱등성")
    void staysIdempotentOnRepeatedPersist() {
        summaryService.collectMissingSummaries(3);

        List<BillSummary> existing = summaryRepository.findAllWithBill(Limit.of(3));
        Assumptions.assumeTrue(!existing.isEmpty(), "저장된 본문이 없어 건너뜁니다");

        List<FetchedSummary> replay = new ArrayList<>();
        for (BillSummary s : existing) {
            Bill bill = s.getBill();
            replay.add(new FetchedSummary(
                    bill.getId(), bill.getBillNo(), s.getSummary(), s.getExternalBillId(), true));
        }

        long before = summaryRepository.count();
        SummaryResult result = persister.persistChunk(replay);

        assertThat(result.inserted()).isZero();
        assertThat(result.updated()).isZero();
        assertThat(result.unchanged()).isEqualTo(replay.size());
        assertThat(summaryRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("본문이 비어 있는 법안도 행을 남겨 다시 호출하지 않는다")
    void storesRowEvenWhenSummaryIsEmpty() {
        // 이 의안번호는 정상 응답(INFO-000)인데 SUMMARY 만 null 로 오는 것이 확인된 건이다.
        AssemblyPage page = apiClient.fetchBillSummary(BillFixtures.BILL_NO_WITH_EMPTY_SUMMARY);
        assertThat(page.rows()).hasSize(1);

        JsonNode row = page.rows().get(0);
        assertThat(row.path("SUMMARY").isNull()).isTrue();

        // 픽스처는 수십 건이라 여기서 걸러도 충분하다.
        // 이것 하나 때문에 운영 저장소에 조회 메서드를 늘리지 않는다.
        Bill bill = billRepository.findAll().stream()
                .filter(b -> BillFixtures.BILL_NO_WITH_EMPTY_SUMMARY.equals(b.getBillNo()))
                .findFirst()
                .orElseThrow();
        SummaryResult result = persister.persistChunk(List.of(new FetchedSummary(
                bill.getId(), bill.getBillNo(), null,
                row.path("BILL_ID").asString(""), true)));

        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.emptyContent()).isEqualTo(1);

        // 행이 남았으므로 다음 실행에서 이 법안은 대상에서 빠진다
        BillSummary stored = summaryRepository.findByBill(bill).orElseThrow();
        assertThat(stored.hasContent()).isFalse();
        assertThat(summaryRepository.findBillsWithoutSummary(0L, Limit.of(100)))
                .extracting(Bill::getBillNo)
                .doesNotContain(BillFixtures.BILL_NO_WITH_EMPTY_SUMMARY);
    }

    @Test
    @DisplayName("응답의 의안 ID 가 다르면 저장하지 않고 건너뛴다")
    void skipsWhenExternalBillIdMismatches() {
        List<Bill> candidates = summaryRepository.findBillsWithoutSummary(0L, Limit.of(1));
        assertThat(candidates).isNotEmpty();

        Bill target = candidates.get(0);
        long before = summaryRepository.count();

        // 엉뚱한 법안의 본문이 돌아온 상황을 흉내 낸다
        SummaryResult result = persister.persistChunk(List.of(new FetchedSummary(
                target.getId(), target.getBillNo(), "다른 법안의 본문", "PRC_전혀다른의안ID", true)));

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.inserted()).isZero();
        assertThat(summaryRepository.count()).isEqualTo(before);

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

    /** 본문이 저장된 법안 id 목록. 재개 동작을 비교하는 데 쓴다. */
    private List<Long> billIdsWithSummary() {
        return summaryRepository.findAllWithBill(Limit.of(1000)).stream()
                .map(s -> s.getBill().getId())
                .toList();
    }
}
