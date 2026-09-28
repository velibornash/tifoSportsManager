package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.newLogic.dto.CountrySummaryDTO;
import org.example.footballmanager.newLogic.dto.LeagueTableDTO;
import org.example.footballmanager.newLogic.dto.MatchDTO;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.repository.*;
import org.example.footballmanager.newLogic.service.ScheduleInsightService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.service.NationalTeamService;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.util.CupFixtureSeeder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.example.footballmanager.newLogic.model.CountryCatalog;
import org.example.footballmanager.newLogic.repository.TeamRepository;

import java.util.Locale;
import java.util.Set;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;


@RestController
@RequestMapping("/countries")
public class CountryController {
    private final CountryRepository countryRepository;
    private final NationalTeamService nationalTeamService;
    private final TeamRepository teamRepository;
    private final CompetitionRepository competitionRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final PlayerRepository playerRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final MatchRepository matchRepository;
    private final MatchFixtureRepository matchFixtureRepository;
    private final SeasonRepository seasonRepository;
    private final ScheduleInsightService scheduleInsightService;
    private final SeasonService seasonService;

    public CountryController(CountryRepository countryRepository, CompetitionRepository competitionRepository, CompetitionEntryRepository competitionEntryRepository, TeamRepository teamRepository, PlayerRepository playerRepository, SeasonCompetitionRepository seasonCompetitionRepository, MatchRepository matchRepository, MatchFixtureRepository matchFixtureRepository, SeasonRepository seasonRepository, ScheduleInsightService scheduleInsightService, SeasonService seasonService, NationalTeamService nationalTeamService) {
        this.countryRepository = countryRepository;
        this.competitionRepository = competitionRepository;
        this.competitionEntryRepository = competitionEntryRepository;
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
        this.seasonCompetitionRepository = seasonCompetitionRepository;
        this.matchRepository = matchRepository;
        this.matchFixtureRepository = matchFixtureRepository;
        this.seasonRepository = seasonRepository;
        this.scheduleInsightService = scheduleInsightService;
        this.seasonService = seasonService;
        this.nationalTeamService = nationalTeamService;
    }

    /**
     * The 48 nations, for the registration form (owner, 2026-09-28).
     *
     * <p>Served from {@link CountryCatalog} rather than from the {@code country} table, and that
     * distinction matters. The registration form must work <b>before anyone has registered</b> — for a
     * new install, and for a manager choosing a country whose leagues are not seeded yet. Listing
     * only seeded countries would hide exactly the countries a new player most wants to see.
     *
     * <p>Each entry also reports whether it is playable right now, so the form can show "not seeded
     * yet" rather than letting someone pick a country that will refuse their registration.
     *
     * <p>Public on purpose: it must be readable by an unauthenticated visitor, and it exposes nothing
     * but a list of country names.
     */
    @GetMapping("/catalog")
    public List<Map<String, Object>> getCountryCatalog() {
        Set<String> seeded = countryRepository.findAll().stream()
                .map(Country::getIsoCode)
                .filter(code -> code != null && !code.isBlank())
                .map(code -> code.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        Set<String> seededWithClubs = teamRepository.findAll().stream()
                .map(team -> team.getCountry())
                .filter(Objects::nonNull)
                .map(Country::getIsoCode)
                .filter(code -> code != null && !code.isBlank())
                .map(code -> code.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());

        return CountryCatalog.all().stream().map(c -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", c.code());
            row.put("name", c.displayName());
            row.put("seeded", seeded.contains(c.code()));
            row.put("hasClubs", seededWithClubs.contains(c.code()));
            return row;
        }).toList();
    }

    @GetMapping
    public List<CountrySummaryDTO> getAllCountries() {
        return countryRepository.findAll().stream()
                .sorted(Comparator.comparing(Country::getName, String.CASE_INSENSITIVE_ORDER))
                .map(CountrySummaryDTO::from)
                .toList();
    }

    /**
     * A national team: squad, pool, selector, ranking, fixtures, election state (owner, 2026-09-28).
     *
     * <p>Replaces a hand-rolled map that reported the viewer as selector. That made every user in a
     * country believe they ran the national team and left nothing to check "is this viewer the
     * selector" against, so the squad could not be restricted to them.
     */
    @GetMapping("/{isoCode}/national-team")
    public Map<String, Object> getNationalTeam(
            @PathVariable String isoCode,
            @RequestParam(defaultValue = "senior") String level,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.example.commonmanager.model.User viewer) {
        Country country = requireCountry(isoCode);
        return nationalTeamService.describe(country, NationalTeamLevel.from(level), viewer);
    }

    /**
     * Puts a club player on the national roster.
     *
     * <p>Selector-only, and re-checked here rather than trusting the client. The client hides the
     * button, but a hidden button is not a permission.
     */
    @PostMapping("/{isoCode}/national-team/squad")
    public Map<String, Object> addToSquad(
            @PathVariable String isoCode,
            @RequestParam(defaultValue = "senior") String level,
            @RequestBody Map<String, Object> body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.example.commonmanager.model.User viewer) {
        Country country = requireCountry(isoCode);
        long sourcePlayerId = Long.parseLong(String.valueOf(body.get("playerId")));
        return nationalTeamService.addToSquad(country, NationalTeamLevel.from(level), sourcePlayerId, viewer);
    }

    /** Takes a national-roster row off the squad. The club player is untouched. */
    @org.springframework.web.bind.annotation.DeleteMapping("/{isoCode}/national-team/squad/{playerId}")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void removeFromSquad(
            @PathVariable String isoCode,
            @PathVariable long playerId,
            @RequestParam(defaultValue = "senior") String level,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.example.commonmanager.model.User viewer) {
        nationalTeamService.removeFromSquad(requireCountry(isoCode), NationalTeamLevel.from(level), playerId, viewer);
    }

    /**
     * The cup bracket for this country: every round, every tie.
     *
     * <p>Rounds that have not been drawn yet are reported as empty rather than omitted, so the cup
     * page can show the shape of the tournament and which round is next instead of a list that
     * silently grows.
     */
    @GetMapping("/{isoCode}/cup")
    public Map<String, Object> getCup(@PathVariable String isoCode) {
        Country country = requireCountry(isoCode);
        Competition cup = competitionRepository.findAll().stream()
                .filter(c -> c.getType() == CompetitionType.CUP)
                .filter(c -> c.getCountry() == null || country.getId().equals(c.getCountry().getId()))
                .findFirst()
                .orElse(null);

        Map<String, Object> out = new LinkedHashMap<>();
        if (cup == null) {
            out.put("exists", false);
            out.put("rounds", List.of());
            return out;
        }
        out.put("exists", true);
        out.put("competitionId", cup.getId());
        out.put("name", cup.getName());
        out.put("teamsPerCompetition", cup.getTeamsPerCompetition());

        List<MatchFixture> all = matchFixtureRepository
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(cup.getId(), 1);
        Map<Integer, List<Map<String, Object>>> byRound = new LinkedHashMap<>();
        for (int round : CupFixtureSeeder.CUP_WEEKS) {
            byRound.put(round, new ArrayList<>());
        }
        for (MatchFixture fixture : all) {
            List<Map<String, Object>> bucket = byRound.computeIfAbsent(
                    fixture.getWeekNumber() == null ? 0 : fixture.getWeekNumber(), k -> new ArrayList<>());
            Map<String, Object> tie = new LinkedHashMap<>();
            tie.put("id", fixture.getId());
            tie.put("home", fixture.getHomeTeam() == null ? null : fixture.getHomeTeam().getName());
            tie.put("away", fixture.getAwayTeam() == null ? null : fixture.getAwayTeam().getName());
            tie.put("played", fixture.isPlayed());
            bucket.add(tie);
        }
        List<Map<String, Object>> rounds = new ArrayList<>();
        for (int round : CupFixtureSeeder.CUP_WEEKS) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("week", round);
            row.put("fixtures", byRound.getOrDefault(round, List.of()));
            rounds.add(row);
        }
        out.put("rounds", rounds);
        out.put("totalFixtures", all.size());
        return out;
    }

    /**
     * Playoff ties for this country.
     *
     * <p>Empty until playoffs exist. Reported as an empty list so the link is live and the page says
     * "none yet" rather than 404-ing at a section the general tab always offers.
     */
    @GetMapping("/{isoCode}/playoffs")
    public Map<String, Object> getPlayoffs(@PathVariable String isoCode) {
        Country country = requireCountry(isoCode);
        List<Competition> playoffs = competitionRepository.findAll().stream()
                .filter(c -> c.getType() == CompetitionType.LEAGUE)
                .filter(c -> c.getName() != null && c.getName().toLowerCase().contains("playoff"))
                .filter(c -> c.getCountry() == null || country.getId().equals(c.getCountry().getId()))
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exists", !playoffs.isEmpty());
        out.put("fixtures", List.of());
        out.put("note", playoffs.isEmpty()
                ? "No playoff competition exists for this country yet."
                : "Playoff competition exists but no ties have been generated.");
        return out;
    }

    private Country requireCountry(String isoCode) {
        return countryRepository.findByIsoCode(String.valueOf(isoCode).toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException("No such country: " + isoCode));
    }

    @GetMapping("/{isoCode}/leagues")
    public List<Competition> getLeagues(@PathVariable String isoCode) {
        return competitionRepository.findByCountryIsoCodeAndType(isoCode, CompetitionType.LEAGUE);
    }
    @GetMapping("/leagues/{leagueId}/teams")
    public List<Map<String, Object>> getTeams(@PathVariable Long leagueId,
                                              @RequestParam(value = "seasonYear", required = false) Integer seasonYear) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Liga nije pronađena"));
        int activeSeasonYear = seasonYear != null ? seasonYear : seasonService.getActiveSeasonYear();
        seasonService.ensureEntriesForSeasonCompetition(league, activeSeasonYear);

        SeasonCompetition seasonCompetition = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, activeSeasonYear)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sezona nije pronađena"));

        return competitionEntryRepository.findBySeasonCompetition(seasonCompetition)
                .stream()
                .filter(entry -> entry.getTeam() != null)
                .sorted(Comparator
                        .comparing(CompetitionEntry::getPosition, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(entry -> entry.getTeam().getName(), String.CASE_INSENSITIVE_ORDER))
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", entry.getTeam().getId());
                    row.put("name", entry.getTeam().getName());
                    row.put("humanControlled", entry.getTeam().isHumanControlled());
                    return row;
                })
                .toList();
    }
    @GetMapping("/leagues/{leagueId}/table")
    public ResponseEntity<List<LeagueTableDTO>> getLeagueTable(@PathVariable Long leagueId,
                                                               @RequestParam(value = "seasonYear", required = false) Integer seasonYear) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Liga nije pronađena"));
        int activeSeasonYear = seasonYear != null ? seasonYear : seasonService.getActiveSeasonYear();
        seasonService.ensureEntriesForSeasonCompetition(league, activeSeasonYear);

        SeasonCompetition currentSeasonComp = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, activeSeasonYear)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sezona nije pronađena"));

        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(currentSeasonComp);

        // Sortiraj
        List<CompetitionEntry> sortedEntries = entries.stream()
                .sorted(Comparator.comparing(CompetitionEntry::getPoints, Comparator.reverseOrder())
                        .thenComparing(e -> e.getGoalsScored() - e.getGoalsConceded(), Comparator.reverseOrder())
                        .thenComparing(CompetitionEntry::getGoalsScored, Comparator.reverseOrder()))
                .toList();

        // Mapiraj na DTO sa position iz sortiranja
        List<LeagueTableDTO> table = new ArrayList<>();
        for (int i = 0; i < sortedEntries.size(); i++) {
            CompetitionEntry e = sortedEntries.get(i);
            table.add(new LeagueTableDTO(
                    e.getTeam().getId(),
                    e.getTeam().getName(),
                    e.getPoints(),
                    e.getGoalsScored(),
                    e.getGoalsConceded(),
                    e.getGoalsScored() - e.getGoalsConceded(),
                    e.getWins() != null ? e.getWins() : 0,
                    e.getDraws() != null ? e.getDraws() : 0,
                    e.getLosses() != null ? e.getLosses() : 0,
                    i + 1,
                    e.getTeam().isHumanControlled()
            ));
        }

        return ResponseEntity.ok(table);
    }
    @GetMapping("/leagues/{leagueId}/matches")
    public List<MatchDTO> getLeagueMatches(@PathVariable Long leagueId,
                                           @RequestParam(value = "seasonYear", required = false) Integer seasonYear) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Liga nije pronađena"));
        int activeSeasonYear = seasonYear != null ? seasonYear : seasonService.getActiveSeasonYear();
        seasonService.ensureEntriesForSeasonCompetition(league, activeSeasonYear);
        SeasonCompetition sc = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, activeSeasonYear)
                .orElse(null);
        if (sc == null) {
            return List.of();
        }

        // Dohvati sve timove u ligi
        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(sc);
        List<Long> teamIds = entries.stream().map(e -> e.getTeam().getId()).toList();

        List<Match> matches = matchRepository
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(leagueId, activeSeasonYear)
                .stream()
                .filter(m -> m.getHomeTeam() != null && m.getAwayTeam() != null)
                .filter(m -> teamIds.contains(m.getHomeTeam().getId()) && teamIds.contains(m.getAwayTeam().getId()))
                .filter(Match::isPlayed)
                .toList();

        // Mapiraj u DTO
        return matches.stream()
                .map(MatchDTO::from)
                .collect(Collectors.toList());
    }

    @GetMapping("/leagues/{leagueId}/schedule")
    public List<Map<String, Object>> getLeagueSchedule(@PathVariable Long leagueId,
                                                        @RequestParam(value = "seasonYear", required = false) Integer seasonYear) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "League not found"));
        int activeSeasonYear = seasonYear != null ? seasonYear : seasonService.getActiveSeasonYear();

        seasonService.ensureEntriesForSeasonCompetition(league, activeSeasonYear);
        seasonService.ensureDoubleRoundRobinSchedule(league, activeSeasonYear);

        List<MatchFixture> fixtures = matchFixtureRepository.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(leagueId, activeSeasonYear);
        Map<Long, ScheduleInsightService.TeamSnapshot> snapshots = scheduleInsightService.buildTeamSnapshots(fixtures.stream()
                .flatMap(fixture -> java.util.stream.Stream.of(fixture.getHomeTeam(), fixture.getAwayTeam()))
                .filter(Objects::nonNull)
                .toList());

        return fixtures
                .stream()
                .filter(f -> f.getHomeTeam() != null && f.getAwayTeam() != null)
                .map(f -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    Match playedMatch = f.getPlayedMatch();
                    ScheduleInsightService.FixtureInsights insights = scheduleInsightService.buildFixtureInsights(
                            f.getHomeTeam(),
                            f.getAwayTeam(),
                            snapshots
                    );
                    row.put("fixtureId", f.getId());
                    row.put("id", playedMatch != null ? playedMatch.getId() : null);
                    row.put("homeTeamId", f.getHomeTeam().getId());
                    row.put("awayTeamId", f.getAwayTeam().getId());
                    row.put("homeTeam", f.getHomeTeam().getName());
                    row.put("awayTeam", f.getAwayTeam().getName());
                    row.put("homeGoals", playedMatch != null ? playedMatch.getHomeGoals() : 0);
                    row.put("awayGoals", playedMatch != null ? playedMatch.getAwayGoals() : 0);
                    row.put("played", f.isPlayed());
                    row.put("round", f.getRoundNumber() != null ? f.getRoundNumber() : 1);
                    row.put("week", f.getWeekNumber() != null ? f.getWeekNumber() : f.getRoundNumber());
                    row.put("seasonYear", f.getSeasonYear());
                    row.put("competitionName", f.getCompetition() != null ? f.getCompetition().getName() : league.getName());
                    row.put("stadium", f.getHomeTeam().getStadium() != null ? f.getHomeTeam().getStadium().getName() : "N/A");
                    row.put("homeTeamStrength", insights.homeTeamStrength());
                    row.put("awayTeamStrength", insights.awayTeamStrength());
                    row.put("homeTeamForm", insights.homeTeamForm());
                    row.put("awayTeamForm", insights.awayTeamForm());
                    row.put("prediction", toPredictionMap(insights.prediction()));
                    row.put("matchDate", f.getMatchDate() != null ? f.getMatchDate().toString().substring(0, 16).replace("T", " ") : "N/A");
                    return row;
                })
                .toList();
    }

    @GetMapping("/leagues/{leagueId}/season-summary")
    public Map<String, Object> getLeagueSeasonSummary(@PathVariable Long leagueId,
                                                      @RequestParam(value = "seasonYear", required = false) Integer seasonYear) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "League not found"));
        int activeSeasonYear = seasonYear != null ? seasonYear : seasonService.getActiveSeasonYear();
        return seasonService.buildPlayoffSummary(league, activeSeasonYear);
    }

    private Map<String, Object> toPredictionMap(ScheduleInsightService.Prediction prediction) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("homeWinProbability", prediction.homeWinProbability());
        payload.put("drawProbability", prediction.drawProbability());
        payload.put("awayWinProbability", prediction.awayWinProbability());
        payload.put("expectedHomeGoals", prediction.expectedHomeGoals());
        payload.put("expectedAwayGoals", prediction.expectedAwayGoals());
        payload.put("mostLikelyResult", prediction.mostLikelyResult());
        payload.put("confidence", prediction.confidence());
        payload.put("analysis", prediction.analysis());
        return payload;
    }

    @GetMapping("/leagues/{leagueId}/seasons")
    public List<Map<String, Object>> getLeagueSeasons(@PathVariable Long leagueId) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "League not found"));
        List<Integer> years = seasonCompetitionRepository.findSeasonYearsByCompetitionId(league.getId());
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < years.size(); i++) {
            Integer year = years.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seasonYear", year);
            row.put("seasonNumber", i + 1);
            result.add(row);
        }
        return result;
    }

    @GetMapping("/leagues/{leagueId}/player-directory")
    public List<Map<String, Object>> getLeaguePlayerDirectory(@PathVariable Long leagueId,
                                                              @RequestParam(value = "seasonYear", required = false) Integer seasonYear) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "League not found"));
        int activeSeasonYear = seasonYear != null ? seasonYear : seasonService.getActiveSeasonYear();
        seasonService.ensureEntriesForSeasonCompetition(league, activeSeasonYear);

        SeasonCompetition seasonCompetition = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, activeSeasonYear)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "League season not found"));

        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(seasonCompetition);
        Map<Long, String> teamNameById = entries.stream()
                .filter(entry -> entry.getTeam() != null && entry.getTeam().getId() != null)
                .collect(Collectors.toMap(entry -> entry.getTeam().getId(), entry -> entry.getTeam().getName(), (left, right) -> left));

        return playerRepository.findByTeamIdIn(new ArrayList<>(teamNameById.keySet())).stream()
                .map(player -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    Long teamId = player.getTeam() != null ? player.getTeam().getId() : null;
                    row.put("id", player.getId());
                    row.put("name", player.getName());
                    row.put("teamId", teamId);
                    row.put("teamName", teamId != null ? teamNameById.get(teamId) : null);
                    return row;
                })
                .toList();
    }

    @GetMapping("/teams/{teamId}/players")
    public List<Player> getPlayers(@PathVariable Long teamId) {
        return playerRepository.findByTeamId(teamId);
    }
}
