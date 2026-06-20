package org.example.americanfootballmanager.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.americanfootballmanager.dto.*;
import org.example.americanfootballmanager.model.*;
import org.example.americanfootballmanager.repository.*;
import org.example.americanfootballmanager.engine.AfMatchResult;
import org.example.americanfootballmanager.service.*;
import org.example.commonmanager.model.CommonCompetition;
import org.example.commonmanager.repository.CommonCompetitionRepository;
import org.example.commonmanager.model.User;
import org.example.commonmanager.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/af")
@Slf4j
public class AfController {

    private final AfTeamRepository teamRepository;
    private final AfPlayerService playerService;
    private final AfLeagueService leagueService;
    private final AfMatchRepository matchRepository;
    private final AfMatchSimulationService simulationService;
    private final AfMatchFixtureRepository matchFixtureRepository;
    private final AfPlayerRepository playerRepository;
    private final AfPlayerSeasonStatsRepository playerSeasonStatsRepository;
    private final AfCompetitionEntryRepository competitionEntryRepository;
    private final UserRepository userRepository;
    private final AfDataInitializer afDataInitializer;
    private final CommonCompetitionRepository commonCompetitionRepository;

    public AfController(AfTeamRepository teamRepository,
                         AfPlayerService playerService,
                         AfLeagueService leagueService,
                         AfMatchRepository matchRepository,
                         AfMatchSimulationService simulationService,
                         AfMatchFixtureRepository matchFixtureRepository,
                         AfPlayerRepository playerRepository,
                         AfPlayerSeasonStatsRepository playerSeasonStatsRepository,
                         AfCompetitionEntryRepository competitionEntryRepository,
                         UserRepository userRepository,
                         AfDataInitializer afDataInitializer,
                         CommonCompetitionRepository commonCompetitionRepository) {
        this.teamRepository = teamRepository;
        this.playerService = playerService;
        this.leagueService = leagueService;
        this.matchRepository = matchRepository;
        this.simulationService = simulationService;
        this.matchFixtureRepository = matchFixtureRepository;
        this.playerRepository = playerRepository;
        this.playerSeasonStatsRepository = playerSeasonStatsRepository;
        this.competitionEntryRepository = competitionEntryRepository;
        this.userRepository = userRepository;
        this.afDataInitializer = afDataInitializer;
        this.commonCompetitionRepository = commonCompetitionRepository;
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
    public ResponseEntity<List<AfLeagueTableEntry>> getLeagueTable(
            @PathVariable Long id,
            @RequestParam(defaultValue = "2025") Integer seasonYear) {
        List<AfLeagueTableEntry> table = leagueService.calculateTable(id, seasonYear);
        return ResponseEntity.ok(table);
    }

    // ─── Teams ───

    @GetMapping("/teams")
    public List<AfTeamDTO> getAllTeams() {
        return teamRepository.findAll().stream()
                .map(this::toTeamDTO)
                .collect(Collectors.toList());
    }

    @GetMapping("/teams/{id}")
    public ResponseEntity<AfTeamDTO> getTeam(@PathVariable Long id) {
        return teamRepository.findById(id)
                .map(team -> {
                    List<AfPlayerDTO> players = playerService.getTeamPlayers(id);
                    AfTeamDTO dto = toTeamDTO(team);
                    dto.setPlayers(players);
                    return ResponseEntity.ok(dto);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/teams/{id}/players")
    public ResponseEntity<List<AfPlayerDTO>> getTeamPlayers(@PathVariable Long id) {
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
        if (userOpt.isEmpty() || userOpt.get().getAmericanFootballTeam() == null) {
            return ResponseEntity.ok(Map.of(
                    "hasTeam", false,
                    "message", "No American Football team assigned. Contact admin."
            ));
        }
        AfTeam team = userOpt.get().getAmericanFootballTeam();
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
    public ResponseEntity<AfPlayerDTO> getPlayer(@PathVariable Long id) {
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
            totals.merge("touchdowns", s.getTouchdowns(), (a, b) -> (int) a + (int) b);
            totals.merge("tackles", s.getTackles(), (a, b) -> (int) a + (int) b);
            totals.merge("interceptions", s.getInterceptions(), (a, b) -> (int) a + (int) b);
            totals.merge("sacks", s.getSacks(), (a, b) -> (int) a + (int) b);
            totals.merge("passingYards", s.getPassingYards(), (a, b) -> (int) a + (int) b);
            totals.merge("rushingYards", s.getRushingYards(), (a, b) -> (int) a + (int) b);
            totals.merge("receivingYards", s.getReceivingYards(), (a, b) -> (int) a + (int) b);
        }
        resp.put("totals", totals);
        return ResponseEntity.ok(resp);
    }

    // ─── Stats / League Leaders ───

    @GetMapping("/stats/league/{competitionId}")
    public ResponseEntity<AfLeagueLeadersDTO> getLeagueLeaders(
            @PathVariable Long competitionId,
            @RequestParam(defaultValue = "10") int limit) {
        AfLeagueLeadersDTO leaders = AfLeagueLeadersDTO.builder()
                .topPassingYards(playerService.getTopPassingYards(competitionId, limit))
                .topRushingYards(playerService.getTopRushingYards(competitionId, limit))
                .topReceivingYards(playerService.getTopReceivingYards(competitionId, limit))
                .topTackles(playerService.getTopTackles(competitionId, limit))
                .topInterceptions(playerService.getTopInterceptions(competitionId, limit))
                .topSacks(playerService.getTopSacks(competitionId, limit))
                .build();
        return ResponseEntity.ok(leaders);
    }

    // ─── Matches ───

    @GetMapping("/matches")
    public List<AfMatchDTO> getAllMatches(
            @RequestParam(required = false) Long competitionId,
            @RequestParam(required = false) Integer seasonYear) {
        List<AfMatch> matches;
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
    public ResponseEntity<AfMatchDTO> getMatch(@PathVariable Long id) {
        return matchRepository.findById(id)
                .map(m -> ResponseEntity.ok(toMatchDTO(m)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/matches/team/{teamId}")
    public List<AfMatchDTO> getTeamMatches(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "2025") Integer seasonYear) {
        return matchRepository.findByTeamIdAndSeasonYear(teamId, seasonYear)
                .stream()
                .map(this::toMatchDTO)
                .collect(Collectors.toList());
    }

    @GetMapping("/matches/team/{teamId}/recent")
    public List<AfMatchDTO> getRecentTeamMatches(
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
    public List<AfMatchFixtureDTO> getTeamFixtures(@PathVariable Long teamId) {
        return matchFixtureRepository.findByHomeTeamIdOrAwayTeamIdOrderByRoundNumber(teamId, teamId)
                .stream()
                .map(this::toFixtureDTO)
                .collect(Collectors.toList());
    }

    @GetMapping("/fixtures")
    public List<AfMatchFixtureDTO> getFixtures(
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
        List<AfMatchFixture> allFixtures = matchFixtureRepository
                .findBySeasonYearAndCompetitionIdOrderByRoundNumber(seasonYear, competitionId);

        int totalRounds = allFixtures.stream()
                .mapToInt(AfMatchFixture::getRoundNumber)
                .max().orElse(18);

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
        List<AfMatchFixture> fixtures = matchFixtureRepository
                .findBySeasonYearAndCompetitionIdOrderByRoundNumber(seasonYear, competitionId)
                .stream()
                .filter(f -> f.getRoundNumber() == roundNumber && !f.getPlayed())
                .collect(Collectors.toList());

        List<Long> playedIds = new ArrayList<>();
        for (AfMatchFixture fixture : fixtures) {
            try {
                simulationService.simulateFixture(fixture.getId());
                playedIds.add(fixture.getId());
            } catch (Exception e) {
                log.error("Failed to simulate fixture {}: {}", fixture.getId(), e.getMessage());
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
    public ResponseEntity<Map<String, Object>> resetAmericanFootball() {
        matchFixtureRepository.resetAllFixtures();
        matchFixtureRepository.flush();

        playerSeasonStatsRepository.deleteAllInBatch();
        matchRepository.deleteAllInBatch();

        competitionEntryRepository.resetAllEntries();

        playerRepository.resetAllPlayers();

        userRepository.findByUsernameOrEmail("velibor@example.com").ifPresent(user -> {
            if (user.getAmericanFootballTeam() == null) {
                teamRepository.findByName("AF Omladinac").ifPresent(user::setAmericanFootballTeam);
                userRepository.save(user);
            }
        });

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("message", "All American Football data reset.");
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/init")
    public ResponseEntity<Map<String, Object>> initAmericanFootball(Authentication auth) {
        boolean needsInit = commonCompetitionRepository.findBySport("AMERICAN_FOOTBALL").size() == 0;
        if (needsInit) {
            afDataInitializer.initAmericanFootballData();
        }

        // Ensure the current user has a team
        String email = auth.getName();
        userRepository.findByUsernameOrEmail(email).ifPresent(user -> {
            if (user.getAmericanFootballTeam() == null) {
                teamRepository.findByName("AF Omladinac").ifPresent(team -> {
                    user.setAmericanFootballTeam(team);
                    userRepository.save(user);
                    log.info("Assigned AF Omladinac to user {}", email);
                });
            }
        });

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("message", needsInit ? "American Football data initialized." : "Data already exists. CTeam assigned if needed.");
        return ResponseEntity.ok(resp);
    }

    // ─── Simulation ───

    @PostMapping("/matches/simulate")
    public ResponseEntity<AfMatchResult> simulateMatch(
            @RequestParam Long homeTeamId,
            @RequestParam Long awayTeamId) {
        try {
            AfMatchResult result = simulationService.simulateAndSave(homeTeamId, awayTeamId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/fixtures/{fixtureId}/play")
    public ResponseEntity<AfMatchResult> playFixture(@PathVariable Long fixtureId) {
        try {
            AfMatchResult result = simulationService.simulateFixture(fixtureId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to play fixture {}: {}", fixtureId, e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    // ─── DTO Mappers ───

    private AfTeamDTO toTeamDTO(AfTeam team) {
        return AfTeamDTO.builder()
                .id(team.getId())
                .name(team.getName())
                .shortName(team.getShortName())
                .city(team.getCity())
                .stadium(team.getStadiumName())
                .stadiumCapacity(team.getStadiumCapacity())
                .color(team.getColor())
                .averageOverall(team.getAverageOverall())
                .build();
    }

    private AfMatchDTO toMatchDTO(AfMatch match) {
        AfTeam home = match.getHomeTeam();
        AfTeam away = match.getAwayTeam();

        List<String> events = match.getEvents() != null && !match.getEvents().isEmpty()
                ? List.of(match.getEvents().split("\\|\\|"))
                : List.of();

        List<AfPlayerGameStatsDTO> homePlayerStats = parsePlayerStats(match.getHomePlayerStats());
        List<AfPlayerGameStatsDTO> awayPlayerStats = parsePlayerStats(match.getAwayPlayerStats());

        return AfMatchDTO.builder()
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

    private List<AfPlayerGameStatsDTO> parsePlayerStats(String statsString) {
        if (statsString == null || statsString.isEmpty()) {
            return List.of();
        }
        return List.of(statsString.split(";"))
                .stream()
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    String[] parts = s.split(",");
                    if (parts.length < 18) return null;
                    return AfPlayerGameStatsDTO.builder()
                            .playerId(Long.parseLong(parts[0]))
                            .playerName(parts[1])
                            .position(parts[2])
                            .minutes(Integer.parseInt(parts[3]))
                            .touchdowns(Integer.parseInt(parts[4]))
                            .fieldGoalsMade(Integer.parseInt(parts[5]))
                            .fieldGoalsAttempted(Integer.parseInt(parts[6]))
                            .tackles(Integer.parseInt(parts[7]))
                            .interceptions(Integer.parseInt(parts[8]))
                            .sacks(Integer.parseInt(parts[9]))
                            .passingYards(Integer.parseInt(parts[10]))
                            .rushingYards(Integer.parseInt(parts[11]))
                            .receivingYards(Integer.parseInt(parts[12]))
                            .passingTouchdowns(Integer.parseInt(parts[13]))
                            .rushingTouchdowns(Integer.parseInt(parts[14]))
                            .receivingTouchdowns(Integer.parseInt(parts[15]))
                            .twoPointConversions(Integer.parseInt(parts[16]))
                            .fumbles(Integer.parseInt(parts[17]))
                            .build();
                })
                .filter(dto -> dto != null)
                .collect(Collectors.toList());
    }

    private AfMatchFixtureDTO toFixtureDTO(AfMatchFixture fixture) {
        AfMatchFixtureDTO dto = AfMatchFixtureDTO.builder()
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
            AfMatch match = fixture.getPlayedMatch();
            dto.setHomeScore(match.getHomeScore());
            dto.setAwayScore(match.getAwayScore());
            dto.setHomeQuarterScores(match.getHomeQuarterScores());
            dto.setAwayQuarterScores(match.getAwayQuarterScores());
            dto.setPlayedMatchId(match.getId());
        }
        return dto;
    }
}
