package com.ddoksi.ddoksi.collection;

import com.ddoksi.ddoksi.bill.entity.Bill;
import com.ddoksi.ddoksi.bill.repository.BillSummaryRepository;
import com.ddoksi.ddoksi.collection.api.AssemblyApiClient;
import com.ddoksi.ddoksi.collection.api.AssemblyApiException;
import com.ddoksi.ddoksi.collection.api.AssemblyApiProperties;
import com.ddoksi.ddoksi.collection.api.AssemblyPage;
import com.ddoksi.ddoksi.collection.entity.CollectionRun;
import com.ddoksi.ddoksi.collection.repository.CollectionRunRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 법률안 제안이유 및 주요내용 수집.
 *
 * <p><b>왜 별도 배치인가.</b> 발의법률안 목록 API 는 한 번에 100건씩 주지만
 * 제안이유 API(BPMBILLSUMMARY)는 BILL_NO 가 필수라 목록을 주지 않는다.
 * 법안 한 건당 한 번씩 호출해야 하므로 호출 수와 소요 시간의 성격이 완전히 다르다.
 * 기존 목록 수집에 끼워 넣으면 매일 도는 야간 배치가 몇 배로 길어진다.
 *
 * <p><b>재개 가능하게 만든 것이 이 배치의 핵심 성질이다.</b> 1만9천 번의 호출은
 * 중간에 끊길 수 있다고 전제해야 한다. 이미 본문이 있는 법안은 조회 대상에서 빠지므로
 * 다시 실행하면 남은 것부터 이어서 받는다.
 *
 * <p>{@link BillCollectionService} 와 마찬가지로 이 메서드에는 @Transactional 을 붙이지 않는다.
 * 저장은 {@link BillSummaryPersister} 가 묶음마다 별도 트랜잭션으로 처리한다.
 */
@Service
public class BillSummaryCollectionService {

    private static final Logger log = LoggerFactory.getLogger(BillSummaryCollectionService.class);

    public static final String JOB_NAME = "bill-summary-collect";

    /** 일시적 실패 시 같은 법안을 다시 시도할 횟수. */
    private static final int MAX_ATTEMPTS = 3;

    /** 한 트랜잭션에 담을 법안 수. 너무 크면 실패 시 잃는 양이 늘고, 너무 작으면 커밋이 잦다. */
    private static final int CHUNK_SIZE = 100;

    private final AssemblyApiClient apiClient;
    private final BillSummaryPersister persister;
    private final BillSummaryRepository summaryRepository;
    private final CollectionRunRepository runRepository;
    private final AssemblyApiProperties properties;

    public BillSummaryCollectionService(AssemblyApiClient apiClient,
                                        BillSummaryPersister persister,
                                        BillSummaryRepository summaryRepository,
                                        CollectionRunRepository runRepository,
                                        AssemblyApiProperties properties) {
        this.apiClient = apiClient;
        this.persister = persister;
        this.summaryRepository = summaryRepository;
        this.runRepository = runRepository;
        this.properties = properties;
    }

    /** 본문이 없는 법안 전부를 대상으로 수집한다. */
    public SummaryResult collectMissingSummaries() {
        return collectMissingSummaries(Integer.MAX_VALUE);
    }

    /**
     * 본문이 없는 법안을 최대 지정 건수까지 수집한다.
     *
     * <p>건수 상한을 두는 이유는 두 가지다. 처음 돌릴 때 소량으로 결과를 확인하고 싶을 때가 있고,
     * 정기 실행에서는 그날 새로 들어온 법안 정도만 처리하면 충분하기 때문이다.
     *
     * @param maxBills 처리할 최대 법안 수
     */
    public SummaryResult collectMissingSummaries(int maxBills) {
        CollectionRun run = runRepository.save(
                CollectionRun.builder().jobName(JOB_NAME).startedAt(null).build());

        long remaining = summaryRepository.countBillsWithoutSummary();
        log.info("제안이유 수집 시작: runId={}, 본문 없는 법안={}건, 이번 실행 상한={}건",
                run.getId(), remaining, maxBills);

        SummaryResult total = SummaryResult.empty();
        // 항상 앞으로만 나아가는 커서. 저장에 실패한 법안 때문에 같은 자리를 맴돌지 않게 한다.
        long cursor = 0L;
        int handled = 0;

        try {
            while (handled < maxBills) {
                int chunkSize = Math.min(CHUNK_SIZE, maxBills - handled);
                List<Bill> bills = summaryRepository.findBillsWithoutSummary(cursor, Limit.of(chunkSize));
                if (bills.isEmpty()) {
                    log.info("더 처리할 법안이 없습니다. 커서={}", cursor);
                    break;
                }

                List<FetchedSummary> fetched = new ArrayList<>(bills.size());
                for (Bill bill : bills) {
                    fetched.add(fetchWithRetry(bill));
                    cursor = bill.getId();
                    handled++;
                    pause();
                }

                SummaryResult chunkResult = persister.persistChunk(fetched);
                total = total.plus(chunkResult);

                log.info("묶음 완료: 누적 처리={}/{} (신규={}, 빈 본문={}, 데이터없음={}, 건너뜀={})",
                        handled, Math.min(maxBills, remaining),
                        total.inserted(), total.emptyContent(), total.notFound(), total.skipped());
            }

            run.recordProgress(handled, total.processed(), total.notFound() + total.skipped());
            run.complete(String.valueOf(cursor));
            runRepository.save(run);
            log.info("제안이유 수집 완료: runId={}, 신규={}, 갱신={}, 변화없음={}, "
                            + "빈 본문={}, 데이터없음={}, 건너뜀={}",
                    run.getId(), total.inserted(), total.updated(), total.unchanged(),
                    total.emptyContent(), total.notFound(), total.skipped());
            return total;

        } catch (Exception e) {
            // 전체성 실패(인증키 무효, UA 차단 등). 여기까지 저장된 묶음은 이미 커밋되어 남는다.
            run.recordProgress(handled, total.processed(), total.notFound() + total.skipped());
            run.fail(e.getMessage());
            runRepository.save(run);
            log.error("제안이유 수집 실패: runId={}, 처리={}건, 커서={}", run.getId(), handled, cursor, e);
            throw e;
        }
    }

    /**
     * 법안 한 건의 본문을 가져온다. 일시적 실패만 재시도하고, 영구적 실패는 즉시 올린다.
     *
     * <p>재시도 대상이 아닌 실패(인증키 무효 등)를 여기서 삼키면 1만9천 번을 전부 실패하면서
     * 도는 배치가 된다. 그래서 재시도 가치가 없는 예외는 그대로 밖으로 던진다.
     */
    private FetchedSummary fetchWithRetry(Bill bill) {
        AssemblyApiException lastError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                AssemblyPage page = apiClient.fetchBillSummary(bill.getBillNo());
                if (page.isEmpty()) {
                    // INFO-200. 아직 국회 쪽에 본문이 등재되지 않은 법안이 여기에 해당한다.
                    return FetchedSummary.notFound(bill.getId(), bill.getBillNo());
                }
                JsonNode row = selectRow(page.rows(), bill);
                return new FetchedSummary(
                        bill.getId(),
                        bill.getBillNo(),
                        textOrNull(row, "SUMMARY"),
                        textOrNull(row, "BILL_ID"),
                        true);

            } catch (AssemblyApiException e) {
                if (!e.isRetryable()) {
                    throw e;
                }
                lastError = e;
                log.warn("본문 호출 실패: billNo={} ({}/{}회): {}",
                        bill.getBillNo(), attempt, MAX_ATTEMPTS, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleep(properties.requestDelay().toMillis() * attempt * 2);
                }
            }
        }
        throw lastError;
    }

    /**
     * 응답 행 중 우리가 찾는 법안의 것을 고른다.
     *
     * <p><b>한 의안번호에 행이 여러 개 오는 경우가 실재한다.</b> 의안번호 2221245 는
     * {@code list_total_count=2} 로, 법안명과 대수가 같은데 BILL_ID 가 다른 행이 둘 온다.
     * 그중 하나는 본문이 비어 있고 우리 DB 에 없는 국회 쪽 중복 레코드이며,
     * 실제 본문은 나머지 행에 들어 있다. 첫 행만 집으면 받을 수 있는 본문을 버리고
     * 의안 ID 불일치로 건너뛰게 된다.
     *
     * <p>그래서 BILL_ID 가 우리 {@code external_bill_id} 와 일치하는 행을 먼저 찾는다.
     * 일치하는 행이 없으면 첫 행을 그대로 돌려준다 — 그 경우는 정말로 엉뚱한 응답이므로
     * {@link BillSummaryPersister} 의 불일치 검사에 걸려 건너뛰는 것이 맞다.
     */
    private JsonNode selectRow(List<JsonNode> rows, Bill bill) {
        if (rows.size() == 1) {
            return rows.get(0);
        }

        for (JsonNode row : rows) {
            if (bill.getExternalBillId().equals(textOrNull(row, "BILL_ID"))) {
                return row;
            }
        }

        log.warn("응답 {}행 중 의안 ID 가 일치하는 행이 없습니다: billNo={}, 기대={}",
                rows.size(), bill.getBillNo(), bill.getExternalBillId());
        return rows.get(0);
    }

    /** 빈 문자열과 없는 필드를 모두 null 로 모은다. 저장 쪽에서 한 가지 경우만 보게 하기 위해서다. */
    private String textOrNull(JsonNode row, String field) {
        JsonNode node = row.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asString("").strip();
        return value.isEmpty() ? null : value;
    }

    private void pause() {
        sleep(properties.requestDelay().toMillis());
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("제안이유 수집이 중단되었습니다", e);
        }
    }
}
