package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.NationalTeamAppointment;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.NationalTeamAppointmentRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reading and editing a national team (owner, 2026-09-28).
 *
 * <p>Owns three things the country page needs and that were previously faked: who the selector is,
 * which players are in the squad versus the pool, and whether an election is running.
 */
@Service
public class NationalTeamService {

    /** Owner: the squad is 25. */
    public static final int SQUAD_SIZE = 25;

    /**
     * How many pool rows the page is sent.
     *
     * <p>The pool itself is now computed whole - the count has to agree with the rows the owner can
     * scroll - and this is where the page stops. 80 rows is what the screen has always shown.
     */
    private static final int POOL_ROWS = 80;

    /**
     * The oldest age that may be called up to a U-21 side, inclusive.
     *
     * <p>A player is eligible in the season he turns 21, which is the football convention and the reason
     * this is {@code <=} rather than {@code <}.
     */
    public static final int U21_MAX_AGE = 21;

    private static final Logger log = LoggerFactory.getLogger(NationalTeamService.class);

    private final TeamRepository teams;
    private final PlayerRepository players;
    private final MatchFixtureRepository fixtures;
    private final org.example.commonmanager.repository.UserRepository users;
    private final NationalTeamElectionService elections;
    private final NationalTeamAppointments appointments;
    private final org.example.footballmanager.newLogic.repository.SeasonRepository seasons;
    private final GameClockRepository clocks;

    public NationalTeamService(TeamRepository teams,
                               PlayerRepository players, MatchFixtureRepository fixtures,
                               org.example.commonmanager.repository.UserRepository users,
                               NationalTeamElectionService elections,
                               NationalTeamAppointments appointments,
                               org.example.footballmanager.newLogic.repository.SeasonRepository seasons,
                               GameClockRepository clocks) {
        this.teams = teams;
        this.players = players;
        this.fixtures = fixtures;
        this.users = users;
        this.elections = elections;
        this.appointments = appointments;
        this.seasons = seasons;
        this.clocks = clocks;
    }

    public Optional<NationalTeamAppointment> activeAppointment(Long countryId, NationalTeamLevel level) {
        return appointments.activeAppointment(countryId, level);
    }


    /**
     * Appointments live in {@link NationalTeamAppointments}.
     *
     * <p>They were methods on this class until the election service needed to appoint a winner, which
     * made NationalTeamService and NationalTeamElectionService depend on each other and the context
     * would not start. Appointing is its own responsibility; the read side and the write side do not
     * need to be one class.
     */
    public NationalTeamAppointment appoint(Country country, NationalTeamLevel level, User selector, boolean elected) {
        return appointments.appoint(country, level, selector, elected);
    }

    public void appointBaselineSelectors(Country country) {
        appointments.appointBaselineSelectors(country);
    }

    public boolean isSelector(Country country, NationalTeamLevel level, User viewer) {
        if (viewer == null || viewer.getId() == null) {
            return false;
        }
        return appointments.activeAppointment(country.getId(), level)
                .map(appointment -> appointment.getSelector() != null
                        && viewer.getId().equals(appointment.getSelector().getId()))
                .orElse(false);
    }

    /**
     * The full national-team payload: squad, pool, selector, ranking, fixtures and election state.
     *
     * <p>The pool is sent to everyone but marked with a flag saying whether the viewer may act on it,
     * so the client does not have to re-derive who the selector is and cannot be used to bypass the
     * check - the endpoints re-check independently.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> describe(Country country, NationalTeamLevel level, User viewer) {
        Team team = teamFor(country, level);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("countryCode", country.getIsoCode());
        out.put("countryName", country.getName());
        out.put("level", level == NationalTeamLevel.U21 ? "u21" : "senior");

        if (team == null) {
            out.put("exists", false);
            out.put("squad", List.of());
            out.put("pool", List.of());
            out.put("squadSize", 0);
            out.put("isSelector", false);
            out.put("election", Map.of("open", false, "stage", "NONE",
                    "candidates", List.of(), "exists", false,
                    "note", "This national team has not been created yet."));
            return out;
        }

        boolean viewerIsSelector = isSelector(country, level, viewer);
        out.put("exists", true);
        out.put("teamId", team.getId());
        out.put("teamName", team.getName());

        activeAppointment(country.getId(), level).ifPresent(appointment -> {
            User selector = appointment.getSelector();
            out.put("selectorName", selector == null ? null
                    : (selector.getDisplayName() != null && !selector.getDisplayName().isBlank()
                            ? selector.getDisplayName() : selector.getUsername()));
            out.put("selectorIsProvisional", !appointment.isElected());
            out.put("mandateEndsAt", appointment.getMandateEndsAt());
        });
        out.put("isSelector", viewerIsSelector);

        List<Player> squad = new ArrayList<>(players.findByTeamId(team.getId()));
        squad.sort(Comparator.comparingInt(Player::getRating).reversed()
                .thenComparing(p -> p.getName() == null ? "" : p.getName()));
        out.put("squad", squad.stream().map(p -> playerRow(p, team.getId())).toList());
        // Over-age members of a U-21 squad are named rather than hidden. The pool filter stops new ones
        // arriving; this tells the selector what is already in the squad that the rule would refuse, so
        // the fix is his to make and he can see what he is fixing.
        out.put("overAgeSquadMembers", squad.stream()
                .filter(p -> !isEligibleAtThisLevel(p, level))
                .map(Player::getName)
                .toList());
        out.put("squadSize", squad.size());

        // The pool is every player in the country's clubs who is not already on the national roster.
        // Read once: `poolRows` and `countPool` both need the country's players, and each running its
        // own pass meant the same query twice and a count that could disagree with the rows above it.
        List<Player> countrySquad = viewerIsSelector ? availablePlayers(country, squad, level) : List.of();
        out.put("pool", viewerIsSelector ? poolRows(countrySquad) : List.of());
        out.put("poolSize", countrySquad.size());

        out.put("lastMatch", lastMatch(team.getId()));
        out.put("nextMatch", nextMatch(team.getId()));
        out.put("ranking", ranking(country.getIsoCode()));
        out.put("election", electionState(country, level, viewer));
        // Sent to everyone, not just the selector: a frozen squad is a fact about the tournament and a
        // non-selector looking at the team should see it too.
        out.put("squadLock", squadLock(level, currentSeasonYear(), clockWeek(), clockDay(), clockHour()));
        return out;
    }

    private Team teamFor(Country country, NationalTeamLevel level) {
        return level == NationalTeamLevel.U21 ? country.getU21NationalTeam() : country.getSeniorNationalTeam();
    }

    /**
     * One indexed query, where this used to load every club in the world and query each of the
     * country's clubs in turn.
     *
     * <p>Called twice per country page - once for the pool rows, once for its count - so the old
     * version read the whole club table twice and issued roughly 620 player queries per load. At
     * 7,730 players in one country that is the difference between a screen that opens and one that
     * makes the owner think the application has hung.
     */
    private List<Player> countryPlayers(Country country) {
        return players.findByTeamCountryId(country.getId());
    }

    /**
     * The country's club players that are not already on the national roster, best first.
     *
     * <p>Compared by source id: a national row carries the id of the club player it was copied from, and
     * the pool rows are the club players themselves. Matching on id is what keeps a called-up player
     * from also appearing in the pool they were just taken from.
     */
    private List<Player> availablePlayers(Country country, List<Player> squad, NationalTeamLevel level) {
        java.util.Set<Long> calledUp = squad.stream()
                .map(Player::getSourcePlayerId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        return countryPlayers(country).stream()
                .filter(p -> !calledUp.contains(p.getId()))
                .filter(p -> isEligibleAtThisLevel(p, level))
                .sorted(Comparator.comparingInt(Player::getRating).reversed()
                        .thenComparing(p -> p.getName() == null ? "" : p.getName()))
                .toList();
    }

    /**
     * Whether a player may be called up to this national side.
     *
     * <p><b>A U-21 squad containing a 31-year-old is not a squad.</b> The pool had no age filter at all,
     * so the U-21 tab offered the same list as the senior one: Serbia's U-21 side held 25 players of
     * which **11 were over 21, the oldest 32** (owner, 2026-10-10).
     *
     * <p>The limit is inclusive of 21, which is the football convention - a player is eligible in the
     * season he turns 21. It is applied to <em>call-ups</em> only. Nobody is deleted and nobody is
     * demoted: a squad that was built before this rule is left exactly as it is until a selector
     * changes it, because quietly rewriting a manager's roster is worse than showing him what he has.
     */
    private boolean isEligibleAtThisLevel(Player player, NationalTeamLevel level) {
        if (level != NationalTeamLevel.U21) {
            return true;
        }
        // `Player.age` is a primitive int and 0 is the only "not recorded" value it can hold, so the
        // check has to treat 0 as unknown rather than as a baby. A player whose age is unknown cannot be
        // shown to be under 21, and the pool is a list of options a manager acts on, so an unknown age
        // is excluded rather than assumed into a youth side.
        int age = player.getAge();
        return age > 0 && age <= U21_MAX_AGE;
    }

    /** The first {@value #POOL_ROWS} of the available players - the page sends a page, not a country. */
    private List<Map<String, Object>> poolRows(List<Player> available) {
        return available.stream().limit(POOL_ROWS).map(p -> playerRow(p, null)).toList();
    }


    private Map<String, Object> playerRow(Player player, Long nationalTeamId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", player.getId());
        // The club player this copy came from. Copying is what lets a national team exist without
        // taking anybody out of the league, and this is the link that makes the copy traceable.
        row.put("sourcePlayerId", player.getSourcePlayerId());
        row.put("name", player.getName());
        row.put("position", player.getPosition() == null ? null : player.getPosition().name());
        row.put("age", player.getAge());
        row.put("rating", player.getRating());
        row.put("form", player.getForm());
        row.put("clubName", player.getTeam() == null ? null : player.getTeam().getName());
        row.put("nationalTeamId", nationalTeamId);
        return row;
    }

    /**
     * Copies a club player onto the national roster.
     *
     * <p>Copy, not move. The player keeps playing for the club; the national row is a separate
     * record. Moving the row would remove them from the league, which is the one thing a national
     * call-up must never do.
     */
    @Transactional
    public Map<String, Object> addToSquad(Country country, NationalTeamLevel level, long sourcePlayerId,
                                         User viewer) {
        requireSelector(country, level, viewer);
        requireSquadUnlocked(clockWeek(), clockDay(), clockHour());
        Team team = requireTeam(country, level);

        if (players.findByTeamId(team.getId()).size() >= SQUAD_SIZE) {
            throw new IllegalStateException("The squad is full at " + SQUAD_SIZE + " players.");
        }
        Player source = players.findById(sourcePlayerId)
                .orElseThrow(() -> new IllegalArgumentException("No such player: " + sourcePlayerId));
        if (source.getTeam() == null || source.getTeam().getCountry() == null
                || !country.getId().equals(source.getTeam().getCountry().getId())) {
            throw new IllegalArgumentException("That player does not belong to this country.");
        }

        // **The rule is enforced here, on the write, and not only in the pool that offers the player.**
        // A pool filter alone stops new mistakes and leaves every existing one in place, and this
        // country's U-21 side was holding 25 players of whom 11 were over 21 with no way to add or
        // remove anybody to fix it. The check belongs at the door.
        if (!isEligibleAtThisLevel(source, level)) {
            throw new IllegalArgumentException(level == NationalTeamLevel.U21
                    ? source.getName() + " is " + source.getAge() + " and cannot be called up to a U-21 "
                            + "side. The limit is " + U21_MAX_AGE + ", inclusive."
                    : source.getName() + " is not eligible for this national side.");
        }

        Player copy = new Player();
        copy.setName(source.getName());
        copy.setAge(source.getAge());
        copy.setPosition(source.getPosition());
        copy.setRating(source.getRating());
        copy.setForm(source.getForm());
        copy.setPlayerValue(0.0);
        copy.setSourcePlayerId(source.getId());
        copy.setNationality(country.getIsoCode());
        copy.setTeam(team);
        copy.setSkills(source.getSkills());
        Player saved = players.save(copy);
        return playerRow(saved, team.getId());
    }

    /** Removes a national-roster row. Only the copy is deleted; the club player is untouched. */
    @Transactional
    public void removeFromSquad(Country country, NationalTeamLevel level, long nationalPlayerId, User viewer) {
        requireSelector(country, level, viewer);
        requireSquadUnlocked(clockWeek(), clockDay(), clockHour());
        Team team = requireTeam(country, level);
        Player onSquad = players.findById(nationalPlayerId)
                .orElseThrow(() -> new IllegalArgumentException("No such player: " + nationalPlayerId));
        if (onSquad.getTeam() == null || !team.getId().equals(onSquad.getTeam().getId())) {
            throw new IllegalArgumentException("That player is not in this squad.");
        }
        players.delete(onSquad);
    }

    private Team requireTeam(Country country, NationalTeamLevel level) {
        Team team = teamFor(country, level);
        if (team == null) {
            throw new IllegalStateException("This national team has not been created yet.");
        }
        return team;
    }

    /**
     * Enforces selector-only access.
     *
     * <p>Every write path calls this rather than trusting a flag from the client. The client is told
     * whether the viewer is the selector so it can hide controls, but a hidden control is not a
     * permission.
     */
    private void requireSelector(Country country, NationalTeamLevel level, User viewer) {
        if (!isSelector(country, level, viewer)) {
            throw new SecurityException("Only the selector of "
                    + (level == NationalTeamLevel.U21 ? "the U-21" : "the national") + " team may do that.");
        }
    }

    // ---------- the World Cup squad lock ----------

    /**
     * When a squad stops being editable, from the game clock (owner, 2026-10-06).
     *
     * <p>The owner: <b>"lock from week 12 day 1, 10:00."</b> The tournament's first round is on day 1,
     * so the list a manager picks from has to be settled before kickoff — ten in the morning is the
     * owner's hour, not a technical one.
     */
    public static final int LOCK_WEEK = 12;
    public static final int LOCK_DAY = 1;
    public static final int LOCK_HOUR = 10;

    /**
     * Whether the squad for a level is frozen, and why.
     *
     * <p>Qualified for the tournament: the 25 chosen for the World Cup cannot be changed. Qualified
     * only: the squad stays editable for the whole season, which is the owner's rule for qualifying —
     * "the squad of 25 may be changed at any time."
     *
     * <p>The U-21 side locks on the same clock, because week 12 day 1 is when its tournament opens too.
     */
    public Map<String, Object> squadLock(NationalTeamLevel level, int seasonYear, Integer week,
                                         Integer day, Integer hour) {
        boolean locked = isSquadLocked(week, day, hour);
        return Map.of(
                "locked", locked,
                "fromWeek", LOCK_WEEK,
                "fromDay", LOCK_DAY,
                "fromHour", LOCK_HOUR,
                "level", level == NationalTeamLevel.U21 ? "u21" : "senior",
                "reason", locked
                        ? "The World Cup squad is fixed. The tournament opens today."
                        : "The squad can be changed until week " + LOCK_WEEK + " day 1, 10:00.");
    }

    /**
     * The one question both write paths ask before touching a squad.
     *
     * <p>Nulls mean the clock is not known, and then the squad is <b>not</b> locked: a corrupt clock
     * should not silently take a manager's ability to pick a team, and the lock has a hard deadline
     * rather than a condition that can be reached by accident.
     */
    public static boolean isSquadLocked(Integer week, Integer day, Integer hour) {
        if (week == null || day == null || hour == null) {
            return false;
        }
        if (week < LOCK_WEEK) {
            return false;
        }
        if (week > LOCK_WEEK) {
            return true;
        }
        if (day < LOCK_DAY) {
            return false;
        }
        if (day > LOCK_DAY) {
            return true;
        }
        return hour >= LOCK_HOUR;
    }

    /** Throws the one message the client shows, so the two write paths cannot word it differently. */
    private void requireSquadUnlocked(Integer week, Integer day, Integer hour) {
        if (isSquadLocked(week, day, hour)) {
            throw new IllegalStateException(
                    "The squad is fixed for the tournament from week " + LOCK_WEEK + " day " + LOCK_DAY + ".");
        }
    }

    // ---------- the clock, for the lock ----------

    /**
     * Where the lock reads the time from.
     *
     * <p>The game clock, not the wall clock. The tournament opens on week 12 day 1 of the <i>season</i>,
     * so a lock keyed on the wall clock would freeze every squad in the world at the same moment
     * regardless of where each player's season had got to.
     */
    private Integer clockWeek() {
        // getCurrentWeek is on GameClock via Lombok's @Getter, and it is the one field here with no
        // hand-written accessor, so it is reached the same way as the other two.
        return clockOrNull(GameClock::getCurrentWeek);
    }

    private Integer clockDay() {
        return clockOrNull(GameClock::getCurrentDay);
    }

    private Integer clockHour() {
        return clockOrNull(GameClock::getCurrentHour);
    }

    private Integer clockOrNull(java.util.function.Function<GameClock, Integer> field) {
        try {
            return clocks.findById(1L).map(field).orElse(null);
        } catch (RuntimeException unreadable) {
            // A clock that cannot be read leaves the squad editable. The lock has a deadline rather
            // than a trigger that can fire by accident, so the safe direction is "not yet".
            log.warn("Could not read the game clock; treating the squad as unlocked: {}", unreadable.getMessage());
            return null;
        }
    }

    /**
     * Delegates to the election service.
     *
     * <p>This used to be a hardcoded "no election running". Now the panel reflects a real election,
     * including for a team that does not exist yet - an election is about a country and a level, and
     * can be contested before anybody is appointed.
     */
    private Map<String, Object> electionState(Country country, NationalTeamLevel level, User viewer) {
        return elections.describeElection(country, level, currentSeasonYear(), viewer,
                viewer != null && "velibor@example.com".equalsIgnoreCase(viewer.getEmail()));
    }

    /**
     * The season the election belongs to: the highest season in the table.
     *
     * <p>Defaulting to 1 rather than 0 keeps a fresh install usable: an election created for
     * season 0 would never match the one the admin screen lists.
     */
    private int currentSeasonYear() {
        try {
            // The season repository only offers a lookup by value, so the world is on the highest
            // season that exists. A database with seasons 1 and 2 in it is on season 2.
            return seasons.findAll().stream()
                    .map(season -> season.getSeasonYear())
                    .filter(java.util.Objects::nonNull)
                    .max(Integer::compareTo)
                    .filter(year -> year > 0)
                    .orElse(1);
        } catch (RuntimeException ignored) {
            // Fall through to the default.
        }
        return 1;
    }

    private Map<String, Object> lastMatch(Long teamId) {
        List<MatchFixture> played = fixtures.findAllForTeam(teamId);
        return played.stream()
                .filter(f -> f.isPlayed())
                .max(Comparator.comparing(MatchFixture::getMatchDate,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(f -> fixtureRow(f, teamId))
                .orElse(null);
    }

    private Map<String, Object> nextMatch(Long teamId) {
        Instant now = Instant.now();
        List<MatchFixture> upcoming = fixtures.findAllForTeam(teamId);
        return upcoming.stream()
                .filter(f -> !f.isPlayed())
                .filter(f -> f.getMatchDate() != null && f.getMatchDate().toInstant(java.time.ZoneOffset.UTC).isAfter(now))
                .min(Comparator.comparing(MatchFixture::getMatchDate))
                .map(f -> fixtureRow(f, teamId))
                .orElse(null);
    }

    private Map<String, Object> fixtureRow(MatchFixture fixture, Long teamId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", fixture.getId());
        row.put("round", fixture.getRoundNumber());
        row.put("week", fixture.getWeekNumber());
        row.put("date", fixture.getMatchDate());
        row.put("played", fixture.isPlayed());
        row.put("homeName", fixture.getHomeTeam() == null ? null : fixture.getHomeTeam().getName());
        row.put("awayName", fixture.getAwayTeam() == null ? null : fixture.getAwayTeam().getName());
        row.put("isHome", fixture.getHomeTeam() != null && fixture.getHomeTeam().getId().equals(teamId));
        return row;
    }

    /**
     * National ranking for a country.
     *
     * <p>There is no rating persistence and no international fixtures yet, so a real rank cannot be
     * computed. Returning null with a stated reason is honest; inventing a number from the club
     * reputation would look like a real ranking and be meaningless.
     */
    private Map<String, Object> ranking(String isoCode) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rank", null);
        row.put("points", null);
        row.put("reason", "No international results yet, so there is nothing to rank on.");
        return row;
    }
}
