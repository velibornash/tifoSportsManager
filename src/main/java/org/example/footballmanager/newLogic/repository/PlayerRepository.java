package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long>, PagingAndSortingRepository<Player, Long> {

    /**
     * Players who have actually played — the only ones daily recovery has anything to say about.
     *
     * <p>Added with the bulk recovery job. It used to be {@code findAll()} with a null check inside the
     * loop, which loaded the whole world — 16,354 players — to throw most of it away, once a day, for
     * every day the job fired on.
     */
    List<Player> findByLastPlayedAtIsNotNull();

    /**
     * Players who are actually tired — the only ones weekly recovery has anything to say about.
     *
     * <p>The sibling of {@link #findByLastPlayedAtIsNotNull()}, and it fixes the same mistake in the
     * other job: weekly fatigue recovery read {@code findAll()} and skipped anybody at zero fatigue
     * inside the loop, so every week it loaded the whole world — 370,000 rows once the simulated
     * countries are seeded — to recover the tired few, and then {@code saveAll}'d the entire list back,
     * changed or not.
     *
     * <p>Fatigue lives on the {@code Skills} embeddable, so this is a column on {@code player} and the
     * question is asked by the database rather than by a loop over everything.
     */
    List<Player> findBySkillsFatigueGreaterThan(int fatigue);

    List<Player> findByTeamId(Long teamId);
    List<Player> findByTeamIdAndPosition(Long teamId, Position position);
    Optional<Player> findByIdAndTeamId(Long id, Long teamId);
    Optional<Player> findByNameAndTeam(String name, Team team);
    int countByTeam(Team team);

    /** Clubs short of fit players are more willing to accept a friendly - see FriendlyRequestService. */
    int countByTeamIdAndInjuredTrue(Long teamId);
    List<Player> findByTeam(Team homeTeam);
    Collection<Player> findByTeamIdIn(List<Long> teamIds);
    List<Player> findByInjuryDaysRemainingGreaterThan(int days);

    @Query("SELECT p FROM Player p WHERE p.team.id = :teamId AND p.injured = false")
    List<Player> findAvailableByTeamId(@Param("teamId") Long teamId);

    @Modifying
    @Query("update Player p set p.age = p.age + 1")
    int incrementAgeForAllPlayers();
}
