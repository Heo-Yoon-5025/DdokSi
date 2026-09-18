package com.ddoksi.ddoksi.collection;

import com.ddoksi.ddoksi.bill.entity.Bill;
import com.ddoksi.ddoksi.bill.entity.BillSummary;
import com.ddoksi.ddoksi.bill.repository.BillRepository;
import com.ddoksi.ddoksi.bill.repository.BillSummaryRepository;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 받아온 제안이유를 묶음 단위로 저장한다.
 *
 * <p>트랜잭션 경계를 묶음마다 끊는 것이 핵심이다. 1만9천 건 전체를 한 트랜잭션에 넣으면
 * 수십 분 동안 커넥션을 붙잡고, 마지막에 실패하면 그때까지 받아온 것이 전부 사라진다.
 * 이미 {@link BillPagePersister} 에서 같은 이유로 페이지 단위 커밋을 택했다.
 */
@Component
public class BillSummaryPersister {

    private static final Logger log = LoggerFactory.getLogger(BillSummaryPersister.class);

    private final BillRepository billRepository;
    private final BillSummaryRepository summaryRepository;

    public BillSummaryPersister(BillRepository billRepository,
                                BillSummaryRepository summaryRepository) {
        this.billRepository = billRepository;
        this.summaryRepository = summaryRepository;
    }

    /** 묶음 하나를 한 트랜잭션으로 저장한다. */
    @Transactional
    public SummaryResult persistChunk(List<FetchedSummary> fetched) {
        int inserted = 0, updated = 0, unchanged = 0, emptyContent = 0, notFound = 0, skipped = 0;

        for (FetchedSummary item : fetched) {
            // 의안번호로 조회했는데 데이터가 없던 건. 오류가 아니라 사실이므로 기록만 남긴다.
            // 행을 만들지 않으므로 다음 실행에서 다시 시도된다 — 국회 쪽에 나중에 등재될 수 있다.
            if (!item.found()) {
                notFound++;
                continue;
            }

            try {
                Bill bill = billRepository.getReferenceById(item.billId());

                // 응답이 다른 법안 것인지 확인한다. 의안번호를 키로 쓰는 API 라
                // 이 대조가 없으면 엉뚱한 본문이 조용히 붙어도 알 수 없다.
                if (isMismatched(bill, item)) {
                    log.warn("의안 ID 불일치로 건너뜀: billNo={}, 응답={}, 기대={}",
                            item.billNo(), item.externalBillId(), bill.getExternalBillId());
                    skipped++;
                    continue;
                }

                Optional<BillSummary> existing = summaryRepository.findByBill(bill);
                if (existing.isPresent()) {
                    // 재수집 경로. 내용이 그대로면 갱신하지 않아 updated_at 이 의미를 유지한다.
                    if (existing.get().updateContent(item.summary())) {
                        updated++;
                    } else {
                        unchanged++;
                    }
                    if (!existing.get().hasContent()) {
                        emptyContent++;
                    }
                    continue;
                }

                BillSummary saved = summaryRepository.save(BillSummary.builder()
                        .bill(bill)
                        .summary(item.summary())
                        .externalBillId(item.externalBillId())
                        .build());
                inserted++;
                // 본문이 빈 채로 저장된 건은 따로 센다. 이것도 "조회 완료" 이므로
                // 행을 남겨야 다음 실행에서 같은 건을 또 호출하지 않는다.
                if (!saved.hasContent()) {
                    emptyContent++;
                }

            } catch (RuntimeException e) {
                // 한 건의 실패가 묶음 전체를 무너뜨리지 않게 한다.
                log.warn("본문 저장 실패: billNo={}, 원인={}", item.billNo(), e.toString());
                skipped++;
            }
        }

        return new SummaryResult(inserted, updated, unchanged, emptyContent, notFound, skipped);
    }

    /** 응답의 의안 ID 가 우리가 아는 값과 다른지. 둘 중 하나라도 비어 있으면 판단하지 않는다. */
    private boolean isMismatched(Bill bill, FetchedSummary item) {
        String responseId = item.externalBillId();
        String knownId = bill.getExternalBillId();
        if (responseId == null || responseId.isBlank() || knownId == null || knownId.isBlank()) {
            return false;
        }
        return !responseId.equals(knownId);
    }
}
