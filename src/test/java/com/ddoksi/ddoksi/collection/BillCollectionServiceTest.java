package com.ddoksi.ddoksi.collection;

import static org.assertj.core.api.Assertions.assertThat;

import com.ddoksi.ddoksi.bill.entity.Bill;
import com.ddoksi.ddoksi.bill.repository.BillRepository;
import com.ddoksi.ddoksi.bill.repository.BillStatusHistoryRepository;
import com.ddoksi.ddoksi.collection.api.AssemblyApiProperties;
import com.ddoksi.ddoksi.collection.entity.CollectionRunStatus;
import com.ddoksi.ddoksi.collection.repository.BillRawRepository;
import com.ddoksi.ddoksi.collection.repository.CollectionRunRepository;
import com.ddoksi.ddoksi.support.DatabaseCleaner;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 수집 배치 통합 테스트 — 실제 국회 API 를 호출하고 실제 DB 에 커밋한다.
 *
 * <p>@Transactional 을 붙이지 않는다. 페이지 단위 커밋이 이 배치의 설계 핵심이고,
 * 테스트를 트랜잭션으로 감싸면 그 커밋이 롤백되어 정작 검증하려는 동작을 확인할 수 없다.
 * 특히 "두 번 돌려도 이력이 중복되지 않는다"는 멱등성은 커밋된 상태를 다시 읽어야 검증된다.
 *
 * <p>커밋을 하므로 데이터가 남는다. 예전에는 그것을 그대로 두었지만, 개발 DB(ddoksi)에
 * 실수집분과 테스트 찌꺼기가 섞여 나중에 버그인지 테스트 탓인지 구분할 수 없었다.
 * 지금은 별도 테스트 DB(ddoksi_test)를 쓰고 각 테스트가 시작할 때 비운다.
 *
 * <p>전체 1만9천 건을 긁지 않도록 2페이지(200건)만 수집한다.
 */
@ActiveProfiles("test")
@SpringBootTest
class BillCollectionServiceTest {

    private static final int ASSEMBLY_AGE = 22;
    private static final int PAGES = 2;

    @Autowired private BillCollectionService collectionService;
    @Autowired private BillRepository billRepository;
    @Autowired private BillRawRepository billRawRepository;
    @Autowired private BillStatusHistoryRepository statusHistoryRepository;
    @Autowired private CollectionRunRepository runRepository;
    @Autowired private AssemblyApiProperties properties;
    @Autowired private DatabaseCleaner cleaner;

    @BeforeEach
    void prepare() {
        Assumptions.assumeTrue(properties.hasKey(), "인증키가 없어 건너뜁니다 (.env 확인)");
        // 앞선 테스트가 남긴 데이터가 건수 검증을 흔들지 않도록 매번 비운다
        cleaner.clean();
    }

    @Test
    @DisplayName("실제 수집 후 두 번째 실행에서 이력이 중복되지 않는다 - 멱등성")
    void collectsThenStaysIdempotent() {
        // --- 1회차 --------------------------------------------------------
        PageResult first = collectionService.collectMemberBills(ASSEMBLY_AGE, PAGES);

        assertThat(first.processed()).isEqualTo(PAGES * properties.pageSize());
        assertThat(first.skipped()).isZero();

        long billsAfterFirst = billRepository.count();
        long historiesAfterFirst = statusHistoryRepository.count();
        long rawsAfterFirst = billRawRepository.count();

        assertThat(billsAfterFirst).isGreaterThanOrEqualTo(first.inserted());
        // 신규 법안마다 최초 이력이 하나씩 생긴다
        assertThat(historiesAfterFirst).isGreaterThanOrEqualTo(first.inserted());
        // 신규/변경된 건에 대해 원문이 남는다
        assertThat(rawsAfterFirst).isGreaterThanOrEqualTo(first.inserted());

        // --- 2회차: 같은 범위를 다시 수집 -----------------------------------
        PageResult second = collectionService.collectMemberBills(ASSEMBLY_AGE, PAGES);

        assertThat(second.processed()).isEqualTo(first.processed());
        // 이미 저장된 것들이므로 신규는 없어야 한다
        assertThat(second.inserted()).isZero();
        // 대부분(사실상 전부)이 "변화 없음" 으로 분류된다
        assertThat(second.unchanged()).isEqualTo(second.processed() - second.statusChanged());

        // 핵심: 법안 건수가 늘지 않고, 이력이 중복으로 쌓이지 않는다
        assertThat(billRepository.count()).isEqualTo(billsAfterFirst);
        assertThat(statusHistoryRepository.count())
                .isEqualTo(historiesAfterFirst + second.statusChanged());
        // 변화 없는 건의 원문은 다시 저장하지 않는다 (매 실행마다 동일 원문이 쌓이면 안 된다)
        assertThat(billRawRepository.count())
                .isEqualTo(rawsAfterFirst + second.statusChanged());
    }

    @Test
    @DisplayName("실행 이력이 성공으로 기록되고 커서가 남는다")
    void recordsRunHistory() {
        collectionService.collectMemberBills(ASSEMBLY_AGE, 1);

        var run = runRepository.findFirstByJobNameAndStatusOrderByStartedAtDesc(
                BillCollectionService.JOB_NAME, CollectionRunStatus.SUCCESS);

        assertThat(run).isPresent();
        assertThat(run.get().getFinishedAt()).isNotNull();
        assertThat(run.get().getSuccessCount()).isPositive();
        // 커서에는 이번 수집에서 본 가장 최신 제안일이 남는다
        assertThat(run.get().getCursorValue()).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    @DisplayName("저장된 법안이 실제 국회 데이터의 모양을 갖는다")
    void storedBillsLookReal() {
        collectionService.collectMemberBills(ASSEMBLY_AGE, 1);

        List<Bill> bills = billRepository.findAll();
        assertThat(bills).isNotEmpty();

        Bill sample = bills.get(0);
        assertThat(sample.getExternalBillId()).startsWith("PRC_");
        assertThat(sample.getTitle()).isNotBlank();
        assertThat(sample.getAssemblyAge()).isEqualTo((short) ASSEMBLY_AGE);
        assertThat(sample.getProposedDate()).isNotNull();
        assertThat(sample.getDetailUrl()).contains("likms.assembly.go.kr");
        // 컬럼으로 뽑지 않은 필드가 extra 에 보존된다 (나중에 재수집 없이 쓸 수 있도록)
        assertThat(sample.getExtra()).containsKey("RST_MONA_CD");

        // 상태는 반드시 분류된다. UNKNOWN 이 섞이면 매핑 규칙에 구멍이 있다는 뜻이다.
        assertThat(bills).allSatisfy(bill ->
                assertThat(bill.getStatus()).isNotNull());
    }
}
