package org.example.basketballmanager.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.basketballmanager.dto.*;
import org.example.basketballmanager.model.*;
import org.example.basketballmanager.repository.*;
import org.example.basketballmanager.engine.BbMatchResult;
import org.example.basketballmanager.service.*;
import org.example.commonmanager.model.CommonCompetition;
import org.example.commonmanager.model.CommonSeason;
import org.example.commonmanager.model.User;
import org.example.commonmanager.repository.CommonSeasonRepository;
import org.example.commonmanager.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/bb")
@Slf4j
public class BbController {

    private final BbTeamRepository teamRepository;
    private final BbPlayerService playerService;
    private final BbLeagueService leagueService;
    private final BbMatchRepository matchRepository;
    private final BbMatchSimulationService simulationService;
    private final BbMatchFixtureRepository matchFixtureRepository;
    private final BbPlayerRepository playerRepository;
    private final BbPlayerSeasonStatsRepository playerSeasonStatsRepository;
    private final BbCompetitionEntryRepository competitionEntryRepository;
    private final BbSeasonCompetitionRepository seasonCompetitionRepository;
    private final CommonSeasonRepository commonSeasonRepository;
    private final UserRepository userRepository;
    private final Random random = new Random();

    public BbController(BbTeamRepository teamRepository,
                        BbPlayerService playerService,
                        BbLeagueService leagueService,
                        BbMatchRepository matchRepository,
                        BbMatchSimulationService simulationService,
                        BbMatchFixtureRepository matchFixtureRepository,
                        BbPlayerRepository playerRepository,
                        BbPlayerSeasonStatsRepository playerSeasonStatsRepository,
                        BbCompetitionEntryRepository competitionEntryRepository,
                        BbSeasonCompetitionRepository seasonCompetitionRepository,
                        CommonSeasonRepository commonSeasonRepository,
                        UserRepository userRepository) {
        this.teamRepository = teamRepository;
        this.playerService = playerService;
        this.leagueService = leagueService;
        this.matchRepository = matchRepository;
        this.simulationService = simulationService;
        this.matchFixtureRepository = matchFixtureRepository;
        this.playerRepository = playerRepository;
        this.playerSeasonStatsRepository = playerSeasonStatsRepository;
        this.competitionEntryRepository = competitionEntryRepository;
        this.seasonCompetitionRepository = seasonCompetitionRepository;
        this.commonSeasonRepository = commonSeasonRepository;
        this.userRepository = userRepository;
    }

    // ─── Leagues (Competitions) ───

    @GetMapping("/leagues")
    public List<CommonCompetition> getAllLeagues() {
        return leagueService.getAllLeagues();
    }

    @GetMapping("/leagues/{id}")
    public ResponseEntity<CommonCompetition> getLeague(@PathVariable Long id) {
        return leagueService.getLeagueById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/leagues/{id}/table")
    public ResponseEntity<List<BbLeagueTableEntry>> getLeagueTable(
            @PathVariable Long id,
            @RequestParam(defaultValue = "2025") Integer seasonYear) {
        List<BbLeagueTableEntry> table = leagueService.calculateTable(id, seasonYear);
        return ResponseEntity.ok(table);
    }

    // ─── Teams ───

    @GetMapping("/teams")
    public List<BbTeamDTO> getAllTeams() {
        return teamRepository.findAll().stream()
                .map(this::toTeamDTO)
                .collect(Collectors.toList());
    }

    @GetMapping("/teams/{id}")
    public ResponseEntity<BbTeamDTO> getTeam(@PathVariable Long id) {
        return teamRepository.findById(id)
                .map(team -> {
                    List<BbPlayerDTO> players = playerService.getTeamPlayers(id);
                    BbTeamDTO dto = toTeamDTO(team);
                    dto.setPlayers(players);
                    return ResponseEntity.ok(dto);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/teams/{id}/players")
    public ResponseEntity<List<BbPlayerDTO>> getTeamPlayers(@PathVariable Long id) {
        if (!teamRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(playerService.getTeamPlayers(id));
    }

    // ─── My CTeam (authenticated user) ───

    @GetMapping("/my-team")
    public ResponseEntity<Map<String, Object>> getMyTeam() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = auth.getName();
        Optional<User> userOpt = userRepository.findByUsernameOrEmail(email);
        if (userOpt.isEmpty() || userOpt.get().getBasketballTeam() == null) {
            return ResponseEntity.ok(Map.of(
                    "hasTeam", false,
                    "message", "No basketball team assigned. Contact admin."
            ));
        }
        BbTeam team = userOpt.get().getBasketballTeam();
        CommonCompetition comp = team.getCompetition();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("hasTeam", true);
        resp.put("teamId", team.getId());
        resp.put("teamName", team.getName());
        resp.put("teamShortName", team.getShortName());
        resp.put("teamColor", team.getColor());
        resp.put("competitionId", comp != null ? comp.getId() : null);
        resp.put("competitionName", comp != null ? comp.getName() : null);
        resp.put("seasonYear", 2025);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/players/{id}")
    public ResponseEntity<BbPlayerDTO> getPlayer(@PathVariable Long id) {
        return playerService.getPlayerById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/players/{id}/seasons")
    public ResponseEntity<Map<String, Object>> getPlayerSeasonStats(@PathVariable Long id) {
        var seasons = playerService.getPlayerSeasonStats(id);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("playerId", id);
        resp.put("seasons", seasons);
        Map<String, Object> totals = new LinkedHashMap<>();
        for (var s : seasons) {
            totals.merge("gamesPlayed", s.getGamesPlayed(), (a, b) -> (int) a + (int) b);
            totals.merge("pointsScored", s.getPointsScored(), (a, b) -> (int) a + (int) b);
            totals.merge("reboundsTotal", s.getReboundsTotal(), (a, b) -> (int) a + (int) b);
            totals.merge("assistsTotal", s.getAssistsTotal(), (a, b) -> (int) a + (int) b);
            totals.merge("stealsTotal", s.getStealsTotal(), (a, b) -> (int) a + (int) b);
            totals.merge("blocksTotal", s.getBlocksTotal(), (a, b) -> (int) a + (int) b);
            totals.merge("turnoversTotal", s.getTurnoversTotal(), (a, b) -> (int) a + (int) b);
            totals.merge("twoPtMade", s.getTwoPtMade(), (a, b) -> (int) a + (int) b);
            totals.merge("twoPtAttempted", s.getTwoPtAttempted(), (a, b) -> (int) a + (int) b);
            totals.merge("threePtMade", s.getThreePtMade(), (a, b) -> (int) a + (int) b);
            totals.merge("threePtAttempted", s.getThreePtAttempted(), (a, b) -> (int) a + (int) b);
            totals.merge("ftMade", s.getFtMade(), (a, b) -> (int) a + (int) b);
            totals.merge("ftAttempted", s.getFtAttempted(), (a, b) -> (int) a + (int) b);
        }
        resp.put("totals", totals);
        return ResponseEntity.ok(resp);
    }

    // ─── Stats / League Leaders ───

    @GetMapping("/stats/league/{competitionId}")
    public ResponseEntity<BbLeagueLeadersDTO> getLeagueLeaders(
            @PathVariable Long competitionId,
            @RequestParam(defaultValue = "10") int limit) {
        BbLeagueLeadersDTO leaders = BbLeagueLeadersDTO.builder()
                .topScorers(playerService.getTopScorers(competitionId, limit))
                .topRebounders(playerService.getTopRebounders(competitionId, limit))
                .topAssists(playerService.getTopAssists(competitionId, limit))
                .build();
        return ResponseEntity.ok(leaders);
    }

    // ─── Matches ───

    @GetMapping("/matches")
    public List<BbMatchDTO> getAllMatches(
            @RequestParam(required = false) Long competitionId,
            @RequestParam(required = false) Integer seasonYear) {
        List<BbMatch> matches;
        if (competitionId != null && seasonYear != null) {
            matches = matchRepository.findByCompetitionIdAndSeasonYearOrderByMatchDate(competitionId, seasonYear);
        } else if (competitionId != null) {
            matches = matchRepository.findByCompetitionIdAndSeasonYearOrderByMatchDate(competitionId, 2025);
        } else {
            matches = matchRepository.findAll();
        }
        return matches.stream().map(this::toMatchDTO).collect(Collectors.toList());
    }

    @GetMapping("/matches/{id}")
    public ResponseEntity<BbMatchDTO> getMatch(@PathVariable Long id) {
        return matchRepository.findById(id)
                .map(m -> ResponseEntity.ok(toMatchDTO(m)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/matches/team/{teamId}")
    public List<BbMatchDTO> getTeamMatches(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "2025") Integer seasonYear) {
        return matchRepository.findByTeamIdAndSeasonYear(teamId, seasonYear)
                .stream()
                .map(this::toMatchDTO)
                .collect(Collectors.toList());
    }

    @GetMapping("/matches/team/{teamId}/recent")
    public List<BbMatchDTO> getRecentTeamMatches(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "2025") Integer seasonYear,
            @RequestParam(defaultValue = "3") int limit) {
        return matchRepository.findByTeamIdAndSeasonYear(teamId, seasonYear)
                .stream()
                .filter(m -> m.getPlayed())
                .sorted((a, b) -> b.getMatchDate().compareTo(a.getMatchDate()))
                .limit(limit)
                .map(this::toMatchDTO)
                .collect(Collectors.toList());
    }

    // ─── Fixtures ───

    @GetMapping("/fixtures/team/{teamId}")
    public List<BbMatchFixtureDTO> getTeamFixtures(@PathVariable Long teamId) {
        return matchFixtureRepository.findByHomeTeamIdOrAwayTeamIdOrderByRoundNumber(teamId, teamId)
                .stream()
                .map(this::toFixtureDTO)
                .collect(Collectors.toList());
    }

    @GetMapping("/fixtures")
    public List<BbMatchFixtureDTO> getFixtures(
            @RequestParam Long competitionId,
            @RequestParam(defaultValue = "2025") Integer seasonYear) {
        return matchFixtureRepository
                .findBySeasonYearAndCompetitionIdOrderByRoundNumber(seasonYear, competitionId)
                .stream()
                .map(this::toFixtureDTO)
                .collect(Collectors.toList());
    }

    // ─── Round Progression ───

    @GetMapping("/fixtures/round/{competitionId}/status")
    public ResponseEntity<Map<String, Object>> getRoundStatus(
            @PathVariable Long competitionId,
            @RequestParam(defaultValue = "2025") Integer seasonYear) {
        List<BbMatchFixture> allFixtures = matchFixtureRepository
                .findBySeasonYearAndCompetitionIdOrderByRoundNumber(seasonYear, competitionId);

        int totalRounds = allFixtures.stream()
                .mapToInt(BbMatchFixture::getRoundNumber)
                .max().orElse(18);

        // Current round = smallest round number where not all fixtures are played
        int currentRound = 1;
        for (int r = 1; r <= totalRounds; r++) {
            int round = r;
            long totalInRound = allFixtures.stream().filter(f -> f.getRoundNumber() == round).count();
            long playedInRound = allFixtures.stream().filter(f -> f.getRoundNumber() == round && f.getPlayed()).count();
            if (playedInRound < totalInRound) {
                currentRound = round;
                break;
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("currentRound", currentRound);
        resp.put("totalRounds", totalRounds);
        Map<String, Object> roundsMap = new LinkedHashMap<>();
        resp.put("rounds", roundsMap);
        for (int r = 1; r <= totalRounds; r++) {
            int round = r;
            long totalInRound = allFixtures.stream().filter(f -> f.getRoundNumber() == round).count();
            long playedInRound = allFixtures.stream().filter(f -> f.getRoundNumber() == round && f.getPlayed()).count();
            Map<String, Object> rInfo = new LinkedHashMap<>();
            rInfo.put("total", totalInRound);
            rInfo.put("played", playedInRound);
            rInfo.put("locked", round > currentRound);
            roundsMap.put(String.valueOf(round), rInfo);
        }

        return ResponseEntity.ok(resp);
    }

    @PostMapping("/fixtures/round/{competitionId}/play-all")
    public ResponseEntity<Map<String, Object>> playAllInRound(
            @PathVariable Long competitionId,
            @RequestParam(defaultValue = "1") Integer roundNumber,
            @RequestParam(defaultValue = "2025") Integer seasonYear) {
        List<BbMatchFixture> fixtures = matchFixtureRepository
                .findBySeasonYearAndCompetitionIdOrderByRoundNumber(seasonYear, competitionId)
                .stream()
                .filter(f -> f.getRoundNumber() == roundNumber && !f.getPlayed())
                .collect(Collectors.toList());

        List<Long> playedIds = new ArrayList<>();
        for (BbMatchFixture fixture : fixtures) {
            try {
                simulationService.simulateFixture(fixture.getId());
                playedIds.add(fixture.getId());
            } catch (Exception e) {
                log.error("Failed to simulate fixture {}: {} {}", fixture.getId(), e.getClass().getSimpleName(), e.getMessage());
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("played", playedIds.size());
        resp.put("total", fixtures.size());
        resp.put("playedFixtureIds", playedIds);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/reset")
    @Transactional
    public ResponseEntity<Map<String, Object>> resetBasketball() {
        // 1. Nullify playedMatch FK on all fixtures (single UPDATE)
        matchFixtureRepository.resetAllFixtures();
        matchFixtureRepository.flush();

        // 2. Delete all per-season stats and matches (batch DELETEs)
        playerSeasonStatsRepository.deleteAllInBatch();
        matchRepository.deleteAllInBatch();

        // 3. Reset league standings (single UPDATE)
        competitionEntryRepository.resetAllEntries();

        // 4. Reset player stats/fatigue/injury (single UPDATE)
        playerRepository.resetAllPlayers();

        // 5. Re-assign owner team in case it was lost
        userRepository.findByUsernameOrEmail("velibor@example.com").ifPresent(user -> {
            if (user.getBasketballTeam() == null) {
                teamRepository.findByName("KK Omladinac").ifPresent(user::setBasketballTeam);
                userRepository.save(user);
            }
        });

        // 6. Regenerate fixtures if they were deleted
        if (matchFixtureRepository.count() == 0) {
            log.info("Fixtures empty after reset – regenerating from competition entries");
            CommonSeason season = commonSeasonRepository.findBySeasonYear(2025).orElse(null);
            if (season != null) {
                List<BbCompetitionEntry> allEntries = competitionEntryRepository.findAll();
                Map<CommonCompetition, List<BbTeam>> compTeams = new LinkedHashMap<>();
                for (BbCompetitionEntry e : allEntries) {
                    BbSeasonCompetition sc = e.getSeasonCompetition();
                    if (sc == null) continue;
                    CommonCompetition comp = sc.getCompetition();
                    if (comp == null) continue;
                    compTeams.computeIfAbsent(comp, k -> new ArrayList<>()).add(e.getTeam());
                }
                for (Map.Entry<CommonCompetition, List<BbTeam>> entry : compTeams.entrySet()) {
                    CommonCompetition comp = entry.getKey();
                    List<BbTeam> teams = entry.getValue();
                    int numTeams = teams.size();
                    LocalDateTime baseDate = LocalDateTime.of(2025, 9, 15, 18, 0);
                    List<BbTeam> rotated = new ArrayList<>(teams);
                    for (int round = 0; round < numTeams - 1; round++) {
                        for (int i = 0; i < numTeams / 2; i++) {
                            BbTeam home = rotated.get(i);
                            BbTeam away = rotated.get(numTeams - 1 - i);
                            if (random.nextBoolean()) { BbTeam t = home; home = away; away = t; }
                            matchFixtureRepository.save(BbMatchFixture.builder()
                                    .homeTeam(home).awayTeam(away).competition(comp)
                                    .seasonYear(season.getSeasonYear()).roundNumber(round + 1).weekNumber(round + 1)
                                    .matchDate(baseDate.plusDays(round * 7L)).played(false).build());
                            matchFixtureRepository.save(BbMatchFixture.builder()
                                    .homeTeam(away).awayTeam(home).competition(comp)
                                    .seasonYear(season.getSeasonYear()).roundNumber(round + 1 + (numTeams - 1)).weekNumber(round + 1 + (numTeams - 1))
                                    .matchDate(baseDate.plusDays((round + numTeams) * 7L)).played(false).build());
                        }
                        BbTeam first = rotated.get(0);
                        List<BbTeam> rest = new ArrayList<>(rotated.subList(1, rotated.size()));
                        rotated = new ArrayList<>();
                        rotated.add(first);
                        rotated.add(rest.get(rest.size() - 1));
                        rotated.addAll(rest.subList(0, rest.size() - 1));
                    }
                }
                log.info("Fixtures regenerated for {} competition(s)", compTeams.size());
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("message", "All basketball data reset. Fixtures, standings, and player stats restored.");
        return ResponseEntity.ok(resp);
    }

    // ─── Simulation ───

    @PostMapping("/matches/simulate")
    public ResponseEntity<BbMatchResult> simulateMatch(
            @RequestParam Long homeTeamId,
            @RequestParam Long awayTeamId) {
        try {
            BbMatchResult result = simulationService.simulateAndSave(homeTeamId, awayTeamId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/fixtures/{fixtureId}/play")
    public ResponseEntity<BbMatchResult> playFixture(@PathVariable Long fixtureId) {
        try {
            BbMatchResult result = simulationService.simulateFixture(fixtureId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to play fixture {}: {}", fixtureId, e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    // ─── DTO Mappers ───

    private BbTeamDTO toTeamDTO(BbTeam team) {
        return BbTeamDTO.builder()
                .id(team.getId())
                .name(team.getName())
                .shortName(team.getShortName())
                .city(team.getCity())
                .hall(team.getHallName())
                .hallCapacity(team.getHallCapacity())
                .color(team.getColor())
                .averageOverall(team.getAverageOverall())
                .build();
    }

    private BbMatchDTO toMatchDTO(BbMatch match) {
        BbTeam home = match.getHomeTeam();
        BbTeam away = match.getAwayTeam();
        
        // Parse events
        List<String> events = match.getEvents() != null && !match.getEvents().isEmpty()
                ? List.of(match.getEvents().split("\\|"))
                : List.of();
        
        // Parse player stats
        List<BbPlayerGameStatsDTO> homePlayerStats = parsePlayerStats(match.getHomePlayerStats());
        List<BbPlayerGameStatsDTO> awayPlayerStats = parsePlayerStats(match.getAwayPlayerStats());

        return BbMatchDTO.builder()
                .id(match.getId())
                .homeTeamId(home != null ? home.getId() : null)
                .homeTeamName(home != null ? home.getName() : null)
                .homeTeamShortName(home != null ? home.getShortName() : null)
                .homeTeamColor(home != null ? home.getColor() : null)
                .awayTeamId(away != null ? away.getId() : null)
                .awayTeamName(away != null ? away.getName() : null)
                .awayTeamShortName(away != null ? away.getShortName() : null)
                .awayTeamColor(away != null ? away.getColor() : null)
                .leagueId(match.getCompetitionId())
                .seasonYear(match.getSeasonYear())
                .roundNumber(match.getRoundNumber())
                .matchDate(match.getMatchDate())
                .played(match.getPlayed())
                .homeScore(match.getHomeScore())
                .awayScore(match.getAwayScore())
                .homeQuarterScores(match.getHomeQuarterScores())
                .awayQuarterScores(match.getAwayQuarterScores())
                .events(events)
                .homePlayerStats(homePlayerStats)
                .awayPlayerStats(awayPlayerStats)
                .build();
    }

    private List<BbPlayerGameStatsDTO> parsePlayerStats(String statsString) {
        if (statsString == null || statsString.isEmpty()) {
            return List.of();
        }
        return List.of(statsString.split(";"))
                .stream()
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    String[] parts = s.split(",");
                    if (parts.length < 17) return null;
                    return BbPlayerGameStatsDTO.builder()
                            .playerId(Long.parseLong(parts[0]))
                            .playerName(parts[1])
                            .position(parts[2])
                            .minutes(Integer.parseInt(parts[3]))
                            .points(Integer.parseInt(parts[4]))
                            .rebounds(Integer.parseInt(parts[5]))
                            .assists(Integer.parseInt(parts[6]))
                            .steals(Integer.parseInt(parts[7]))
                            .blocks(Integer.parseInt(parts[8]))
                            .turnovers(Integer.parseInt(parts[9]))
                            .fouls(Integer.parseInt(parts[10]))
                            .twoPtMade(Integer.parseInt(parts[11]))
                            .twoPtAttempted(Integer.parseInt(parts[12]))
                            .threePtMade(Integer.parseInt(parts[13]))
                            .threePtAttempted(Integer.parseInt(parts[14]))
                            .ftMade(Integer.parseInt(parts[15]))
                            .ftAttempted(Integer.parseInt(parts[16]))
                            .build();
                })
                .filter(dto -> dto != null)
                .collect(Collectors.toList());
    }

    private BbMatchFixtureDTO toFixtureDTO(BbMatchFixture fixture) {
        BbMatchFixtureDTO dto = BbMatchFixtureDTO.builder()
                .id(fixture.getId())
                .homeTeamId(fixture.getHomeTeam().getId())
                .homeTeamName(fixture.getHomeTeam().getName())
                .homeTeamShortName(fixture.getHomeTeam().getShortName())
                .awayTeamId(fixture.getAwayTeam().getId())
                .awayTeamName(fixture.getAwayTeam().getName())
                .awayTeamShortName(fixture.getAwayTeam().getShortName())
                .competitionId(fixture.getCompetition().getId())
                .seasonYear(fixture.getSeasonYear())
                .roundNumber(fixture.getRoundNumber())
                .matchDate(fixture.getMatchDate())
                .played(fixture.getPlayed())
                .build();
        if (fixture.getPlayed() && fixture.getPlayedMatch() != null) {
            BbMatch match = fixture.getPlayedMatch();
            dto.setHomeScore(match.getHomeScore());
            dto.setAwayScore(match.getAwayScore());
            dto.setHomeQuarterScores(match.getHomeQuarterScores());
            dto.setAwayQuarterScores(match.getAwayQuarterScores());
            dto.setPlayedMatchId(match.getId());
        }
        return dto;
    }
}
