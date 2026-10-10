package com.ddoksi.ddoksi.bill.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 기동 시 배치 백필.
 *
 * <p>전량 백필의 실행 경로다. 동기 러너({@link BillAnalysisBackfillRunner})는 표본 실행용으로
 * 남겨 둔다 — 소량을 돌려보고 결과를 바로 눈으로 봐야 할 때는 동기가 맞다.
 *
 * <p><b>기본값은 비활성이다.</b> 다른 백필 러너들과 같은 이유에, 켜두면 재시작할 때마다
 * 돈이 나간다는 이유가 더해진다. 배치는 그 액수가 더 크다.
 *
 * <p>사용법:
 * <pre>
 * // 소량으로 배치 경로 자체를 확인한다 (제출 → 폴링 → 수거가 도는지)
 * ./gradlew bootRun --args='--ddoksi.analysis.batch-backfill.enabled=true \
 *     --ddoksi.analysis.batch-backfill.max-bills=20 \
 *     --ddoksi.analysis.batch.chunk-size=20 --server.port=0'
 *
 * // 전량 (약 17만원, 배치 10개)
 * ./gradlew bootRun --args='--ddoksi.analysis.batch-backfill.enabled=true \
 *     --ddoksi.analysis.batch-backfill.max-bills=20000 --server.port=0'
 * </pre>
 *
 * <p>중단되어도 안전하다. 제출한 배치는 {@code analysis_batch} 에 남아 있고, 다시 실행하면
 * 새 배치를 제출하기 전에 그것부터 수거한다.
 */
@Component
@ConditionalOnProperty(name = "ddoksi.analysis.batch-backfill.enabled", havingValue = "true")
public class BillAnalysisBatchRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BillAnalysisBatchRunner.class);

    private final BillAnalysisBatchService batchService;

    /**
     * 이번 실행에서 새로 제출할 최대 법안 수.
     *
     * <p>동기 러너와 같은 이유로 기본값을 무제한으로 두지 않았다. 여기서 상한을 빠뜨리면
     * 기동 한 번에 19,306건이 제출되고 그 순간 약 17만원이 확정된다.
     */
    @Value("${ddoksi.analysis.batch-backfill.max-bills:100}")
    private int maxBills;

    public BillAnalysisBatchRunner(BillAnalysisBatchService batchService) {
        this.batchService = batchService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("배치 분석 백필 시작 (제출 상한={}건). 제출하는 순간 비용이 확정됩니다.", maxBills);
        AnalysisChunkResult result = batchService.runBackfill(maxBills);
        log.info("배치 분석 백필 완료: 호출={}, 신규={}, 재시도={}, 실패={}",
                result.called(), result.inserted(), result.retried(), result.failed());
    }
}
