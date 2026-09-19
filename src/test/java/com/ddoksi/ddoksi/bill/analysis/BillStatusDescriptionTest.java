package com.ddoksi.ddoksi.bill.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.ddoksi.ddoksi.bill.entity.Bill;
import com.ddoksi.ddoksi.bill.entity.BillStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 상태 라벨과 설명문.
 *
 * <p>라벨을 테스트로 못 박는 이유는 앱이 같은 문구를 들고 있기 때문이다
 * ({@code app/src/components/FilterTabs.tsx}). 서버만 바뀌면 배지와 필터 탭이
 * 조용히 갈라진다. 이 테스트가 깨지면 앱도 함께 고쳐야 한다는 신호다.
 */
class BillStatusDescriptionTest {

    @Test
    @DisplayName("MERGED 라벨은 '통합 처리' — 국회 용어 '대안반영' 을 그대로 쓰지 않는다")
    void mergedLabelIsReadable() {
        assertThat(BillStatus.MERGED.label()).isEqualTo("통합 처리");
    }

    @Test
    @DisplayName("MERGED 설명문은 '끝났다' 와 '내용은 남았다' 를 모두 전달한다")
    void mergedDescriptionCarriesBothFacts() {
        String description = BillStatus.MERGED.description();

        assertThat(description).contains("합쳐졌습니다");
        assertThat(description).contains("내용은 살아 있습니다");
        // 위원장 제안 대안은 우리 수집 범위 밖이라 그 결말을 알 수 없다.
        // 통과했다고 단정하면 근거 없는 사실을 말하는 것이 된다.
        assertThat(description).doesNotContain("통과");
    }

    @Test
    @DisplayName("모든 상태가 라벨과 설명문을 갖는다")
    void everyStatusHasLabelAndDescription() {
        for (BillStatus status : BillStatus.values()) {
            assertThat(status.label()).isNotBlank();
            assertThat(status.description()).isNotBlank();
        }
    }

    @Test
    @DisplayName("철회는 폐기와 다른 문장을 받는다 — 스스로 거둔 것과 심사 끝에 버려진 것은 다르다")
    void withdrawnBillGetsItsOwnSentence() {
        Bill withdrawn = billWith(BillStatus.DISCARDED, "철회");
        Bill discarded = billWith(BillStatus.DISCARDED, "폐기");

        assertThat(withdrawn.statusDescription()).contains("스스로 거두어들였습니다");
        assertThat(discarded.statusDescription()).isEqualTo(BillStatus.DISCARDED.description());
    }

    @Test
    @DisplayName("DISCARDED 가 아니면 원문과 무관하게 enum 설명문을 쓴다")
    void otherStatusesIgnoreProcResultRaw() {
        Bill merged = billWith(BillStatus.MERGED, "대안반영폐기");

        assertThat(merged.statusDescription()).isEqualTo(BillStatus.MERGED.description());
    }

    private Bill billWith(BillStatus status, String procResultRaw) {
        return Bill.builder()
                .externalBillId("TEST_" + status + "_" + procResultRaw)
                .title("테스트 법률안")
                .status(status)
                .procResultRaw(procResultRaw)
                .build();
    }
}
