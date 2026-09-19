package com.ddoksi.ddoksi.bill.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 주제 어휘 정제 규칙.
 *
 * <p>이 필터가 무너지면 어휘 밖의 값이 DB 에 들어가고, 그 순간 "이번 달 환경 법안" 같은
 * 묶음 조회가 조용히 일부를 빠뜨리기 시작한다. 모델 출력은 언제든 어휘를 벗어날 수 있으므로
 * 마지막 방어선이 여기다.
 */
class BillTopicTest {

    @Test
    @DisplayName("어휘에 있는 태그만 남고 나머지는 걸러진다")
    void keepsOnlyKnownLabels() {
        List<String> sanitized = BillTopic.sanitize(List.of("교통·안전", "우주항공", "교육·보육"));

        assertThat(sanitized).containsExactly("교통·안전", "교육·보육");
    }

    @Test
    @DisplayName("상한을 넘으면 뒤를 버린다 — 앞에 온 것이 더 중심적인 주제다")
    void limitsToMaxPerBill() {
        List<String> sanitized =
                BillTopic.sanitize(List.of("세금·재정", "일자리·노동", "복지·연금"));

        assertThat(sanitized).hasSize(BillTopic.MAX_PER_BILL);
        assertThat(sanitized).containsExactly("세금·재정", "일자리·노동");
    }

    @Test
    @DisplayName("중복은 제거하고 공백은 다듬는다")
    void deduplicatesAndTrims() {
        assertThat(BillTopic.sanitize(Arrays.asList(" 의료·보건 ", "의료·보건", null)))
                .containsExactly("의료·보건");
    }

    @Test
    @DisplayName("맞는 어휘가 없으면 빈 목록 — '기타' 로 채우지 않는다")
    void returnsEmptyWhenNothingMatches() {
        assertThat(BillTopic.sanitize(List.of("해당없음"))).isEmpty();
        assertThat(BillTopic.sanitize(null)).isEmpty();
    }

    @Test
    @DisplayName("어휘 밖 값이 있었는지 알려준다 — 경고 로그의 근거가 된다")
    void detectsUnknownLabels() {
        assertThat(BillTopic.hasUnknown(List.of("교통·안전"))).isFalse();
        assertThat(BillTopic.hasUnknown(List.of("교통·안전", "우주항공"))).isTrue();
    }

    @Test
    @DisplayName("프롬프트에 박히는 어휘 목록과 enum 이 갈라지지 않는다")
    void labelsCoverEveryConstant() {
        assertThat(BillTopic.labels()).hasSize(BillTopic.values().length);
        assertThat(BillAnalysisPrompt.SYSTEM).contains(BillTopic.labels());
    }
}
