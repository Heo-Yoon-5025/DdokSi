package com.ddoksi.ddoksi.bill;

import static org.assertj.core.api.Assertions.assertThat;

import com.ddoksi.ddoksi.bill.entity.BillStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 상태 매핑 규칙 테스트.
 *
 * 여기 나오는 문자열은 전부 2026-08-30 에 실제 국회 API 응답에서 관측된 값이다.
 * 추측으로 만든 케이스가 아니다.
 */
class BillStatusMapperTest {

    @Nested
    @DisplayName("계류")
    class Pending {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("처리결과가 없으면 계류로 본다 - 국회 API 는 미처리 법안의 PROC_RESULT 를 null 로 준다")
        void blankMeansPending(String procResult) {
            assertThat(BillStatusMapper.from(procResult)).isEqualTo(BillStatus.PENDING);
        }
    }

    @ParameterizedTest
    @CsvSource({
            // 통과 — 본회의 가결
            "원안가결,       PASSED",
            "수정가결,       PASSED",
            // 대안반영 — 법안은 폐기됐으나 내용이 대안에 흡수됨
            "대안반영폐기,   MERGED",
            "수정안반영폐기, MERGED",
            // 폐기 — 내용이 남지 않음
            "임기만료폐기,   DISCARDED",
            "철회,           DISCARDED",
            "폐기,           DISCARDED",
            "부결,           DISCARDED",
    })
    @DisplayName("실제 관측된 처리결과 문자열이 모두 매핑된다")
    void mapsObservedValues(String procResult, BillStatus expected) {
        assertThat(BillStatusMapper.from(procResult)).isEqualTo(expected);
    }

    @Test
    @DisplayName("'대안반영폐기' 는 PASSED 도 DISCARDED 도 아니다 - 이 구분이 C안의 핵심이다")
    void mergedIsItsOwnCategory() {
        BillStatus merged = BillStatusMapper.from("대안반영폐기");

        assertThat(merged).isEqualTo(BillStatus.MERGED);
        assertThat(merged).isNotEqualTo(BillStatus.PASSED);
        assertThat(merged).isNotEqualTo(BillStatus.DISCARDED);
    }

    @Test
    @DisplayName("앞뒤 공백이 섞여 와도 매핑된다")
    void trimsWhitespace() {
        assertThat(BillStatusMapper.from("  원안가결  ")).isEqualTo(BillStatus.PASSED);
    }

    @Test
    @DisplayName("모르는 값은 추측하지 않고 UNKNOWN 으로 둔다")
    void unknownValueIsNotGuessed() {
        // 위원회 단계에서만 관측된 값들. 본회의 처리결과로 오지는 않지만 방어적으로 확인한다.
        assertThat(BillStatusMapper.from("심사미료")).isEqualTo(BillStatus.UNKNOWN);
        assertThat(BillStatusMapper.from("회송")).isEqualTo(BillStatus.UNKNOWN);
        // 국회가 앞으로 새로 쓸 수 있는 값
        assertThat(BillStatusMapper.from("본회의부의안건")).isEqualTo(BillStatus.UNKNOWN);
    }

    @Test
    @DisplayName("미분류 값을 식별할 수 있다 - 배치가 경고 로그를 남기기 위한 것")
    void detectsUnmappedValues() {
        assertThat(BillStatusMapper.isUnmapped("처음보는값")).isTrue();
        assertThat(BillStatusMapper.isUnmapped("원안가결")).isFalse();
        // 계류(null)는 미분류가 아니다 — 정상적인 상태다
        assertThat(BillStatusMapper.isUnmapped(null)).isFalse();
    }
}
