package com.ddoksi.ddoksi.collection.repository;

import com.ddoksi.ddoksi.collection.entity.CollectionRun;
import com.ddoksi.ddoksi.collection.entity.CollectionRunStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionRunRepository extends JpaRepository<CollectionRun, Long> {

    /**
     * 마지막으로 성공한 실행을 찾는다.
     * 증분 수집을 이어갈 위치(cursorValue)를 여기서 가져온다.
     */
    Optional<CollectionRun> findFirstByJobNameAndStatusOrderByStartedAtDesc(
            String jobName, CollectionRunStatus status);
}
