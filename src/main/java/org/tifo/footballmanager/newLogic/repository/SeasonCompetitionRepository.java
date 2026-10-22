package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.Competition;
import org.tifo.footballmanager.newLogic.model.SeasonCompetition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SeasonCompetitionRepository extends JpaRepository<SeasonCompetition, Long> {
    Optional<SeasonCompetition> findByCompetitionAndSeasonYear(Competition league, Integer seasonYear);

    /**
     * This competition's row for this season, created if it is not there yet.
     *
     * <p>The find-or-create was written three times — {@code SeasonService.ensureSeasonCompetition} and a
     * private copy each in {@code NationalTournamentSeeder} and {@code InternationalClubCupDraw}, the
     * latter two byte for byte identical. It lives here because every one of those callers is holding
     * this repository anyway, and because a season row is the table's own business rather than a rule of
     * the draw that happens to need one.
     *
     * <p>Not transactional of its own accord: the caller decides. A draw already runs inside one, and the
     * read-then-create is only correct while it does.
     */
    default SeasonCompetition ensure(Competition competition, int seasonYear) {
        return findByCompetitionAndSeasonYear(competition, seasonYear).orElseGet(() -> {
            SeasonCompetition seasonCompetition = new SeasonCompetition();
            seasonCompetition.setCompetition(competition);
            seasonCompetition.setSeasonYear(seasonYear);
            seasonCompetition.setFinished(false);
            return save(seasonCompetition);
        });
    }
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
