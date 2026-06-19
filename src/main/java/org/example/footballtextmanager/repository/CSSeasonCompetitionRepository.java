package org.example.footballtextmanager.repository;

import org.example.footballtextmanager.model.CSCompetition;
import org.example.footballtextmanager.model.CSSeasonCompetition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface CSSeasonCompetitionRepository extends JpaRepository<CSSeasonCompetition, Long> {
    Optional<CSSeasonCompetition> findByCsCompetitionAndSeasonYear(CSCompetition league, Integer seasonYear);

    @Query("""
            select distinct sc.seasonYear
            from CSSeasonCompetition sc
            where sc.csCompetition.id = :csCompetitionId
              and sc.seasonYear is not null
            order by sc.seasonYear
            """)
    List<Integer> findSeasonYearsByCSCompetitionId(@Param("csCompetitionId") Long csCompetitionId);
}
