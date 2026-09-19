package com.ddoksi.ddoksi.bill.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 기동 시 분석 백필.
 *
 * <p><b>이 러너는 동기 호출 경로다.</b> 설계상 전량 백필은 Batch API(모든 토큰 50% 할인)로
 * 돌리기로 했지만, 그쪽은 제출·폴링·결과 수거가 얽혀 있어 프롬프트가 검증된 뒤에 붙이는 것이
 * 순서다. 먼저 할 일은 표본 실행이고, 표본은 결과를 바로 봐야 하므로 어차피 동기가 맞다.
 *
 * <p>중단되어도 이미 성공한 법안은 대상에서 빠지므로 다시 실행하면 남은 것부터 이어서 한다.
 *
 * <p><b>기본값은 비활성이다.</b> 다른 백필 러너들과 같은 이유에, 켜두면 재시작할 때마다
 * 돈이 나간다는 이유가 더해진다.
 *
 * <p>사용법:
 * <pre>
 * // 표본 100건 — 토큰 비율, 추론량, 출력 길이, 캐시 적중을 실측하고 품질을 눈으로 본다
 * ./gradlew bootRun --args='--ddoksi.analysis.backfill.enabled=true --ddoksi.analysis.backfill.max-bills=100'
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "ddoksi.analysis.backfill.enabled", havingValue = "true")
public class BillAnalysisBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BillAnalysisBackfillRunner.class);

    private final BillAnalysisService analysisService;

    /**
     * 한 번의 실행에서 처리할 최대 법안 수.
     *
     * <p>다른 백필 러너들과 달리 기본값을 무제한으로 두지 않았다. 여기서 상한을 빠뜨리면
     * 19,406건이 전량 호출된다. 전량을 돌릴 때는 그 수를 명시적으로 넘기게 한다.
     */
    @Value("${ddoksi.analysis.backfill.max-bills:100}")
    private int maxBills;

    public BillAnalysisBackfillRunner(BillAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("법안 분석 백필 시작 (상한={}건). 호출 한 건이 곧 비용입니다.", maxBills);
        AnalysisChunkResult result = analysisService.analyzePending(maxBills);
        log.info("법안 분석 백필 완료: 호출={}, 신규={}, 재시도={}, 실패={}",
                result.called(), result.inserted(), result.retried(), result.failed());
    }
}
