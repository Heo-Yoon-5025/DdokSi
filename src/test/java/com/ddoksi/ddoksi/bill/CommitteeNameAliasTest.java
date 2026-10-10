package com.ddoksi.ddoksi.bill;

import static org.assertj.core.api.Assertions.assertThat;

import com.ddoksi.ddoksi.bill.dto.BillSummaryResponse;
import com.ddoksi.ddoksi.bill.entity.BillStatus;
import com.ddoksi.ddoksi.common.dto.PageResponse;
import com.ddoksi.ddoksi.support.BillFixtures;
import com.ddoksi.ddoksi.support.DatabaseCleaner;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 개편 전후로 갈라진 위원회 이름의 통합.
 *
 * <p><b>이 테스트가 막는 것은 조용한 실패다.</b> 22대 중간 개편으로 같은 위원회가 두 이름으로
 * 기록돼 있다. 통합하지 않으면 구독자가 '환경노동위원회' 를 고를 수 있게 되는데, 그 이름의
 * 법안은 전부 심사가 끝난 것들이라 매칭되는 PENDING 법안이 0건이다. 매달 빈 레터가 나가고
 * 예외도 로그도 남지 않는다 — 아무도 모르는 채로 서비스가 망가져 있다.
 *
 * <p>{@code committee_alias} 는 마이그레이션이 심는 참조 데이터라 {@link DatabaseCleaner}
 * 가 지우지 않는다. 지우면 여기서 검증하는 매핑 자체가 사라진다.
 */
@ActiveProfiles("test")
@SpringBootTest
class CommitteeNameAliasTest {

    @Autowired private CommitteeNameResolver resolver;
    @Autowired private BillQueryService queryService;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private BillFixtures fixtures;

    @BeforeEach
    void prepare() {
        cleaner.clean();
        fixtures.seed();
    }

    @Test
    @DisplayName("정식 이름으로 조회하면 옛 이름도 함께 찾는다")
    void expandsCanonicalToOldName() {
        assertThat(resolver.expand("기후에너지환경노동위원회"))
                .containsExactlyInAnyOrder("기후에너지환경노동위원회", "환경노동위원회");
        assertThat(resolver.expand("재정경제기획위원회"))
                .containsExactlyInAnyOrder("재정경제기획위원회", "기획재정위원회");
        assertThat(resolver.expand("성평등가족위원회"))
                .containsExactlyInAnyOrder("성평등가족위원회", "여성가족위원회");
    }

    @Test
    @DisplayName("옛 이름으로 조회해도 같은 결과가 나온다 — 앱이나 구독 설정에 옛 값이 남아 있을 수 있다")
    void expandsOldNameToo() {
        assertThat(resolver.expand("환경노동위원회"))
                .containsExactlyInAnyOrder("기후에너지환경노동위원회", "환경노동위원회");
    }

    @Test
    @DisplayName("별칭이 없는 위원회는 입력 그대로 한 건이다")
    void leavesUnaliasedNamesAlone() {
        assertThat(resolver.expand("법제사법위원회")).containsExactly("법제사법위원회");
        assertThat(resolver.expand("없는위원회")).containsExactly("없는위원회");
        assertThat(resolver.expand(null)).isEmpty();
        assertThat(resolver.expand("  ")).isEmpty();
    }

    @Test
    @DisplayName("특별위원회는 통합 대상이 아니다 — PENDING 이 0건이어도 이름이 바뀐 게 아니라 기한이 끝난 것")
    void doesNotMergeSpecialCommittees() {
        assertThat(resolver.expand("기후위기 특별위원회"))
                .containsExactly("기후위기 특별위원회");
        assertThat(resolver.expand("정치개혁특별위원회"))
                .containsExactly("정치개혁특별위원회");
    }

    @Test
    @DisplayName("필터 목록에서 옛 이름이 사라지고 같은 위원회가 두 번 보이지 않는다")
    void committeeListFoldsOldNames() {
        List<String> names = queryService.findCommitteeNames();

        assertThat(names).contains("기후에너지환경노동위원회", "재정경제기획위원회", "성평등가족위원회");
        assertThat(names).doesNotContain("환경노동위원회", "기획재정위원회", "여성가족위원회");
        // 특별위원회는 그대로 남는다
        assertThat(names).contains("기후위기 특별위원회");
        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("정식 이름으로 목록을 조회하면 옛 이름으로 기록된 법안도 함께 나온다")
    void searchByCanonicalIncludesOldNameBills() {
        // 픽스처: 기후에너지환경노동위원회 3건 + 환경노동위원회 1건
        PageResponse<BillSummaryResponse> found =
                queryService.search(null, "기후에너지환경노동위원회", null, 0, 100);

        assertThat(found.content()).hasSize(4);
        assertThat(found.content())
                .extracting(BillSummaryResponse::committeeName)
                .contains("환경노동위원회");
    }

    @Test
    @DisplayName("옛 이름으로 목록을 조회해도 같은 4건이 나온다")
    void searchByOldNameReturnsSameSet() {
        PageResponse<BillSummaryResponse> byOld =
                queryService.search(null, "환경노동위원회", null, 0, 100);
        PageResponse<BillSummaryResponse> byCanonical =
                queryService.search(null, "기후에너지환경노동위원회", null, 0, 100);

        assertThat(byOld.content()).hasSize(4);
        assertThat(byOld.totalElements()).isEqualTo(byCanonical.totalElements());
    }

    @Test
    @DisplayName("상태 필터와 함께 써도 통합이 유지된다 — 레터가 쓰는 경로")
    void aliasSurvivesStatusFilter() {
        // 레터는 "이 위원회의 PENDING 법안" 을 묶는다. 통합이 여기서 깨지면 빈 호가 나간다.
        PageResponse<BillSummaryResponse> pending = queryService.search(
                BillStatus.PENDING, "기후에너지환경노동위원회", null, 0, 100);

        assertThat(pending.content())
                .isNotEmpty()
                .allSatisfy(bill -> assertThat(bill.status()).isEqualTo(BillStatus.PENDING));
    }
}
