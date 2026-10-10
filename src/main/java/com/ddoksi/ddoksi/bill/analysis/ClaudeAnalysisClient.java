package com.ddoksi.ddoksi.bill.analysis;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Usage;
import com.ddoksi.ddoksi.bill.entity.Bill;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Claude API 호출. SDK 와 닿는 코드를 여기 한 곳에 모은다.
 *
 * <p>분리한 이유는 SDK 표면이 버전에 따라 바뀌기 때문이다. 호출 형태가 달라져도
 * 고칠 파일이 하나로 끝나고, 배치 로직은 {@link AnalysisResult} 만 알면 된다.
 *
 * <p><b>호출 파라미터에 담긴 판단 두 가지.</b>
 *
 * <p>첫째, {@code effort} 를 LOW 로 둔다. Opus 5 는 thinking 이 기본으로 켜져 있고
 * 추론 토큰이 <b>출력 단가</b>로 과금된다. 기본값(high)으로 19,406건을 돌리면 추론만으로
 * 수백 달러가 붙는다. 법안 요약은 본문을 읽고 정리하는 작업이라 깊은 추론이 필요하지 않다.
 *
 * <p>둘째, thinking 자체는 <b>끄지 않는다</b>. Opus 5 에서 thinking 을 끄면
 * 응답에 {@code <thinking>} 태그가 새거나 도구 호출을 본문 텍스트로 쓰는 알려진 문제가 있다.
 * effort 를 낮추는 쪽이 같은 비용 효과를 내면서 그 함정을 피한다.
 */
@Component
public class ClaudeAnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeAnalysisClient.class);

    /**
     * 응답 상한. 목표 출력은 460자(약 310토큰)지만 추론 토큰이 함께 잡히므로 여유를 둔다.
     *
     * <p>상한은 쓰지 않으면 과금되지 않는다. 반대로 빠듯하게 잡으면 응답이 문장 중간에서
     * 잘리고, 잘린 JSON 은 파싱 실패로 이어져 재호출 비용이 발생한다.
     */
    static final long MAX_TOKENS = 16_000L;

    /**
     * effort 와 출력 스키마. 동기 경로와 배치 경로가 함께 쓴다.
     *
     * <p>두 경로가 이 설정을 각자 만들면 한쪽만 고쳤을 때 결과가 갈린다. 그리고 그 차이는
     * 예외가 아니라 "배치로 돌린 법안만 분석 품질이 다르다" 는 형태로 나타나 알아채기 어렵다.
     */
    static OutputConfig outputConfig() {
        return OutputConfig.builder()
                .effort(OutputConfig.Effort.LOW)
                .format(JsonOutputFormat.builder()
                        .schema(JsonValue.from(BillAnalysisPrompt.outputSchema()))
                        .build())
                .build();
    }

    private final AnthropicClient client;
    private final ObjectMapper objectMapper;
    private final String model;

    public ClaudeAnalysisClient(AnthropicClient client,
                                ObjectMapper objectMapper,
                                @Value("${ddoksi.analysis.model}") String model) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.model = model;
    }

    public String model() {
        return model;
    }

    /**
     * 법안 한 건을 분석한다.
     *
     * <p>예외를 삼키지 않는다. 재시도할지 실패로 기록할지는 호출부가 정한다 —
     * 여기서 판단하면 인증 실패 같은 전체성 오류도 건별 실패로 묻혀서
     * 19,406번을 전부 실패하며 도는 배치가 된다.
     */
    public AnalysisResult analyze(Bill bill, String billText) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                // 적응형 추론 유지. 깊이는 effort 로 조절한다.
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(outputConfig())
                // 시스템 프롬프트는 19,406번 동일하므로 캐시 대상이다.
                // plain .system(String) 오버로드는 cache_control 을 실을 수 없다.
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(BillAnalysisPrompt.SYSTEM)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .addUserMessage(BillAnalysisPrompt.userMessage(bill, billText))
                .build();

        Message response = client.messages().create(params);
        logUsage(bill.getId(), response.usage());
        return parse(extractText(response), bill.getId());
    }

    /**
     * 캐시가 실제로 걸렸는지 남긴다.
     *
     * <p>캐시 실패는 조용하다 — 요청은 성공하고 청구서만 늘어난다. 프리픽스에 글자 하나가
     * 섞여도 이렇게 되므로, 로그로 확인할 수 있게 해 둔다. 전량 실행 전에
     * {@code 캐시읽기} 가 0 이 아닌지 반드시 본다.
     */
    private void logUsage(Long billId, Usage usage) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("분석 호출: billId={} 입력={} 캐시쓰기={} 캐시읽기={} 출력={}",
                billId,
                usage.inputTokens(),
                usage.cacheCreationInputTokens().orElse(0L),
                usage.cacheReadInputTokens().orElse(0L),
                usage.outputTokens());
    }

    /**
     * 응답에서 텍스트 블록만 이어 붙인다. thinking 블록은 여기서 걸러진다.
     *
     * <p>{@link ClaudeBatchAnalysisClient} 도 같은 해석을 써야 한다. 동기 경로와 배치 경로가
     * 응답을 다르게 읽으면 어느 경로로 만들었느냐에 따라 저장 결과가 갈린다.
     */
    String extractText(Message response) {
        StringBuilder text = new StringBuilder();
        response.content().forEach(block -> block.text().ifPresent(t -> text.append(t.text())));
        return text.toString().strip();
    }

    /**
     * 응답 JSON 을 결과로 바꾼다.
     *
     * <p>structured outputs 를 걸었으므로 형태는 스키마를 따르지만, 파싱 실패를 예외로
     * 올려 호출부가 재시도하게 한다. 조용히 빈 결과를 돌려주면 그 법안은 빈 분석이
     * 성공으로 저장되어 다시는 재시도되지 않는다.
     */
    AnalysisResult parse(String json, Long billId) {
        if (json.isEmpty()) {
            throw new IllegalStateException("응답에 텍스트 블록이 없습니다: billId=" + billId);
        }
        JsonNode root = objectMapper.readTree(json);
        List<String> topics = new java.util.ArrayList<>();
        root.path("topics").forEach(node -> topics.add(node.asString("")));

        if (BillTopic.hasUnknown(topics)) {
            // 프롬프트가 어휘를 지켰는지 보는 신호. 파일럿에서 이 경고가 잦으면
            // 스키마에 enum 을 거는 것을 검토한다.
            log.warn("어휘 밖 주제 태그를 걸러냈습니다: billId={}, 응답={}", billId, topics);
        }

        return new AnalysisResult(
                textOrNull(root, "hook"),
                textOrNull(root, "summary"),
                textOrNull(root, "example"),
                textOrNull(root, "background"),
                topics).sanitized();
    }

    /** 빈 문자열과 JSON null 을 모두 null 로 모은다. 저장 쪽에서 한 가지 경우만 보게 한다. */
    private String textOrNull(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asString("").strip();
        return value.isEmpty() ? null : value;
    }
}
