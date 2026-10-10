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
     * The named players who have actually played — recovery's population, asked for by id.
     *
     * <p>{@link #findByLastPlayedAtIsNotNull()} answers "every player who has ever played", and the
     * daily recovery job wanted "the ones who played in the last two days" — so it loaded the whole
     * answer and skipped the rest inside the loop. At the scale this project targets that is every
     * player in the world, every game day, to recover the few thousand who actually turned up.
     *
     * <p><b>The predicate is kept rather than assumed.</b> "These ids came from zone loads, so they must
     * have played" is true today and is the kind of thing that stops being true when some other code
     * writes a zone load. Stating it in the query means a violation is a missed player rather than a
     * quietly recovered one.
     */
    List<Player> findByIdInAndLastPlayedAtIsNotNull(Collection<Long> ids);

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

    /**
     * Every club player in one country, in a single query.
     *
     * <p>This replaces a loop that loaded <b>every club in the world</b> and filtered in Java, then
     * issued one query per club. On the country page that was two full club reads and ~620 player
     * queries per load, because {@code poolRows} and {@code countPool} each did it separately. It is one
     * indexed join on {@code team.country_id} here.
     *
     * <p>Unsorted on purpose: the caller sorts by rating and then name, and that comparator is the
     * definition of "best first" for the pool. Ordering here would be a second, silently different one.
     */
    @Query("select p from Player p where p.team.country.id = :countryId")
    List<Player> findByTeamCountryId(@Param("countryId") Long countryId);

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

    /**
     * Every player still at a club and still playing — the retirement sweep's whole input.
     *
     * <p>Filtered in the query rather than in Java because {@code SeasonService} ages the entire world
     * with one bulk update, so there is no smaller set to work from. A retired player has no club, so
     * {@code team is not null} already excludes him and the sweep is idempotent; {@code retiredSeason
     * is null} states the same thing twice on purpose, so the query is correct even if a future change
     * ever leaves a retired player attached to a club.
     *
     * <p>Served by {@code ix_player_team_not_retired (team_id, retired_season, age)}. The per-player
     * retirement age is a function of his rating, so the age range cannot be pushed into SQL — this
     * returns club players and the service decides who is due.
     */
    @Query("SELECT p FROM Player p WHERE p.team IS NOT NULL AND p.retiredSeason IS NULL")
    List<Player> findActiveClubPlayers();

    /**
     * Every player's squad, for many teams at once.
     *
     * <p>Added with {@code ScheduleInsightService.buildTeamSnapshots}, which is called by both ranking
     * services over the whole world. Reading one squad per team there was 14,731 queries — and, worse,
     * every entity it returned stayed in the persistence context, so the transaction's flush then
     * dirty-checked the entire accumulated graph before it could write anything.
     */
    @Query("SELECT p FROM Player p WHERE p.team.id IN :teamIds")
    List<Player> findByTeamIdIn(Collection<Long> teamIds);
}
