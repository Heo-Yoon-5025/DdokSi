package com.ddoksi.ddoksi.bill;

import com.ddoksi.ddoksi.bill.dto.BillDetailResponse;
import com.ddoksi.ddoksi.bill.dto.BillSummaryResponse;
import com.ddoksi.ddoksi.bill.entity.BillStatus;
import com.ddoksi.ddoksi.common.dto.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 법안 조회 API.
 *
 * 컨트롤러는 얇게 유지한다. 요청 검증과 응답 변환만 하고 비즈니스 로직은 서비스에 둔다.
 *
 * 로그인은 없다. 공개된 국회 정보를 보여주는 것이라 인증이 필요하지 않고,
 * 구독/알림 기능이 생길 때 그 경로에만 인증을 붙인다.
 */
@RestController
@RequestMapping("/api/bills")
public class BillController {

    private final BillQueryService billQueryService;

    public BillController(BillQueryService billQueryService) {
        this.billQueryService = billQueryService;
    }

    /**
     * 법안 목록.
     *
     * @param status    상태 필터 (PENDING/PASSED/MERGED/DISCARDED). 생략하면 전체
     * @param committee 상임위 필터. 생략하면 전체
     * @param keyword   법안명 검색어. 생략하면 전체
     */
    @GetMapping
    public PageResponse<BillSummaryResponse> list(
            @RequestParam(required = false) BillStatus status,
            @RequestParam(required = false) String committee,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return billQueryService.search(status, committee, keyword, page, size);
    }

    /** 법안 상세. 상태 변경 이력을 포함한다. */
    @GetMapping("/{id}")
    public BillDetailResponse detail(@PathVariable Long id) {
        return billQueryService.findDetail(id);
    }

    /** 필터 UI 용 상임위 목록. */
    @GetMapping("/committees")
    public List<String> committees() {
        return billQueryService.findCommitteeNames();
    }
}
