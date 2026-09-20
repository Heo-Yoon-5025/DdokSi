package com.ddoksi.ddoksi.bill.analysis;

import com.ddoksi.ddoksi.bill.entity.AnalysisBatch;
import com.ddoksi.ddoksi.bill.entity.AnalysisBatchItem;
import com.ddoksi.ddoksi.bill.entity.AnalysisBatchStatus;
import com.ddoksi.ddoksi.bill.repository.AnalysisBatchItemRepository;
import com.ddoksi.ddoksi.bill.repository.AnalysisBatchRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배치 제출 이력을 남긴다.
 *
 * <p>별도 빈으로 뽑은 이유는 트랜잭션 경계 때문이다. 제출 직후의 기록은 <b>즉시 커밋</b>되어야
 * 한다. 오케스트레이션 쪽 메서드에 {@code @Transactional} 을 붙이면 배치가 끝날 때까지
 * (최대 24시간) 트랜잭션과 커넥션이 열린 채로 남고, 그 사이 프로세스가 죽으면 기록이 통째로
 * 롤백되어 정작 막으려던 "이미 지불한 배치를 잃어버리는 일" 이 그대로 일어난다.
 *
 * <p>같은 이유로 자기 호출(self-invocation)로는 안 된다 — 프록시를 타지 않아 트랜잭션이
 * 걸리지 않는다. 그래서 호출부와 다른 빈이어야 한다.
 */
@Component
public class AnalysisBatchRecorder {

    private final AnalysisBatchRepository batchRepository;
    private final AnalysisBatchItemRepository itemRepository;

    public AnalysisBatchRecorder(AnalysisBatchRepository batchRepository,
                                 AnalysisBatchItemRepository itemRepository) {
        this.batchRepository = batchRepository;
        this.itemRepository = itemRepository;
    }

    /**
     * 제출한 배치와 그 안에 담은 법안들을 기록한다.
     *
     * <p>항목까지 함께 남기는 이유는 두 가지다. 제출 시점의 입력 해시를 얼려 두어야 하고
     * ({@link AnalysisBatchItem} 주석 참고), 결과에 나타나지 않은 건을 알아채려면 무엇을
     * 보냈는지가 남아 있어야 한다.
     *
     * <p>반환값은 커밋 후 준영속 상태가 된 엔티티다. 호출부는 id 와 providerBatchId 만
     * 읽으므로 지연 로딩을 건드리지 않는다.
     */
    @Transactional
    public AnalysisBatch record(Long runId, String providerBatchId, String promptVersion,
                                String model, List<PendingAnalysis> pending) {
        AnalysisBatch batch = batchRepository.save(AnalysisBatch.builder()
                .runId(runId)
                .providerBatchId(providerBatchId)
                .promptVersion(promptVersion)
                .model(model)
                .requestCount(pending.size())
                .build());

        itemRepository.saveAll(pending.stream()
                .map(item -> new AnalysisBatchItem(batch.getId(), item.billId(), item.sourceHash()))
                .toList());

        return batch;
    }

    /** 아직 수거하지 않은 배치. 새 배치를 제출하기 전에 반드시 먼저 비워야 하는 목록이다. */
    @Transactional(readOnly = true)
    public List<AnalysisBatch> findPending() {
        return batchRepository.findByStatusOrderBySubmittedAtAsc(AnalysisBatchStatus.SUBMITTED);
    }

    /** 제출 시점에 얼려 둔 {@code 법안 id → 입력 해시} 표. */
    @Transactional(readOnly = true)
    public Map<Long, String> sourceHashes(Long batchId) {
        Map<Long, String> hashes = new HashMap<>();
        for (AnalysisBatchItem item : itemRepository.findByBatchId(batchId)) {
            hashes.put(item.getBillId(), item.getSourceHash());
        }
        return hashes;
    }

    @Transactional
    public void markCollected(Long batchId, int succeeded, int errored, int missing) {
        batchRepository.findById(batchId)
                .ifPresent(batch -> batch.markCollected(succeeded, errored, missing));
    }

    @Transactional
    public void markFailed(Long batchId, String errorMessage) {
        batchRepository.findById(batchId).ifPresent(batch -> batch.markFailed(errorMessage));
    }
}
