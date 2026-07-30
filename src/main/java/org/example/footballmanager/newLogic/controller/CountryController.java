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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    private final CompetitionRepository competitionRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final PlayerRepository playerRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final MatchRepository matchRepository;
    private final MatchFixtureRepository matchFixtureRepository;
    private final SeasonRepository seasonRepository;
    private final ScheduleInsightService scheduleInsightService;
    private final SeasonService seasonService;

    public CountryController(CountryRepository countryRepository, CompetitionRepository competitionRepository, CompetitionEntryRepository competitionEntryRepository, TeamRepository teamRepository, PlayerRepository playerRepository, SeasonCompetitionRepository seasonCompetitionRepository, MatchRepository matchRepository, MatchFixtureRepository matchFixtureRepository, SeasonRepository seasonRepository, ScheduleInsightService scheduleInsightService, SeasonService seasonService) {
        this.countryRepository = countryRepository;
        this.competitionRepository = competitionRepository;
        this.competitionEntryRepository = competitionEntryRepository;
        this.playerRepository = playerRepository;
        this.seasonCompetitionRepository = seasonCompetitionRepository;
        this.matchRepository = matchRepository;
        this.matchFixtureRepository = matchFixtureRepository;
        this.seasonRepository = seasonRepository;
        this.scheduleInsightService = scheduleInsightService;
        this.seasonService = seasonService;
    }

    @GetMapping
    public List<CountrySummaryDTO> getAllCountries() {
        return countryRepository.findAll().stream()
                .sorted(Comparator.comparing(Country::getName, String.CASE_INSENSITIVE_ORDER))
                .map(CountrySummaryDTO::from)
                .toList();
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
