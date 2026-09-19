package com.ddoksi.ddoksi.bill.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 분석 배치 스케줄러.
 *
 * <p><b>기본값은 비활성이다.</b> 수집 배치들과 같은 이유에 하나가 더 붙는다 —
 * 이 배치는 호출 한 건이 곧 과금이라, 모르고 켜두면 로컬 기동만으로도 청구서가 생긴다.
 *
 * <p>목록 수집(03:30)과 제안이유 수집(04:00) 뒤인 04:30 에 돈다. 그날 새로 들어온 법안이
 * bill 과 bill_summary 에 모두 들어온 뒤라야 분석할 입력이 갖춰진다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "ddoksi.analysis.scheduled.enabled", havingValue = "true")
public class BillAnalysisScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillAnalysisScheduler.class);

    private final BillAnalysisService analysisService;

    /**
     * 정기 실행 1회 상한.
     *
     * <p>일평균 신규 발의가 23건이라 기본 200이면 충분히 여유가 있다. 상한을 두는 진짜 이유는
     * 분량이 아니라 비용이다 — 어떤 이유로 대상이 갑자기 불어나도 하룻밤에 쓰는 돈이
     * 예측 가능한 범위를 넘지 않게 막는다.
     */
    @Value("${ddoksi.analysis.scheduled.max-bills:200}")
    private int maxBills;

    public BillAnalysisScheduler(BillAnalysisService analysisService) {
        this.analysisService = analysisService;
        log.info("법안 분석 스케줄러 활성화됨");
    }

    /**
     * 정기 분석.
     *
     * <p>예외를 밖으로 던지지 않는다. 스케줄러 스레드로 예외가 올라가면 다음 실행이 멈출 수 있다.
     * 실패는 로그와 collection_run 에 남기고 다음 주기를 기다린다.
     */
    @Scheduled(cron = "${ddoksi.analysis.scheduled.cron}")
    public void analyze() {
        try {
            AnalysisChunkResult result = analysisService.analyzePending(maxBills);
            log.info("정기 분석 완료: 호출={}, 신규={}, 실패={}",
                    result.called(), result.inserted(), result.failed());
        } catch (Exception e) {
            log.error("정기 분석 실패. 다음 주기에 다시 시도합니다.", e);
        }
    }
}
