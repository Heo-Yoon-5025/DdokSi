package com.ddoksi.ddoksi.bill.analysis;

import com.ddoksi.ddoksi.bill.entity.AnalysisBatch;
import com.ddoksi.ddoksi.bill.entity.Bill;
import com.ddoksi.ddoksi.bill.repository.BillAnalysisRepository;
import com.ddoksi.ddoksi.bill.repository.BillRepository;
import com.ddoksi.ddoksi.bill.repository.BillSummaryRepository;
import com.ddoksi.ddoksi.collection.entity.CollectionRun;
import com.ddoksi.ddoksi.collection.repository.CollectionRunRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * 법안 AI 분석 — Batch API 경로.
 *
 * <p>{@link BillAnalysisService}(동기)와 같은 일을 하지만 값이 절반이다. 남은 19,306건 기준
 * 동기는 약 34만원에 30시간, 배치는 약 17만원이다. 대신 결과가 즉시 오지 않는다 —
 * 그 하나의 차이가 아래 설계 전부를 설명한다.
 *
 * <h2>순서가 곧 안전장치다</h2>
 *
 * <p>실행은 언제나 <b>미수거 배치 수거 → 새 배치 제출</b> 순이고, 새 배치는 한 번에 하나만
 * 떠 있는다. 이것을 지키지 않으면 이중 과금이 난다.
 *
 * <p>이유: 대상 법안 조회({@code findBillsNeedingAnalysis})는 "성공한 분석 행이 없는 법안" 을
 * 고른다. 그런데 배치에 실어 보낸 법안은 결과를 수거하기 전까지 분석 행이 <b>없다</b>.
 * 즉 제출 중인 법안과 아직 손대지 않은 법안이 이 조회에서 구별되지 않는다. 배치 두 개를
 * 동시에 띄우거나 미수거 배치를 두고 새로 제출하면 같은 법안을 두 번 보내고 두 번 낸다.
 *
 * <p>한 실행 안에서는 커서({@code afterId})가 같은 역할을 하지만, 앱이 재시작되면 커서는
 * 사라지고 DB 만 남는다. 그래서 재시작 후에도 성립하는 규칙이 필요했다.
 *
 * <h2>남아 있는 위험 하나</h2>
 *
 * <p>제출이 성공한 뒤 {@code analysis_batch} 커밋이 끝나기 전에 프로세스가 죽으면, 그 배치는
 * Anthropic 쪽에만 존재하는 미아가 된다 (창은 수십 밀리초다). 값은 이미 나갔고 우리는 id 를
 * 모른다. 그런 일이 의심되면 콘솔이나 {@code /v1/messages/batches} 목록에서 우리 테이블에
 * 없는 배치를 찾아 수동으로 확인해야 한다. 자동 복구를 만들지 않은 이유는, 미아 배치에는
 * 제출 시점 입력 해시가 없어 안전하게 저장할 수 없기 때문이다.
 */
@Service
public class BillAnalysisBatchService {

    private static final Logger log = LoggerFactory.getLogger(BillAnalysisBatchService.class);

    public static final String JOB_NAME = "bill-analysis-batch";

    /**
     * 한 트랜잭션에 저장할 결과 수.
     *
     * <p>동기 경로(20건)보다 크게 잡았다. 저쪽은 호출과 저장이 붙어 있어 묶음이 커지면
     * 이미 지불한 결과가 오래 커밋되지 않은 채 남지만, 여기서는 결과가 이미 전부
     * Anthropic 쪽에 있어 다시 가져올 수 있다. 잃을 수 있는 것은 돈이 아니라 시간이다.
     */
    private static final int PERSIST_CHUNK = 100;

    private final ClaudeBatchAnalysisClient batchClient;
    private final ClaudeAnalysisClient syncClient;
    private final AnalysisBatchRecorder recorder;
    private final BillAnalysisPersister persister;
    private final BillRepository billRepository;
    private final BillSummaryRepository summaryRepository;
    private final BillAnalysisRepository analysisRepository;
    private final CollectionRunRepository runRepository;

    @Value("${ddoksi.analysis.prompt-version}")
    private String promptVersion;

    /**
     * 배치 하나에 담을 법안 수.
     *
     * <p>한도는 배치당 100,000건 또는 256MB 다. 건수는 문제가 아니지만 크기는 문제가 된다 —
     * 시스템 프롬프트(4.8KB)가 요청마다 실려 가므로 요청 하나가 약 7KB 이고, 19,306건을
     * 한 배치에 넣으면 132MB 짜리 POST 가 된다. 한도 안이긴 해도 한 번의 실패로 전부를
     * 다시 보내야 하는 크기다. 2,000건이면 약 14MB, 배치 10개로 나뉜다.
     */
    @Value("${ddoksi.analysis.batch.chunk-size:2000}")
    private int chunkSize;

    @Value("${ddoksi.analysis.batch.poll-interval-seconds:60}")
    private int pollIntervalSeconds;

    /**
     * 배치 하나를 기다리는 한도.
     *
     * <p>Anthropic 의 만료 한도가 24시간이라 그보다 짧게 잡는다. 한도를 넘기면 실패로
     * 처리하지 않고 {@code SUBMITTED} 인 채로 두고 실행만 끝낸다 — 결과는 29일간 남아 있고,
     * 다음 실행의 첫 단계가 바로 그 배치를 수거하는 일이다.
     */
    @Value("${ddoksi.analysis.batch.poll-timeout-minutes:360}")
    private int pollTimeoutMinutes;

    public BillAnalysisBatchService(ClaudeBatchAnalysisClient batchClient,
                                    ClaudeAnalysisClient syncClient,
                                    AnalysisBatchRecorder recorder,
                                    BillAnalysisPersister persister,
                                    BillRepository billRepository,
                                    BillSummaryRepository summaryRepository,
                                    BillAnalysisRepository analysisRepository,
                                    CollectionRunRepository runRepository) {
        this.batchClient = batchClient;
        this.syncClient = syncClient;
        this.recorder = recorder;
        this.persister = persister;
        this.billRepository = billRepository;
        this.summaryRepository = summaryRepository;
        this.analysisRepository = analysisRepository;
        this.runRepository = runRepository;
    }

    /**
     * 분석이 필요한 법안을 배치로 처리한다.
     *
     * @param maxBills 이번 실행에서 새로 제출할 최대 법안 수. 미수거 배치 수거분은 여기 포함되지 않는다
     */
    public AnalysisChunkResult runBackfill(int maxBills) {
        CollectionRun run = runRepository.save(
                CollectionRun.builder().jobName(JOB_NAME).startedAt(null).build());

        long remaining = analysisRepository.countBillsNeedingAnalysis(promptVersion);
        log.info("배치 분석 시작: runId={}, 버전={}, 모델={}, 남은 법안={}건, "
                        + "이번 제출 상한={}건, 배치당={}건",
                run.getId(), promptVersion, syncClient.model(), remaining, maxBills, chunkSize);

        AnalysisChunkResult total = AnalysisChunkResult.empty();
        int submitted = 0;

        try {
            // 1) 지난 실행이 남긴 배치부터 비운다. 이미 값을 치른 결과이고,
            //    이것을 두고 새로 제출하면 같은 법안을 두 번 낸다.
            total = total.plus(collectOutstanding());

            // 2) 남은 법안을 배치 단위로 제출하고 수거한다. 한 번에 하나씩만 띄운다.
            long cursor = 0L;
            while (submitted < maxBills) {
                int size = Math.min(chunkSize, maxBills - submitted);
                List<PendingAnalysis> pending = nextChunk(cursor, size);
                if (pending.isEmpty()) {
                    log.info("더 제출할 법안이 없습니다.");
                    break;
                }
                cursor = pending.get(pending.size() - 1).billId();
                submitted += pending.size();

                AnalysisBatch batch = submitAndRecord(run.getId(), pending);
                if (!awaitEnd(batch.getProviderBatchId())) {
                    log.warn("배치가 제한 시간({}분) 안에 끝나지 않았습니다. SUBMITTED 로 두고 실행을 마칩니다 "
                            + "— 다음 실행이 이어서 수거합니다: batchId={}",
                            pollTimeoutMinutes, batch.getProviderBatchId());
                    break;
                }
                total = total.plus(collect(batch));
            }

            run.recordProgress(submitted, total.called(), total.failed());
            run.complete(String.valueOf(submitted));
            runRepository.save(run);
            log.info("배치 분석 완료: runId={}, 제출={}건, 신규={}, 재시도={}, 재생성={}, 변화없음={}, 실패={}",
                    run.getId(), submitted, total.inserted(), total.retried(),
                    total.regenerated(), total.unchanged(), total.failed());
            return total;

        } catch (Exception e) {
            run.recordProgress(submitted, total.called(), total.failed());
            run.fail(e.getMessage());
            runRepository.save(run);
            log.error("배치 분석 실패: runId={}, 제출={}건", run.getId(), submitted, e);
            throw e;
        }
    }

    /**
     * 아직 수거하지 않은 배치를 처리한다.
     *
     * <p>아직 끝나지 않은 배치를 만나면 기다린다. 건너뛰고 다음으로 넘어가면 그 법안들이
     * 대상 조회에 다시 잡혀 재제출되기 때문이다.
     */
    private AnalysisChunkResult collectOutstanding() {
        List<AnalysisBatch> outstanding = recorder.findPending();
        if (outstanding.isEmpty()) {
            return AnalysisChunkResult.empty();
        }

        log.info("미수거 배치 {}개를 먼저 처리합니다.", outstanding.size());
        AnalysisChunkResult total = AnalysisChunkResult.empty();

        for (AnalysisBatch batch : outstanding) {
            if (!awaitEnd(batch.getProviderBatchId())) {
                log.warn("미수거 배치가 제한 시간 안에 끝나지 않았습니다. 이번 실행은 여기서 멈춥니다: batchId={}",
                        batch.getProviderBatchId());
                throw new IllegalStateException(
                        "미수거 배치 " + batch.getProviderBatchId() + " 가 아직 처리 중입니다. "
                                + "새 배치를 제출하면 같은 법안에 이중 과금이 발생합니다.");
            }
            total = total.plus(collect(batch));
        }
        return total;
    }

    /** 다음 배치에 담을 법안을 고르고 프롬프트 재료를 붙인다. */
    private List<PendingAnalysis> nextChunk(long cursor, int size) {
        List<Bill> bills = billRepository.findBillsNeedingAnalysis(
                promptVersion, cursor, Limit.of(size));
        if (bills.isEmpty()) {
            return List.of();
        }

        // 본문을 한 번에 읽는다. 법안마다 조회하면 2,000번의 왕복이 된다.
        Map<Long, String> texts = new HashMap<>();
        for (Object[] row : summaryRepository.findSummaryTextsByBillIds(
                bills.stream().map(Bill::getId).toList())) {
            texts.put((Long) row[0], (String) row[1]);
        }

        List<PendingAnalysis> pending = new ArrayList<>(bills.size());
        for (Bill bill : bills) {
            String billText = texts.get(bill.getId());
            if (billText == null || billText.isBlank()) {
                // 대상 조회가 걸렀어야 하는 경우지만, 사이에 본문이 지워졌을 수 있다.
                log.warn("제안이유가 없어 배치에서 제외합니다: billId={}", bill.getId());
                continue;
            }
            pending.add(new PendingAnalysis(
                    bill.getId(),
                    BillAnalysisPrompt.sourceHash(bill, billText),
                    BillAnalysisPrompt.userMessage(bill, billText)));
        }
        return pending;
    }

    /**
     * 제출하고 그 사실을 즉시 기록한다.
     *
     * <p>두 줄 사이가 이 클래스에서 가장 위험한 구간이다. 클래스 주석의 "미아 배치" 참고.
     */
    private AnalysisBatch submitAndRecord(Long runId, List<PendingAnalysis> pending) {
        String providerBatchId = batchClient.submit(pending);
        AnalysisBatch batch = recorder.record(
                runId, providerBatchId, promptVersion, syncClient.model(), pending);

        log.info("배치 기록 완료: batchId={}, providerBatchId={}, {}건",
                batch.getId(), providerBatchId, pending.size());
        return batch;
    }

    /** 배치가 끝날 때까지 기다린다. 폴링 루프 자체는 SDK 를 만지는 일이라 클라이언트에 있다. */
    private boolean awaitEnd(String providerBatchId) {
        return batchClient.awaitEnd(providerBatchId,
                Duration.ofSeconds(pollIntervalSeconds),
                Duration.ofMinutes(pollTimeoutMinutes));
    }

    /** 결과를 흘려 읽으며 100건씩 커밋한다. */
    private AnalysisChunkResult collect(AnalysisBatch batch) {
        Map<Long, String> hashes = recorder.sourceHashes(batch.getId());
        ResultCollector collector = new ResultCollector(hashes);

        int seen = batchClient.streamResults(
                batch.getProviderBatchId(), hashes::get, collector::accept);
        collector.flush();

        int missing = batch.getRequestCount() - seen;
        recorder.markCollected(batch.getId(), collector.succeeded, collector.failed, missing);

        if (missing > 0) {
            log.warn("결과에 나타나지 않은 요청이 {}건 있습니다: batchId={}. "
                            + "해당 법안은 분석 행이 없으므로 다음 실행이 다시 집어갑니다.",
                    missing, batch.getProviderBatchId());
        }
        log.info("배치 수거 완료: batchId={}, 결과={}건 (성공={}, 실패={}, 누락={})",
                batch.getProviderBatchId(), seen, collector.succeeded, collector.failed, missing);

        return collector.total;
    }

    /**
     * 수거 결과를 모았다가 묶음마다 저장한다.
     *
     * <p>스트림을 읽는 동안 상태를 들고 있어야 해서 클래스로 뺐다 — 람다 안에서 지역변수를
     * 누적할 수 없다.
     */
    private final class ResultCollector {

        private final Map<Long, String> hashes;
        private final List<AnalyzedBill> buffer = new ArrayList<>(PERSIST_CHUNK);
        private AnalysisChunkResult total = AnalysisChunkResult.empty();
        private int succeeded;
        private int failed;

        private ResultCollector(Map<Long, String> hashes) {
            this.hashes = hashes;
        }

        void accept(AnalyzedBill analyzed) {
            if (!hashes.containsKey(analyzed.billId())) {
                // 우리가 보내지 않은 법안이 결과에 있다. 입력 해시를 모르므로 저장할 수 없다.
                log.error("제출 기록에 없는 법안이 결과에 있습니다: billId={}", analyzed.billId());
                return;
            }
            if (analyzed.isSuccess()) {
                succeeded++;
            } else {
                failed++;
            }
            buffer.add(analyzed);
            if (buffer.size() >= PERSIST_CHUNK) {
                flush();
            }
        }

        void flush() {
            if (buffer.isEmpty()) {
                return;
            }
            total = total.plus(persister.persistChunk(List.copyOf(buffer), promptVersion));
            buffer.clear();
        }
    }

}
