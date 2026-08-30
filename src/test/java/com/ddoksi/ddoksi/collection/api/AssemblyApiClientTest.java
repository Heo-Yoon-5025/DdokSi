package com.ddoksi.ddoksi.collection.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 국회 API 클라이언트 통합 테스트 — 실제 네트워크를 호출한다.
 *
 * 인증키가 없으면 건너뛴다. (.env 의 ASSEMBLY_API_KEY)
 *
 * 목(mock)이 아니라 실제 호출로 검증하는 이유:
 * 이 클라이언트가 막아야 할 두 함정(User-Agent 누락 시 WAF 차단, 오류도 HTTP 200)은
 * 실제 서버가 어떻게 응답하는가에 대한 것이다. 목으로는 우리가 상상한 응답만 검증하게 된다.
 */
@SpringBootTest
class AssemblyApiClientTest {

    @Autowired
    private AssemblyApiClient client;

    @Autowired
    private AssemblyApiProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void requireApiKey() {
        Assumptions.assumeTrue(properties.hasKey(), "인증키가 없어 건너뜁니다 (.env 확인)");
    }

    @Test
    @DisplayName("22대 발의법률안 첫 페이지를 실제로 가져온다")
    void fetchesFirstPage() {
        AssemblyPage page = client.fetchMemberBills(22, 1);

        assertThat(page.totalCount()).isGreaterThan(10_000);
        assertThat(page.rows()).hasSize(properties.pageSize());

        JsonNode first = page.rows().get(0);
        // 우리 스키마가 의존하는 필드들이 실제로 오는지 확인한다
        assertThat(first.path("BILL_ID").asString()).startsWith("PRC_");
        assertThat(first.path("BILL_NAME").asString()).isNotBlank();
        assertThat(first.path("PROPOSE_DT").asString()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(first.path("AGE").asString()).isEqualTo("22");
    }

    @Test
    @DisplayName("페이지 끝을 넘어가면 빈 페이지를 돌려준다 - 순회 종료 조건")
    void returnsEmptyPastLastPage() {
        // 전체 건수를 훨씬 넘는 페이지를 요청하면 INFO-200(데이터 없음)이 온다.
        // 이것은 오류가 아니라 정상적인 종료 신호다.
        AssemblyPage page = client.fetchMemberBills(22, 99_999);

        assertThat(page.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("인증키가 틀리면 재시도 불가 오류로 즉시 중단시킨다")
    void invalidKeyIsNotRetryable() {
        AssemblyApiProperties badKey = new AssemblyApiProperties(
                "this-key-does-not-exist",
                properties.baseUrl(),
                properties.userAgent(),
                properties.connectTimeout(),
                properties.readTimeout(),
                5,
                properties.requestDelay());
        AssemblyApiClient badClient = new AssemblyApiClient(badKey, objectMapper);

        assertThatThrownBy(() -> badClient.fetchMemberBills(22, 1))
                .isInstanceOf(AssemblyApiException.class)
                .hasMessageContaining("ERROR-290")
                .satisfies(e -> {
                    AssemblyApiException ex = (AssemblyApiException) e;
                    // 키가 틀린 건 다시 호출해도 마찬가지다. 재시도 대상이 아니어야 한다.
                    assertThat(ex.isRetryable()).isFalse();
                    assertThat(ex.getResultCode()).isEqualTo("ERROR-290");
                });
    }

    @Test
    @DisplayName("curl 계열 User-Agent 는 WAF 가 막는다 - 이 클라이언트가 UA 를 강제하는 이유")
    void curlUserAgentIsBlockedByWaf() {
        // 실측(2026-08-30): 차단되는 것은 curl/* 과 빈 UA 뿐이고,
        // JDK 기본이나 자체 UA 는 통과한다. 브라우저 사칭은 필요 없다.
        AssemblyApiClient blocked = clientWithUserAgent("curl/8.7.1");

        // WAF 는 JSON 이 아닌 평문 "Bad Request." 를 내려준다.
        // 원인을 알 수 있도록 본문 일부가 예외 메시지에 남아야 한다.
        assertThatThrownBy(() -> blocked.fetchMemberBills(22, 1))
                .isInstanceOf(AssemblyApiException.class)
                .hasMessageContaining("Bad Request");
    }

    @Test
    @DisplayName("봇 정체를 밝히는 자체 User-Agent 로도 정상 호출된다")
    void ownBotUserAgentIsAccepted() {
        AssemblyApiClient bot = clientWithUserAgent("ddoksi-collector/1.0");

        assertThat(bot.fetchMemberBills(22, 1).rows()).isNotEmpty();
    }

    @Test
    @DisplayName("User-Agent 설정이 비면 기동 시점에 실패한다 - JDK 기본값에 우연히 기대지 않는다")
    void blankUserAgentFailsFast() {
        assertThatThrownBy(() -> clientWithUserAgent(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("user-agent");
    }

    @Test
    @DisplayName("키가 비어 있으면 호출하지 않고 즉시 실패한다 - 호출 한도를 낭비하지 않는다")
    void missingKeyFailsFast() {
        AssemblyApiProperties noKey = new AssemblyApiProperties(
                "", properties.baseUrl(), properties.userAgent(),
                properties.connectTimeout(), properties.readTimeout(), 5, properties.requestDelay());
        AssemblyApiClient keylessClient = new AssemblyApiClient(noKey, objectMapper);

        assertThatThrownBy(() -> keylessClient.fetch("anything", 1, Map.of()))
                .isInstanceOf(AssemblyApiException.class)
                .hasMessageContaining("ASSEMBLY_API_KEY");
    }

    @Test
    @DisplayName("설정이 실제로 주입된다")
    void propertiesAreBound() {
        assertThat(properties.baseUrl()).isEqualTo("https://open.assembly.go.kr/portal/openapi");
        // 브라우저를 사칭하지 않고 봇 정체를 밝힌다
        assertThat(properties.userAgent()).startsWith("ddoksi-collector/");
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.pageSize()).isPositive();
    }

    /** User-Agent 만 바꾼 클라이언트를 만든다. 나머지 설정은 실제 값을 그대로 쓴다. */
    private AssemblyApiClient clientWithUserAgent(String userAgent) {
        AssemblyApiProperties props = new AssemblyApiProperties(
                properties.key(), properties.baseUrl(), userAgent,
                properties.connectTimeout(), properties.readTimeout(), 5, properties.requestDelay());
        return new AssemblyApiClient(props, objectMapper);
    }
}
