package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.PlayerDiscipline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlayerDisciplineRepository extends JpaRepository<PlayerDiscipline, Long> {

    /**
     * Does this player owe a ban in any official match this season?
     *
     * <p><b>This answers the question the caller actually has</b>, and it deliberately returns a
     * count rather than a row. The callers ask "is he carrying an un-served ban", never "which row is
     * his" - and returning a row made them throw the moment a player had two.
     *
     * <p>That was not hypothetical: two players in this world each carried two identical club-wide rows
     * ({@code bans_owed = 1} twice), and Spring Data's {@code Optional} return turned the pair into a
     * {@code NonUniqueResultException}. It surfaced as a failed matchday - *both* the prepare and the
     * publish job failed, two fixtures were skipped, and the error was 40 lines of Hibernate stack
     * rather than anything naming a player. One duplicate row anywhere on the planet stops the football.
     */
    @Query("SELECT COUNT(d) FROM PlayerDiscipline d "
            + "WHERE d.player.id = :playerId AND d.season = :season AND d.competition IS NULL "
            + "AND d.bansOwed > 0")
    long countOwedClubWideBans(@Param("playerId") Long playerId, @Param("season") Integer season);

    /**
     * The club-wide rows, oldest first, so a caller that has to pick one row to mutate picks the same
     * one every time instead of throwing.
     */
    List<PlayerDiscipline> findByPlayerIdAndSeasonAndCompetitionIsNullOrderByIdAsc(
            Long playerId, Integer season);

    /** The club-wide row, where red-card bans live. */
    Optional<PlayerDiscipline> findByPlayerIdAndSeasonAndCompetitionIsNull(Long playerId, Integer season);

    /** The competition row, where the yellow accumulation lives. */
    Optional<PlayerDiscipline> findByPlayerIdAndSeasonAndCompetitionId(
            Long playerId, Integer season, Long competitionId);

    List<PlayerDiscipline> findByPlayerIdAndSeason(Long playerId, Integer season);
}
