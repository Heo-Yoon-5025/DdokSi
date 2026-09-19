package com.ddoksi.ddoksi.bill.analysis;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 법안 주제 태그의 고정 어휘.
 *
 * <p><b>왜 우리가 만들어야 하는가.</b> 국회 Open API 에는 주제 분류가 아예 없다.
 * {@code committee_name} 이 유일하게 주제 비슷한 축인데 대체가 되지 않는다 —
 * 법제사법위원회(1,822건)는 주제가 아니라 모든 법안이 거쳐가는 관문이고,
 * 행정안전위원회(2,655건)는 지방자치·경찰·재난·개인정보가 뒤섞여 있다.
 *
 * <p><b>왜 고정 어휘인가.</b> 모델에게 자유롭게 태그를 붙이라고 하면 19,406개의 서로 다른
 * 태그가 나온다. 그러면 "이번 달 환경 법안" 같은 묶음을 만들 수 없어 분류의 목적이 사라진다.
 *
 * <p>어휘는 실제 데이터 분포에서 뽑았다. 가장 많이 개정되는 법률 상위 30개
 * (조세특례제한법 839 · 공직선거법 322 · 지방세특례제한법 287 · 국회법 267 · 소득세법 194 …)
 * 가 모두 하나 이상에 들어가는 것을 확인했다.
 *
 * <p>어휘를 바꾸는 것은 스키마 변경이 아니라 {@code prompt_version} 을 올리는 사건이다.
 * 그래서 DB CHECK 제약이 아니라 여기에 둔다.
 */
public enum BillTopic {

    TAX_FINANCE("세금·재정"),
    LABOR("일자리·노동"),
    WELFARE_PENSION("복지·연금"),
    HEALTH("의료·보건"),
    EDUCATION("교육·보육"),
    HOUSING("주거·부동산"),
    TRANSPORT_SAFETY("교통·안전"),
    ENVIRONMENT_ENERGY("환경·에너지"),
    INDUSTRY("산업·창업"),
    FINANCE_CONSUMER("금융·소비자"),
    DIGITAL_PRIVACY("디지털·개인정보"),
    CRIME_JUSTICE("범죄·사법"),
    POLITICS_ADMIN("정치·행정"),
    CULTURE_SPORTS("문화·체육"),
    AGRICULTURE_MARINE("농림·해양"),
    DEFENSE_FOREIGN("국방·외교");

    /** 한 법안에 붙일 수 있는 태그 수 상한. 셋 이상이면 사실상 분류가 안 된 것이다. */
    public static final int MAX_PER_BILL = 2;

    private final String label;

    BillTopic(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 프롬프트에 그대로 박아 넣을 어휘 목록. 여기와 프롬프트가 갈라지지 않게 한 곳에서 만든다. */
    public static List<String> labels() {
        return Arrays.stream(values()).map(BillTopic::label).toList();
    }

    /**
     * 모델이 돌려준 태그 중 어휘에 있는 것만 남긴다.
     *
     * <p><b>왜 서버에서 다시 거르는가.</b> 어휘 제약을 JSON Schema 의 {@code enum} 으로
     * 걸 수도 있지만, 그 경로는 한국어 값이 포함된 스키마가 SDK 버전마다 동일하게 동작하는지
     * 확인하지 못했다. 스키마가 막아주면 이 필터는 아무것도 하지 않고, 막아주지 못하면
     * 여기서 걸린다 — 어느 쪽이든 어휘 밖의 값이 DB 에 들어가지 않는다.
     *
     * <p>중복은 제거하고 순서는 모델이 준 순서를 유지한다. 앞에 온 것이 더 중심적인 주제다.
     * 상한을 넘으면 뒤를 버린다.
     *
     * @return 어휘에 있는 태그만, 최대 {@link #MAX_PER_BILL} 개
     */
    public static List<String> sanitize(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> known = new LinkedHashSet<>(labels());
        return raw.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::strip)
                .filter(known::contains)
                .distinct()
                .limit(MAX_PER_BILL)
                .toList();
    }

    /** 걸러진 값이 있었는지 — 호출부가 경고 로그를 남길지 판단하는 데 쓴다. */
    public static boolean hasUnknown(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        Set<String> known = new LinkedHashSet<>(labels());
        return raw.stream().anyMatch(t -> t == null || !known.contains(t.strip()));
    }
}
