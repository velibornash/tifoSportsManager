package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.Season;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SeasonRepository extends JpaRepository<Season, Long> {
    Optional<Season> findBySeasonYear(Integer seasonYear);
}