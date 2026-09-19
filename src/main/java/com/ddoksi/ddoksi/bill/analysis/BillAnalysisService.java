package com.ddoksi.ddoksi.bill.analysis;

import com.ddoksi.ddoksi.bill.entity.Bill;
import com.ddoksi.ddoksi.bill.entity.BillSummary;
import com.ddoksi.ddoksi.bill.repository.BillAnalysisRepository;
import com.ddoksi.ddoksi.bill.repository.BillRepository;
import com.ddoksi.ddoksi.bill.repository.BillSummaryRepository;
import com.ddoksi.ddoksi.collection.entity.CollectionRun;
import com.ddoksi.ddoksi.collection.repository.CollectionRunRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * 법안 AI 분석 배치.
 *
 * <p>제안이유 수집({@code BillSummaryCollectionService})이 끝난 뒤에 돈다. 순서가 중요하다 —
 * 신규 법안은 목록에 먼저 잡히고 본문은 그 다음에 들어오므로, 본문이 없는 상태에서 부르면
 * 분석할 입력이 없다.
 *
 * <p><b>수집 배치 안에서 호출하지 않는 이유.</b> 수집 트랜잭션 안에서 Claude 를 부르면
 * Anthropic 장애가 법안 수집 자체를 멈춘다. 수집은 무료고 분석은 유료인데, 유료 쪽 실패가
 * 무료 쪽을 망치는 구조는 피한다.
 *
 * <p>이 메서드에는 @Transactional 을 붙이지 않는다. 저장은 {@link BillAnalysisPersister} 가
 * 묶음마다 별도 트랜잭션으로 처리한다.
 */
@Service
public class BillAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(BillAnalysisService.class);

    public static final String JOB_NAME = "bill-analysis";

    /** 일시적 실패 시 같은 법안을 다시 시도할 횟수. */
    private static final int MAX_ATTEMPTS = 3;

    /** 한 트랜잭션에 담을 법안 수. 호출한 만큼은 돈이므로 너무 크게 묶지 않는다. */
    private static final int CHUNK_SIZE = 20;

    /**
     * 연속 실패 차단선.
     *
     * <p>인증 오류나 크레딧 소진은 건별 실패로 보이지만 실제로는 전체성 실패다. SDK 예외
     * 종류로 판정하려면 버전마다 달라지는 타입 이름에 기대야 해서, 대신 "연달아 N건이
     * 실패하면 환경 문제로 본다" 로 잡는다. 19,406번을 전부 실패하며 도는 것을 막는 장치다.
     */
    private static final int CONSECUTIVE_FAILURE_LIMIT = 5;

    private final ClaudeAnalysisClient claudeClient;
    private final BillAnalysisPersister persister;
    private final BillRepository billRepository;
    private final BillSummaryRepository summaryRepository;
    private final BillAnalysisRepository analysisRepository;
    private final CollectionRunRepository runRepository;

    /** 이번 실행이 생성할 버전. 조회용 활성 버전과 별개다 — 전환을 통제하기 위해 나눠 둔다. */
    @Value("${ddoksi.analysis.prompt-version}")
    private String promptVersion;

    public BillAnalysisService(ClaudeAnalysisClient claudeClient,
                               BillAnalysisPersister persister,
                               BillRepository billRepository,
                               BillSummaryRepository summaryRepository,
                               BillAnalysisRepository analysisRepository,
                               CollectionRunRepository runRepository) {
        this.claudeClient = claudeClient;
        this.persister = persister;
        this.billRepository = billRepository;
        this.summaryRepository = summaryRepository;
        this.analysisRepository = analysisRepository;
        this.runRepository = runRepository;
    }

    /**
     * 분석이 필요한 법안을 최대 지정 건수까지 처리한다.
     *
     * <p>상한을 두는 이유는 두 가지다. 호출 한 건이 곧 비용이라 처음에는 소량으로
     * 결과와 단가를 확인해야 하고, 정기 실행에서는 그날 들어온 법안(일평균 23건) 정도만
     * 처리하면 충분하다.
     *
     * @param maxBills 처리할 최대 법안 수
     */
    public AnalysisChunkResult analyzePending(int maxBills) {
        CollectionRun run = runRepository.save(
                CollectionRun.builder().jobName(JOB_NAME).startedAt(null).build());

        long remaining = analysisRepository.countBillsNeedingAnalysis(promptVersion);
        log.info("법안 분석 시작: runId={}, 버전={}, 모델={}, 남은 법안={}건, 이번 실행 상한={}건",
                run.getId(), promptVersion, claudeClient.model(), remaining, maxBills);

        AnalysisChunkResult total = AnalysisChunkResult.empty();
        long cursor = 0L;
        int handled = 0;
        int consecutiveFailures = 0;

        try {
            while (handled < maxBills) {
                int chunkSize = Math.min(CHUNK_SIZE, maxBills - handled);
                List<Bill> bills = billRepository.findBillsNeedingAnalysis(
                        promptVersion, cursor, Limit.of(chunkSize));
                if (bills.isEmpty()) {
                    log.info("더 처리할 법안이 없습니다. 커서={}", cursor);
                    break;
                }

                List<AnalyzedBill> analyzed = new ArrayList<>(bills.size());
                for (Bill bill : bills) {
                    cursor = bill.getId();
                    handled++;

                    Optional<AnalyzedBill> outcome = analyzeOne(bill);
                    if (outcome.isEmpty()) {
                        // 입력이 사라졌거나 이미 최신인 경우. 호출도 저장도 하지 않는다.
                        continue;
                    }
                    analyzed.add(outcome.get());

                    consecutiveFailures = outcome.get().isSuccess() ? 0 : consecutiveFailures + 1;
                    if (consecutiveFailures >= CONSECUTIVE_FAILURE_LIMIT) {
                        // 여기까지 받은 것은 이미 비용을 치렀으므로 버리지 않고 커밋한다.
                        total = total.plus(persister.persistChunk(analyzed, promptVersion));
                        throw new IllegalStateException(
                                "연속 " + consecutiveFailures + "건 실패. 인증·크레딧·모델 설정을 확인하세요.");
                    }
                }

                total = total.plus(persister.persistChunk(analyzed, promptVersion));
                log.info("묶음 완료: 누적 처리={}/{} (호출={}, 실패={})",
                        handled, maxBills, total.called(), total.failed());
            }

            run.recordProgress(handled, total.called(), total.failed());
            run.complete(String.valueOf(cursor));
            runRepository.save(run);
            log.info("법안 분석 완료: runId={}, 신규={}, 재시도={}, 재생성={}, 변화없음={}, 실패={}",
                    run.getId(), total.inserted(), total.retried(), total.regenerated(),
                    total.unchanged(), total.failed());
            return total;

        } catch (Exception e) {
            run.recordProgress(handled, total.called(), total.failed());
            run.fail(e.getMessage());
            runRepository.save(run);
            log.error("법안 분석 실패: runId={}, 처리={}건, 커서={}", run.getId(), handled, cursor, e);
            throw e;
        }
    }

    /**
     * 법안 한 건을 분석한다.
     *
     * @return 저장할 결과. 호출할 필요가 없었으면 비어 있다
     */
    private Optional<AnalyzedBill> analyzeOne(Bill bill) {
        String billText = summaryRepository.findByBill(bill)
                .map(BillSummary::getSummary)
                .orElse(null);
        if (billText == null || billText.isBlank()) {
            // 쿼리가 걸렀어야 하는 경우지만, 사이에 본문이 지워졌을 수 있다.
            log.warn("제안이유가 없어 건너뜁니다: billId={}", bill.getId());
            return Optional.empty();
        }

        String sourceHash = BillAnalysisPrompt.sourceHash(bill, billText);
        String model = claudeClient.model();
        Exception lastError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                AnalysisResult result = claudeClient.analyze(bill, billText);
                if (result.isEmpty()) {
                    // 호출은 성공했는데 네 항목이 전부 비었다. 저장할 가치가 없으므로
                    // 실패로 남겨 다음 실행에서 다시 시도되게 한다.
                    throw new IllegalStateException("모든 항목이 비어 있습니다");
                }
                return Optional.of(AnalyzedBill.success(bill.getId(), sourceHash, model, result));

            } catch (Exception e) {
                lastError = e;
                log.warn("분석 호출 실패: billId={} ({}/{}회): {}",
                        bill.getId(), attempt, MAX_ATTEMPTS, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleep(1000L * attempt * attempt);
                }
            }
        }

        return Optional.of(AnalyzedBill.failure(
                bill.getId(), sourceHash, model, summarize(lastError)));
    }

    /** 오류 메시지를 컬럼에 담기 좋은 길이로 줄인다. 원문 전체는 로그에 남아 있다. */
    private String summarize(Exception e) {
        if (e == null) {
            return "알 수 없는 오류";
        }
        String message = e.getClass().getSimpleName() + ": " + e.getMessage();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("법안 분석이 중단되었습니다", e);
        }
    }
}
