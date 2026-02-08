package org.zhzssp.memorandum.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.zhzssp.memorandum.entity.Link;

import java.util.List;

public interface LinkRepository extends JpaRepository<Link, Long> {

    List<Link> findBySourceTypeAndSourceId(Link.LinkSourceType sourceType, Long sourceId);

    List<Link> findByTargetTypeAndTargetId(Link.LinkTargetType targetType, Long targetId);
}
