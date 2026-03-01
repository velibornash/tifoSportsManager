package org.example.footballmanager.controller;

import org.example.footballmanager.dto.LeagueTableDto;
import org.example.footballmanager.dto.MatchDTO;
import org.example.footballmanager.model.*;
import org.example.footballmanager.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    private final SeasonRepository seasonRepository;

    public CountryController(CountryRepository countryRepository, CompetitionRepository competitionRepository, CompetitionEntryRepository competitionEntryRepository, TeamRepository teamRepository, PlayerRepository playerRepository, SeasonCompetitionRepository seasonCompetitionRepository, MatchRepository matchRepository, SeasonRepository seasonRepository) {
        this.countryRepository = countryRepository;
        this.competitionRepository = competitionRepository;
        this.competitionEntryRepository = competitionEntryRepository;
        this.playerRepository = playerRepository;
        this.seasonCompetitionRepository = seasonCompetitionRepository;
        this.matchRepository = matchRepository;
        this.seasonRepository = seasonRepository;
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
    public ResponseEntity<List<LeagueTableDto>> getLeagueTable(@PathVariable Long leagueId) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Liga nije pronađena"));

        SeasonCompetition currentSeasonComp = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, 2025)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sezona nije pronađena"));

        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(currentSeasonComp);

        // Sortiraj
        List<CompetitionEntry> sortedEntries = entries.stream()
                .sorted(Comparator.comparing(CompetitionEntry::getPoints, Comparator.reverseOrder())
                        .thenComparing(e -> e.getGoalsScored() - e.getGoalsConceded(), Comparator.reverseOrder())
                        .thenComparing(CompetitionEntry::getGoalsScored, Comparator.reverseOrder()))
                .toList();

        // Mapiraj na DTO sa position iz sortiranja
        List<LeagueTableDto> table = new ArrayList<>();
        for (int i = 0; i < sortedEntries.size(); i++) {
            CompetitionEntry e = sortedEntries.get(i);
            table.add(new LeagueTableDto(
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
    public List<MatchDTO> getLeagueMatches(@PathVariable Long leagueId) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Liga nije pronađena"));

        // Pronađi tekuću sezonu
        Season currentSeason = seasonRepository.findBySeasonYear(2025).orElseThrow();
        SeasonCompetition sc = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, 2025)
                .orElseThrow();

        // Dohvati sve timove u ligi
        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(sc);
        List<Long> teamIds = entries.stream().map(e -> e.getTeam().getId()).toList();

        // Dohvati mečeve gde su oba tima iz ove lige
        List<Match> matches = matchRepository.findByHomeTeamIdInAndAwayTeamIdIn(teamIds, teamIds);

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

    @GetMapping("/teams/{teamId}/players")
    public List<Player> getPlayers(@PathVariable Long teamId) {
        return playerRepository.findByTeamId(teamId);
    }
}