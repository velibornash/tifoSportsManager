package org.example.commonmanager.repository;

import org.example.commonmanager.model.CommonSeason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CommonSeasonRepository extends JpaRepository<CommonSeason, Long> {
    Optional<CommonSeason> findBySeasonYear(Integer seasonYear);
}
