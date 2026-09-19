package com.ddoksi.ddoksi.bill.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.ddoksi.ddoksi.bill.entity.AnalysisStatus;
import com.ddoksi.ddoksi.bill.entity.Bill;
import com.ddoksi.ddoksi.bill.entity.BillAnalysis;
import com.ddoksi.ddoksi.bill.repository.BillAnalysisRepository;
import com.ddoksi.ddoksi.bill.repository.BillRepository;
import com.ddoksi.ddoksi.support.BillFixtures;
import com.ddoksi.ddoksi.support.DatabaseCleaner;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 분석 결과 저장 — 특히 {@code UNIQUE (bill_id, prompt_version)} 재시도 경로.
 *
 * <p><b>이 테스트가 지키는 것.</b> 실패한 분석은 FAILED 행으로 남는데, 다음 실행이 그 행을
 * "이미 처리됨" 으로 읽으면 해당 법안은 영원히 분석 없이 방치된다. 예외도 안 나고 로그도
 * 안 남아서 아무도 모른다. 조용히 깨지는 종류의 버그라 테스트로 고정한다.
 *
 * <p>Claude API 를 부르지 않는다. 검증 대상은 저장 분기이지 모델 응답이 아니므로
 * 결과를 손으로 만들어 넣는다 — 키 없이도 돌고, 호출 비용도 들지 않는다.
 *
 * <p>{@code @Transactional} 을 붙이지 않는 이유는 수집 배치 테스트들과 같다.
 * 묶음 단위 커밋이 설계의 일부라 롤백으로 감싸면 검증하려는 동작이 사라진다.
 */
@ActiveProfiles("test")
@SpringBootTest
class BillAnalysisPersisterTest {

    private static final String VERSION = "test-v1";
    private static final String MODEL = "claude-opus-5";

    @Autowired private BillAnalysisPersister persister;
    @Autowired private BillAnalysisRepository analysisRepository;
    @Autowired private BillRepository billRepository;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private BillFixtures fixtures;

    private Bill bill;

    @BeforeEach
    void prepare() {
        cleaner.clean();
        fixtures.seed();
        bill = billRepository.findAll().getFirst();
    }

    @Test
    @DisplayName("성공 결과가 hook 과 topics 까지 저장된다")
    void savesNewAnalysis() {
        persister.persistChunk(List.of(success("해시A", result())), VERSION);

        BillAnalysis saved = find().orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(AnalysisStatus.SUCCESS);
        assertThat(saved.getHook()).isEqualTo("어린이통학버스, 버스정류장에 설 수 있게 된다");
        assertThat(saved.getTopics()).containsExactly("교통·안전", "교육·보육");
        assertThat(saved.getSourceHash()).isEqualTo("해시A");
    }

    @Test
    @DisplayName("실패도 행으로 남는다 — 남기지 않으면 '아직 안 함' 과 구별할 수 없다")
    void recordsFailure() {
        persister.persistChunk(List.of(failure("해시A", "타임아웃")), VERSION);

        BillAnalysis saved = find().orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(saved.getErrorMessage()).isEqualTo("타임아웃");
    }

    @Test
    @DisplayName("FAILED 였던 행은 INSERT 가 아니라 갱신으로 채워진다 (UNIQUE 제약 함정)")
    void retryUpdatesFailedRowInPlace() {
        persister.persistChunk(List.of(failure("해시A", "타임아웃")), VERSION);
        Long failedRowId = find().orElseThrow().getId();

        AnalysisChunkResult retry = persister.persistChunk(List.of(success("해시A", result())), VERSION);

        // 새 행이 생기지 않아야 한다. 생겼다면 UNIQUE 제약에 걸렸을 것이고,
        // 건너뛰었다면 FAILED 가 그대로 남았을 것이다.
        assertThat(analysisRepository.count()).isEqualTo(1);
        assertThat(retry.retried()).isEqualTo(1);
        assertThat(retry.inserted()).isZero();

        BillAnalysis updated = find().orElseThrow();
        assertThat(updated.getId()).isEqualTo(failedRowId);
        assertThat(updated.getStatus()).isEqualTo(AnalysisStatus.SUCCESS);
        assertThat(updated.getHook()).isNotBlank();
        // 지난 실패 흔적이 성공한 행에 남아 있으면 로그를 볼 때 무엇이 사실인지 알 수 없다.
        assertThat(updated.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("이미 성공했고 입력도 그대로면 다시 부르지 않는다 — 재호출은 곧 재과금이다")
    void skipsWhenAlreadySucceededAndUnchanged() {
        persister.persistChunk(List.of(success("해시A", result())), VERSION);

        AnalysisChunkResult again = persister.persistChunk(List.of(success("해시A", result())), VERSION);

        assertThat(again.unchanged()).isEqualTo(1);
        assertThat(again.called()).isZero();
    }

    @Test
    @DisplayName("원문이 바뀌면(해시 불일치) 재생성으로 덮어쓴다")
    void regeneratesWhenSourceChanged() {
        persister.persistChunk(List.of(success("해시A", result())), VERSION);

        AnalysisResult changed = new AnalysisResult(
                "바뀐 훅", "바뀐 요약", "바뀐 예시", "바뀐 배경", List.of("교통·안전"));
        AnalysisChunkResult regenerated =
                persister.persistChunk(List.of(success("해시B", changed)), VERSION);

        assertThat(regenerated.regenerated()).isEqualTo(1);
        assertThat(analysisRepository.count()).isEqualTo(1);

        BillAnalysis updated = find().orElseThrow();
        assertThat(updated.getSourceHash()).isEqualTo("해시B");
        assertThat(updated.getHook()).isEqualTo("바뀐 훅");
        assertThat(updated.getTopics()).containsExactly("교통·안전");
    }

    @Test
    @DisplayName("재시도가 또 실패하면 기존 내용은 두고 사유만 갱신한다")
    void keepsPreviousContentWhenRetryFailsAgain() {
        persister.persistChunk(List.of(success("해시A", result())), VERSION);
        persister.persistChunk(List.of(failure("해시B", "또 실패")), VERSION);

        BillAnalysis updated = find().orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(updated.getErrorMessage()).isEqualTo("또 실패");
        // 옛 내용이라도 남아 있는 편이 빈칸보다 낫다. 조회 API 는 FAILED 를 내보내지 않으므로
        // 화면에 뜨지는 않는다.
        assertThat(updated.getHook()).isNotBlank();
    }

    @Test
    @DisplayName("prompt_version 이 다르면 별개 행으로 공존한다")
    void differentVersionsCoexist() {
        persister.persistChunk(List.of(success("해시A", result())), VERSION);
        persister.persistChunk(List.of(success("해시A", result())), "test-v2");

        assertThat(analysisRepository.count()).isEqualTo(2);
        assertThat(find()).isPresent();
        assertThat(analysisRepository.findByBillAndPromptVersion(bill, "test-v2")).isPresent();
    }

    private Optional<BillAnalysis> find() {
        return analysisRepository.findByBillAndPromptVersion(bill, VERSION);
    }

    private AnalyzedBill success(String sourceHash, AnalysisResult result) {
        return AnalyzedBill.success(bill.getId(), sourceHash, MODEL, result);
    }

    private AnalyzedBill failure(String sourceHash, String message) {
        return AnalyzedBill.failure(bill.getId(), sourceHash, MODEL, message);
    }

    private AnalysisResult result() {
        return new AnalysisResult(
                "어린이통학버스, 버스정류장에 설 수 있게 된다",
                "버스정류장 10미터 안에서는 모든 차량의 정차가 금지돼 있다.",
                "통학버스로 등하교하는 아이들은 지금 차도에 내려 인도까지 걸어야 한다.",
                "정류장에 설 수 없는 통학버스는 갓길에 멈춰 왔다.",
                List.of("교통·안전", "교육·보육"));
    }
}
