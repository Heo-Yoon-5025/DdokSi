package com.ddoksi.ddoksi.collection;

import com.ddoksi.ddoksi.collection.api.AssemblyApiClient;
import com.ddoksi.ddoksi.collection.api.AssemblyApiException;
import com.ddoksi.ddoksi.collection.api.AssemblyApiProperties;
import com.ddoksi.ddoksi.collection.api.AssemblyPage;
import com.ddoksi.ddoksi.collection.entity.CollectionRun;
import com.ddoksi.ddoksi.collection.repository.CollectionRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 국회의원 발의법률안 수집.
 *
 * <p><b>이 메서드에 @Transactional 을 붙이지 않는다.</b> 190여 페이지를 도는 동안
 * 트랜잭션을 열어두면 커넥션을 계속 붙잡고, 마지막 페이지에서 실패하면 전부 롤백된다.
 * 실제 저장은 {@link BillPagePersister} 가 페이지마다 별도 트랜잭션으로 처리한다.
 *
 * <p>실패 처리 원칙:
 * <ul>
 *   <li><b>개별 건 실패</b> — 기록하고 다음으로 넘어간다. 법안 하나 때문에 전체가 멈추면 안 된다.
 *   <li><b>일시적 실패</b>(타임아웃, 5xx) — 같은 페이지를 다시 시도한다.
 *   <li><b>전체성 실패</b>(인증키 무효, UA 차단) — 즉시 중단한다. 계속 돌려도 실패만 쌓인다.
 * </ul>
 */
@Service
public class BillCollectionService {

    private static final Logger log = LoggerFactory.getLogger(BillCollectionService.class);

    public static final String JOB_NAME = "member-bill-collect";

    /** 일시적 실패 시 같은 페이지를 다시 시도할 횟수. */
    private static final int MAX_ATTEMPTS = 3;

    /** 페이지 순회 상한. 무한 루프 방지용 안전장치다. */
    private static final int MAX_PAGES = 500;

    private final AssemblyApiClient apiClient;
    private final BillPagePersister pagePersister;
    private final CollectionRunRepository runRepository;
    private final AssemblyApiProperties properties;

    public BillCollectionService(AssemblyApiClient apiClient,
                                 BillPagePersister pagePersister,
                                 CollectionRunRepository runRepository,
                                 AssemblyApiProperties properties) {
        this.apiClient = apiClient;
        this.pagePersister = pagePersister;
        this.runRepository = runRepository;
        this.properties = properties;
    }

    /**
     * 지정한 대수의 발의법률안을 처음부터 끝까지 순회해 수집한다.
     *
     * @param assemblyAge 국회 대수 (예: 22)
     */
    public PageResult collectMemberBills(int assemblyAge) {
        return collectMemberBills(assemblyAge, MAX_PAGES);
    }

    /**
     * 페이지 상한을 지정해 수집한다.
     *
     * 부분 수집이 필요한 경우에 쓴다 — 테스트, 또는 장애 후 최근 몇 페이지만 복구할 때다.
     * API 가 최신순으로 정렬해 주므로 앞쪽 몇 페이지만 받아도 최근 변경은 모두 잡힌다.
     *
     * @param maxPages 순회할 최대 페이지 수
     */
    public PageResult collectMemberBills(int assemblyAge, int maxPages) {
        // 실행 이력을 먼저 남긴다. 중간에 죽어도 "시도했다" 는 기록이 남아야 추적이 가능하다.
        CollectionRun run = runRepository.save(
                CollectionRun.builder().jobName(JOB_NAME).startedAt(null).build());
        log.info("수집 시작: runId={}, 대수={}, 최대 페이지={}", run.getId(), assemblyAge, maxPages);

        PageResult total = PageResult.empty();
        String newestProposeDate = null;

        try {
            for (int page = 1; page <= Math.min(maxPages, MAX_PAGES); page++) {
                AssemblyPage apiPage = fetchWithRetry(assemblyAge, page);

                // 빈 페이지는 순회의 정상 종료 신호다 (INFO-200).
                if (apiPage.isEmpty()) {
                    log.info("순회 종료: 마지막 페이지={}", page - 1);
                    break;
                }

                // 첫 페이지 첫 행이 가장 최신이다 (API 가 최신순으로 정렬해 내려준다).
                if (page == 1) {
                    newestProposeDate = firstProposeDate(apiPage);
                }

                PageResult pageResult = pagePersister.persistPage(
                        apiPage.rows(), run.getId(), AssemblyApiClient.API_MEMBER_BILLS);
                total = total.plus(pageResult);

                log.info("페이지 {} 완료: 누적 처리={}/{} (신규={}, 상태변경={})",
                        page, total.processed(), apiPage.totalCount(),
                        total.inserted(), total.statusChanged());

                // 마지막 페이지였는지 확인해 불필요한 호출을 한 번 줄인다.
                if (apiPage.rows().size() < properties.pageSize()) {
                    log.info("순회 종료: 마지막 페이지={} (행 수 부족)", page);
                    break;
                }

                pause();
            }

            run.recordProgress(total.processed() + total.skipped(), total.processed(), total.skipped());
            run.complete(newestProposeDate);
            runRepository.save(run);
            log.info("수집 완료: runId={}, 신규={}, 상태변경={}, 변화없음={}, 건너뜀={}",
                    run.getId(), total.inserted(), total.statusChanged(),
                    total.unchanged(), total.skipped());
            return total;

        } catch (Exception e) {
            // 전체성 실패. 여기까지 처리된 페이지는 이미 커밋되어 남아 있다.
            run.recordProgress(total.processed() + total.skipped(), total.processed(), total.skipped());
            run.fail(e.getMessage());
            runRepository.save(run);
            log.error("수집 실패: runId={}, 처리된 건수={}", run.getId(), total.processed(), e);
            throw e;
        }
    }

    /**
     * 한 페이지를 가져온다. 일시적 실패는 다시 시도하고, 영구적 실패는 즉시 올린다.
     *
     * 재시도 여부를 구분하지 않으면 UA 차단이나 인증키 오류처럼 절대 성공할 수 없는 실패에도
     * 배치가 세 번씩 재시도하며 호출 한도를 낭비한다.
     */
    private AssemblyPage fetchWithRetry(int assemblyAge, int page) {
        AssemblyApiException lastError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return apiClient.fetchMemberBills(assemblyAge, page);
            } catch (AssemblyApiException e) {
                if (!e.isRetryable()) {
                    throw e;
                }
                lastError = e;
                log.warn("페이지 {} 호출 실패 ({}/{}회): {}", page, attempt, MAX_ATTEMPTS, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    // 시도할수록 간격을 늘린다. 상대가 과부하인 경우 즉시 재시도는 상황을 악화시킨다.
                    sleep(properties.requestDelay().toMillis() * attempt * 2);
                }
            }
        }
        throw lastError;
    }

    private String firstProposeDate(AssemblyPage page) {
        JsonNode first = page.rows().get(0);
        String value = first.path("PROPOSE_DT").asString("").strip();
        return value.isEmpty() ? null : value;
    }

    /** 호출 한도를 모르는 상태이므로 요청 사이에 간격을 둔다. 차단되면 복구가 오래 걸린다. */
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
            // 인터럽트 신호를 삼키지 않는다. 삼키면 애플리케이션 종료 요청이 무시된다.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("수집이 중단되었습니다", e);
        }
    }
}
