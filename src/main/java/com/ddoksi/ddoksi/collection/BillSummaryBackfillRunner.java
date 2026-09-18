package com.ddoksi.ddoksi.collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 기동 시 제안이유 본문 백필.
 *
 * <p>본문이 없는 법안을 찾아 채운다. 법안 한 건당 API 를 한 번씩 불러야 해서
 * 전량 처리에는 시간이 걸리지만, 이미 받은 법안은 대상에서 빠지므로
 * 중단 후 다시 실행하면 남은 것부터 이어서 받는다.
 *
 * <p><b>기본값은 비활성이다.</b> {@link BillBackfillRunner} 와 같은 이유다 —
 * 켜둔 채로 두면 재시작할 때마다 기동이 길게 지연된다.
 *
 * <p>사용법:
 * <pre>
 * // 소량으로 먼저 확인
 * ./gradlew bootRun --args='--ddoksi.collection.summary-backfill.enabled=true --ddoksi.collection.summary-backfill.max-bills=100'
 * // 전량
 * ./gradlew bootRun --args='--ddoksi.collection.summary-backfill.enabled=true'
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "ddoksi.collection.summary-backfill.enabled", havingValue = "true")
public class BillSummaryBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BillSummaryBackfillRunner.class);

    private final BillSummaryCollectionService summaryService;

    /** 한 번의 실행에서 처리할 최대 법안 수. 기본값은 사실상 무제한이다. */
    @Value("${ddoksi.collection.summary-backfill.max-bills:2147483647}")
    private int maxBills;

    public BillSummaryBackfillRunner(BillSummaryCollectionService summaryService) {
        this.summaryService = summaryService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("제안이유 백필 시작 (상한={}건). 법안 한 건당 한 번씩 호출하므로 오래 걸립니다.", maxBills);
        SummaryResult result = summaryService.collectMissingSummaries(maxBills);
        log.info("제안이유 백필 완료: 신규={}, 빈 본문={}, 데이터없음={}, 건너뜀={}",
                result.inserted(), result.emptyContent(), result.notFound(), result.skipped());
    }
}
