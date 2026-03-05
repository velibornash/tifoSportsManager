package org.example.footballmanager.controller;

import org.example.footballmanager.dto.LeagueTableDTO;
import org.example.footballmanager.dto.MatchDTO;
import org.example.footballmanager.model.*;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.service.SeasonService;
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
    private final SeasonService seasonService;

    public CountryController(CountryRepository countryRepository, CompetitionRepository competitionRepository, CompetitionEntryRepository competitionEntryRepository, TeamRepository teamRepository, PlayerRepository playerRepository, SeasonCompetitionRepository seasonCompetitionRepository, MatchRepository matchRepository, MatchFixtureRepository matchFixtureRepository, SeasonRepository seasonRepository, SeasonService seasonService) {
        this.countryRepository = countryRepository;
        this.competitionRepository = competitionRepository;
        this.competitionEntryRepository = competitionEntryRepository;
        this.playerRepository = playerRepository;
        this.seasonCompetitionRepository = seasonCompetitionRepository;
        this.matchRepository = matchRepository;
        this.matchFixtureRepository = matchFixtureRepository;
        this.seasonRepository = seasonRepository;
        this.seasonService = seasonService;
    }

    @GetMapping
    public List<Country> getAllCountries() {
        return countryRepository.findAll();
    }

    @GetMapping("/{isoCode}/leagues")
    public List<Competition> getLeagues(@PathVariable String isoCode) {
        return competitionRepository.findByCountryIsoCodeAndType(isoCode, CompetitionType.LEAGUE);
    }
    @GetMapping("/leagues/{leagueId}/teams")
    public List<Team> getTeams(@PathVariable Long leagueId) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new RuntimeException("Liga nije pronađena"));

        return competitionEntryRepository.findBySeasonCompetitionCompetition(league)
                .stream()
                .map(CompetitionEntry::getTeam)
                .filter(team -> team != null)  // sigurnosno, ako postoji null
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
                    e.getTeam().getName(),
                    e.getPoints(),
                    e.getGoalsScored(),
                    e.getGoalsConceded(),
                    e.getGoalsScored() - e.getGoalsConceded(),
                    e.getWins() != null ? e.getWins() : 0,
                    e.getDraws() != null ? e.getDraws() : 0,
                    e.getLosses() != null ? e.getLosses() : 0,
                    i + 1  // ← position iz sortiranja
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
                .map(m -> new MatchDTO(
                        m.getId(),
                        m.getHomeTeam().getName(),
                        m.getAwayTeam().getName(),
                        m.getHomeGoals(),
                        m.getAwayGoals(),
                        m.getMatchDate() != null
                                ? m.getMatchDate().toString().substring(0, 16).replace("T", " ")  // npr. "2026-02-20 12:00"
                                : "N/A"
                ))
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

        return matchFixtureRepository.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(leagueId, activeSeasonYear)
                .stream()
                .map(f -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    Match playedMatch = f.getPlayedMatch();
                    row.put("fixtureId", f.getId());
                    row.put("id", playedMatch != null ? playedMatch.getId() : null);
                    row.put("homeTeam", f.getHomeTeam().getName());
                    row.put("awayTeam", f.getAwayTeam().getName());
                    row.put("homeGoals", playedMatch != null ? playedMatch.getHomeGoals() : 0);
                    row.put("awayGoals", playedMatch != null ? playedMatch.getAwayGoals() : 0);
                    row.put("played", f.isPlayed());
                    row.put("round", f.getRoundNumber() != null ? f.getRoundNumber() : 1);
                    row.put("week", f.getWeekNumber() != null ? f.getWeekNumber() : f.getRoundNumber());
                    row.put("matchDate", f.getMatchDate() != null ? f.getMatchDate().toString().substring(0, 16).replace("T", " ") : "N/A");
                    return row;
                })
                .toList();
    }

    @GetMapping("/leagues/{leagueId}/seasons")
    public List<Map<String, Object>> getLeagueSeasons(@PathVariable Long leagueId) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "League not found"));
        List<Integer> years = seasonCompetitionRepository.findAll().stream()
                .filter(sc -> sc.getCompetition() != null && Objects.equals(sc.getCompetition().getId(), league.getId()))
                .map(SeasonCompetition::getSeasonYear)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
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

    @GetMapping("/teams/{teamId}/players")
    public List<Player> getPlayers(@PathVariable Long teamId) {
        return playerRepository.findByTeamId(teamId);
    }
}
