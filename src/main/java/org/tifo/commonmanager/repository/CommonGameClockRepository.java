package org.tifo.commonmanager.repository;

import org.tifo.commonmanager.model.CommonGameClock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CommonGameClockRepository extends JpaRepository<CommonGameClock, Long> {
}
