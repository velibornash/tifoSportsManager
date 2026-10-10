package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.Lineup;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerRole;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.LineupRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The XI a club picks for one fixture.
 *
 * <p><b>The schema was already right and nothing wrote it.</b> {@code Lineup.match} has been a nullable
 * {@code @ManyToOne} all along, and every reader asked for {@code match IS NULL} — the template — because
 * that was the only row that could ever exist. So a manager could not pick a team for a game; the squad
 * order was whatever the last template happened to say.
 *
 * <p><b>A per-match lineup is keyed by the <em>match</em>, not the fixture</b>, because that is what the
 * column holds and because a lineup is the record of who actually started. The fixture's own copy is
 * resolved through {@code MatchFixture.playedMatch} at read time, so a lineup set before kickoff reaches
 * the match that fixture becomes.
 *
 * <p><b>Warnings never block.</b> An injured player in the XI is a mistake the manager can see and
 * correct, not a reason to refuse a save and lose the rest of the team selection with it. The engine has
 * its own rules and will sub him; refusing here would only teach the manager that the screen is not worth
 * using. The one thing refused is a selection that cannot produce a match at all.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatchLineupService {

    /** Eleven is a law, not a preference. */
    public static final int STARTERS = 11;

    /** The bench the substitution rules can draw on. */
    public static final int MAX_BENCH = 7;

    private final LineupRepository lineups;
    private final PlayerRepository players;
    private final MatchRepository matches;
    private final MatchFixtureRepository fixtures;
    private final org.example.footballmanager.newLogic.repository.TeamRepository teams;
    private final DisciplineService discipline;

    /**
     * Saves the club's XI for one fixture.
     *
     * @param starterIds in the order the manager picked them, which is the order they keep
     * @param benchIds   up to {@value #MAX_BENCH}; order kept too, because the bench is not a set
     * @param formation  e.g. {@code 4-4-2}
     * @throws IllegalArgumentException only when the selection cannot produce a match
     */
    @Transactional
    public Lineup save(Long teamId, Long fixtureId, List<Long> starterIds, List<Long> benchIds,
                       String formation, String style) {

        Team team = requireTeam(teamId);
        MatchFixture fixture = requireFixture(fixtureId);
        if (!playsIn(fixture, teamId)) {
            throw new IllegalArgumentException("Your club is not playing in this fixture.");
        }

        List<Long> starters = distinct(starterIds);
        List<Long> bench = distinct(benchIds);

        if (starters.size() != STARTERS) {
            // The one hard refusal. Fewer than eleven is not a lineup with a problem in it; it is not a
            // lineup, and the engine would auto-pick one anyway — silently, which is the thing worth
            // avoiding.
            throw new IllegalArgumentException(
                    "A team needs exactly " + STARTERS + " players, and " + starters.size()
                            + (starters.isEmpty() ? " were chosen." : " were chosen."));
        }
        for (Long id : starters) {
            if (bench.contains(id)) {
                throw new IllegalArgumentException("The same player cannot start and be on the bench.");
            }
        }
        if (bench.size() > MAX_BENCH) {
            throw new IllegalArgumentException("A bench is at most " + MAX_BENCH + " players.");
        }

        List<Player> picked = resolvePlayers(teamId, starters);
        List<Player> pickedBench = resolvePlayers(teamId, bench);

        Lineup lineup = lineups.findByTeamIdAndMatchId(teamId, matchIdOf(fixture))
                .orElseGet(Lineup::new);
        lineup.setTeam(team);
        lineup.setMatch(matchOrNull(matchIdOf(fixture)));
        lineup.setStartingPlayers(picked);
        lineup.setSubstitutes(pickedBench);
        lineup.setStarterOrderFromIds(starters);
        lineup.setBenchOrderFromIds(bench);
        lineup.setFormation(formation == null || formation.isBlank() ? "4-4-2" : formation.trim());
        lineup.setStyle(style == null ? "" : style.trim());
        return lineups.save(lineup);
    }

    /**
     * The XI to actually field: this fixture's own lineup first, the club's template second.
     *
     * <p><b>This is the decision, and it lives here rather than inside the simulation</b> so it can be
     * tested directly. A test that copied these two lookups would have proved a copy: mutating the
     * production reader left all ten tests green, because none of them called it.
     *
     * @param fixture may be null — an exhibition has none — in which case the template stands
     */
    @Transactional(readOnly = true)
    public Lineup resolve(Long teamId, MatchFixture fixture) {
        if (teamId == null) {
            return null;
        }
        if (fixture != null && fixture.getPlayedMatch() != null && fixture.getPlayedMatch().getId() != null) {
            Lineup perMatch = lineups.findByTeamIdAndMatchId(teamId, fixture.getPlayedMatch().getId())
                    .orElse(null);
            if (perMatch != null) {
                return perMatch;
            }
        }
        return lineups.findFirstByTeamIdAndMatchIsNullOrderByIdDesc(teamId).orElse(null);
    }

    /** The club's XI for this fixture, or empty when they have not picked one. */
    @Transactional(readOnly = true)
    public Optional<Lineup> forFixture(Long teamId, Long fixtureId) {
        Long matchId = matchIdOf(requireFixture(fixtureId));
        return matchId == null ? Optional.empty() : lineups.findByTeamIdAndMatchId(teamId, matchId);
    }

    /**
     * Whether the XI can actually be fielded, said in the manager's words.
     *
     * <p><b>Advisory, never blocking.</b> The goalkeeper rule is the one the owner specified: a keeper may
     * be substituted, but only by another keeper, and {@code SubstitutionService.pickReplacement} already
     * enforces it by matching GK status. Telling the manager here means they find out while they are still
     * looking at the screen, rather than at minute 60 from a void reason they never see.
     *
     * @return ordered warnings; empty means the XI is clean
     */
    @Transactional(readOnly = true)
    public List<String> warningsFor(Long teamId, Long fixtureId,
                                    List<Long> starterIds, List<Long> benchIds) {
        List<String> warnings = new ArrayList<>();
        List<Player> starters = resolvePlayers(teamId, distinct(starterIds));
        List<Player> bench = resolvePlayers(teamId, distinct(benchIds));

        for (Player player : starters) {
            if (player.isInjured()) {
                warnings.add(displayName(player) + " is injured.");
            }
            // Restored once suspensions existed (owner, 2026-10-10). A red card bars the next official
            // match of the club and the yellow accumulation bars this competition, and both are said here
            // so the manager finds out on the screen he is already looking at rather than from a void
            // reason after the whistle.
            String suspension = suspensionOf(player, fixtureId).orElse(null);
            if (suspension != null) {
                warnings.add(displayName(player) + " is " + suspension + " and cannot play this fixture.");
            }
        }

        boolean keeperOnBench = bench.stream().anyMatch(MatchLineupService::isKeeper);
        long keepersStarting = starters.stream().filter(MatchLineupService::isKeeper).count();

        if (keepersStarting == 0) {
            warnings.add("No goalkeeper is in the starting eleven. The engine will put one in.");
        }
        if (keepersStarting > 1) {
            warnings.add("More than one goalkeeper is in the starting eleven; only one can goalkeeper.");
        }
        if (keeperOnBench && keepersStarting > 0) {
            // Not a warning about this XI — it is the thing the owner specified, and the manager needs to
            // be able to see that a keeper on the bench is the only way his keeper can ever be replaced.
            warnings.add("A goalkeeper is on the bench, so the starting goalkeeper can be substituted. "
                    + "He can only be replaced by another goalkeeper.");
        }
        if (!keeperOnBench && keepersStarting > 0) {
            warnings.add("With no goalkeeper on the bench, the starting goalkeeper cannot be substituted.");
        }
        return warnings;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private Long matchIdOf(MatchFixture fixture) {
        return fixture == null || fixture.getPlayedMatch() == null ? null : fixture.getPlayedMatch().getId();
    }

    private Match matchOrNull(Long matchId) {
        return matchId == null ? null : matches.findById(matchId).orElse(null);
    }

    private Team requireTeam(Long teamId) {
        return teamId == null ? null : teams.findById(teamId).orElse(null);
    }

    private MatchFixture requireFixture(Long fixtureId) {
        return fixtureId == null ? null : fixtures.findById(fixtureId).orElse(null);
    }

    private boolean playsIn(MatchFixture fixture, Long teamId) {
        return fixture != null
                && ((fixture.getHomeTeam() != null && teamId.equals(fixture.getHomeTeam().getId()))
                || (fixture.getAwayTeam() != null && teamId.equals(fixture.getAwayTeam().getId())));
    }

    /**
     * The chosen players, in the order chosen, filtered to the club.
     *
     * <p>The filter is not decoration. {@code getOrderedStartingPlayers()} does the same thing on read —
     * the P2-7 defect was join-table rows being returned regardless of club membership — and this is the
     * second reader of the same table, so it is checked here too rather than assumed.
     */
    private List<Player> resolvePlayers(Long teamId, List<Long> ids) {
        List<Player> resolved = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Player player = players.findById(id).orElse(null);
            if (player != null && player.getTeam() != null
                    && teamId.equals(player.getTeam().getId())) {
                resolved.add(player);
            }
        }
        return resolved;
    }

    private static List<Long> distinct(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    /** Why this player cannot play this fixture, if he cannot. Empty when he can. */
    @Transactional(readOnly = true)
    public java.util.Optional<String> suspensionOf(Player player, Long fixtureId) {
        if (player == null || player.getId() == null || fixtureId == null || discipline == null) {
            return java.util.Optional.empty();
        }
        MatchFixture fixture = fixtures.findById(fixtureId).orElse(null);
        Integer season = fixture == null || fixture.getSeasonYear() == null
                ? null : fixture.getSeasonYear();
        return discipline.suspensionFor(player.getId(), season, fixture);
    }

    /**
     * A goalkeeper, by the role the engine will treat him as.
     *
     * <p>{@code effectiveRole()} and not the position string, because a player with no explicit role gets
     * one derived from his position — asking the position directly would call some keepers outfielders.
     */
    static boolean isKeeper(Player player) {
        return player != null && player.effectiveRole() == PlayerRole.GOALKEEPER;
    }

    private static String displayName(Player player) {
        String name = player == null ? null : player.getName();
        return name == null || name.isBlank() ? "That player" : name;
    }
}