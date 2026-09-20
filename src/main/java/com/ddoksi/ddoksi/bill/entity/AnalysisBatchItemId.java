package com.ddoksi.ddoksi.bill.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** {@link AnalysisBatchItem} 의 복합키 (배치, 법안). */
@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisBatchItemId implements Serializable {

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "bill_id", nullable = false)
    private Long billId;

    public AnalysisBatchItemId(Long batchId, Long billId) {
        this.batchId = batchId;
        this.billId = billId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AnalysisBatchItemId other)) {
            return false;
        }
        return Objects.equals(batchId, other.batchId) && Objects.equals(billId, other.billId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(batchId, billId);
    }
}
