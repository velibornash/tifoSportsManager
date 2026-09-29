package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.NationalTeamAppointment;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
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

    private static final Logger log = LoggerFactory.getLogger(NationalTeamService.class);

    private final TeamRepository teams;
    private final PlayerRepository players;
    private final MatchFixtureRepository fixtures;
    private final org.example.commonmanager.repository.UserRepository users;
    private final NationalTeamElectionService elections;
    private final NationalTeamAppointments appointments;
    private final org.example.footballmanager.newLogic.repository.SeasonRepository seasons;

    public NationalTeamService(TeamRepository teams,
                               PlayerRepository players, MatchFixtureRepository fixtures,
                               org.example.commonmanager.repository.UserRepository users,
                               NationalTeamElectionService elections,
                               NationalTeamAppointments appointments,
                               org.example.footballmanager.newLogic.repository.SeasonRepository seasons) {
        this.teams = teams;
        this.players = players;
        this.fixtures = fixtures;
        this.users = users;
        this.elections = elections;
        this.appointments = appointments;
        this.seasons = seasons;
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
        out.put("squadSize", squad.size());

        // The pool is every player in the country's clubs who is not already on the national roster.
        out.put("pool", viewerIsSelector ? poolRows(country, squad) : List.of());
        out.put("poolSize", viewerIsSelector ? countPool(country, squad) : 0);

        out.put("lastMatch", lastMatch(team.getId()));
        out.put("nextMatch", nextMatch(team.getId()));
        out.put("ranking", ranking(country.getIsoCode()));
        out.put("election", electionState(country, level, viewer));
        return out;
    }

    private Team teamFor(Country country, NationalTeamLevel level) {
        return level == NationalTeamLevel.U21 ? country.getU21NationalTeam() : country.getSeniorNationalTeam();
    }

    private List<Player> countryPlayers(Country country) {
        List<Player> all = new ArrayList<>();
        for (Team club : teams.findClubTeamsForOperations()) {
            if (club.getId() == null || club.getCountry() == null
                    || !country.getId().equals(club.getCountry().getId())) {
                continue;
            }
            all.addAll(players.findByTeamId(club.getId()));
        }
        return all;
    }

    private List<Map<String, Object>> poolRows(Country country, List<Player> squad) {
        // Compared by source id: a national row carries the id of the club player it was copied
        // from, and the pool rows are the club players themselves. Matching on id is what keeps a
        // called-up player from also appearing in the pool they were just taken from.
        java.util.Set<Long> calledUp = squad.stream()
                .map(Player::getSourcePlayerId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        List<Player> pool = countryPlayers(country).stream()
                .filter(p -> !calledUp.contains(p.getId()))
                .sorted(Comparator.comparingInt(Player::getRating).reversed()
                        .thenComparing(p -> p.getName() == null ? "" : p.getName()))
                .limit(80)
                .toList();
        return pool.stream().map(p -> playerRow(p, null)).toList();
    }

    private int countPool(Country country, List<Player> squad) {
        java.util.Set<Long> calledUp = squad.stream()
                .map(Player::getSourcePlayerId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        return (int) countryPlayers(country).stream()
                .filter(p -> !calledUp.contains(p.getId()))
                .count();
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
