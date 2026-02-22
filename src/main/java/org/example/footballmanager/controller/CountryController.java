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

    // Dodaj u CountryController ili napravi LeagueController
    @GetMapping("/leagues/{leagueId}/table")
    public ResponseEntity<List<LeagueTableDto>> getLeagueTable(@PathVariable Long leagueId) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Liga nije pronađena"));

        // Dohvati sve CompetitionEntry za ovu ligu (preko trenutne sezone)
        // Za sada uzimamo poslednju sezonu – kasnije možeš dodati filter po sezoni
        SeasonCompetition currentSeasonComp = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, 2025) // ili dinamički iz GameClock
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sezona nije pronađena"));

        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(currentSeasonComp);

        // Sortiraj po bodovima descending, pa goal difference descending
        List<LeagueTableDto> table = entries.stream()
                .sorted(Comparator.comparing(CompetitionEntry::getPoints, Comparator.reverseOrder())
                        .thenComparing(CompetitionEntry::getGoalsScored, Comparator.reverseOrder())
                        .thenComparing(entry -> entry.getGoalsScored() - entry.getGoalsConceded(), Comparator.reverseOrder()))
                .map(entry -> new LeagueTableDto(
                        entry.getTeam().getName(),
                        entry.getPoints(),
                        entry.getGoalsScored() - entry.getGoalsConceded()
                ))
                .collect(Collectors.toList());

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
                        m.getMatchDate() != null ? m.getMatchDate().toString() : null
                ))
                .collect(Collectors.toList());
    }

    @GetMapping("/teams/{teamId}/players")
    public List<Player> getPlayers(@PathVariable Long teamId) {
        return playerRepository.findByTeamId(teamId);
    }
}