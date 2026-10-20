package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.NationalStage;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.NationalTournamentSchedule;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.service.NationalGroupTable;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.example.footballmanager.newLogic.util.NationalTeamCompetitions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;

/**
 * The national-team competitions' own pages: groups, standings, results and the bracket.
 *
 * <p><b>One endpoint per competition, not one per group.</b> A group is part of the competition's
 * payload rather than a resource of its own, because a group's membership is fully determined by the
 * fixtures it drew — the same argument {@code InternationalClubCupDraw} makes — and a second URL for it
 * would be a second thing to fall out of step with them.
 *
 * <p>Everything is derived from the fixtures and the played matches, never stored alongside them. A
 * table that is written as matches are played and also readable from scratch is one table; this is
 * read from scratch, so it cannot drift from the results it claims to describe.
 *
 * <p><b>A competition that has not been created is a 404-shaped empty answer, not an error.</b> The
 * World page lists all four competitions whether or not they exist, and a link that returned a stack
 * trace for a competition nobody has drawn yet would be worse than one that says so.
 */
@RestController
@RequestMapping("/api/national-tournaments")
public class NationalTournamentController {

    private final NationalTeamCompetitions catalogue;
    private final NationalGroupTable groupTable;
    private final MatchFixtureRepository fixtures;
    private final CountryRepository countries;
    private final SeasonService seasons;

    public NationalTournamentController(NationalTeamCompetitions catalogue,
                                        NationalGroupTable groupTable,
                                        MatchFixtureRepository fixtures,
                                        CountryRepository countries,
                                        SeasonService seasons) {
        this.catalogue = catalogue;
        this.groupTable = groupTable;
        this.fixtures = fixtures;
        this.countries = countries;
        this.seasons = seasons;
    }

    /**
     * Every national-team competition, whether or not it has been created.
     *
     * <p>What the World page's four tiles are driven from, so a tile is a link exactly when the
     * competition behind it exists and says so when it does not.
     */
    @GetMapping
    public Map<String, Object> index() {
        int seasonYear = seasons.getActiveSeasonYear();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (NationalTeamLevel level : NationalTeamLevel.values()) {
            for (NationalStage stage : List.of(NationalStage.QUALIFYING, NationalStage.WORLD_CUP)) {
                Competition competition = catalogue.find(level, stage).orElse(null);

                // **Any season, not only the active one.** This counted fixtures for `seasonYear` only,
                // so a competition drawn in an earlier season reported "not drawn" on the World page
                // while it plainly was: the qualifiers here hold 120 fixtures each in season 1, the
                // active season is 2, and all four national competitions read "Not drawn yet" - directly
                // above three club cups that correctly read "238 qualified" (owner, 2026-10-10).
                //
                // The tile answers "is there a competition to click into", which does not change with the
                // calendar. What the active season decides is which one is coming next, and the season
                // the fixtures belong to is reported alongside so the number is explainable rather than
                // merely true.
                List<MatchFixture> allFixtures = competition == null ? List.of()
                        : fixtures.findByCompetitionIdOrdered(competition.getId());
                int drawn = allFixtures.size();
                Integer drawnSeason = drawn == 0 ? null : allFixtures.get(0).getSeasonYear();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("level", level == NationalTeamLevel.U21 ? "u21" : "senior");
                row.put("stage", stage.name());
                row.put("name", NationalTeamCompetitions.nameFor(level, stage));
                // **Drawn, not "a row exists".** `ensureAll` creates all four rows the first time any
                // one of them is drawn, so "the row is there" became true for all four and the World
                // page offered four competitions to click into, three of which were empty. A tile is a
                // link exactly when there is something behind it.
                row.put("exists", drawn > 0);
                row.put("competitionId", competition == null ? null : competition.getId());
                // The season those fixtures belong to, so the count is explainable rather than
                // merely true. A tile that says "120 fixtures" with no season invites the reader
                // to assume it is this season's, which is exactly the confusion this fix removes.
                row.put("drawnSeason", drawnSeason);
                if (competition != null) {
                    row.put("week", stage == NationalStage.QUALIFYING
                            ? NationalTournamentSchedule.QUALIFYING_WEEK
                            : NationalTournamentSchedule.TOURNAMENT_WEEK);
                    row.put("fixtures", drawn);
                }
                rows.add(row);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("season", seasonYear);
        body.put("competitions", rows);
        return body;
    }

    /**
     * One competition in full: its groups with their standings, and its rounds with their results.
     *
     * <p>A qualifying competition has groups and no knockout rounds; a tournament has rounds and no
     * groups. Both are read from the fixtures, so the shape of the answer follows the shape of what
     * was drawn rather than a second description of it.
     */
    @GetMapping("/{level}/{stage}")
    public ResponseEntity<Map<String, Object>> competition(
            @PathVariable String level,
            @PathVariable String stage,
            @RequestParam(required = false) Integer season) {

        NationalTeamLevel resolvedLevel = parseLevel(level);
        NationalStage resolvedStage = parseStage(stage);
        if (resolvedLevel == null || resolvedStage == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown level or stage",
                    "detail", "level is 'senior' or 'u21'; stage is 'QUALIFYING' or 'WORLD_CUP'."));
        }

        int seasonYear = season != null ? season : seasons.getActiveSeasonYear();
        Competition competition = catalogue.find(resolvedLevel, resolvedStage).orElse(null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("level", resolvedLevel == NationalTeamLevel.U21 ? "u21" : "senior");
        body.put("stage", resolvedStage.name());
        body.put("season", seasonYear);
        body.put("name", NationalTeamCompetitions.nameFor(resolvedLevel, resolvedStage));
        body.put("week", resolvedStage == NationalStage.QUALIFYING
                ? NationalTournamentSchedule.QUALIFYING_WEEK : NationalTournamentSchedule.TOURNAMENT_WEEK);

        if (competition == null) {
            body.put("exists", false);
            body.put("note", "This competition has not been drawn yet.");
            body.put("groups", List.of());
            body.put("rounds", List.of());
            return ResponseEntity.ok(body);
        }

        List<Map<String, Object>> groups = groupsOf(competition, seasonYear, resolvedStage);
        List<Map<String, Object>> rounds = roundsOf(competition, seasonYear);
        // Same rule as the index: created is not drawn. The row existing only means ensureAll has run.
        boolean drawn = !groups.isEmpty() || !rounds.isEmpty();
        body.put("exists", drawn);
        body.put("competitionId", competition.getId());
        body.put("groups", groups);
        body.put("rounds", rounds);
        if (!drawn) {
            // **Which seasons DO have a draw, said out loud.** The page opens on the active season and
            // this competition's 120 qualifying fixtures are in season 1, so it reported "nothing has
            // been drawn into it yet" about a competition holding 120 of them (owner, 2026-10-10). An
            // empty view with no reason is the worst of the three: not wrong enough to be a bug report
            // and not right enough to be believed.
            List<Integer> seasonsWithFixtures = competition == null ? List.of()
                    : fixtures.findByCompetitionIdOrdered(competition.getId()).stream()
                            .map(MatchFixture::getSeasonYear)
                            .filter(Objects::nonNull)
                            .distinct()
                            .sorted()
                            .toList();

            body.put("seasonsWithFixtures", seasonsWithFixtures);
            body.put("note", seasonsWithFixtures.isEmpty()
                    ? "This competition has been created but nothing has been drawn into it yet."
                    : "Nothing is drawn into this competition for season " + seasonYear
                            + ", but it has been drawn for season " + seasonsWithFixtures.get(0) + ".");
        }
        return ResponseEntity.ok(body);
    }

    /**
     * The groups and their tables.
     *
     * <p>Eight rows for a qualifying competition with groups; nothing for a tournament, which has none.
     * A group with no results yet is still listed, on zero points — dropping it would make a drawn
     * tournament look undrawn.
     */
    private List<Map<String, Object>> groupsOf(Competition competition, int seasonYear, NationalStage stage) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (stage != NationalStage.QUALIFYING) {
            return out;
        }
        boolean anyGrouped = fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(competition.getId(), seasonYear)
                .stream().anyMatch(f -> f.getGroupCode() != null);
        if (!anyGrouped) {
            return out;
        }
        for (int g = 0; g < NationalTournamentSchedule.GROUPS; g++) {
            String code = NationalTournamentSchedule.groupCode(g);
            List<NationalGroupTable.Standing> standings = groupTable.standings(competition, seasonYear, code);
            if (standings.isEmpty()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("name", "Group " + code);
            List<Map<String, Object>> table = new ArrayList<>();
            int position = 0;
            for (NationalGroupTable.Standing standing : standings) {
                Map<String, Object> entry = new LinkedHashMap<>();
                position++;
                entry.put("position", position);
                entry.put("teamId", standing.team().getId());
                entry.put("teamName", standing.team().getName());
                entry.put("countryIso", standing.team().getCountry() == null
                        ? null : standing.team().getCountry().getIsoCode());
                entry.put("played", standing.played());
                entry.put("won", standing.wins());
                entry.put("drawn", standing.draws());
                entry.put("lost", standing.losses());
                entry.put("goalsScored", standing.goalsScored());
                entry.put("goalsConceded", standing.goalsConceded());
                entry.put("goalDifference", standing.goalDifference());
                entry.put("points", standing.points());
                entry.put("qualified", position <= NationalTournamentSchedule.QUALIFY_PER_GROUP);
                table.add(entry);
            }
            row.put("table", table);
            row.put("fixtures", fixturesOfGroup(competition, seasonYear, code));
            out.add(row);
        }
        return out;
    }

    /**
     * One group's qualifying ties, by matchday.
     *
     * <p><b>This was missing entirely, and it is the schedule.</b> {@link #roundsOf} skips every fixture
     * carrying a group code, which is correct for a knockout and meant that a qualifying competition
     * returned **no fixtures at all** — a drawn tournament with eight groups on screen and nowhere to
     * see who plays whom, or when. The owner asked where the matches were; they had never been sent.
     *
     * <p>Read from the same fixture rows as everything else here rather than re-derived, and shaped by
     * round number, which for qualifying <em>is</em> the matchday — one matchday a day on days 2 to 6.
     */
    private List<Map<String, Object>> fixturesOfGroup(Competition competition, int seasonYear, String groupCode) {
        Map<Integer, List<Map<String, Object>>> byRound = new java.util.TreeMap<>();
        for (MatchFixture fixture : fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(competition.getId(), seasonYear)) {
            if (fixture.getGroupCode() == null || !groupCode.equals(fixture.getGroupCode())) {
                continue;
            }
            byRound.computeIfAbsent(fixture.getRoundNumber(), round -> new ArrayList<>())
                    .add(tie(fixture));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        byRound.forEach((round, ties) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("round", round);
            row.put("day", NationalTournamentSchedule.qualifyingDay(round));
            row.put("fixtures", ties);
            out.add(row);
        });
        return out;
    }

    /**
     * The rounds and their ties.
     *
     * <p>Qualifying matchdays for a qualifying competition, knockout rounds for a tournament — the same
     * fixture rows read two ways, grouped by whatever the round number means in that competition. A
     * cup's round 1 and a qualifying matchday 1 are both round 1 and mean different things, which is
     * why the label comes from the competition's stage and not from the number.
     */
    private List<Map<String, Object>> roundsOf(Competition competition, int seasonYear) {
        Map<Integer, List<MatchFixture>> byRound = new LinkedHashMap<>();
        for (MatchFixture fixture : fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                        competition.getId(), seasonYear)) {
            if (fixture.getRoundNumber() == null || fixture.getGroupCode() != null) {
                continue;
            }
            byRound.computeIfAbsent(fixture.getRoundNumber(), key -> new ArrayList<>()).add(fixture);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Integer, List<MatchFixture>> entry : byRound.entrySet()) {
            int round = entry.getKey();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("round", round);
            row.put("name", NationalTournamentSchedule.roundLabel(round));
            row.put("day", NationalTournamentSchedule.tournamentDay(round));
            row.put("played", entry.getValue().stream().allMatch(MatchFixture::isPlayed));
            List<Map<String, Object>> ties = new ArrayList<>();
            for (MatchFixture fixture : entry.getValue()) {
                ties.add(tie(fixture));
            }
            row.put("ties", ties);
            out.add(row);
        }
        return out;
    }

    /** One tie, with its result and its shootout when there was one. */
    /** A team's country code, or null when there is not one behind it. */
    private String isoOf(org.example.footballmanager.newLogic.model.Team team) {
        return team == null || team.getCountry() == null ? null : team.getCountry().getIsoCode();
    }

    private Map<String, Object> tie(MatchFixture fixture) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", fixture.getId());
        row.put("homeName", nameOf(fixture.getHomeTeam()));
        row.put("awayName", nameOf(fixture.getAwayTeam()));
        // The country codes, so a name on the screen can be a link to that country rather than text.
        // Null for a side with no country behind it, which is the honest answer and the frontend skips
        // the link rather than building one that goes nowhere.
        row.put("homeIso", isoOf(fixture.getHomeTeam()));
        row.put("awayIso", isoOf(fixture.getAwayTeam()));
        row.put("date", fixture.getMatchDate());
        row.put("played", fixture.isPlayed());
        var played = fixture.getPlayedMatch();
        // The Match this fixture became, so a tie can open the shared match view (P0-PREV-3). Every
        // post-match endpoint is keyed by match id, so a played tie opened on the fixture alone would
        // come up with lineups, stats, goals and the report all empty.
        row.put("matchId", played == null ? null : played.getId());
        row.put("homeGoals", played == null ? null : played.getHomeGoals());
        row.put("awayGoals", played == null ? null : played.getAwayGoals());
        // Null means "no shootout", which is different from "a shootout of 0-0". A knockout tie that
        // finished level always has one; a group tie never does.
        row.put("homePenalties", played == null ? null : played.getHomePenaltyGoals());
        row.put("awayPenalties", played == null ? null : played.getAwayPenaltyGoals());
        return row;
    }

    private String nameOf(org.example.footballmanager.newLogic.model.Team team) {
        return team == null ? null : team.getName();
    }

    private NationalTeamLevel parseLevel(String level) {
        if (level == null) {
            return null;
        }
        return switch (level.toLowerCase()) {
            case "senior" -> NationalTeamLevel.SENIOR;
            case "u21", "u-21", "under21" -> NationalTeamLevel.U21;
            default -> null;
        };
    }

    private NationalStage parseStage(String stage) {
        if (stage == null) {
            return null;
        }
        return switch (stage.toUpperCase()) {
            case "QUALIFYING", "QUALIFIERS" -> NationalStage.QUALIFYING;
            case "WORLD_CUP", "WORLDCUP", "TOURNAMENT" -> NationalStage.WORLD_CUP;
            default -> null;
        };
    }
}