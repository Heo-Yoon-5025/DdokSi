package com.ddoksi.ddoksi.collection.api;

import tools.jackson.databind.JsonNode;
import java.util.List;

/**
 * 국회 API 응답 한 페이지.
 *
 * 행을 특정 DTO 로 바로 변환하지 않고 JsonNode 로 넘기는 이유:
 * 응답 필드가 API 마다 다르고(BILL_NAME vs BILL_NM) 아직 안 쓰는 필드도 많다.
 * 원문을 그대로 들고 있다가 저장 단계에서 필요한 것만 꺼내 쓰고,
 * 나머지는 bill.extra 에 통째로 보관한다.
 *
 * @param totalCount 서버가 알려준 전체 건수 (페이지 순회 종료 판정에 쓴다)
 * @param rows       이 페이지의 행들
 */
public record AssemblyPage(int totalCount, List<JsonNode> rows) {

    public static AssemblyPage empty() {
        return new AssemblyPage(0, List.of());
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }
}
