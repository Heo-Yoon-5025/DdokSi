package com.ddoksi.ddoksi.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ddoksi.ddoksi.bill.entity.*;
import com.ddoksi.ddoksi.collection.entity.BillRaw;
import com.ddoksi.ddoksi.collection.entity.CollectionRun;
import com.ddoksi.ddoksi.collection.entity.CollectionRunStatus;
import com.ddoksi.ddoksi.letter.entity.*;
import com.ddoksi.ddoksi.subscription.entity.*;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 엔티티 매핑 스모크 테스트.
 *
 * 애플리케이션 기동만으로는 컬럼 존재 여부(ddl-auto=validate)만 확인된다.
 * JSONB 직렬화, enum 변환, UUID, 복합키, Auditing 은 실제로 저장하고 다시 읽어봐야 검증된다.
 *
 * 각 테스트는 flush() 로 DB 에 실제 SQL 을 보내고 clear() 로 영속성 컨텍스트를 비운 뒤
 * 다시 조회한다. 이렇게 해야 1차 캐시에서 그냥 꺼내온 게 아니라 DB 왕복을 거친 값임이 보장된다.
 *
 * ⚠️ 로컬에 PostgreSQL 이 떠 있어야 실행된다. (brew services start postgresql@17)
 */
@SpringBootTest
@Transactional
class EntityPersistenceTest {

    @Autowired
    private EntityManager em;

    /**
     * 저장 후 영속성 컨텍스트를 비우고 다시 조회한다.
     *
     * id 를 값이 아니라 추출 함수로 받는 이유: persist 전에는 id 가 아직 null 이므로,
     * flush 로 INSERT 가 나간 뒤에 꺼내야 한다.
     */
    private <T> T saveAndReload(T entity, Class<T> type, Function<T, Object> idExtractor) {
        em.persist(entity);
        em.flush();
        Object id = idExtractor.apply(entity);
        em.clear();
        return em.find(type, id);
    }

    @Test
    @DisplayName("Bill - JSONB(extra), enum(status), Auditing 이 모두 왕복한다")
    void billRoundTrip() {
        Bill bill = Bill.builder()
                .externalBillId("TEST_BILL_1")
                .billNo("2200999")
                .assemblyAge((short) 22)
                .title("테스트 법률안")
                .status(BillStatus.PENDING)
                .procResultRaw("접수")
                .committeeName("법제사법위원회")
                .proposedDate(LocalDate.of(2026, 8, 1))
                .extra(Map.of("RST_PROPOSER", "홍길동", "AGE", "22"))
                .build();

        em.persist(bill);
        em.flush();
        Long id = bill.getId();
        em.clear();

        Bill found = em.find(Bill.class, id);

        assertThat(found.getExternalBillId()).isEqualTo("TEST_BILL_1");
        assertThat(found.getStatus()).isEqualTo(BillStatus.PENDING);
        assertThat(found.getAssemblyAge()).isEqualTo((short) 22);
        assertThat(found.getProposedDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        // JSONB 가 Map 으로 복원되는지
        assertThat(found.getExtra()).containsEntry("RST_PROPOSER", "홍길동");
        // Auditing 이 값을 채웠는지
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Bill - status 가 기본값 UNKNOWN 으로 들어가고, 변경 감지가 동작한다")
    void billStatusChangeDetection() {
        Bill bill = Bill.builder()
                .externalBillId("TEST_BILL_2")
                .title("상태 미상 법률안")
                .procResultRaw("심사중")
                .build();

        assertThat(bill.getStatus()).isEqualTo(BillStatus.UNKNOWN);

        // 같은 상태 + 같은 원문 문자열이면 변경으로 보지 않는다 (배치 멱등성의 근거)
        assertThat(bill.hasStatusChanged(BillStatus.UNKNOWN, "심사중")).isFalse();
        // 우리 분류가 바뀌면 변경
        assertThat(bill.hasStatusChanged(BillStatus.PASSED, "심사중")).isTrue();
        // 분류가 같아도 국회 원문이 달라지면 변경
        assertThat(bill.hasStatusChanged(BillStatus.UNKNOWN, "체계자구심사")).isTrue();
    }

    @Test
    @DisplayName("CollectionRun + BillRaw - 응답 원문(JSONB 문자열)이 그대로 보존된다")
    void collectionRunAndRawPayload() {
        CollectionRun run = CollectionRun.builder().jobName("bill-collect").build();
        em.persist(run);

        String payload = "{\"BILL_ID\":\"PRC_TEST\",\"BILL_NAME\":\"테스트\"}";
        BillRaw raw = BillRaw.builder()
                .collectionRun(run)
                .sourceApi("nzmimeepazxkubdpn")
                .externalBillId("PRC_TEST")
                .payload(payload)
                .payloadHash("abc123")
                .build();

        BillRaw found = saveAndReload(raw, BillRaw.class, BillRaw::getId);

        assertThat(found.getPayload()).contains("PRC_TEST");
        assertThat(found.getSourceApi()).isEqualTo("nzmimeepazxkubdpn");
        assertThat(found.getCollectionRun().getJobName()).isEqualTo("bill-collect");
        assertThat(found.getCollectionRun().getStatus()).isEqualTo(CollectionRunStatus.RUNNING);
    }

    @Test
    @DisplayName("BillStatusHistory - 최초 이력은 from 이 비어 있다")
    void statusHistoryInitial() {
        CollectionRun run = CollectionRun.builder().jobName("bill-collect").build();
        em.persist(run);

        Bill bill = Bill.builder()
                .externalBillId("TEST_BILL_3")
                .title("이력 테스트")
                .status(BillStatus.PENDING)
                .procResultRaw("접수")
                .build();
        em.persist(bill);

        BillStatusHistory history = BillStatusHistory.initial(bill, run);
        BillStatusHistory found = saveAndReload(history, BillStatusHistory.class, BillStatusHistory::getId);

        assertThat(found.getFromStatus()).isNull();
        assertThat(found.getToStatus()).isEqualTo(BillStatus.PENDING);
        assertThat(found.getToProcResultRaw()).isEqualTo("접수");
        assertThat(found.getChangedAt()).isNotNull();
    }

    @Test
    @DisplayName("BillAnalysis - 찬반 논거 배열(JSONB)이 왕복하고, 원문 변경을 감지한다")
    void analysisRoundTrip() {
        Bill bill = Bill.builder()
                .externalBillId("TEST_BILL_4")
                .title("분석 대상 법률안")
                .status(BillStatus.PENDING)
                .build();
        em.persist(bill);

        BillAnalysis analysis = BillAnalysis.builder()
                .bill(bill)
                .promptVersion("v1")
                .model("claude-sonnet-5")
                .sourceHash("hash-original")
                .summary("한 줄 요약")
                .pros(List.of("찬성 논거 1", "찬성 논거 2"))
                .cons(List.of("반대 논거 1"))
                .status(AnalysisStatus.SUCCESS)
                .build();

        BillAnalysis found = saveAndReload(analysis, BillAnalysis.class, BillAnalysis::getId);

        assertThat(found.getPros()).containsExactly("찬성 논거 1", "찬성 논거 2");
        assertThat(found.getCons()).containsExactly("반대 논거 1");
        assertThat(found.getStatus()).isEqualTo(AnalysisStatus.SUCCESS);
        // 원문이 그대로면 재생성 대상이 아니고, 바뀌었으면 재생성 대상이다
        assertThat(found.isStale("hash-original")).isFalse();
        assertThat(found.isStale("hash-changed")).isTrue();
    }

    @Test
    @DisplayName("BillAnalysis - 생성 실패도 기록으로 남는다")
    void analysisFailureIsRecorded() {
        Bill bill = Bill.builder()
                .externalBillId("TEST_BILL_5")
                .title("분석 실패 법률안")
                .build();
        em.persist(bill);

        BillAnalysis failed = BillAnalysis.failed(bill, "v1", "claude-sonnet-5", "hash", "timeout");
        BillAnalysis found = saveAndReload(failed, BillAnalysis.class, BillAnalysis::getId);

        assertThat(found.getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(found.getErrorMessage()).isEqualTo("timeout");
        // 실패해도 빈 배열이 들어가 NULL 로 인한 NPE 가 나지 않는다
        assertThat(found.getPros()).isEmpty();
    }

    @Test
    @DisplayName("Subscriber - UUID 토큰이 생성되고 구독 생명주기가 동작한다")
    void subscriberLifecycle() {
        Subscriber subscriber = Subscriber.builder().email("Reader@Example.com").build();

        assertThat(subscriber.getStatus()).isEqualTo(SubscriberStatus.PENDING);
        assertThat(subscriber.getUnsubscribeToken()).isNotNull();

        subscriber.verify();
        Subscriber found = saveAndReload(subscriber, Subscriber.class, Subscriber::getId);

        assertThat(found.getStatus()).isEqualTo(SubscriberStatus.ACTIVE);
        assertThat(found.getUnsubscribeToken()).isEqualTo(subscriber.getUnsubscribeToken());
        assertThat(found.getVerifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("SubscriptionCommittee - 복합키(구독자 + 상임위명)가 동작한다")
    void subscriptionCommitteeCompositeKey() {
        Subscriber subscriber = Subscriber.builder().email("filter@example.com").build();
        em.persist(subscriber);
        em.flush();

        SubscriptionCommittee filter = new SubscriptionCommittee(subscriber, "법제사법위원회");
        em.persist(filter);
        em.flush();
        em.clear();

        SubscriptionCommittee found = em.find(
                SubscriptionCommittee.class,
                new SubscriptionCommitteeId(subscriber.getId(), "법제사법위원회"));

        assertThat(found).isNotNull();
        assertThat(found.getCommitteeName()).isEqualTo("법제사법위원회");
        assertThat(found.getSubscriber().getEmail()).isEqualTo("filter@example.com");
    }

    @Test
    @DisplayName("LetterDelivery - 발송 결과 전이가 동작한다")
    void letterDeliveryTransitions() {
        Subscriber subscriber = Subscriber.builder().email("letter@example.com").build();
        subscriber.verify();
        em.persist(subscriber);

        LetterIssue issue = LetterIssue.builder()
                .issueMonth(LocalDate.of(2026, 9, 1))
                .subject("2026년 9월 국회 법안 다이제스트")
                .build();
        em.persist(issue);

        LetterDelivery delivery = LetterDelivery.builder()
                .letterIssue(issue)
                .subscriber(subscriber)
                .build();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);

        delivery.markSent("ses-message-id-123");
        LetterDelivery found = saveAndReload(delivery, LetterDelivery.class, LetterDelivery::getId);

        assertThat(found.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(found.getProviderMessageId()).isEqualTo("ses-message-id-123");
        assertThat(found.getSentAt()).isNotNull();
        assertThat(found.getLetterIssue().getIssueMonth()).isEqualTo(LocalDate.of(2026, 9, 1));
    }
}
