package com.ddoksi.ddoksi.bill.repository;

import com.ddoksi.ddoksi.bill.entity.CommitteeAlias;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommitteeAliasRepository extends JpaRepository<CommitteeAlias, String> {

    /**
     * 전부 읽는다.
     *
     * <p>이름으로 한 건씩 찾지 않는 이유는 규모다. 22대 기준 3행이고 개편이 몇 번 더
     * 일어나도 열 행을 넘기 어렵다. 통째로 읽어 메모리에서 맵을 만드는 편이
     * 조회 경로마다 왕복하는 것보다 단순하고 빠르다.
     */
    @Override
    List<CommitteeAlias> findAll();
}
