package com.ddoksi.ddoksi.bill;

import com.ddoksi.ddoksi.bill.entity.CommitteeAlias;
import com.ddoksi.ddoksi.bill.repository.CommitteeAliasRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개편 전후로 갈라진 위원회 이름을 하나로 묶어 준다.
 *
 * <p>하는 일은 둘이다. 화면에 <b>정식 이름만</b> 보여주고, 그 이름으로 조회할 때
 * <b>옛 이름으로 기록된 법안까지</b> 함께 찾는다.
 *
 * <p><b>이것이 없으면 레터가 조용히 실패한다.</b> 구독자가 '환경노동위원회' 를 고르면
 * 그 이름의 법안 145건은 전부 심사가 끝난 것들이라 매칭되는 PENDING 법안이 0건이다.
 * 매달 빈 호가 나가고 예외도 로그도 남지 않는다.
 *
 * <p><b>캐시하지 않는다.</b> 22대 기준 3행이고 개편이 몇 번 더 일어나도 열 행을 넘기 어렵다.
 * 이 크기에서 캐시가 버는 시간보다 "표를 고쳤는데 재기동 전까지 반영되지 않는" 혼란의
 * 비용이 크다.
 */
@Component
@Transactional(readOnly = true)
public class CommitteeNameResolver {

    private static final Logger log = LoggerFactory.getLogger(CommitteeNameResolver.class);

    /** 별칭을 따라가는 최대 깊이. 체인은 금지돼 있으므로 이 값에 닿는 것 자체가 사고 신호다. */
    private static final int MAX_HOPS = 5;

    private final CommitteeAliasRepository aliasRepository;

    public CommitteeNameResolver(CommitteeAliasRepository aliasRepository) {
        this.aliasRepository = aliasRepository;
    }

    /**
     * 조회에 쓸 이름들. 정식 이름 하나를 받아 그것과 모든 옛 이름을 함께 돌려준다.
     *
     * <p>입력이 옛 이름이어도 동작한다. 앱이 오래된 필터 값을 들고 있거나 구독 설정에
     * 옛 이름이 저장돼 있을 수 있는데, 그런 요청이 0건을 받는 것이 바로 막으려던 문제다.
     *
     * @param requested 사용자가 고른 위원회명 (정식 이름이든 옛 이름이든)
     * @return 같은 위원회를 가리키는 모든 이름. 알 수 없는 이름이면 입력 그대로 한 건
     */
    public List<String> expand(String requested) {
        if (requested == null || requested.isBlank()) {
            return List.of();
        }
        String name = requested.strip();
        Map<String, String> toCanonical = resolvedAliases();

        String canonical = toCanonical.getOrDefault(name, name);

        Set<String> names = new LinkedHashSet<>();
        names.add(canonical);
        toCanonical.forEach((alias, target) -> {
            if (target.equals(canonical)) {
                names.add(alias);
            }
        });
        return List.copyOf(names);
    }

    /**
     * 화면에 내보낼 위원회 목록. 옛 이름은 정식 이름으로 접어 중복을 없앤다.
     *
     * @param rawNames DB 에 실제로 존재하는 위원회명 (중복 없이)
     */
    public List<String> toCanonicalNames(List<String> rawNames) {
        Map<String, String> toCanonical = resolvedAliases();
        return rawNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> toCanonical.getOrDefault(name, name))
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    /**
     * 별칭 → 최종 정식 이름.
     *
     * <p>한 칸만 옮기지 않고 <b>종점까지 따라간다.</b> A→B, B→C 같은 체인은 규칙으로
     * 금지돼 있지만(V7 주석 참고), 개편이 또 일어났을 때 기존 행을 갱신하지 않고 새 행만
     * 추가하면 자연스럽게 생긴다. 한 칸만 옮기면 중간 이름을 정식 이름으로 착각해
     * 목록에 옛 이름이 다시 나타나고, 아무도 예외를 보지 못한다.
     */
    private Map<String, String> resolvedAliases() {
        List<CommitteeAlias> aliases = aliasRepository.findAll();
        Map<String, String> direct = new HashMap<>();
        for (CommitteeAlias alias : aliases) {
            direct.put(alias.getAliasName(), alias.getCanonicalName());
        }

        Map<String, String> resolved = new HashMap<>();
        for (String alias : direct.keySet()) {
            resolved.put(alias, terminal(alias, direct));
        }
        return resolved;
    }

    /** 별칭을 더 이상 옮겨갈 곳이 없을 때까지 따라간다. */
    private String terminal(String alias, Map<String, String> direct) {
        String current = direct.get(alias);
        List<String> path = new ArrayList<>(List.of(alias, current));

        for (int hop = 0; hop < MAX_HOPS; hop++) {
            String next = direct.get(current);
            if (next == null) {
                if (hop > 0) {
                    log.warn("위원회 별칭이 체인으로 연결돼 있습니다. 정식 이름만 가리키도록 "
                            + "committee_alias 를 정리하세요: {}", String.join(" → ", path));
                }
                return current;
            }
            if (path.contains(next)) {
                log.error("위원회 별칭에 순환이 있습니다. 입력 이름을 그대로 씁니다: {} → {}",
                        String.join(" → ", path), next);
                return alias;
            }
            path.add(next);
            current = next;
        }

        log.error("위원회 별칭이 {}단계를 넘어갑니다. 입력 이름을 그대로 씁니다: {}",
                MAX_HOPS, String.join(" → ", path));
        return alias;
    }
}
