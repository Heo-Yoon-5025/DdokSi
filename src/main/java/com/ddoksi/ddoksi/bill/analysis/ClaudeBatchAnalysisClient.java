package com.ddoksi.ddoksi.bill.analysis;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Usage;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.anthropic.models.messages.batches.MessageBatchRequestCounts;
import com.anthropic.models.messages.batches.MessageBatchResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Claude Batch API 호출. 동기 경로({@link ClaudeAnalysisClient})의 비동기 대응물이다.
 *
 * <p><b>왜 배치인가.</b> 모든 토큰이 50% 할인이다. 파일럿 실측(건당 비캐시입력 698 / 캐시읽기
 * 2,240 / 출력 320 토큰)을 남은 19,306건에 곱하면 동기는 약 34만원에 30시간, 배치는 약 17만원이다.
 * 캐시가 전혀 걸리지 않는 최악의 경우에도 배치가 동기보다 싸다.
 *
 * <p><b>파라미터가 동기 경로와 다른 곳은 캐시 TTL 하나뿐이다.</b> 나머지(모델, maxTokens,
 * 적응형 추론, effort=LOW, 출력 스키마)는 {@link ClaudeAnalysisClient#outputConfig()} 등을
 * 함께 써서 갈라지지 않게 묶어 두었다.
 *
 * <p>TTL 을 1시간으로 올린 이유는 산수다. 시스템 프롬프트는 2,263토큰이고, 캐시 읽기는 정가의
 * 0.1배, 5분 쓰기는 1.25배, 1시간 쓰기는 2배다. 즉 1시간 쓰기 한 번은 정가 대비 2,263토큰을
 * 더 쓰고, 읽기 한 번은 2,037토큰을 아낀다 — 쓰기 한 번이 읽기 1.1번이면 본전이다. 배치는
 * 처리에 한 시간을 넘길 수 있어 5분 TTL 로는 캐시가 중간에 만료되고, 그러면 읽기가 통째로
 * 사라진다. 배치 안에서 캐시 적중은 보장이 아니라 최선 노력이라 확률을 올려두는 쪽이 맞다.
 */
@Component
public class ClaudeBatchAnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeBatchAnalysisClient.class);

    private final AnthropicClient client;
    private final ClaudeAnalysisClient syncClient;

    public ClaudeBatchAnalysisClient(AnthropicClient client, ClaudeAnalysisClient syncClient) {
        this.client = client;
        this.syncClient = syncClient;
    }

    /**
     * 법안 묶음을 배치로 제출하고 Anthropic 이 발급한 배치 id 를 돌려준다.
     *
     * <p><b>이 메서드가 성공한 순간 비용이 확정된다.</b> 그래서 반환값을 받은 호출부는 다른
     * 무엇보다 먼저 그 id 를 저장해야 한다. 저장 전에 프로세스가 죽으면 결과를 찾아갈
     * 방법이 사라진다.
     *
     * <p>예외를 삼키지 않는다. 제출 실패는 건별 문제가 아니라 전체성 문제다.
     */
    public String submit(List<PendingAnalysis> pending) {
        List<BatchCreateParams.Request> requests = pending.stream()
                .map(this::toRequest)
                .toList();

        MessageBatch batch = client.messages().batches().create(
                BatchCreateParams.builder().requests(requests).build());

        log.info("배치 제출 완료: batchId={}, 요청={}건", batch.id(), requests.size());
        return batch.id();
    }

    /** 요청 한 건. {@code custom_id} 는 법안 id 다 — 결과는 순서를 보장하지 않으므로 이 값으로만 맞춘다. */
    private BatchCreateParams.Request toRequest(PendingAnalysis item) {
        return BatchCreateParams.Request.builder()
                .customId(String.valueOf(item.billId()))
                .params(BatchCreateParams.Request.Params.builder()
                        .model(syncClient.model())
                        .maxTokens(ClaudeAnalysisClient.MAX_TOKENS)
                        .thinking(ThinkingConfigAdaptive.builder().build())
                        .outputConfig(ClaudeAnalysisClient.outputConfig())
                        // 시스템 프롬프트는 모든 요청에서 같은 바이트라 캐시 대상이다.
                        // TTL 1시간: 위 클래스 주석의 산수 참고.
                        .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                                .text(BillAnalysisPrompt.SYSTEM)
                                .cacheControl(CacheControlEphemeral.builder()
                                        .ttl(CacheControlEphemeral.Ttl.TTL_1H)
                                        .build())
                                .build()))
                        .addUserMessage(item.userMessage())
                        .build())
                .build();
    }

    /**
     * 배치가 끝날 때까지 기다린다.
     *
     * <p>폴링을 호출부가 아니라 여기서 도는 이유는, 이 루프가 전부 SDK 타입을 만지는 일이기
     * 때문이다({@code MessageBatch}, {@code ProcessingStatus}, {@code RequestCounts}).
     * 호출부에 두면 오케스트레이션 코드가 SDK 에 묶여 시험하기 어려워진다.
     *
     * <p>{@code ended} 는 "전부 성공" 이 아니라 "더 기다릴 것이 없다" 는 뜻이다. 실패·만료된
     * 요청이 섞여 있어도 ended 이므로, 무엇이 어떻게 됐는지는 결과를 읽어봐야 안다.
     *
     * @return 끝났으면 true, 제한 시간을 넘겼으면 false
     */
    public boolean awaitEnd(String providerBatchId, Duration interval, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            MessageBatch batch = client.messages().batches().retrieve(providerBatchId);
            MessageBatchRequestCounts counts = batch.requestCounts();

            if (batch.processingStatus().equals(MessageBatch.ProcessingStatus.ENDED)) {
                log.info("배치 종료: batchId={}, 성공={}, 오류={}, 취소={}, 만료={}",
                        providerBatchId, counts.succeeded(), counts.errored(),
                        counts.canceled(), counts.expired());
                return true;
            }
            log.info("배치 진행 중: batchId={}, 처리중={}, 성공={}, 오류={}",
                    providerBatchId, counts.processing(), counts.succeeded(), counts.errored());
            sleep(interval.toMillis());
        }
        return false;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("배치 대기가 중단되었습니다", e);
        }
    }

    /**
     * 결과를 흘려 읽으며 한 건씩 넘긴다.
     *
     * <p><b>전부 메모리에 모으지 않는다.</b> 한 배치가 수천 건이고 건별 응답에 본문이 딸려
     * 오므로, 리스트로 받으면 수거 단계가 힙을 통째로 먹는다. 스트림을 열어 둔 채 호출부가
     * 100건씩 커밋하게 하는 편이 낫다.
     *
     * <p>결과는 <b>순서가 보장되지 않는다.</b> 그래서 위치가 아니라 {@code custom_id} 로만
     * 법안을 찾는다. 제출 순서와 같을 것이라 가정하면 분석이 엉뚱한 법안에 붙는다.
     *
     * @param hashLookup 법안 id → 제출 시점 입력 해시. 지금 다시 계산하지 않는다
     * @param consumer   건별 결과를 받는 쪽
     * @return 실제로 돌아온 건수. 제출 건수와 다르면 그 차이가 취소·만료된 건이다
     */
    public int streamResults(String providerBatchId,
                             Function<Long, String> hashLookup,
                             Consumer<AnalyzedBill> consumer) {
        UsageTotals totals = new UsageTotals();
        int seen = 0;

        try (StreamResponse<MessageBatchIndividualResponse> stream =
                     client.messages().batches().resultsStreaming(providerBatchId)) {

            for (MessageBatchIndividualResponse response : stream.stream().toList()) {
                seen++;
                Long billId = parseBillId(response.customId());
                if (billId == null) {
                    // custom_id 를 우리가 넣었으므로 일어나선 안 되는 일이다. 다만 여기서
                    // 예외를 던지면 나머지 결과까지 못 가져오므로 건너뛰고 로그만 남긴다.
                    log.error("배치 결과의 custom_id 를 법안 id 로 읽을 수 없습니다: {}",
                            response.customId());
                    continue;
                }
                consumer.accept(toAnalyzed(billId, hashLookup.apply(billId), response.result(), totals));
            }
        }

        totals.log(providerBatchId, seen);
        return seen;
    }

    /** 건별 결과를 저장 가능한 형태로 바꾼다. 성공/실패 모두 행으로 남는다. */
    private AnalyzedBill toAnalyzed(Long billId, String sourceHash,
                                    MessageBatchResult result, UsageTotals totals) {
        String model = syncClient.model();

        if (result.isSucceeded()) {
            Message message = result.asSucceeded().message();
            totals.add(message.usage());
            try {
                AnalysisResult parsed = syncClient.parse(syncClient.extractText(message), billId);
                if (parsed.isEmpty()) {
                    // 호출은 성공했는데 네 항목이 전부 비었다. 동기 경로와 같은 판단 —
                    // 빈 분석을 성공으로 저장하면 다시는 재시도되지 않는다.
                    return AnalyzedBill.failure(billId, sourceHash, model, "모든 항목이 비어 있습니다");
                }
                return AnalyzedBill.success(billId, sourceHash, model, parsed);
            } catch (Exception e) {
                log.warn("배치 응답 해석 실패: billId={}", billId, e);
                return AnalyzedBill.failure(billId, sourceHash, model, summarize(e));
            }
        }

        // errored / canceled / expired. 셋 다 FAILED 행으로 남겨 다음 실행이 재시도하게 한다.
        return AnalyzedBill.failure(billId, sourceHash, model, describe(result));
    }

    private String describe(MessageBatchResult result) {
        String message;
        if (result.isErrored()) {
            message = "errored: " + result.asErrored().error().error();
        } else if (result.isCanceled()) {
            message = "canceled: 배치가 취소되었습니다";
        } else if (result.isExpired()) {
            message = "expired: 24시간 안에 처리되지 못했습니다";
        } else {
            message = "unknown: " + result;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private String summarize(Exception e) {
        String message = e.getClass().getSimpleName() + ": " + e.getMessage();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private Long parseBillId(String customId) {
        try {
            return Long.valueOf(customId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 배치 전체의 토큰 사용량 집계.
     *
     * <p><b>캐시 적중을 확인하려고 둔다.</b> 배치 안에서 캐시는 보장이 아니라 최선 노력이고,
     * 안 걸려도 요청은 그냥 성공한다 — 청구서만 두 배가 된다. 캐시읽기가 0 으로 찍히는지
     * 아닌지가 전량 실행 비용이 17만원이냐 31만원이냐를 가른다.
     */
    private static final class UsageTotals {
        private final AtomicLong input = new AtomicLong();
        private final AtomicLong cacheWrite = new AtomicLong();
        private final AtomicLong cacheRead = new AtomicLong();
        private final AtomicLong output = new AtomicLong();

        void add(Usage usage) {
            input.addAndGet(usage.inputTokens());
            cacheWrite.addAndGet(usage.cacheCreationInputTokens().orElse(0L));
            cacheRead.addAndGet(usage.cacheReadInputTokens().orElse(0L));
            output.addAndGet(usage.outputTokens());
        }

        void log(String providerBatchId, int count) {
            log.info("배치 사용량: batchId={}, 결과={}건, 입력={}, 캐시쓰기={}, 캐시읽기={}, 출력={}",
                    providerBatchId, count, input.get(), cacheWrite.get(), cacheRead.get(), output.get());
            if (count > 0 && cacheRead.get() == 0) {
                log.warn("캐시읽기가 0 입니다. 시스템 프롬프트 캐시가 걸리지 않았을 수 있습니다 "
                        + "— 전량 실행 전에 확인하세요.");
            }
        }
    }
}
