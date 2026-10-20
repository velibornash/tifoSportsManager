package org.example.commonmanager.repository;

import org.example.commonmanager.model.CommonGameClock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CommonGameClockRepository extends JpaRepository<CommonGameClock, Long> {
}
