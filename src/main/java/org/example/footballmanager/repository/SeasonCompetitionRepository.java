package org.example.footballmanager.repository;

import org.example.footballmanager.model.Competition;
import org.example.footballmanager.model.SeasonCompetition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SeasonCompetitionRepository extends JpaRepository<SeasonCompetition, Long> {
    Optional<SeasonCompetition> findByCompetitionAndSeasonYear(Competition league, Integer seasonYear);

    @Query("""
            select distinct sc.seasonYear
            from SeasonCompetition sc
            where sc.competition.id = :competitionId
              and sc.seasonYear is not null
            order by sc.seasonYear
            """)
    List<Integer> findSeasonYearsByCompetitionId(@Param("competitionId") Long competitionId);
}
