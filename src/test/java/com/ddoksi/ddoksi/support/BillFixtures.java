package com.ddoksi.ddoksi.support;

import com.ddoksi.ddoksi.bill.entity.Bill;
import com.ddoksi.ddoksi.bill.entity.BillStatus;
import com.ddoksi.ddoksi.bill.entity.BillStatusHistory;
import com.ddoksi.ddoksi.bill.repository.BillRepository;
import com.ddoksi.ddoksi.bill.repository.BillStatusHistoryRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 테스트용 법안 픽스처를 심는다.
 *
 * <p><b>왜 픽스처를 쓰는가.</b> 예전에는 테스트가 개발 DB 에 쌓인 수집분 1만9천 건에
 * 기대고 있었다. 그러면 테스트 결과가 각자의 DB 상태에 따라 달라져 재현이 되지 않고,
 * DB 를 비우면 대부분이 그냥 건너뛰어 통과한 것처럼 보인다.
 *
 * <p><b>왜 실제 데이터인가.</b> 제안이유 수집 테스트는 이 의안번호로 국회 API 를 실제로
 * 호출한다. 지어낸 번호를 넣으면 전부 INFO-200(데이터 없음)이 되어 정작 검증하려는
 * 본문 저장 경로를 한 줄도 지나지 않는다. 그래서 공개 데이터에서 실제 값을 가져와 쓴다.
 *
 * <p>데이터 자체는 {@code fixtures/bills.psv} 에 있다. 코드에 박아두면 39건이 Java 파일을
 * 가득 채워 정작 로직이 묻힌다.
 */
@Component
public class BillFixtures {

    private static final String FIXTURE_PATH = "fixtures/bills.psv";

    /** 본문(SUMMARY)이 빈 채로 내려오는 것이 확인된 의안번호. 빈 본문 처리 경로 검증에 쓴다. */
    public static final String BILL_NO_WITH_EMPTY_SUMMARY = "2208675";

    /** 제목에 "개인정보" 가 들어가는 의안번호. 키워드 검색 검증에 쓴다. */
    public static final String BILL_NO_WITH_KEYWORD = "2221128";

    /**
     * 한 의안번호에 응답 행이 2개 오는 것이 확인된 의안번호.
     * 첫 행은 본문이 비어 있는 중복 레코드이고 실제 본문은 둘째 행에 있다.
     * 행 선택 로직 검증에 쓴다.
     */
    public static final String BILL_NO_WITH_DUPLICATE_ROWS = "2221245";

    private final BillRepository billRepository;
    private final BillStatusHistoryRepository historyRepository;

    public BillFixtures(BillRepository billRepository,
                        BillStatusHistoryRepository historyRepository) {
        this.billRepository = billRepository;
        this.historyRepository = historyRepository;
    }

    /**
     * 픽스처 법안을 저장하고 각 법안에 최초 상태 이력을 한 건씩 남긴다.
     *
     * <p>이력을 함께 만드는 이유: 상세 조회 API 가 상태 이력을 내려주고, 수집 배치도
     * 신규 법안마다 최초 이력을 남긴다. 픽스처가 그 모양을 따라가지 않으면
     * 테스트가 실제와 다른 상태를 검증하게 된다.
     *
     * @return 저장된 법안들
     */
    @Transactional
    public List<Bill> seed() {
        List<Bill> bills = new ArrayList<>();
        for (String line : readFixtureLines()) {
            bills.add(saveFixtureLine(line));
        }
        return bills;
    }

    /**
     * 픽스처 중 의안번호가 일치하는 법안 하나만 심는다.
     *
     * <p>제안이유 수집은 "본문 없는 법안을 앞에서부터" 가져가는 구조라, 특정 법안 하나의
     * 응답을 검증하려면 그 법안만 대상에 남아 있어야 한다. 전체를 심어두면 그 법안 차례가
     * 오기까지 수십 번의 실호출이 일어난다.
     *
     * @param billNo 심을 의안번호
     */
    @Transactional
    public Bill seedOne(String billNo) {
        for (String line : readFixtureLines()) {
            if (line.startsWith(billNo + "|")) {
                return saveFixtureLine(line);
            }
        }
        throw new IllegalStateException("픽스처에 없는 의안번호입니다: " + billNo);
    }

    /** 픽스처 한 줄을 법안과 최초 이력으로 저장한다. */
    private Bill saveFixtureLine(String line) {
        // 형식: 의안번호|국회의안ID|법안명|상태|처리결과원문|소관위원회|제안일|대표발의자|원문링크
        // -1 을 주어 뒤쪽 빈 칸(처리결과원문 등)이 잘려나가지 않게 한다
        String[] f = line.split("\\|", -1);
        if (f.length < 9) {
            throw new IllegalStateException("픽스처 형식이 잘못되었습니다: " + line);
        }

        Bill bill = billRepository.save(Bill.builder()
                .billNo(f[0])
                .externalBillId(f[1])
                .title(f[2])
                .status(BillStatus.valueOf(f[3]))
                .procResultRaw(blankToNull(f[4]))
                .committeeName(blankToNull(f[5]))
                .proposedDate(LocalDate.parse(f[6]))
                .rstProposer(blankToNull(f[7]))
                .detailUrl(f[8])
                .assemblyAge((short) 22)
                .proposerKind("의원")
                .build());

        // 수집 배치가 신규 법안에 남기는 것과 같은 모양의 최초 이력
        historyRepository.save(BillStatusHistory.builder()
                .bill(bill)
                .fromStatus(null)
                .toStatus(bill.getStatus())
                .toProcResultRaw(bill.getProcResultRaw())
                .build());

        return bill;
    }

    /** 주석(#)과 빈 줄을 걸러낸 픽스처 본문만 돌려준다. */
    private List<String> readFixtureLines() {
        List<String> lines = new ArrayList<>();
        ClassPathResource resource = new ClassPathResource(FIXTURE_PATH);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                lines.add(trimmed);
            }
        } catch (IOException e) {
            throw new IllegalStateException("픽스처 파일을 읽지 못했습니다: " + FIXTURE_PATH, e);
        }

        if (lines.isEmpty()) {
            throw new IllegalStateException("픽스처가 비어 있습니다: " + FIXTURE_PATH);
        }
        return lines;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
