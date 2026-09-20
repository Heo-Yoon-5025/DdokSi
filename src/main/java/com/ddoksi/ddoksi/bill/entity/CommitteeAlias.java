package com.ddoksi.ddoksi.bill.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 개편 전후로 갈라진 위원회 이름을 묶는 별칭.
 *
 * <p>22대 국회 중간 상임위 개편으로 같은 위원회가 두 이름으로 존재한다. 심사가 끝난 법안은
 * 당시 이름이 기록에 얼어붙었고 열려 있는 법안만 새 이름으로 옮겨갔는데, 원천 데이터
 * 어디에도 둘이 같은 위원회라는 표시가 없다.
 *
 * <p><b>{@code bill.committeeName} 을 직접 고치지 않는 이유.</b> 그 값은 국회가 내려준
 * 사실이다. 그 법안은 실제로 '환경노동위원회' 시절에 처리됐고, 덮어쓰면 그 사실이 사라진다.
 * 수집 배치가 같은 값을 다시 내려주므로 매번 되돌려야 하는 문제도 생긴다.
 *
 * <p>자연키를 그대로 {@code @Id} 로 쓴다. 옛 이름 자체가 조회 키이고, 대리키를 두면
 * 이름으로 찾는 조회에 인덱스가 하나 더 필요해질 뿐이다.
 */
@Getter
@Entity
@Table(name = "committee_alias")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommitteeAlias {

    /** 기록에 남아 있는 옛 이름. */
    @Id
    @Column(name = "alias_name", length = 100)
    private String aliasName;

    /** 지금 쓰는 정식 이름. 앱 필터와 구독 설정에 노출되는 쪽이다. */
    @Column(name = "canonical_name", nullable = false, length = 100)
    private String canonicalName;

    /** 묶은 근거. 몇 년 뒤 "이건 왜 묶여 있지?" 가 반드시 나온다. */
    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public CommitteeAlias(String aliasName, String canonicalName, String note) {
        this.aliasName = aliasName;
        this.canonicalName = canonicalName;
        this.note = note;
        this.createdAt = Instant.now();
    }
}
