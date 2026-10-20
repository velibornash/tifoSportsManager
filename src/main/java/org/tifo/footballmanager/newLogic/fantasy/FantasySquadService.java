package org.tifo.footballmanager.newLogic.fantasy;

import lombok.RequiredArgsConstructor;
import org.tifo.commonmanager.model.User;
import org.tifo.commonmanager.repository.UserRepository;
import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.FantasyPick;
import org.tifo.footballmanager.newLogic.model.FantasyTeam;
import org.tifo.footballmanager.newLogic.model.Position;
import org.tifo.footballmanager.newLogic.repository.CountryRepository;
import org.tifo.footballmanager.newLogic.repository.FantasyPickRepository;
import org.tifo.footballmanager.newLogic.repository.FantasyTeamRepository;
import org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.tifo.footballmanager.newLogic.service.SeasonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Joining a fantasy league, saving a squad, and what changing it costs (owner, 2026-10-17).
 *
 * <p><b>The server decides.</b> {@link FantasySquadValidator} is the gate; the picker shows the same rules
 * as a manager picks, and that is a courtesy rather than the check. A submission that arrives here is
 * validated against the pool the server just built, so a client cannot propose its own prices, its own
 * tiers or its own idea of how many players are in a squad — nor its own idea of what its changes cost.
 *
 * <p><b>A save replaces the fifteen.</b> Not a diff: a squad is small, and working out what changed is the
 * sort of code that is wrong once and then wrong quietly. The one thing a diff would protect is the
 * purchase price of a player who stayed, and that is carried forward explicitly.
 */
@Service
@RequiredArgsConstructor
public class FantasySquadService {

    private final FantasyPoolService poolService;
    private final FantasyTeamRepository fantasyTeams;
    private final FantasyPickRepository fantasyPicks;
    private final UserRepository users;
    private final CountryRepository countries;
    private final org.tifo.footballmanager.newLogic.repository.PlayerRepository players;
    private final MatchFixtureRepository fixtures;
    private final SeasonService seasonService;

    /** The country's priced pool, which is also how the page learns whether the country is eligible. */
    @Transactional(readOnly = true)
    public FantasyPool pool(Long countryId) {
        return poolService.poolFor(countryId, seasonService.getActiveSeasonYear());
    }

    /**
     * Whether the current gameweek can still be picked for (owner, 2026-10-18).
     *
     * <p>The page needs this <b>before</b> the manager picks, not when he presses Save. A manager who is
     * allowed to build a whole squad and only then told the gameweek is locked has been wasted, and the
     * refusal arrives as an error rather than as a fact about the calendar.
     */
    @Transactional(readOnly = true)
    public boolean weekOpen(Long countryId) {
        int seasonYear = seasonService.getActiveSeasonYear();
        return fixtures.countStartedInWeek(countryId, seasonYear, seasonService.getCurrentWeek()) == 0;
    }

    /** Why a squad cannot be saved right now, or null when it can. */
    public String lockReason(boolean weekOpen) {
        return weekOpen ? null
                : "This gameweek is locked — its matches have been played. You can still look at your "
                        + "squad, and you can change it again when the next gameweek opens.";
    }

    /**
     * Saves a squad, or reports everything wrong with it.
     *
     * <p>Returns rather than throws, because "your captain is on the bench and you are 4.5 over budget" is
     * a normal thing for a manager to be told, not an error. The caller turns a non-empty violation list
     * into a 400 and a legal squad into a 200.
     */
    @Transactional
    public SaveResult save(Long userId, Long countryId, List<FantasySquadValidator.Selection> selections) {
        FantasyPool pool = poolService.poolFor(countryId, seasonService.getActiveSeasonYear());
        if (!pool.eligible()) {
            return SaveResult.refused(pool.ineligibleReason());
        }

        List<FantasySquadValidator.Violation> violations =
                FantasySquadValidator.validate(selections, pool.players());
        if (!violations.isEmpty()) {
            return new SaveResult(violations, null);
        }

        int seasonYear = seasonService.getActiveSeasonYear();
        int week = seasonService.getCurrentWeek();

        FantasyTeam team = fantasyTeams
                .findByUserIdAndCountryIdAndSeasonYear(userId, countryId, seasonYear)
                .orElse(null);

        // **The deadline.** A squad is editable while the gameweek is open and locked once it is not.
        // "Locked" means the week's matches are played or already computed an hour before kickoff — the
        // prepare job has settled the eleven by then, so a squad that could still change would be a squad
        // whose points came from a team nobody picked.
        boolean weekOpen = fixtures.countStartedInWeek(countryId, seasonYear, week) == 0;
        if (team != null && !weekOpen) {
            return SaveResult.refused("This gameweek is locked — its matches have been played. "
                    + "You can change your squad again when the next gameweek opens.");
        }

        Map<Long, FantasyPoolPlayer> byId = new HashMap<>();
        for (FantasyPoolPlayer player : pool.players()) {
            byId.put(player.playerId(), player);
        }

        // What the manager already has, read before anything is written: the purchase prices to carry
        // forward, and the squad the gameweek's changes are measured against.
        Map<Long, Double> alreadyPaid = new HashMap<>();
        Set<Long> stored = new LinkedHashSet<>();
        if (team != null) {
            for (FantasyPick existing : fantasyPicks.findByFantasyTeamId(team.getId())) {
                alreadyPaid.put(existing.getPlayer().getId(), existing.getPurchasePrice());
                stored.add(existing.getPlayer().getId());
            }
        }

        boolean joining = team == null;
        if (joining) {
            User joiningUser = users.findById(userId).orElse(null);
            if (joiningUser == null) {
                return SaveResult.refused("That manager no longer exists.");
            }
            Country joiningCountry = countries.findById(countryId).orElse(null);
            if (joiningCountry == null) {
                return SaveResult.refused("That country no longer exists.");
            }
            team = new FantasyTeam();
            team.setUser(joiningUser);
            team.setCountry(joiningCountry);
            team.setSeasonYear(seasonYear);
            // A manager who arrives into a locked week starts scoring next week. He keeps his squad; he
            // does not inherit the points of football he did not pick for.
            team.setScoresFromWeek(weekOpen ? week : week + 1);
        }

        // **The gameweek's baseline, and the cost of departing from it.** Captured the first time the squad
        // is touched in a gameweek and kept until the week rolls over, so a change is counted against the
        // squad the manager started the week with and an undo costs nothing. A brand new entry is its own
        // baseline: joining is not fourteen transfers.
        boolean newGameweek = team.getBaselineWeek() == null || team.getBaselineWeek() != week;
        Set<Long> baseline;
        if (joining) {
            baseline = idsOf(selections);
        } else if (newGameweek) {
            baseline = new LinkedHashSet<>(stored);
        } else {
            baseline = parseIds(team.getBaselinePlayers());
        }
        // Written only when the gameweek's baseline is first captured; the change count below reads it back
        // through the same helper the page does, so there is one answer and not two.
        if (newGameweek) {
            team.setBaselineWeek(week);
            team.setBaselinePlayers(writeIds(baseline));
        }

        // One implementation of "how many changes", shared with the read. Counting them here in a second
        // loop over the selections looked equivalent and was not: the two could disagree, and the number a
        // manager is shown would then not be the number he was charged.
        int changes = changesAgainstBaseline(team, idsOf(selections), week);
        team.setTransferCost(FantasyTransferRules.cost(changes));
        team.setTransferCostWeek(week);
        team = fantasyTeams.save(team);

        fantasyPicks.deleteByFantasyTeamId(team.getId());
        fantasyPicks.flush();

        List<FantasyPick> written = new ArrayList<>(selections.size());
        Integer nextBenchOrder = 1;
        for (FantasySquadValidator.Selection selection : selections) {
            FantasyPoolPlayer player = byId.get(selection.playerId());
            FantasyPick pick = new FantasyPick();
            pick.setFantasyTeam(team);
            pick.setPlayer(players.getReferenceById(player.playerId()));
            pick.setStarter(selection.starter());
            pick.setCaptain(selection.captain());
            pick.setPurchasePrice(alreadyPaid.getOrDefault(player.playerId(), player.price()));
            if (selection.starter()) {
                pick.setBenchOrder(null);
            } else if (FantasyRules.fantasyPositionOf(player.position()) == Position.GK) {
                // The reserve goalkeeper is always first on the bench, because that is what he is.
                pick.setBenchOrder(0);
            } else {
                pick.setBenchOrder(nextBenchOrder++);
            }
            written.add(pick);
        }
        fantasyPicks.saveAll(written);

        return new SaveResult(List.of(), readSquad(team, countryId, seasonYear).orElse(null));
    }

    /** The manager's squad for the active season, or empty when he has not joined. */
    @Transactional(readOnly = true)
    public Optional<FantasySquad> squad(Long userId, Long countryId) {
        int seasonYear = seasonService.getActiveSeasonYear();
        FantasyTeam team = fantasyTeams
                .findByUserIdAndCountryIdAndSeasonYear(userId, countryId, seasonYear)
                .orElse(null);
        if (team == null) {
            return Optional.empty();
        }
        return readSquad(team, countryId, seasonYear);
    }

    /**
     * Reads a stored squad back and re-prices it against the pool.
     *
     * <p>A player who has left the pool — his club dropped out of the pyramid — is dropped from the read
     * rather than crashing it. The squad then reads as short, which is the honest state: it is short, and
     * the manager has to fix it.
     */
    private Optional<FantasySquad> readSquad(FantasyTeam team, Long countryId, int seasonYear) {
        List<FantasyPick> stored = fantasyPicks.findByFantasyTeamId(team.getId());
        int week = seasonService.getCurrentWeek();
        Set<Long> current = new LinkedHashSet<>();
        stored.forEach(pick -> current.add(pick.getPlayer().getId()));
        int changes = changesAgainstBaseline(team, current, week);
        int cost = currentCost(team, week);
        if (stored.isEmpty()) {
            return Optional.of(new FantasySquad(team.getId(), countryId, seasonYear, List.of(),
                    changes, cost));
        }

        Map<Long, FantasyPoolPlayer> byId = new HashMap<>();
        for (FantasyPoolPlayer player : poolService.poolFor(countryId, seasonYear).players()) {
            byId.put(player.playerId(), player);
        }

        List<FantasySquad.FantasySquadPick> picks = new ArrayList<>(stored.size());
        for (FantasyPick pick : stored) {
            FantasyPoolPlayer priced = byId.get(pick.getPlayer().getId());
            if (priced == null) {
                continue;
            }
            picks.add(new FantasySquad.FantasySquadPick(priced, pick.isStarter(), pick.isCaptain(),
                    pick.getBenchOrder(), pick.getPurchasePrice()));
        }
        // Starters first, then the bench in its own order, so the read is the shape the pitch shows.
        picks.sort((a, b) -> {
            if (a.starter() != b.starter()) {
                return a.starter() ? -1 : 1;
            }
            int ao = a.benchOrder() == null ? 0 : a.benchOrder();
            int bo = b.benchOrder() == null ? 0 : b.benchOrder();
            return Integer.compare(ao, bo);
        });
        return Optional.of(new FantasySquad(team.getId(), countryId, seasonYear, picks, changes, cost));
    }

    /**
     * How many of the stored players were not in the squad the gameweek started with.
     *
     * <p>A baseline from an earlier gameweek counts for nothing: it is replaced the moment the manager next
     * saves, and until then there are no changes to charge for. Reading it as though it still applied would
     * show him a bill for a week that has ended.
     */
    private int changesAgainstBaseline(FantasyTeam team, Collection<Long> current, int week) {
        if (team.getBaselineWeek() == null || team.getBaselineWeek() != week) {
            return 0;
        }
        Set<Long> baseline = parseIds(team.getBaselinePlayers());
        int changes = 0;
        for (Long playerId : current) {
            if (!baseline.contains(playerId)) {
                changes++;
            }
        }
        return changes;
    }

    /**
     * What this gameweek's changes cost, read from the row rather than recomputed.
     *
     * <p>Recomputing it from the change count would give the same answer today and a different one the
     * moment either rule changed — and the number the page shows must be the number that will be deducted,
     * not a second opinion about it.
     */
    private int currentCost(FantasyTeam team, int week) {
        return team.getTransferCostWeek() != null && team.getTransferCostWeek() == week
                ? team.getTransferCost() : 0;
    }

    private static Set<Long> idsOf(List<FantasySquadValidator.Selection> selections) {
        Set<Long> ids = new LinkedHashSet<>();
        selections.forEach(selection -> ids.add(selection.playerId()));
        return ids;
    }

    private static Set<Long> parseIds(String joined) {
        Set<Long> ids = new LinkedHashSet<>();
        if (joined == null || joined.isBlank()) {
            return ids;
        }
        for (String part : joined.split(",")) {
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                // A malformed entry is dropped rather than throwing: the worst it can do is make a change
                // look like a new one, and losing a manager's whole save to a bad character is worse.
            }
        }
        return ids;
    }

    private static String writeIds(Set<Long> ids) {
        StringBuilder out = new StringBuilder();
        for (Long id : ids) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(id);
        }
        return out.toString();
    }

    /**
     * The result of a save: either what is wrong, or the squad as saved.
     *
     * <p>A refusal that is not about the squad — a country with four tiers, a manager who has been deleted
     * — comes back through the same channel, as a violation, so the caller has one thing to handle.
     */
    public record SaveResult(List<FantasySquadValidator.Violation> violations, FantasySquad squad) {

        public boolean ok() {
            return violations.isEmpty() && squad != null;
        }

        static SaveResult refused(String message) {
            return new SaveResult(List.of(new FantasySquadValidator.Violation("not_available", message)), null);
        }
    }
}
