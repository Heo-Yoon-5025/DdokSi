package com.ddoksi.ddoksi.collection;

import com.ddoksi.ddoksi.bill.BillStatusMapper;
import com.ddoksi.ddoksi.bill.entity.Bill;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 국회 API 응답 행(JSON)을 {@link Bill} 로 변환한다.
 *
 * <p><b>필드 매핑은 이 클래스에만 둔다.</b> 외부 스펙이 바뀌었을 때 고칠 파일이 하나여야 한다.
 *
 * <p>컬럼으로 뽑지 않은 나머지 필드는 버리지 않고 {@code bill.extra}(JSONB)에 그대로 담는다.
 * 위원회 단계(CMT_*)나 법사위 단계(LAW_*) 정보는 지금 쓰지 않지만 나중에 필요해질 수 있고,
 * 그때 재수집하지 않으려면 지금 받아둔 것을 남겨야 한다.
 */
@Component
public class BillRowMapper {

    private static final Logger log = LoggerFactory.getLogger(BillRowMapper.class);

    /** 전용 컬럼으로 승격시킨 필드. 나머지는 extra 로 흘려보낸다. */
    private static final Set<String> MAPPED_FIELDS = Set.of(
            "BILL_ID", "BILL_NO", "BILL_NAME", "AGE",
            "PROPOSE_DT", "PROC_RESULT", "PROC_DT",
            "COMMITTEE", "COMMITTEE_ID",
            "PROPOSER", "RST_PROPOSER", "DETAIL_LINK");

    /**
     * 응답 행을 Bill 로 변환한다.
     *
     * @return 변환된 Bill. 식별자(BILL_ID)나 법안명이 없으면 null 을 돌려준다.
     *         개별 건의 결함이 페이지 전체 처리를 막아서는 안 되므로 예외를 던지지 않는다.
     */
    public Bill toBill(JsonNode row) {
        String externalBillId = text(row, "BILL_ID");
        String title = text(row, "BILL_NAME");

        // 이 둘이 없으면 저장해도 식별/표시가 불가능하다. 원문(bill_raw)은 이미 남아 있으므로 건너뛴다.
        if (externalBillId == null || title == null) {
            log.warn("필수 필드 누락으로 건너뜀: BILL_ID={}, BILL_NAME={}", externalBillId, title);
            return null;
        }

        String procResultRaw = text(row, "PROC_RESULT");

        // 분류 규칙에 없는 처리결과가 나오면 경고를 남긴다.
        // UNKNOWN 으로 저장되고 원문이 보존되므로 데이터는 잃지 않지만, 규칙 추가가 필요하다는 신호다.
        if (BillStatusMapper.isUnmapped(procResultRaw)) {
            log.warn("분류되지 않은 처리결과: billId={}, procResult='{}'", externalBillId, procResultRaw);
        }

        return Bill.builder()
                .externalBillId(externalBillId)
                .billNo(text(row, "BILL_NO"))
                .assemblyAge(shortValue(row, "AGE"))
                .title(title)
                .status(BillStatusMapper.from(procResultRaw))
                .procResultRaw(procResultRaw)
                .proposerSummary(text(row, "PROPOSER"))
                .rstProposer(text(row, "RST_PROPOSER"))
                .committeeName(text(row, "COMMITTEE"))
                .committeeId(text(row, "COMMITTEE_ID"))
                .proposedDate(date(row, "PROPOSE_DT"))
                .procDate(date(row, "PROC_DT"))
                .detailUrl(text(row, "DETAIL_LINK"))
                .extra(extraFields(row))
                .build();
    }

    /** 원문 내용이 직전 수집분과 같은지 판정하기 위한 해시. */
    public String hash(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 JDK 표준이라 실제로는 발생하지 않는다.
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
        }
    }

    /** 컬럼으로 뽑지 않은 필드를 모아 extra 에 담는다. */
    private Map<String, Object> extraFields(JsonNode row) {
        Map<String, Object> extra = new HashMap<>();
        Iterator<String> names = row.propertyNames().iterator();
        while (names.hasNext()) {
            String name = names.next();
            if (MAPPED_FIELDS.contains(name)) {
                continue;
            }
            String value = text(row, name);
            // 빈 값은 담지 않는다. JSONB 가 의미 없는 null 로 부풀지 않게 한다.
            if (value != null) {
                extra.put(name, value);
            }
        }
        return extra;
    }

    /**
     * 문자열 필드를 읽는다.
     *
     * 공공 API 는 값이 없을 때 null 과 빈 문자열을 섞어 쓴다.
     * 둘을 구분하지 않고 모두 null 로 정규화해, 상위 로직이 한 가지만 신경 쓰게 한다.
     */
    private String text(JsonNode row, String field) {
        JsonNode node = row.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asString().strip();
        return value.isEmpty() ? null : value;
    }

    private Short shortValue(JsonNode row, String field) {
        String value = text(row, field);
        if (value == null) {
            return null;
        }
        try {
            return Short.valueOf(value);
        } catch (NumberFormatException e) {
            log.warn("숫자가 아닌 값: field={}, value='{}'", field, value);
            return null;
        }
    }

    private LocalDate date(JsonNode row, String field) {
        String value = text(row, field);
        if (value == null) {
            return null;
        }
        try {
            // 실측 형식은 yyyy-MM-dd 다 (예: 2026-08-28)
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            log.warn("날짜 파싱 실패: field={}, value='{}'", field, value);
            return null;
        }
    }
}
