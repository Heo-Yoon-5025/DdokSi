package com.ddoksi.ddoksi.bill.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ddoksi.ddoksi.bill.entity.AnalysisBatch;
import com.ddoksi.ddoksi.bill.entity.AnalysisBatchStatus;
import com.ddoksi.ddoksi.bill.entity.AnalysisStatus;
import com.ddoksi.ddoksi.bill.entity.Bill;
import com.ddoksi.ddoksi.bill.entity.BillAnalysis;
import com.ddoksi.ddoksi.bill.entity.BillSummary;
import com.ddoksi.ddoksi.bill.repository.AnalysisBatchRepository;
import com.ddoksi.ddoksi.bill.repository.BillAnalysisRepository;
import com.ddoksi.ddoksi.bill.repository.BillRepository;
import com.ddoksi.ddoksi.bill.repository.BillSummaryRepository;
import com.ddoksi.ddoksi.support.BillFixtures;
import com.ddoksi.ddoksi.support.DatabaseCleaner;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 배치 분석의 오케스트레이션.
 *
 * <p><b>이 테스트가 지키는 것은 돈이다.</b> 배치는 제출하는 순간 과금이 확정되고 결과는
 * 나중에 온다. 그 시차 때문에 동기 경로에는 없던 실패 방식이 둘 생긴다.
 *
 * <ol>
 *   <li>미수거 배치를 둔 채 새로 제출하면 같은 법안을 두 번 낸다. 대상 조회는 "분석 행이
 *       없는 법안" 을 고르는데, 제출 중인 법안에는 아직 분석 행이 없기 때문이다.</li>
 *   <li>결과는 순서가 보장되지 않는다. 위치로 맞추면 분석이 엉뚱한 법안에 붙는다 —
 *       예외도 안 나고 값도 다 차 있어서, 읽어보기 전에는 아무도 모른다.</li>
 * </ol>
 *
 * <p>Claude 를 부르지 않는다. 검증 대상은 호출 순서와 매칭 규칙이지 모델 응답이 아니다.
 * 실제로 부르면 테스트 한 번에 돈이 나가고 결과도 매번 달라진다.
 */
@ActiveProfiles("test")
@SpringBootTest
class BillAnalysisBatchServiceTest {

    /** {@code application.properties} 의 {@code ddoksi.analysis.prompt-version} 과 같아야 한다. */
    private static final String VERSION = "v1";

    @MockitoBean private ClaudeBatchAnalysisClient batchClient;

    @Autowired private BillAnalysisBatchService service;
    @Autowired private BillRepository billRepository;
    @Autowired private BillSummaryRepository summaryRepository;
    @Autowired private BillAnalysisRepository analysisRepository;
    @Autowired private AnalysisBatchRepository batchRepository;
    @Autowired private AnalysisBatchRecorder recorder;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private BillFixtures fixtures;

    private List<Bill> bills;

    @BeforeEach
    void prepare() {
        cleaner.clean();
        bills = fixtures.seed().stream()
                .sorted(Comparator.comparing(Bill::getId))
                .toList();
        // 분석 대상이 되려면 제안이유가 있어야 한다. 픽스처는 법안만 심는다.
        for (Bill bill : bills) {
            summaryRepository.save(BillSummary.builder()
                    .bill(bill)
                    .summary(bill.getTitle() + " 의 제안이유 본문")
                    .externalBillId(bill.getExternalBillId())
                    .build());
        }
    }

    @Test
    @DisplayName("제출 → 기록 → 수거 → 저장이 한 번에 돈다")
    void submitsPollsAndPersists() {
        when(batchClient.submit(any())).thenReturn("msgbatch_01");
        when(batchClient.awaitEnd(anyString(), any(), any())).thenReturn(true);
        stubResults(2, false);

        AnalysisChunkResult result = service.runBackfill(2);

        assertThat(result.inserted()).isEqualTo(2);
        assertThat(analysisRepository.count()).isEqualTo(2);

        AnalysisBatch batch = batchRepository.findByProviderBatchId("msgbatch_01").orElseThrow();
        assertThat(batch.getStatus()).isEqualTo(AnalysisBatchStatus.COLLECTED);
        assertThat(batch.getRequestCount()).isEqualTo(2);
        assertThat(batch.getSucceededCount()).isEqualTo(2);
        assertThat(batch.getMissingCount()).isZero();
    }

    @Test
    @DisplayName("결과 순서가 뒤집혀도 custom_id 로 제 법안에 붙는다")
    void matchesByCustomIdNotPosition() {
        when(batchClient.submit(any())).thenReturn("msgbatch_01");
        when(batchClient.awaitEnd(anyString(), any(), any())).thenReturn(true);
        stubResults(2, true);

        service.runBackfill(2);

        // 역순으로 돌려줬으므로 위치로 맞췄다면 두 법안의 분석이 서로 뒤바뀐다.
        for (Bill bill : bills.subList(0, 2)) {
            BillAnalysis analysis = analysisRepository
                    .findByBillAndPromptVersion(bill, VERSION).orElseThrow();
            assertThat(analysis.getHook()).isEqualTo(hookOf(bill.getId()));
        }
    }

    @Test
    @DisplayName("미수거 배치가 남아 있으면 새 배치를 제출하지 않는다 (이중 과금 차단)")
    void refusesToSubmitWhileAnotherBatchIsOutstanding() {
        // 지난 실행이 제출만 해두고 죽은 상황을 만든다.
        recorder.record(null, "msgbatch_old", VERSION, "claude-opus-5",
                List.of(new PendingAnalysis(bills.get(0).getId(), "해시", "메시지")));
        // 그 배치는 아직 처리 중이다.
        when(batchClient.awaitEnd(anyString(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.runBackfill(2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이중 과금");

        verify(batchClient, never()).submit(any());
        assertThat(batchRepository.findByProviderBatchId("msgbatch_old").orElseThrow().getStatus())
                .isEqualTo(AnalysisBatchStatus.SUBMITTED);
    }

    @Test
    @DisplayName("돌아오지 않은 요청은 누락으로 집계되고 분석 행이 남지 않는다")
    void countsMissingResults() {
        when(batchClient.submit(any())).thenReturn("msgbatch_01");
        when(batchClient.awaitEnd(anyString(), any(), any())).thenReturn(true);
        // 2건을 보냈는데 1건만 돌아온다 (취소·만료된 요청이 이렇게 보인다).
        stubResults(1, false);

        service.runBackfill(2);

        AnalysisBatch batch = batchRepository.findByProviderBatchId("msgbatch_01").orElseThrow();
        assertThat(batch.getMissingCount()).isEqualTo(1);
        // 분석 행이 없어야 다음 실행의 대상 조회에 다시 걸린다.
        assertThat(analysisRepository.count()).isEqualTo(1);
        assertThat(analysisRepository
                .findByBillAndPromptVersion(bills.get(1), VERSION)).isEmpty();
    }

    @Test
    @DisplayName("실패한 건도 행으로 남아 다음 실행이 재시도할 수 있다")
    void persistsFailuresAsRows() {
        when(batchClient.submit(any())).thenReturn("msgbatch_01");
        when(batchClient.awaitEnd(anyString(), any(), any())).thenReturn(true);
        stubFailure();

        service.runBackfill(1);

        BillAnalysis analysis = analysisRepository
                .findByBillAndPromptVersion(bills.get(0), VERSION).orElseThrow();
        assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(analysis.getErrorMessage()).contains("expired");
    }

    /**
     * 앞에서 {@code count} 건의 결과를 돌려주도록 클라이언트를 흉내 낸다.
     *
     * @param reversed 참이면 제출 순서의 역순으로 넘긴다
     */
    private void stubResults(int count, boolean reversed) {
        when(batchClient.streamResults(anyString(), any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<AnalyzedBill> consumer = invocation.getArgument(2);

            List<Bill> targets = new ArrayList<>(bills.subList(0, count));
            if (reversed) {
                targets = targets.reversed();
            }
            for (Bill bill : targets) {
                consumer.accept(AnalyzedBill.success(
                        bill.getId(), "해시-" + bill.getId(), "claude-opus-5",
                        new AnalysisResult(hookOf(bill.getId()), "요약", "예시", "배경",
                                List.of("교통·안전"))));
            }
            return targets.size();
        });
    }

    /** 만료된 요청 하나만 돌려준다. */
    private void stubFailure() {
        when(batchClient.streamResults(anyString(), any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<AnalyzedBill> consumer = invocation.getArgument(2);
            consumer.accept(AnalyzedBill.failure(
                    bills.get(0).getId(), "해시", "claude-opus-5",
                    "expired: 24시간 안에 처리되지 못했습니다"));
            return 1;
        });
    }

    /** 법안마다 다른 hook. 결과가 제 법안에 붙었는지 가리는 표식이다. */
    private String hookOf(Long billId) {
        return "법안 " + billId + " 의 한 줄";
    }
}
