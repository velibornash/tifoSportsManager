package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.example.footballmanager.newLogic.util.InternationalClubCupDraw;
import org.example.footballmanager.newLogic.util.InternationalClubCups;
import org.example.footballmanager.newLogic.util.LeagueTableOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The three international club cups, read for the screen (owner, 2026-10-06) — P1-CUPS-4.
 *
 * <p>The World page listed Champions Cup, Masters Cup and Challenge Cup as three rows of text, and each
 * one said how many clubs had qualified. That was the only thing any screen could say about them, and it
 * stayed that way through five tiers of qualification being written, tested and correct.
 *
 * <p>The owner asked for a page each, linked from the World page, with <b>a tab per tier</b> and the
 * results and the table on it. That is these two endpoints.
 *
 * <h2>Queries, because this page runs fifteen times</h2>
 *
 * <p>Per cup and tier: <b>two</b> queries — every fixture for the season, and every table row for the
 * season. Country names are resolved once for the whole response rather than per row, which matters
 * because a Champions Cup tier has 48 clubs in 8 groups and a Masters Cup tier has 96 in 16. All fifteen
 * cups together is about thirty queries, and the World page's own budget is already under test in
 * {@code InternationalClubCupsQueryBudgetTest}.
 *
 * <h2>The group table is assembled, not stored</h2>
 *
 * <p>Group membership exists in exactly one place: the {@code groupCode} on a fixture. There is no table
 * of groups, which is deliberate — a group's members are fully determined by who appears in its five
 * matchdays, and a second record of it would be a second thing to fall out of step with the fixtures. So
 * this reads the group codes off the fixtures and then asks the cup's own
 * {@code SeasonCompetition} for the numbers.
 *
 * <p><b>Those numbers only exist because of P0-CUPS-1.</b> Until a cup group match wrote its table, every
 * row here was zero and the ranking fell through to team id.
 */
@RestController
@RequestMapping("/club-cups")
public class ClubCupController {

    /** The three cups, by the key the URL uses. The display name is the owner's, not a URL. */
    private static final Map<String, String> CUPS_BY_KEY = Map.of(
            "champions", InternationalClubCups.CHAMPIONS,
            "masters", InternationalClubCups.MASTERS,
            "challenge", InternationalClubCups.CHALLENGE);

    private static final List<String> CUP_KEYS = List.of("champions", "masters", "challenge");

    private final CompetitionRepository competitions;
    private final MatchFixtureRepository fixtures;
    private final CompetitionEntryRepository entries;
    private final SeasonCompetitionRepository seasonCompetitions;
    private final CountryRepository countries;
    private final SeasonService seasons;

    public ClubCupController(CompetitionRepository competitions,
                             MatchFixtureRepository fixtures,
                             CompetitionEntryRepository entries,
                             SeasonCompetitionRepository seasonCompetitions,
                             CountryRepository countries,
                             SeasonService seasons) {
        this.competitions = competitions;
        this.fixtures = fixtures;
        this.entries = entries;
        this.seasonCompetitions = seasonCompetitions;
        this.countries = countries;
        this.seasons = seasons;
    }

    /**
     * The three cups and every tier of each — what the World page's links are built from.
     *
     * <p><b>Read-only, and it does not create anything.</b> A GET that inserts is wrong twice over: it
     * breaks under a read-only transaction, and it makes the page work only if the page is the thing that
     * happens to run first.
     */
    @GetMapping
    public Map<String, Object> index() {
        int season = seasons.getActiveSeasonYear();
        int qualifying = qualifyingSeason(season);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seasonYear", season);
        out.put("qualifyingSeasonYear", qualifying);

        List<Map<String, Object>> cups = new ArrayList<>();
        for (String key : CUP_KEYS) {
            String name = CUPS_BY_KEY.get(key);
            Map<String, Object> cup = new LinkedHashMap<>();
            cup.put("key", key);
            cup.put("name", name);
            List<Map<String, Object>> tiers = new ArrayList<>();
            for (int tier = 1; tier <= 5; tier++) {
                tiers.add(tierSummary(name, tier, season, qualifying));
            }
            cup.put("tiers", tiers);
            cups.add(cup);
        }
        out.put("cups", cups);
        return out;
    }

    /**
     * One cup, one tier: the group tables, the results, and the bracket.
     *
     * @param key    {@code champions}, {@code masters} or {@code challenge}
     * @param tier   1 to 5
     */
    @GetMapping("/{key}")
    public Map<String, Object> cup(@PathVariable String key,
                                   @RequestParam(value = "tier", defaultValue = "1") int tier) {
        String name = CUPS_BY_KEY.get(key);
        if (name == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No such cup: " + key + ". These are " + CUP_KEYS + ".");
        }
        if (tier < 1 || tier > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tier " + tier + " does not exist. These cups run in five tiers, 1 to 5.");
        }

        int season = seasons.getActiveSeasonYear();
        Competition competition = theCompetition(name, tier);
        if (competition == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    name + " tier " + tier + " has no competition row.");
        }

        List<MatchFixture> all = fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                competition.getId(), season);

        // Two queries for the whole tier: every fixture, and every table row.
        Map<Long, CompetitionEntry> entryByTeam = entriesFor(competition, season);

        // Country names once. A Champions Cup tier has 48 clubs and a Masters Cup tier 96, and resolving
        // a country per row is the N+1 this codebase has removed three times already.
        Map<Long, String> countryByTeam = countryNamesFor(all);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cup", name);
        out.put("key", key);
        out.put("tier", tier);
        out.put("competitionId", competition.getId());
        out.put("seasonYear", season);
        out.put("qualifyPerGroup", InternationalClubCupDraw.qualifyPerGroupFor(name));

        List<MatchFixture> groupFixtures = all.stream()
                .filter(f -> f.getRoundNumber() != null
                        && f.getRoundNumber() <= InternationalClubCupDraw.GROUP_MATCHDAYS)
                .toList();
        List<MatchFixture> knockoutFixtures = all.stream()
                .filter(f -> f.getRoundNumber() != null
                        && f.getRoundNumber() > InternationalClubCupDraw.GROUP_MATCHDAYS)
                .toList();

        out.put("groups", groupsOf(groupFixtures, entryByTeam, countryByTeam));
        out.put("knockoutRounds", knockoutRoundsOf(knockoutFixtures, countryByTeam));
        out.put("totalFixtures", all.size());
        out.put("playedFixtures", all.stream().filter(MatchFixture::isPlayed).count());
        return out;
    }

    // ------------------------------------------------------------------ pieces

    /** One tier's headline for the World page: how many clubs qualified and how far the draw has got. */
    private Map<String, Object> tierSummary(String cupName, int tier, int season, int qualifying) {
        Competition competition = theCompetition(cupName, tier);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tier", tier);
        out.put("competitionId", competition == null ? null : competition.getId());

        List<MatchFixture> all = competition == null ? List.of() : fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(competition.getId(), season);

        out.put("fixtures", all.size());
        out.put("played", all.stream().filter(MatchFixture::isPlayed).count());
        out.put("groups", all.stream()
                .filter(f -> f.getGroupCode() != null && !f.getGroupCode().isBlank())
                .map(MatchFixture::getGroupCode)
                .distinct().count());

        if (competition == null) {
            out.put("state", "missing");
            return out;
        }
        if (all.isEmpty()) {
            // Distinguish "no club has finished a season yet" from "not created", because they are
            // different facts and only one of them is a defect.
            out.put("state", season <= 1 ? "no-finished-season" : "not-drawn");
            return out;
        }
        out.put("state", knockoutFixturesOf(all) ? "knockouts" : "group-stage");
        return out;
    }

    /**
     * Every group of a tier, its table, and its fixtures.
     *
     * <p>Groups in letter order, and each table in {@link LeagueTableOrder} — the one comparator in the
     * codebase. The position is the index in that order, because a stored position column is not written
     * during a season.
     */
    private List<Map<String, Object>> groupsOf(List<MatchFixture> groupFixtures,
                                               Map<Long, CompetitionEntry> entryByTeam,
                                               Map<Long, String> countryByTeam) {
        Map<String, List<MatchFixture>> byGroup = new TreeMap<>();
        for (MatchFixture fixture : groupFixtures) {
            String code = fixture.getGroupCode();
            if (code == null || code.isBlank()) {
                continue;
            }
            byGroup.computeIfAbsent(code, key -> new ArrayList<>()).add(fixture);
        }

        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, List<MatchFixture>> group : byGroup.entrySet()) {
            // Members come from the fixtures, in first-seen order, so the table is the set of clubs that
            // actually appear in this group's matchdays.
            Map<Long, Team> members = new LinkedHashMap<>();
            for (MatchFixture fixture : group.getValue()) {
                if (fixture.getHomeTeam() != null) {
                    members.putIfAbsent(fixture.getHomeTeam().getId(), fixture.getHomeTeam());
                }
                if (fixture.getAwayTeam() != null) {
                    members.putIfAbsent(fixture.getAwayTeam().getId(), fixture.getAwayTeam());
                }
            }

            List<CompetitionEntry> rows = new ArrayList<>();
            for (Team team : members.values()) {
                CompetitionEntry row = entryByTeam.get(team.getId());
                rows.add(row != null ? row : anEmptyRowFor(team));
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("code", group.getKey());
            out.put("table", tableOf(rows, countryByTeam));
            out.put("fixtures", group.getValue().stream().map(f -> tieOf(f, countryByTeam)).toList());
            groups.add(out);
        }
        return groups;
    }

    /**
     * The knockout rounds, in round order.
     *
     * <p>Round 10 is the final and round 9 the third place, so they are reported in that order rather than
     * in week order — a cup final is the last tie of the tournament and the page should say so.
     */
    private List<Map<String, Object>> knockoutRoundsOf(List<MatchFixture> knockoutFixtures,
                                                       Map<Long, String> countryByTeam) {
        Map<Integer, List<MatchFixture>> byRound = new TreeMap<>();
        for (MatchFixture fixture : knockoutFixtures) {
            if (fixture.getRoundNumber() != null) {
                byRound.computeIfAbsent(fixture.getRoundNumber(), key -> new ArrayList<>()).add(fixture);
            }
        }

        List<Map<String, Object>> rounds = new ArrayList<>();
        byRound.forEach((round, ties) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("round", round);
            out.put("name", roundName(round));
            out.put("week", ties.isEmpty() ? null : ties.get(0).getWeekNumber());
            out.put("fixtures", ties.stream().map(f -> tieOf(f, countryByTeam)).toList());
            rounds.add(out);
        });
        return rounds;
    }

    /** A table in league-table order, with the position from the sort and not from a stored column. */
    private List<Map<String, Object>> tableOf(List<CompetitionEntry> rows, Map<Long, String> countryByTeam) {
        List<Map<String, Object>> table = new ArrayList<>();
        List<CompetitionEntry> sorted = LeagueTableOrder.sort(rows);
        for (int index = 0; index < sorted.size(); index++) {
            CompetitionEntry row = sorted.get(index);
            Team team = row.getTeam();
            int scored = value(row.getGoalsScored());
            int conceded = value(row.getGoalsConceded());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("position", index + 1);
            out.put("teamId", team == null ? null : team.getId());
            out.put("team", team == null ? null : team.getName());
            out.put("country", team == null ? null : countryByTeam.get(team.getId()));
            out.put("played", value(row.getWins()) + value(row.getDraws()) + value(row.getLosses()));
            out.put("wins", value(row.getWins()));
            out.put("draws", value(row.getDraws()));
            out.put("losses", value(row.getLosses()));
            out.put("goalsScored", scored);
            out.put("goalsConceded", conceded);
            out.put("goalDifference", scored - conceded);
            out.put("points", value(row.getPoints()));
            table.add(out);
        }
        return table;
    }

    private Map<String, Object> tieOf(MatchFixture fixture, Map<Long, String> countryByTeam) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", fixture.getId());
        out.put("round", fixture.getRoundNumber());
        out.put("week", fixture.getWeekNumber());
        out.put("group", fixture.getGroupCode());
        out.put("homeTeamId", fixture.getHomeTeam() == null ? null : fixture.getHomeTeam().getId());
        out.put("home", fixture.getHomeTeam() == null ? null : fixture.getHomeTeam().getName());
        out.put("homeCountry", fixture.getHomeTeam() == null ? null : countryByTeam.get(fixture.getHomeTeam().getId()));
        out.put("awayTeamId", fixture.getAwayTeam() == null ? null : fixture.getAwayTeam().getId());
        out.put("away", fixture.getAwayTeam() == null ? null : fixture.getAwayTeam().getName());
        out.put("awayCountry", fixture.getAwayTeam() == null ? null : countryByTeam.get(fixture.getAwayTeam().getId()));
        out.put("played", fixture.isPlayed());
        // The Match this fixture became, and its score. Both needed for a tie to open the shared match
        // view: every post-match endpoint is keyed by MATCH id, so without this a played tie would open
        // with lineups, stats, goals and the report all empty. The score is on the Match, not the
        // fixture - `MatchFixture` carries no goals of its own.
        org.example.footballmanager.newLogic.model.Match played = fixture.getPlayedMatch();
        out.put("matchId", played == null ? null : played.getId());
        out.put("homeGoals", played == null ? null : played.getHomeGoals());
        out.put("awayGoals", played == null ? null : played.getAwayGoals());
        return out;
    }

    private boolean knockoutFixturesOf(List<MatchFixture> all) {
        return all.stream().anyMatch(f -> f.getRoundNumber() != null
                && f.getRoundNumber() > InternationalClubCupDraw.GROUP_MATCHDAYS);
    }

    /** Every table row for a cup's season, keyed by club. One query for the whole tier. */
    private Map<Long, CompetitionEntry> entriesFor(Competition cup, int season) {
        Map<Long, CompetitionEntry> byTeam = new LinkedHashMap<>();
        SeasonCompetition sc = seasonCompetitions.findByCompetitionAndSeasonYear(cup, season).orElse(null);
        if (sc == null) {
            return byTeam;
        }
        for (CompetitionEntry entry : entries.findBySeasonCompetition(sc)) {
            if (entry.getTeam() != null && entry.getTeam().getId() != null) {
                byTeam.put(entry.getTeam().getId(), entry);
            }
        }
        return byTeam;
    }

    /** Every club that appears in these fixtures, with its country's name. One query for the whole tier. */
    private Map<Long, String> countryNamesFor(List<MatchFixture> all) {
        Map<Long, String> byTeam = new LinkedHashMap<>();
        List<Long> countryIds = new ArrayList<>();
        Map<Long, Long> teamToCountry = new LinkedHashMap<>();

        for (MatchFixture fixture : all) {
            collectTeam(fixture.getHomeTeam(), teamToCountry, countryIds);
            collectTeam(fixture.getAwayTeam(), teamToCountry, countryIds);
        }
        if (countryIds.isEmpty()) {
            return byTeam;
        }
        Map<Long, String> countryNameById = new LinkedHashMap<>();
        for (Country country : countries.findAllById(countryIds.stream().distinct().toList())) {
            countryNameById.put(country.getId(), country.getName());
        }
        teamToCountry.forEach((teamId, countryId) ->
                byTeam.put(teamId, countryNameById.get(countryId)));
        return byTeam;
    }

    private void collectTeam(Team team, Map<Long, Long> teamToCountry, List<Long> countryIds) {
        if (team == null || team.getId() == null || team.getCountry() == null || team.getCountry().getId() == null) {
            return;
        }
        teamToCountry.putIfAbsent(team.getId(), team.getCountry().getId());
        countryIds.add(team.getCountry().getId());
    }

    /** A club in the fixtures with no table row yet — before its first match, or before P0-CUPS-1. */
    private CompetitionEntry anEmptyRowFor(Team team) {
        CompetitionEntry row = new CompetitionEntry();
        row.setTeam(team);
        row.setPoints(0);
        row.setWins(0);
        row.setDraws(0);
        row.setLosses(0);
        row.setGoalsScored(0);
        row.setGoalsConceded(0);
        return row;
    }

    /**
     * The competition row for a cup at a tier.
     *
     * <p><b>Scoped and tiered.</b> Sixteen {@code CUP} competitions exist — one domestic cup and fifteen
     * continental — so asking for "a CUP" answers with whichever row came first, which is what
     * P0-CUPS-6 is about. {@code scope = INTERNATIONAL} is the only column that says the entrants come
     * from several countries.
     */
    private Competition theCompetition(String cupName, int tier) {
        List<Competition> candidates = new ArrayList<>(
                competitions.findByTypeAndTier(CompetitionType.CUP, tier));
        return candidates.stream()
                .filter(c -> c.getScope() == CompetitionScope.INTERNATIONAL)
                .filter(c -> cupName.equals(c.getName()) || ("Tier " + tier + " " + cupName).equals(c.getName()))
                .min(Comparator.comparing(Competition::getId))
                .orElse(null);
    }

    private static String roundName(int round) {
        return switch (round) {
            case InternationalClubCupDraw.ROUND_LAST_SIXTEEN -> "Round of 16";
            case InternationalClubCupDraw.ROUND_QUARTER_FINAL -> "Quarter-finals";
            case InternationalClubCupDraw.ROUND_SEMI_FINAL -> "Semi-finals";
            case InternationalClubCupDraw.ROUND_THIRD_PLACE -> "Third place";
            case InternationalClubCupDraw.ROUND_FINAL -> "Final";
            default -> "Round " + round;
        };
    }

    /** The season whose finished tables decide this season's entry. Never below 1 — there is no season 0. */
    private int qualifyingSeason(int season) {
        return Math.max(1, season - 1);
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }
}