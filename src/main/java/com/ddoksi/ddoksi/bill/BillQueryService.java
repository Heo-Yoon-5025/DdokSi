package com.ddoksi.ddoksi.bill;

import com.ddoksi.ddoksi.bill.dto.BillDetailResponse;
import com.ddoksi.ddoksi.bill.dto.BillStatusChangeResponse;
import com.ddoksi.ddoksi.bill.dto.BillSummaryResponse;
import com.ddoksi.ddoksi.bill.entity.Bill;
import com.ddoksi.ddoksi.bill.entity.BillStatus;
import com.ddoksi.ddoksi.bill.repository.BillRepository;
import com.ddoksi.ddoksi.bill.repository.BillSpecifications;
import com.ddoksi.ddoksi.bill.repository.BillStatusHistoryRepository;
import com.ddoksi.ddoksi.common.dto.PageResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    public BillQueryService(BillRepository billRepository,
                            BillStatusHistoryRepository statusHistoryRepository) {
        this.billRepository = billRepository;
        this.statusHistoryRepository = statusHistoryRepository;
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

    /** 법안 상세. 상태 변경 이력을 함께 내보낸다. */
    public BillDetailResponse findDetail(Long id) {
        Bill bill = billRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("법안을 찾을 수 없습니다: id=" + id));

        List<BillStatusChangeResponse> history =
                statusHistoryRepository.findByBillIdOrderByChangedAtDesc(id).stream()
                        .map(BillStatusChangeResponse::from)
                        .toList();

        return BillDetailResponse.of(bill, history);
    }

    /** 필터 UI 에 채울 상임위 목록. */
    public List<String> findCommitteeNames() {
        return billRepository.findDistinctCommitteeNames();
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
            conditions.add(BillSpecifications.inCommittee(committee));
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
