package com.ddoksi.ddoksi.collection.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 국회 API 응답 원문.
 *
 * 파싱하기 전 상태 그대로 보관한다. 외부 API 스펙은 예고 없이 바뀌고,
 * 그때 "우리가 실제로 무엇을 받았는지" 를 볼 수 있어야 원인을 찾을 수 있다.
 *
 * payload 를 Map 이 아니라 String 으로 다루는 이유:
 * 역직렬화-재직렬화 과정에서 필드 순서나 표현이 바뀔 수 있는데, 원문 보존이 목적이므로
 * 받은 문자열을 손대지 않고 그대로 저장한다.
 */
@Getter
@Entity
@Table(name = "bill_raw")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillRaw {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 이 원문을 수집한 배치 실행. 지연 로딩으로 두어 원문 조회 시 불필요한 조인을 피한다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collection_run_id")
    private CollectionRun collectionRun;

    /** 어느 API 에서 온 응답인지 (예: nzmimeepazxkubdpn, ALLBILL) */
    @Column(name = "source_api", nullable = false, length = 50)
    private String sourceApi;

    /** 응답에서 뽑아낸 국회 의안 ID. 파싱 실패 시 null 일 수 있다. */
    @Column(name = "external_bill_id", length = 50)
    private String externalBillId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    /** 직전 수집분과 내용이 같은지 판정해 불필요한 재파싱을 건너뛰기 위한 해시 */
    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @Builder
    private BillRaw(CollectionRun collectionRun, String sourceApi, String externalBillId,
                    String payload, String payloadHash) {
        this.collectionRun = collectionRun;
        this.sourceApi = sourceApi;
        this.externalBillId = externalBillId;
        this.payload = payload;
        this.payloadHash = payloadHash;
        this.collectedAt = Instant.now();
    }
}
