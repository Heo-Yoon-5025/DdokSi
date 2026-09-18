package com.ddoksi.ddoksi.collection.api;

// Spring Boot 4 는 Jackson 3 을 쓴다. 패키지가 com.fasterxml.jackson -> tools.jackson 으로 바뀌었으므로
// 인터넷의 Jackson 2 예제를 그대로 가져오면 컴파일되지 않는다.
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;

/**
 * 열린국회정보 Open API 호출 클라이언트.
 *
 * <p>이 클래스가 국회 API 와 통신하는 유일한 지점이다. 아래 두 함정은 여기서만 처리하고,
 * 상위 서비스는 신경 쓰지 않아도 되게 한다. (2026-08-30 실제 호출로 확인)
 *
 * <ol>
 *   <li><b>WAF 가 특정 User-Agent 를 HTTP 400 으로 막는다.</b> 본문은 "Bad Request." 12바이트
 *       평문이며 API 오류가 아니다. 이걸 모르면 API 전면 장애로 오해하게 된다.
 *       실측 결과 차단되는 것은 {@code curl/*} 과 빈/누락 UA 뿐이고,
 *       그 외(JDK 기본, python-requests, 자체 UA, 브라우저)는 모두 통과한다.
 *       따라서 브라우저를 사칭하지 않고 봇 정체를 밝히는 UA 를 쓴다.
 *   <li><b>오류도 HTTP 200 으로 온다.</b> 상태 코드로는 성공/실패를 알 수 없고
 *       본문의 {@code RESULT.CODE} 를 봐야 한다. 이 구분을 놓치면
 *       에러만 받으면서 "수집 성공"이라고 로그를 남기는 배치가 된다.
 * </ol>
 *
 * <p>응답 구조:
 * <pre>
 * 정상: {"&lt;API명&gt;":[{"head":[{"list_total_count":19058},{"RESULT":{"CODE":"INFO-000"}}]},
 *                      {"row":[{...},{...}]}]}
 * 오류: {"RESULT":{"CODE":"ERROR-290","MESSAGE":"인증키가 유효하지 않습니다..."}}
 * </pre>
 */
@Component
public class AssemblyApiClient {

    private static final Logger log = LoggerFactory.getLogger(AssemblyApiClient.class);

    /** 정상 처리 */
    private static final String CODE_SUCCESS = "INFO-000";
    /** 조건에 맞는 데이터가 없음 — 오류가 아니라 빈 결과다 */
    private static final String CODE_NO_DATA = "INFO-200";

    /** 국회의원 발의법률안 */
    public static final String API_MEMBER_BILLS = "nzmimeepazxkubdpn";

    /** 법률안 제안이유 및 주요내용 */
    public static final String API_BILL_SUMMARY = "BPMBILLSUMMARY";

    private final AssemblyApiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public AssemblyApiClient(AssemblyApiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        // UA 가 비면 JDK 가 자기 기본값으로 채워 우연히 통과할 수 있다.
        // 그런 암묵적 동작에 기대지 않고 설정 누락을 기동 시점에 드러낸다.
        if (properties.userAgent() == null || properties.userAgent().isBlank()) {
            throw new IllegalStateException(
                    "assembly.api.user-agent 가 비어 있습니다. WAF 차단을 피하려면 반드시 설정해야 합니다.");
        }

        // 타임아웃 없는 HTTP 호출을 만들지 않는다. 상대가 응답하지 않으면
        // 배치 스레드가 무한정 붙잡혀 다음 실행까지 밀린다.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(properties.baseUrl())
                // WAF 차단을 피하기 위한 필수 헤더
                .defaultHeader("User-Agent", properties.userAgent())
                .build();
    }

    /**
     * 국회의원 발의법률안 한 페이지를 가져온다.
     *
     * @param assemblyAge 대수 (예: 22)
     * @param pageIndex   1부터 시작하는 페이지 번호
     */
    public AssemblyPage fetchMemberBills(int assemblyAge, int pageIndex) {
        return fetch(API_MEMBER_BILLS, pageIndex, Map.of("AGE", String.valueOf(assemblyAge)));
    }

    /**
     * 법안 한 건의 제안이유 및 주요내용을 가져온다.
     *
     * <p><b>이 API 는 목록을 주지 않는다.</b> BILL_NO 를 빼고 호출하면 ERROR-300 으로 거절되므로
     * 페이징으로 전체를 훑을 수 없고, 법안 한 건당 한 번씩 호출해야 한다.
     * (2026-09-13 실호출 확인)
     *
     * <p>돌려주는 페이지에는 행이 0개 또는 1개 들어 있다.
     * 존재하지 않는 의안번호는 INFO-200 이라 빈 페이지가 되고, 이는 오류가 아니다.
     *
     * @param billNo 의안번호 (예: 2220236)
     */
    public AssemblyPage fetchBillSummary(String billNo) {
        return fetch(API_BILL_SUMMARY, 1, Map.of("BILL_NO", billNo));
    }

    /**
     * 지정한 API 의 한 페이지를 가져온다.
     *
     * @param apiName     API 코드명
     * @param pageIndex   1부터 시작하는 페이지 번호
     * @param extraParams API 별 추가 파라미터
     */
    public AssemblyPage fetch(String apiName, int pageIndex, Map<String, String> extraParams) {
        if (!properties.hasKey()) {
            // 키가 없으면 호출 자체를 하지 않는다. 재시도해도 소용없는 설정 오류다.
            throw AssemblyApiException.fatal(
                    "국회 API 인증키가 설정되지 않았습니다. ASSEMBLY_API_KEY 환경변수를 확인하세요.");
        }

        String body = requestBody(apiName, pageIndex, extraParams);
        JsonNode root = parseJson(body, apiName);

        // 1) 최상위에 RESULT 만 있으면 오류 응답이다 (정상 응답에서는 head 안에 들어 있다)
        JsonNode topLevelResult = root.path("RESULT");
        if (!topLevelResult.isMissingNode()) {
            return handleResultOnlyResponse(topLevelResult, apiName);
        }

        // 2) 정상 응답: {"<API명>": [{head:[...]}, {row:[...]}]}
        JsonNode container = root.path(apiName);
        if (!container.isArray()) {
            throw AssemblyApiException.fatal(
                    "예상과 다른 응답 구조입니다. api=" + apiName + ", body=" + preview(body));
        }

        int totalCount = 0;
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode block : container) {
            JsonNode head = block.path("head");
            if (head.isArray()) {
                totalCount = readTotalCount(head, apiName);
            }
            JsonNode rowArray = block.path("row");
            if (rowArray.isArray()) {
                rowArray.forEach(rows::add);
            }
        }

        log.debug("국회 API 응답: api={}, page={}, rows={}, total={}",
                apiName, pageIndex, rows.size(), totalCount);
        return new AssemblyPage(totalCount, rows);
    }

    /** 실제 HTTP 호출. 네트워크 계층의 실패는 재시도 가치가 있다. */
    private String requestBody(String apiName, int pageIndex, Map<String, String> extraParams) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/" + apiName)
                                .queryParam("KEY", properties.key())
                                .queryParam("Type", "json")
                                .queryParam("pIndex", pageIndex)
                                .queryParam("pSize", properties.pageSize());
                        extraParams.forEach(uriBuilder::queryParam);
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException e) {
            // 4xx 는 요청 자체가 문제이므로 재시도해도 결과가 같다.
            // WAF 의 UA 차단이 여기로 온다(400 "Bad Request."). 본문을 남겨야 원인을 알 수 있다.
            throw AssemblyApiException.fatal(
                    "국회 API 요청 거부: api=" + apiName + ", page=" + pageIndex
                            + ", status=" + e.getStatusCode()
                            + ", body=" + preview(e.getResponseBodyAsString()), e);
        } catch (HttpServerErrorException e) {
            // 5xx 는 서버 쪽 일시 장애일 수 있어 재시도 가치가 있다.
            throw AssemblyApiException.retryable(
                    "국회 API 서버 오류: api=" + apiName + ", page=" + pageIndex
                            + ", status=" + e.getStatusCode(), e);
        } catch (RestClientException e) {
            // 타임아웃, 연결 실패 등 네트워크 계층 문제.
            throw AssemblyApiException.retryable(
                    "국회 API 호출 실패: api=" + apiName + ", page=" + pageIndex, e);
        }
    }

    private JsonNode parseJson(String body, String apiName) {
        if (body == null || body.isBlank()) {
            throw AssemblyApiException.retryable("응답 본문이 비어 있습니다. api=" + apiName);
        }
        // User-Agent 누락 시 앞단이 내려주는 "Bad Request." 같은 평문도 여기서 걸린다.
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw AssemblyApiException.fatal(
                    "응답이 JSON 이 아닙니다. api=" + apiName + ", body=" + preview(body), e);
        }
    }

    /** 최상위 RESULT 만 있는 응답 처리. 데이터 없음은 정상, 나머지는 오류다. */
    private AssemblyPage handleResultOnlyResponse(JsonNode result, String apiName) {
        String code = result.path("CODE").asText("");
        String message = result.path("MESSAGE").asText("");

        if (CODE_NO_DATA.equals(code)) {
            // 페이지 순회가 끝에 도달했을 때 정상적으로 나온다
            log.debug("데이터 없음: api={}, code={}", apiName, code);
            return AssemblyPage.empty();
        }

        // 인증키 무효 등 설정 문제는 재시도해도 결과가 같다. 즉시 중단시킨다.
        throw AssemblyApiException.apiError(
                "국회 API 오류: api=" + apiName + ", code=" + code + ", message=" + message, code);
    }

    /** head 블록에서 전체 건수를 읽고, 함께 들어 있는 RESULT 코드도 검증한다. */
    private int readTotalCount(JsonNode head, String apiName) {
        int totalCount = 0;
        for (JsonNode entry : head) {
            if (entry.has("list_total_count")) {
                totalCount = entry.path("list_total_count").asInt(0);
            }
            JsonNode result = entry.path("RESULT");
            if (!result.isMissingNode()) {
                String code = result.path("CODE").asText("");
                // 행이 함께 왔더라도 성공 코드가 아니면 신뢰하지 않는다
                if (!CODE_SUCCESS.equals(code) && !CODE_NO_DATA.equals(code)) {
                    throw AssemblyApiException.apiError(
                            "국회 API 오류: api=" + apiName + ", code=" + code
                                    + ", message=" + result.path("MESSAGE").asText(""), code);
                }
            }
        }
        return totalCount;
    }

    /** 오류 메시지에 응답 본문을 남기되, 로그가 폭발하지 않도록 잘라낸다. */
    private String preview(String body) {
        if (body == null) {
            return "(null)";
        }
        String trimmed = body.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
    }
}
