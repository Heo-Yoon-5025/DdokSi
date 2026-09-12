package com.ddoksi.ddoksi.collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 기동 시 1회 전체 수집(백필).
 *
 * <p>DB 가 비어 있는 상태에서 과거 법안을 한 번에 채워 넣을 때 쓴다.
 * 정기 스케줄은 매일 최신분만 확인하므로, 처음 한 번은 전체를 훑어야 한다.
 *
 * <p><b>기본값은 비활성이다.</b> 켜진 채로 두면 애플리케이션을 재시작할 때마다
 * 1만9천 건을 다시 훑게 된다. 멱등성이 보장되어 데이터가 망가지지는 않지만
 * 기동이 수 분간 지연되고 호출 한도를 낭비한다.
 *
 * <p>사용법:
 * <pre>
 * ./gradlew bootRun --args='--ddoksi.collection.backfill.enabled=true'
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "ddoksi.collection.backfill.enabled", havingValue = "true")
public class BillBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BillBackfillRunner.class);

    private final BillCollectionService collectionService;

    @Value("${ddoksi.collection.assembly-age}")
    private int assemblyAge;

    public BillBackfillRunner(BillCollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("백필 시작 (대수={}). 전체 순회이므로 수 분이 걸립니다.", assemblyAge);
        PageResult result = collectionService.collectMemberBills(assemblyAge);
        log.info("백필 완료: 신규={}, 상태변경={}, 변화없음={}, 건너뜀={}",
                result.inserted(), result.statusChanged(), result.unchanged(), result.skipped());
    }
}
