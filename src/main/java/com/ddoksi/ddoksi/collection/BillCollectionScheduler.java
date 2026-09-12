package com.ddoksi.ddoksi.collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 수집 배치 스케줄러.
 *
 * <p><b>기본값은 비활성(enabled=false)이다.</b> 활성 상태가 기본이면
 * 테스트나 로컬 기동만으로도 국회 API 를 1만9천 건 긁어오게 된다.
 * 실행이 필요한 환경에서 {@code ddoksi.collection.scheduled.enabled=true} 로 명시한다.
 *
 * <p>Spring Batch 를 쓰지 않은 이유: 우리 작업은 "페이지 순회 → upsert → 상태 비교" 이고
 * Job/Step/Chunk 구조와 자체 메타데이터 테이블로 얻는 이득이 적다.
 * 실행 이력은 collection_run 으로 직접 관리한다.
 * 나중에 필요해지면 BillCollectionService 를 Step 안에서 호출하면 된다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "ddoksi.collection.scheduled.enabled", havingValue = "true")
public class BillCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillCollectionScheduler.class);

    private final BillCollectionService collectionService;

    @Value("${ddoksi.collection.assembly-age}")
    private int assemblyAge;

    public BillCollectionScheduler(BillCollectionService collectionService) {
        this.collectionService = collectionService;
        log.info("수집 스케줄러 활성화됨");
    }

    /**
     * 정기 수집.
     *
     * 예외를 밖으로 던지지 않는다. 스케줄러 스레드에서 예외가 올라가면
     * 다음 실행이 멈출 수 있다. 실패는 로그와 collection_run 에 남기고 다음 주기를 기다린다.
     */
    @Scheduled(cron = "${ddoksi.collection.scheduled.cron}")
    public void collect() {
        try {
            PageResult result = collectionService.collectMemberBills(assemblyAge);
            log.info("정기 수집 완료: 신규={}, 상태변경={}", result.inserted(), result.statusChanged());
        } catch (Exception e) {
            log.error("정기 수집 실패. 다음 주기에 다시 시도합니다.", e);
        }
    }
}
