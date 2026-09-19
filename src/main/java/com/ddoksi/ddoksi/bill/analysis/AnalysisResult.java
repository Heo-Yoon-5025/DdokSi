package com.ddoksi.ddoksi.bill.analysis;

import java.util.List;

/**
 * 모델이 돌려준 분석 결과 한 건.
 *
 * <p>모든 문자열 필드가 null 을 허용한다. 이것은 실수가 아니라 설계다 —
 * 프롬프트가 "근거가 없으면 비운다" 를 지시하는데, 비울 수 없는 구조를 주면
 * 모델은 근거가 없어도 무언가를 지어내 채운다. 빈칸을 허용해야 빈칸이 나온다.
 *
 * <p>{@code pros} / {@code cons} 가 여기 없는 이유: 입력으로 쓰는 제안이유는 발의자가
 * 자기 법안을 통과시키려 쓴 설득 문서라 반대 근거가 구조적으로 존재하지 않는다.
 * 요구하면 모델이 만들어내고, 정치 콘텐츠에서 그것은 치명적이다. 컬럼은 남겨두었으므로
 * 위원회 검토보고서처럼 근거 있는 출처가 생기면 prompt_version 을 올려 채운다.
 */
public record AnalysisResult(
        String hook,
        String summary,
        String example,
        String background,
        List<String> topics
) {
    /** 태그를 고정 어휘로 거른 사본을 만든다. 저장 직전에 한 번 통과시킨다. */
    public AnalysisResult sanitized() {
        return new AnalysisResult(hook, summary, example, background, BillTopic.sanitize(topics));
    }

    /**
     * 쓸 만한 결과인지.
     *
     * <p>네 항목이 전부 비면 저장할 가치가 없다. 호출은 성공했지만 결과가 없는 것이므로
     * 실패로 기록해 다음 실행에서 재시도되게 한다.
     */
    public boolean isEmpty() {
        return isBlank(hook) && isBlank(summary) && isBlank(example) && isBlank(background);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
