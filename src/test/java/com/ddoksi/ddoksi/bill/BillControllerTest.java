package com.ddoksi.ddoksi.bill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ddoksi.ddoksi.bill.entity.BillStatus;
import com.ddoksi.ddoksi.bill.repository.BillRepository;
import com.ddoksi.ddoksi.support.BillFixtures;
import com.ddoksi.ddoksi.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// Boot 4 에서 패키지가 org.springframework.boot.test.autoconfigure.web.servlet 에서 옮겨졌다
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 법안 조회 API 테스트.
 *
 * 테스트 DB 를 비우고 픽스처를 심은 뒤 검증한다. 예전에는 개발 DB 에 쌓인 수집분에
 * 기대고 있었는데, 그러면 각자의 DB 상태에 따라 결과가 달라져 재현이 되지 않았다.
 *
 * 오류 응답의 상태 코드를 명시적으로 검증한다. 예외 핸들러를 잘못 넓게 잡으면
 * Spring 이 이미 올바르게 매핑해 둔 400 을 500 으로 덮어쓰게 되는데,
 * 실제로 그 문제가 있었고 이 테스트가 재발을 막는다.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class BillControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private BillRepository billRepository;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private BillFixtures fixtures;

    @BeforeEach
    void seedFixtures() {
        // 매번 비우고 다시 심는다. 이전 테스트가 남긴 데이터가 건수 검증을 흔들면 안 된다.
        cleaner.clean();
        fixtures.seed();
    }

    @Test
    @DisplayName("목록은 페이지 정보와 함께 최신 제안일 순으로 내려온다")
    void listReturnsPagedBills() throws Exception {
        mockMvc.perform(get("/api/bills").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(5))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.last").value(false))
                // 목록 항목에는 로직용 상태와 표시용 라벨이 함께 담긴다
                .andExpect(jsonPath("$.content[0].status").isNotEmpty())
                .andExpect(jsonPath("$.content[0].statusLabel").isNotEmpty())
                .andExpect(jsonPath("$.content[0].title").isNotEmpty());
    }

    @Test
    @DisplayName("상태 필터가 해당 상태만 돌려준다")
    void filtersByStatus() throws Exception {
        mockMvc.perform(get("/api/bills").param("status", "PASSED").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].status").value(
                        org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.is(BillStatus.PASSED.name()))))
                .andExpect(jsonPath("$.content[0].statusLabel").value("통과"));
    }

    @Test
    @DisplayName("통합 처리(MERGED)가 별도 상태로 조회된다")
    void filtersByMergedStatus() throws Exception {
        // 라벨을 여기서 못 박아 두는 이유: 앱 필터 탭(FilterTabs.tsx)이 같은 문구를 들고 있어
        // 서버만 바뀌면 배지와 탭이 조용히 갈라진다. 바꿀 때는 양쪽을 함께 바꾼다는 신호다.
        mockMvc.perform(get("/api/bills").param("status", "MERGED").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].statusLabel").value("통합 처리"));
    }

    @Test
    @DisplayName("키워드로 법안명을 검색한다")
    void searchesByKeyword() throws Exception {
        mockMvc.perform(get("/api/bills").param("keyword", "개인정보").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].title").value(
                        org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.containsString("개인정보"))));
    }

    @Test
    @DisplayName("빈 키워드는 필터 없음과 같게 동작한다 - 앱이 빈 검색창을 보내도 된다")
    void blankKeywordIsIgnored() throws Exception {
        mockMvc.perform(get("/api/bills").param("keyword", "   ").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3));
    }

    @Test
    @DisplayName("상임위 목록을 내려준다")
    void returnsCommittees() throws Exception {
        mockMvc.perform(get("/api/bills/committees"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(10)));
    }

    @Test
    @DisplayName("상세 조회에 원문 링크와 상태 이력이 포함된다")
    void returnsDetail() throws Exception {
        Long id = billRepository.findAll(
                org.springframework.data.domain.PageRequest.of(0, 1)).getContent().get(0).getId();

        mockMvc.perform(get("/api/bills/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.statusLabel").isNotEmpty())
                // 원문 링크는 AI 요약이 틀렸을 때 사용자가 확인할 경로라 항상 있어야 한다
                .andExpect(jsonPath("$.detailUrl").value(
                        org.hamcrest.Matchers.containsString("assembly.go.kr")))
                // 신규 수집 시 최초 이력이 한 건 생긴다
                .andExpect(jsonPath("$.statusHistory.length()")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("없는 법안은 404")
    void missingBillIsNotFound() throws Exception {
        mockMvc.perform(get("/api/bills/{id}", 99_999_999L))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("잘못된 status 값은 400 - 500 으로 새지 않아야 한다")
    void invalidStatusIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/bills").param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("요청 형식이 올바르지 않습니다"));
    }

    @Test
    @DisplayName("size 상한을 넘기면 400 - 클라이언트가 DB 를 통째로 긁어가지 못한다")
    void oversizedPageIsRejected() throws Exception {
        mockMvc.perform(get("/api/bills").param("size", "999"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("지원하지 않는 메서드는 405")
    void unsupportedMethodIsRejected() throws Exception {
        mockMvc.perform(post("/api/bills"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("Expo 웹 개발 서버 출처에 CORS 를 허용한다")
    void allowsExpoWebOrigin() throws Exception {
        mockMvc.perform(get("/api/bills").param("size", "1")
                        .header("Origin", "http://localhost:8081"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8081"));
    }

    @Test
    @DisplayName("응답 형식이 Spring 의 Page 내부 구조에 의존하지 않는다")
    void pageResponseShapeIsOurs() throws Exception {
        // Page 를 그대로 직렬화하면 pageable/sort 같은 내부 필드가 노출되고
        // Spring 버전에 따라 구조가 바뀐다. 앱이 의존할 형태는 우리가 정한다.
        mockMvc.perform(get("/api/bills").param("size", "1"))
                .andExpect(jsonPath("$.pageable").doesNotExist())
                .andExpect(jsonPath("$.sort").doesNotExist())
                .andExpect(jsonPath("$.numberOfElements").doesNotExist());
    }
}
