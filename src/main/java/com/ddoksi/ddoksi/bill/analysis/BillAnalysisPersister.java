package com.ddoksi.ddoksi.bill.analysis;

import com.ddoksi.ddoksi.bill.entity.AnalysisStatus;
import com.ddoksi.ddoksi.bill.entity.Bill;
import com.ddoksi.ddoksi.bill.entity.BillAnalysis;
import com.ddoksi.ddoksi.bill.repository.BillAnalysisRepository;
import com.ddoksi.ddoksi.bill.repository.BillRepository;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분석 결과를 묶음 단위로 저장한다.
 *
 * <p>트랜잭션을 묶음마다 끊는 것은 {@code BillSummaryPersister} 와 같은 이유다.
 * 여기서는 이유가 하나 더 있다 — 이미 호출한 분은 돈을 쓴 것이므로, 뒤에서 실패해도
 * 앞의 결과를 잃으면 그만큼을 다시 결제하게 된다.
 */
@Component
public class BillAnalysisPersister {

    private static final Logger log = LoggerFactory.getLogger(BillAnalysisPersister.class);

    private final BillRepository billRepository;
    private final BillAnalysisRepository analysisRepository;

    public BillAnalysisPersister(BillRepository billRepository,
                                 BillAnalysisRepository analysisRepository) {
        this.billRepository = billRepository;
        this.analysisRepository = analysisRepository;
    }

    /**
     * 묶음 하나를 한 트랜잭션으로 저장한다.
     *
     * <p><b>UNIQUE (bill_id, prompt_version) 이 이 메서드의 형태를 정한다.</b>
     * 같은 법안·같은 버전으로 두 번째 행을 만들 수 없으므로 재시도는 INSERT 가 아니다.
     * 그렇다고 "행이 있으면 건너뛴다" 로 처리하면 FAILED 행이 영구히 남는다.
     * 존재 여부가 아니라 <b>상태와 입력 해시</b>를 함께 봐야 경로가 정해진다.
     */
    @Transactional
    public AnalysisChunkResult persistChunk(List<AnalyzedBill> analyzed, String promptVersion) {
        int inserted = 0, retried = 0, regenerated = 0, unchanged = 0, failed = 0;

        for (AnalyzedBill item : analyzed) {
            Bill bill = billRepository.getReferenceById(item.billId());
            Optional<BillAnalysis> existing =
                    analysisRepository.findByBillAndPromptVersion(bill, promptVersion);

            if (existing.isPresent()) {
                BillAnalysis prev = existing.get();

                // 이미 성공했고 입력도 그대로면 아무것도 하지 않는다. 재호출은 곧 재과금이다.
                // (호출 단계에서 이미 걸렀어야 하지만, 동시 실행 같은 경우를 대비해 한 번 더 본다)
                if (prev.getStatus() == AnalysisStatus.SUCCESS && !prev.isStale(item.sourceHash())) {
                    unchanged++;
                    continue;
                }

                boolean wasFailure = prev.getStatus() == AnalysisStatus.FAILED;
                if (item.isSuccess()) {
                    AnalysisResult result = item.result();
                    prev.replaceWith(result.hook(), result.summary(), result.example(),
                            result.background(), result.topics(), item.sourceHash(), item.model());
                    if (wasFailure) {
                        retried++;
                    } else {
                        regenerated++;
                    }
                } else {
                    // 재시도했는데 또 실패. 기존 내용은 두고 사유만 갱신한다.
                    prev.markFailed(item.errorMessage());
                    failed++;
                }
                continue;
            }

            analysisRepository.save(toNewEntity(bill, item, promptVersion));
            inserted++;
            if (!item.isSuccess()) {
                failed++;
            }
        }

        log.info("분석 저장: 신규={} 재시도={} 재생성={} 변화없음={} 실패={}",
                inserted, retried, regenerated, unchanged, failed);
        return new AnalysisChunkResult(inserted, retried, regenerated, unchanged, failed);
    }

    /**
     * 새 행을 만든다. 실패도 행으로 남긴다.
     *
     * <p>실패를 기록하지 않으면 두 가지가 무너진다. 다음 실행이 그 법안을 "아직 안 한 것" 과
     * 구별하지 못하고, 무엇이 왜 실패했는지 추적할 근거가 사라진다.
     * 분석이 없다고 법안 기본 정보 노출이 막히지는 않으므로 서비스는 계속 동작한다.
     */
    private BillAnalysis toNewEntity(Bill bill, AnalyzedBill item, String promptVersion) {
        if (!item.isSuccess()) {
            return BillAnalysis.failed(bill, promptVersion, item.model(),
                    item.sourceHash(), item.errorMessage());
        }
        AnalysisResult result = item.result();
        return BillAnalysis.builder()
                .bill(bill)
                .promptVersion(promptVersion)
                .model(item.model())
                .sourceHash(item.sourceHash())
                .hook(result.hook())
                .summary(result.summary())
                .example(result.example())
                .background(result.background())
                .topics(result.topics())
                .status(AnalysisStatus.SUCCESS)
                .build();
    }
}
