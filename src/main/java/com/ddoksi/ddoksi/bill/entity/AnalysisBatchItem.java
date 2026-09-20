package com.ddoksi.ddoksi.bill.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 배치에 담아 보낸 법안 한 건.
 *
 * <p>기록하는 것은 두 가지다 — 무엇을 보냈는지, 그리고 보낼 때의 입력 해시가 무엇이었는지.
 *
 * <p><b>해시를 제출 시점에 박아 두는 이유.</b> 수거할 때 다시 계산하면 그 사이 법안 제목이나
 * 위원회명이 바뀐 경우 <i>보내지도 않은 입력</i>의 해시를 저장하게 된다. 그러면 다음 실행은
 * 그 분석을 "현재 입력으로 만든 최신 결과" 로 오판하고 재생성 대상에서 빼버린다. 위원회
 * 개편으로 이름이 실제로 바뀐 전례가 있으니 가정이 아니라 일어난 일이다.
 *
 * <p>연관관계를 매핑하지 않고 id 값만 들고 있는다. 이 행에서 배치나 법안 객체를 타고 들어갈
 * 일이 없고, 수거 경로가 필요로 하는 것은 {@code billId → sourceHash} 표 하나뿐이다.
 */
@Getter
@Entity
@Table(name = "analysis_batch_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisBatchItem {

    @EmbeddedId
    private AnalysisBatchItemId id;

    /** 제출 시점 입력(제목 + 위원회 + 제안이유)의 SHA-256. */
    @Column(name = "source_hash", nullable = false, length = 64)
    private String sourceHash;

    public AnalysisBatchItem(Long batchId, Long billId, String sourceHash) {
        this.id = new AnalysisBatchItemId(batchId, billId);
        this.sourceHash = sourceHash;
    }

    public Long getBillId() {
        return id.getBillId();
    }
}
