package com.ddoksi.ddoksi.collection;

import com.ddoksi.ddoksi.bill.entity.Bill;
import com.ddoksi.ddoksi.bill.entity.BillStatusHistory;
import com.ddoksi.ddoksi.bill.repository.BillRepository;
import com.ddoksi.ddoksi.bill.repository.BillStatusHistoryRepository;
import com.ddoksi.ddoksi.collection.entity.BillRaw;
import com.ddoksi.ddoksi.collection.entity.CollectionRun;
import com.ddoksi.ddoksi.collection.repository.BillRawRepository;
import com.ddoksi.ddoksi.collection.repository.CollectionRunRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 응답 한 페이지를 DB 에 반영한다.
 *
 * <p>순회 전체가 아니라 <b>페이지 단위로 트랜잭션을 잡는다.</b> 전체를 한 트랜잭션에 묶으면
 * 190여 페이지를 도는 동안 커넥션과 락을 붙잡고 있게 되고, 마지막에 실패하면 앞서 처리한
 * 모든 것이 함께 롤백된다. 페이지 단위로 커밋하면 중간에 죽어도 거기까지는 남는다.
 *
 * <p><b>멱등성</b>: 같은 배치를 두 번 돌려도 결과가 같아야 한다.
 * 상태 이력은 값이 실제로 바뀐 경우에만 기록하고, 법안은 있으면 갱신/없으면 삽입한다.
 */
@Component
public class BillPagePersister {

    private static final Logger log = LoggerFactory.getLogger(BillPagePersister.class);

    private final BillRepository billRepository;
    private final BillRawRepository billRawRepository;
    private final BillStatusHistoryRepository statusHistoryRepository;
    private final CollectionRunRepository collectionRunRepository;
    private final BillRowMapper rowMapper;

    public BillPagePersister(BillRepository billRepository,
                             BillRawRepository billRawRepository,
                             BillStatusHistoryRepository statusHistoryRepository,
                             CollectionRunRepository collectionRunRepository,
                             BillRowMapper rowMapper) {
        this.billRepository = billRepository;
        this.billRawRepository = billRawRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.collectionRunRepository = collectionRunRepository;
        this.rowMapper = rowMapper;
    }

    /**
     * 페이지의 행들을 반영한다.
     *
     * @param rows      응답 행
     * @param runId     이 처리를 유발한 배치 실행 id.
     *                  엔티티를 넘기지 않는 이유: 다른 트랜잭션에서 만들어진 엔티티는 준영속 상태이므로,
     *                  id 로 받아 이 트랜잭션 안에서 참조를 얻는다.
     * @param sourceApi 원문 출처 API 코드명
     */
    @Transactional
    public PageResult persistPage(List<JsonNode> rows, Long runId, String sourceApi) {
        if (rows.isEmpty()) {
            return PageResult.empty();
        }

        // 같은 페이지 안에 같은 의안이 중복으로 올 수 있다. 마지막 것만 남긴다.
        Map<String, JsonNode> rowsById = new LinkedHashMap<>();
        int skipped = 0;
        for (JsonNode row : rows) {
            String billId = row.path("BILL_ID").asString("").strip();
            if (billId.isEmpty()) {
                skipped++;
                continue;
            }
            rowsById.put(billId, row);
        }

        // 건별로 조회하면 쿼리가 건수만큼 나간다. 페이지 단위로 한 번에 가져와 메모리에서 대조한다.
        Map<String, Bill> existingById = billRepository
                .findAllByExternalBillIdIn(new ArrayList<>(rowsById.keySet()))
                .stream()
                .collect(Collectors.toMap(Bill::getExternalBillId, Function.identity()));

        CollectionRun run = collectionRunRepository.getReferenceById(runId);

        int inserted = 0;
        int statusChanged = 0;
        int unchanged = 0;

        for (Map.Entry<String, JsonNode> entry : rowsById.entrySet()) {
            JsonNode row = entry.getValue();
            Bill incoming = rowMapper.toBill(row);
            if (incoming == null) {
                skipped++;
                continue;
            }

            Bill existing = existingById.get(entry.getKey());

            if (existing == null) {
                // 신규 법안: 원문 → 법안 → 최초 이력
                saveRaw(row, incoming.getExternalBillId(), run, sourceApi);
                Bill saved = billRepository.save(incoming);
                statusHistoryRepository.save(BillStatusHistory.initial(saved, run));
                inserted++;
                continue;
            }

            if (existing.hasStatusChanged(incoming.getStatus(), incoming.getProcResultRaw())) {
                // 상태가 실제로 바뀐 경우에만 이력을 남긴다. 이것이 멱등성의 핵심이다.
                statusHistoryRepository.save(BillStatusHistory.builder()
                        .bill(existing)
                        .fromStatus(existing.getStatus())
                        .toStatus(incoming.getStatus())
                        .fromProcResultRaw(existing.getProcResultRaw())
                        .toProcResultRaw(incoming.getProcResultRaw())
                        .detectedByRun(run)
                        .build());

                saveRaw(row, existing.getExternalBillId(), run, sourceApi);
                existing.refreshFrom(incoming);
                statusChanged++;
            } else {
                // 변화가 없으면 생존 시각만 갱신하고 원문은 다시 저장하지 않는다.
                // 매 실행마다 1만9천 건의 동일한 원문을 쌓으면 테이블이 의미 없이 커진다.
                existing.touch();
                unchanged++;
            }
        }

        PageResult result = new PageResult(inserted, statusChanged, unchanged, skipped);
        log.debug("페이지 반영: 신규={}, 상태변경={}, 변화없음={}, 건너뜀={}",
                inserted, statusChanged, unchanged, skipped);
        return result;
    }

    /** 응답 행 원문을 보관한다. 파싱 로직에 버그가 있어도 재수집 없이 재처리할 수 있게 한다. */
    private void saveRaw(JsonNode row, String externalBillId, CollectionRun run, String sourceApi) {
        String payload = row.toString();
        billRawRepository.save(BillRaw.builder()
                .collectionRun(run)
                .sourceApi(sourceApi)
                .externalBillId(externalBillId)
                .payload(payload)
                .payloadHash(rowMapper.hash(payload))
                .build());
    }
}
