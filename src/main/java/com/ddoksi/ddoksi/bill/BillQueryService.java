package com.ddoksi.ddoksi.bill;

import com.ddoksi.ddoksi.bill.dto.BillAnalysisResponse;
import com.ddoksi.ddoksi.bill.dto.BillDetailResponse;
import com.ddoksi.ddoksi.bill.dto.BillStatusChangeResponse;
import com.ddoksi.ddoksi.bill.dto.BillSummaryResponse;
import com.ddoksi.ddoksi.bill.entity.AnalysisStatus;
import com.ddoksi.ddoksi.bill.entity.Bill;
import com.ddoksi.ddoksi.bill.entity.BillAnalysis;
import com.ddoksi.ddoksi.bill.entity.BillStatus;
import com.ddoksi.ddoksi.bill.entity.BillSummary;
import com.ddoksi.ddoksi.bill.repository.BillAnalysisRepository;
import com.ddoksi.ddoksi.bill.repository.BillRepository;
import com.ddoksi.ddoksi.bill.repository.BillSpecifications;
import com.ddoksi.ddoksi.bill.repository.BillStatusHistoryRepository;
import com.ddoksi.ddoksi.bill.repository.BillSummaryRepository;
import com.ddoksi.ddoksi.common.dto.PageResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 법안 조회.
 *
 * 읽기 전용이므로 {@code readOnly = true} 로 둔다.
 * Hibernate 가 변경 감지(dirty checking)를 건너뛰고, 읽기 복제본으로 라우팅할 여지도 생긴다.
 */
@Service
@Transactional(readOnly = true)
public class BillQueryService {

    /** 한 페이지 최대 크기. 클라이언트가 큰 값을 보내 DB 를 통째로 긁어가는 것을 막는다. */
    private static final int MAX_PAGE_SIZE = 100;

    private final BillRepository billRepository;
    private final BillStatusHistoryRepository statusHistoryRepository;
    private final BillSummaryRepository summaryRepository;
    private final BillAnalysisRepository analysisRepository;
    private final CommitteeNameResolver committeeResolver;

    /**
     * 조회가 읽어갈 분석 버전. 생성용({@code prompt-version})과 나눠 둔 값이다.
     *
     * <p>둘을 나눈 이유는 전환을 통제하기 위해서다. v2 를 백필하는 동안에도 앱에는 v1 만
     * 보이고, 전량이 채워진 뒤 이 값을 바꾸면 화면이 한 번에 넘어간다. 평상시에는 두 값이 같다.
     */
    @Value("${ddoksi.analysis.active-prompt-version}")
    private String activePromptVersion;

    public BillQueryService(BillRepository billRepository,
                            BillStatusHistoryRepository statusHistoryRepository,
                            BillSummaryRepository summaryRepository,
                            BillAnalysisRepository analysisRepository,
                            CommitteeNameResolver committeeResolver) {
        this.billRepository = billRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.summaryRepository = summaryRepository;
        this.analysisRepository = analysisRepository;
        this.committeeResolver = committeeResolver;
    }

    /**
     * 조건에 맞는 법안 목록을 최신 제안일 순으로 조회한다.
     *
     * @param status        상태 필터. null 이면 전체
     * @param committeeName 상임위 필터. null 이면 전체
     * @param keyword       법안명 검색어. null 이면 전체
     */
    public PageResponse<BillSummaryResponse> search(BillStatus status, String committeeName,
                                                    String keyword, int page, int size) {
        // 정렬은 서버가 정한다. 클라이언트가 임의 컬럼으로 정렬하게 두면
        // 인덱스 없는 컬럼 정렬로 전체 스캔이 발생할 수 있다.
        Sort sort = Sort.by(Sort.Direction.DESC, "proposedDate")
                .and(Sort.by(Sort.Direction.DESC, "id"));
        PageRequest pageRequest = PageRequest.of(
                Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), sort);

        Page<Bill> found = billRepository.findAll(buildSpec(status, committeeName, keyword), pageRequest);

        return PageResponse.of(found, found.getContent().stream()
                .map(BillSummaryResponse::from)
                .toList());
    }

    /**
     * 법안 상세. 상태 변경 이력, 제안이유 원문, AI 분석을 함께 내보낸다.
     *
     * <p>원문과 분석 모두 없을 수 있다. 원문이 없는 법안이 41건 있고, 분석은 아직
     * 생성되지 않았거나 실패했을 수 있다. 화면은 셋 중 무엇이 없어도 그려져야 한다.
     */
    public BillDetailResponse findDetail(Long id) {
        Bill bill = billRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("법안을 찾을 수 없습니다: id=" + id));

        List<BillStatusChangeResponse> history =
                statusHistoryRepository.findByBillIdOrderByChangedAtDesc(id).stream()
                        .map(BillStatusChangeResponse::from)
                        .toList();

        String billText = summaryRepository.findByBill(bill)
                .map(BillSummary::getSummary)
                .orElse(null);

        return BillDetailResponse.of(bill, history, billText, findAnalysis(bill));
    }

    /**
     * 활성 버전의 성공한 분석만 꺼낸다.
     *
     * <p><b>왜 generated_at 최신값을 쓰지 않는가.</b> v2 백필이 도중에 멈추면 어떤 법안은 v2,
     * 어떤 법안은 v1 이 최신이 되어 한 화면 안에서 톤이 다른 요약이 섞인다.
     * 어느 버전을 보여줄지는 시간이 아니라 설정이 정해야 한다.
     *
     * <p>FAILED 는 내보내지 않는다. 앱은 분석이 없을 때 원문만 보여주면 되고,
     * 생성이 실패했다는 사실은 사용자에게 알릴 내용이 아니다.
     */
    private BillAnalysisResponse findAnalysis(Bill bill) {
        return analysisRepository.findByBillAndPromptVersion(bill, activePromptVersion)
                .filter(analysis -> analysis.getStatus() == AnalysisStatus.SUCCESS)
                .map(BillAnalysisResponse::from)
                .orElse(null);
    }

    /**
     * 필터 UI 에 채울 상임위 목록.
     *
     * <p>개편 전후로 갈라진 이름은 정식 이름으로 접어 내보낸다. 접지 않으면 같은 위원회가
     * 두 번 보이고, 구독자가 옛 이름을 골랐을 때 매달 빈 레터를 받는다.
     * 실측 기준 raw 25개가 22개로 줄어든다.
     */
    public List<String> findCommitteeNames() {
        return committeeResolver.toCanonicalNames(billRepository.findDistinctCommitteeNames());
    }

    /**
     * 지정된 필터만 조건으로 만든다.
     *
     * 빈 문자열은 "필터 없음" 과 같게 취급한다. 앱이 빈 검색창 값을 그대로 보내도 동작해야 한다.
     * 조건이 하나도 없으면 null 을 돌려주고, findAll 은 그 경우 전체를 조회한다.
     */
    private Specification<Bill> buildSpec(BillStatus status, String committeeName, String keyword) {
        List<Specification<Bill>> conditions = new ArrayList<>();
        if (status != null) {
            conditions.add(BillSpecifications.hasStatus(status));
        }
        String committee = blankToNull(committeeName);
        if (committee != null) {
            // 옛 이름으로 기록된 법안까지 함께 찾는다. 알 수 없는 이름이면 그대로 한 건이 되어
            // 기존과 똑같이 동작한다.
            conditions.add(BillSpecifications.inCommittees(committeeResolver.expand(committee)));
        }
        String searchWord = blankToNull(keyword);
        if (searchWord != null) {
            conditions.add(BillSpecifications.titleContains(searchWord));
        }
        return conditions.stream().reduce(Specification::and).orElse(null);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.strip();
    }
}
