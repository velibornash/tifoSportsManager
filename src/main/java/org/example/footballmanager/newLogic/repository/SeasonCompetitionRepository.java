package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SeasonCompetitionRepository extends JpaRepository<SeasonCompetition, Long> {
    Optional<SeasonCompetition> findByCompetitionAndSeasonYear(Competition league, Integer seasonYear);
    List<SeasonCompetition> findBySeasonYear(Integer seasonYear);

    /**
     * The season's row for many competitions at once.
     *
     * <p>One query where {@code findByCompetitionAndSeasonYear} is one per competition. Tier 5 has
     * sixteen divisions in each of forty-eight countries, so the World page was asking 768 times for
     * the same answer before the three cups of that tier multiplied it by three.
     */
    List<SeasonCompetition> findByCompetitionInAndSeasonYear(List<Competition> competitions, Integer seasonYear);

    /** The season competitions of one competition, newest season first. */
    List<SeasonCompetition> findByCompetitionOrderBySeasonYearDesc(Competition competition);

    @Query("""
            select distinct sc.seasonYear
            from SeasonCompetition sc
            where sc.competition.id = :competitionId
              and sc.seasonYear is not null
            order by sc.seasonYear
            """)
    List<Integer> findSeasonYearsByCompetitionId(@Param("competitionId") Long competitionId);
}
