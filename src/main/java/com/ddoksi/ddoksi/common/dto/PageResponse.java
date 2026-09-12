package com.ddoksi.ddoksi.common.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 페이지 응답 포맷.
 *
 * Spring Data 의 Page 를 그대로 직렬화하지 않는 이유:
 * Page 의 JSON 구조는 Spring 버전에 따라 바뀌어 왔고(그래서 경고도 뜬다),
 * 내부 구현이 클라이언트 계약이 되어버린다. 앱이 의존할 형태는 우리가 정한다.
 *
 * @param content       이 페이지의 항목
 * @param page          0부터 시작하는 페이지 번호
 * @param size          페이지 크기
 * @param totalElements 전체 건수
 * @param totalPages    전체 페이지 수
 * @param last          마지막 페이지 여부 (앱의 무한 스크롤 종료 판정에 쓴다)
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {
    public static <E, T> PageResponse<T> of(Page<E> page, List<T> content) {
        return new PageResponse<>(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }
}
