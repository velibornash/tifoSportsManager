package org.example.footballmanager.cleanSheet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.cleanSheet.engine.CSLeagueManager;
import org.example.footballmanager.cleanSheet.engine.CSMatchSimulator;
import org.example.footballmanager.cleanSheet.engine.CSMatchReportGenerator;
import org.example.footballmanager.cleanSheet.engine.CSInboxGenerator;
import org.example.footballmanager.cleanSheet.model.*;
import org.example.footballmanager.cleanSheet.state.CleanSheetGameState;
import org.example.footballmanager.model.*;
import org.example.footballmanager.repository.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CleanSheetService {

    private final CompetitionRepository competitionRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final PlayerRepository playerRepository;

    private final Map<Long, CleanSheetGameState> activeGames = new ConcurrentHashMap<>();
    private final CSMatchSimulator matchSimulator = new CSMatchSimulator();
    private final CSLeagueManager leagueManager = new CSLeagueManager();
    private final CSMatchReportGenerator reportGenerator = new CSMatchReportGenerator();
    private final CSInboxGenerator inboxGenerator = new CSInboxGenerator();
    private final Random random = new Random();
    private final AtomicLong generatedTeamId = new AtomicLong(-10_000);
    private final AtomicLong generatedPlayerId = new AtomicLong(-500_000);

    /**
     * Pokrece novu igru — cita iz baze JEDNOM, mapira u CS objekte,
     * generise raspored, i cuva u memoriji.
     */
    public CleanSheetGameState startNewGame(Long userId, Team userTeamEntity) {
        log.info("Starting new Clean Sheet game for user {} with team {}", userId, userTeamEntity.getName());

        // 1. Nadji ligu u kojoj je korisnikov tim
        Competition league = userTeamEntity.getCompetition();
        if (league == null) {
            league = competitionRepository.findById(1L)
                    .orElseThrow(() -> new RuntimeException("League not found"));
        }

        int seasonYear = Calendar.getInstance().get(Calendar.YEAR);
        Competition finalLeague = league;
        SeasonCompetition sc = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, seasonYear)
                .orElseGet(() -> seasonCompetitionRepository
                        .findByCompetitionAndSeasonYear(finalLeague, seasonYear - 1)
                        .orElseThrow(() -> new RuntimeException("SeasonCompetition not found")));

        // 2. Ucitaj sve timove u ligi
        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(sc);
        List<Team> teamsInLeague = entries.stream()
                .map(CompetitionEntry::getTeam)
                .filter(Objects::nonNull)
                .toList();

        // 3. Mapiraj timove
        List<CSTeam> csTeams = teamsInLeague.stream()
                .map(CSMapper::toCSTeam)
                .toList();

        CSTeam userTeam = csTeams.stream()
                .filter(t -> t.getId().equals(userTeamEntity.getId()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("User team not found in league"));

        // 4. Mapiraj igrace svih timova
        Map<Long, List<CSPlayer>> allRosters = new HashMap<>();
        for (Team team : teamsInLeague) {
            List<Player> players = playerRepository.findByTeam(team);
            allRosters.put(team.getId(), CSMapper.toCSPlayers(players));
        }

        List<CSPlayer> userRoster = allRosters.getOrDefault(userTeam.getId(), new ArrayList<>());

        // 5. Kreiraj GameState
        CleanSheetGameState state = new CleanSheetGameState();
        state.setUserId(userId);
        state.setSeasonYear(sc.getSeasonYear());
        state.setLeagueName(league.getName() != null ? league.getName() : "League");
        state.setCurrentRound(1);
        state.setUserTeam(userTeam);
        state.setRoster(userRoster);
        state.setAllTeams(new ArrayList<>(csTeams));
        state.setAllTeamRosters(allRosters);
        state.setTactics(CSTactics.builder().build());
        state.getTactics().setStarterIds(
                userRoster.stream()
                        .sorted(Comparator.comparingInt(CSPlayer::getRating).reversed())
                        .limit(11)
                        .map(CSPlayer::getId)
                        .toList()
        );
        state.getTactics().setBenchIds(
                userRoster.stream()
                        .sorted(Comparator.comparingInt(CSPlayer::getRating).reversed())
                        .skip(11)
                        .limit(7)
                        .map(CSPlayer::getId)
                        .toList()
        );

        // 6. Inicijalizuj tabelu (sve na nuli — nova sezona)
        state.setLeagueTable(leagueManager.initializeTable(csTeams));

        // 7. Generisi raspored
        // 7. Generate derby rivalries before schedule
        Map<Long, Set<Long>> derbyRivalries = leagueManager.generateDerbyRivalries(csTeams);
        // Generate schedule
        List<CSFixture> schedule = leagueManager.generateSchedule(csTeams);
        // Apply derby flags
        leagueManager.applyDerbyFlags(schedule, derbyRivalries);
        state.setSchedule(schedule);

        // 8. Welcome poruka
        state.addInboxMessage("welcome", buildWelcomeMessage(state, userTeam, csTeams.size()));

        // 9. Sacuvaj
        activeGames.put(userId, state);
        log.info("Clean Sheet game started: {} teams, {} rounds, {} players for user team",
                csTeams.size(), state.getTotalRounds(), userRoster.size());

        return state;
    }

    /**
     * Odigraj sledece kolo — simulira korisnikov mec (puna simulacija)
     * i sve ostale meceve u kolu (brza simulacija).
     */
    public Map<String, Object> advanceRound(Long userId) {
        CleanSheetGameState state = getStateOrThrow(userId);

        if (state.isSeasonOver()) {
            rolloverToNextSeason(state);
            Map<String, Object> response = new HashMap<>();
            response.put("seasonRestarted", true);
            response.put("seasonOver", false);
            response.put("round", 0);
            response.put("table", state.getLeagueTable());
            response.put("roster", state.getRoster());
            response.put("seasonYear", state.getSeasonYear());
            response.put("leagueName", state.getLeagueName());
            response.put("seasonHistory", state.getSeasonHistory());
            return response;
        }

        int round = state.getCurrentRound();

        // Nadji korisnikov mec u ovom kolu
        CSFixture userFixture = state.getSchedule().stream()
                .filter(f -> f.getRound() == round)
                .filter(f -> f.getHomeTeamId().equals(state.getUserTeam().getId())
                        || f.getAwayTeamId().equals(state.getUserTeam().getId()))
                .findFirst()
                .orElse(null);

        CSMatchResult userResult = null;
        if (userFixture != null && !userFixture.isPlayed()) {
            CSTeam home = findTeam(state, userFixture.getHomeTeamId());
            CSTeam away = findTeam(state, userFixture.getAwayTeamId());

            List<CSPlayer> homePlayers = state.getAllTeamRosters()
                    .getOrDefault(home.getId(), List.of());
            List<CSPlayer> awayPlayers = state.getAllTeamRosters()
                    .getOrDefault(away.getId(), List.of());

            // Korisnikova taktika se koristi za njegov tim
            CSTactics homeTactics = home.getId().equals(state.getUserTeam().getId())
                    ? state.getTactics() : CSTactics.builder().build();
            CSTactics awayTactics = away.getId().equals(state.getUserTeam().getId())
                    ? state.getTactics() : CSTactics.builder().build();

            List<CSPlayer> homeStarters = matchSimulator.pickStartingEleven(
                    homePlayers,
                    home.getId().equals(state.getUserTeam().getId()) ? state.getTactics().getStarterIds() : List.of()
            );
            List<CSPlayer> homeBench = matchSimulator.pickBenchPlayers(
                    homePlayers,
                    homeStarters,
                    home.getId().equals(state.getUserTeam().getId()) ? state.getTactics().getBenchIds() : List.of()
            );
            List<CSPlayer> awayStarters = matchSimulator.pickStartingEleven(
                    awayPlayers,
                    away.getId().equals(state.getUserTeam().getId()) ? state.getTactics().getStarterIds() : List.of()
            );
            List<CSPlayer> awayBench = matchSimulator.pickBenchPlayers(
                    awayPlayers,
                    awayStarters,
                    away.getId().equals(state.getUserTeam().getId()) ? state.getTactics().getBenchIds() : List.of()
            );

            userResult = matchSimulator.simulate(home, homeStarters, homeBench, away, awayStarters, awayBench,
                    homeTactics, awayTactics, round);
            if (userFixture != null) {
                userResult.setDerby(userFixture.isDerby());
            }

            userFixture.setPlayed(true);
            userFixture.setResult(userResult);

            state.getMatchHistory().add(userResult);
            String detailedReport = reportGenerator.buildDetailedReport(userResult);
            userResult.setReport(detailedReport);
            state.addInboxMessage("match", buildMatchInboxText(round, userResult, state.getUserTeam().getId()));
            state.addInboxMessage("report", detailedReport);
            
            // Update season stats and club mood
            state.updateSeasonStats(userResult);
            int leaguePosition = findLeaguePosition(state, state.getUserTeam().getId());
            state.updateClubMood(userResult, leaguePosition, state.getAllTeams().size());
            // Calculate financial health and update mood label
            int financialHealth = calculateFinancialHealth(state);
            state.getClubMood().setFinancialHealth(financialHealth);
            updateMoodLabel(state);
            
            // Determine result type for additional messages
            boolean userHome = Objects.equals(userResult.getHomeTeamId(), state.getUserTeam().getId());
            int goalsFor = userHome ? userResult.getHomeGoals() : userResult.getAwayGoals();
            int goalsAgainst = userHome ? userResult.getAwayGoals() : userResult.getHomeGoals();
            String resultType = goalsFor > goalsAgainst ? "WIN" : goalsFor < goalsAgainst ? "LOSS" : "DRAW";
            
            // Generate rich inbox messages
            generatePostMatchInbox(state, userResult, resultType, round);
        }

        // Simuliraj ostale meceve
        List<CSMatchResult> allResults = leagueManager.simulateRound(state, round, userResult);
        allResults.forEach(result -> {
            if (result != null && (result.getReport() == null || result.getReport().isBlank())) {
                result.setReport(reportGenerator.buildDetailedReport(result));
            }
        });
        state.addInboxMessage("round-report", reportGenerator.buildRoundReport(round, allResults, state.getUserTeam().getId()));
        generateInternationalInbox(state, round);
        generateRumorInbox(state, round);
        
        // Generate periodic special messages
        generatePeriodicInbox(state, round);

        // Oporavi fatigue izmedju kola
        recoverFatigueBetweenRounds(state);

        state.setCurrentRound(round + 1);

        // Ako je sezona gotova
        if (state.isSeasonOver()) {
            CSTableEntry champion = state.getLeagueTable().get(0);
            state.addInboxMessage("info", buildSeasonFinishMessage(state, champion));
            // Generate end of season board assessment
            state.addInboxMessage("board", inboxGenerator.generateBoardMeeting(state, "season_end"));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("userMatch", userResult);
        response.put("allResults", allResults);
        response.put("round", round);
        response.put("table", state.getLeagueTable());
        response.put("seasonOver", state.isSeasonOver());
        // Return updated roster so frontend can refresh goals/assists
        response.put("roster", state.getRoster());
        response.put("leagueName", state.getLeagueName());
        // Include new data
        response.put("clubMood", state.getClubMood());
        response.put("seasonStats", state.getSeasonStats());
        return response;
    }
    
    /**
     * Generate post-match inbox messages (press conference, fan reaction, media)
     */
    private void generatePostMatchInbox(CleanSheetGameState state, CSMatchResult result, String resultType, int round) {
        // Post-match press conference (always)
        state.addInboxMessage("press", inboxGenerator.generatePostMatchPressConference(state, result));
        
        // Fan reaction (70% chance)
        if (random.nextDouble() < 0.70) {
            state.addInboxMessage("fans", inboxGenerator.generateFanReaction(state, result, resultType));
        }
        
        // Media headlines (60% chance, higher for big results)
        boolean bigResult = Math.abs(result.getHomeGoals() - result.getAwayGoals()) >= 3;
        if (random.nextDouble() < (bigResult ? 0.85 : 0.60)) {
            state.addInboxMessage("media", inboxGenerator.generateMediaHeadlines(state, result, resultType));
        }
        
        // Board message on poor/good runs
        CSSeasonStats stats = state.getSeasonStats();
        if (stats != null) {
            if (stats.getCurrentLossStreak() >= 3) {
                state.addInboxMessage("board", inboxGenerator.generateBoardMeeting(state, "poor_run"));
            } else if (stats.getCurrentWinStreak() >= 4) {
                state.addInboxMessage("board", inboxGenerator.generateBoardMeeting(state, "good_run"));
            }
        }
        // Derby match banter
        if (result.isDerby()) {
            state.addInboxMessage("derby", inboxGenerator.generatePostMatchDerbyBanter(result, resultType));
        }
    }
    
    /**
     * Generate periodic inbox messages (scout reports, youth academy, etc.)
     */
    private void generatePeriodicInbox(CleanSheetGameState state, int round) {
        int totalRounds = state.getTotalRounds();
        
        // Mid-season board review
        if (round == totalRounds / 2) {
            state.addInboxMessage("board", inboxGenerator.generateBoardMeeting(state, "mid_season"));
        }
        
        // Scout report (every 3-5 rounds, 40% chance)
        if (round % (3 + random.nextInt(3)) == 0 && random.nextDouble() < 0.40) {
            String scoutReport = inboxGenerator.generateScoutReport(state);
            if (scoutReport != null) {
                state.addInboxMessage("scout", scoutReport);
            }
        }
        
        // Youth academy update (every 5-7 rounds, 35% chance)
        if (round % (5 + random.nextInt(3)) == 0 && random.nextDouble() < 0.35) {
            state.addInboxMessage("youth", inboxGenerator.generateYouthAcademyUpdate(state));
        }
        
        // Pre-match press conference for next fixture (30% chance)
        if (random.nextDouble() < 0.30) {
            CSFixture nextFixture = state.getSchedule().stream()
                    .filter(f -> f.getRound() == round + 1)
                    .filter(f -> f.getHomeTeamId().equals(state.getUserTeam().getId())
                            || f.getAwayTeamId().equals(state.getUserTeam().getId()))
                    .findFirst()
                    .orElse(null);
            if (nextFixture != null) {
                state.addInboxMessage("press", inboxGenerator.generatePreMatchPressConference(state, nextFixture));
            }
        }
    }
    
    /**
     * Find league position for a team
     */
    private int findLeaguePosition(CleanSheetGameState state, Long teamId) {
        List<CSTableEntry> table = state.getLeagueTable();
        for (int i = 0; i < table.size(); i++) {
            if (Objects.equals(table.get(i).getTeamId(), teamId)) {
                return i + 1;
            }
        }
        return table.size();
    }

    public CleanSheetGameState getState(Long userId) {
        return activeGames.get(userId);
    }

    public List<CSTableEntry> getTable(Long userId) {
        return getStateOrThrow(userId).getLeagueTable();
    }

    public List<CSPlayer> getPlayers(Long userId) {
        return getStateOrThrow(userId).getRoster();
    }

    public List<CSFixture> getSchedule(Long userId) {
        return getStateOrThrow(userId).getSchedule();
    }

    public List<CSInboxMessage> getInbox(Long userId) {
        return getStateOrThrow(userId).getInbox();
    }

    public CSTactics changeTactics(Long userId, String formation, String style) {
        CleanSheetGameState state = getStateOrThrow(userId);
        CSTactics tactics = state.getTactics();

        if (formation != null && !formation.isBlank()) {
            tactics.setFormation(formation);
        }
        if (style != null && !style.isBlank()) {
            try {
                tactics.setStyle(CSPlayStyle.valueOf(style.toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown play style: {}", style);
            }
        }
        state.addInboxMessage("info",
                "Tactics updated: " + tactics.getFormation() + " / " + tactics.getStyle());
        return tactics;
    }

    public CSTactics changeLineup(Long userId, List<Long> starterIds, List<Long> benchIds) {
        CleanSheetGameState state = getStateOrThrow(userId);
        Set<Long> rosterIds = state.getRoster().stream().map(CSPlayer::getId).collect(Collectors.toSet());
        List<Long> validIds = (starterIds == null ? List.<Long>of() : starterIds).stream()
                .filter(Objects::nonNull)
                .filter(rosterIds::contains)
                .distinct()
                .limit(11)
                .toList();
        Set<Long> used = new HashSet<>(validIds);
        List<Long> validBenchIds = (benchIds == null ? List.<Long>of() : benchIds).stream()
                .filter(Objects::nonNull)
                .filter(rosterIds::contains)
                .filter(id -> !used.contains(id))
                .distinct()
                .limit(7)
                .toList();
        state.getTactics().setStarterIds(validIds);
        state.getTactics().setBenchIds(validBenchIds);
        state.addInboxMessage("info", "Starting XI and bench updated.");
        return state.getTactics();
    }

    public boolean hasActiveGame(Long userId) {
        return activeGames.containsKey(userId);
    }

    public void resetGame(Long userId) {
        activeGames.remove(userId);
    }

    private CleanSheetGameState getStateOrThrow(Long userId) {
        CleanSheetGameState state = activeGames.get(userId);
        if (state == null) {
            throw new RuntimeException("No active Clean Sheet game for user " + userId);
        }
        return state;
    }

    private CSTeam findTeam(CleanSheetGameState state, Long teamId) {
        return state.getAllTeams().stream()
                .filter(t -> t.getId().equals(teamId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Team not found: " + teamId));
    }

    public Map<String, Object> getTeamInfo(Long userId, Long teamId) {
        CleanSheetGameState state = getStateOrThrow(userId);
        CSTeam team = state.getAllTeams().stream()
                .filter(t -> t.getId().equals(teamId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Team not found: " + teamId));
        List<CSPlayer> roster = state.getAllTeamRosters()
                .getOrDefault(teamId, List.of());
        Map<String, Object> result = new HashMap<>();
        result.put("team", team);
        result.put("roster", roster);
        return result;
    }

    public List<Map<String, Object>> getTopScorers(Long userId) {
        CleanSheetGameState state = getStateOrThrow(userId);
        return state.getAllTeamRosters().values().stream()
                .flatMap(List::stream)
                .filter(p -> p.getGoals() > 0)
                .sorted(Comparator.comparingInt(CSPlayer::getGoals).reversed())
                .limit(20)
                .map(p -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", p.getName());
                    m.put("goals", p.getGoals());
                    m.put("position", p.getPosition());
                    m.put("playerId", p.getId());
                    // find team name
                    m.put("teamName", findTeamNameForPlayer(state, p.getId()));
                    return m;
                })
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getTopAssists(Long userId) {
        CleanSheetGameState state = getStateOrThrow(userId);
        return state.getAllTeamRosters().values().stream()
                .flatMap(List::stream)
                .filter(p -> p.getAssists() > 0)
                .sorted(Comparator.comparingInt(CSPlayer::getAssists).reversed())
                .limit(20)
                .map(p -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", p.getName());
                    m.put("assists", p.getAssists());
                    m.put("position", p.getPosition());
                    m.put("playerId", p.getId());
                    m.put("teamName", findTeamNameForPlayer(state, p.getId()));
                    return m;
                })
                .collect(Collectors.toList());
    }

    private String findTeamNameForPlayer(CleanSheetGameState state, Long playerId) {
        for (var entry : state.getAllTeamRosters().entrySet()) {
            for (CSPlayer p : entry.getValue()) {
                if (p.getId().equals(playerId)) {
                    return state.getAllTeams().stream()
                            .filter(t -> t.getId().equals(entry.getKey()))
                            .map(CSTeam::getName)
                            .findFirst().orElse("?");
                }
            }
        }
        return "?";
    }

    private String buildWelcomeMessage(CleanSheetGameState state, CSTeam userTeam, int teamCount) {
        CSFixture opener = state.getSchedule().stream()
                .filter(f -> f.getRound() == 1)
                .filter(f -> Objects.equals(f.getHomeTeamId(), userTeam.getId()) || Objects.equals(f.getAwayTeamId(), userTeam.getId()))
                .findFirst()
                .orElse(null);

        String openingLine = opener == null
                ? "Opening fixture will be confirmed once the schedule office finishes its work."
                : "Opening fixture: " + opener.getHomeTeamName() + " vs " + opener.getAwayTeamName() + ".";

        return "Chairman's briefing:\n"
                + "You take charge of " + userTeam.getName() + " for the " + state.getSeasonYear() + "/" + (state.getSeasonYear() + 1) + " campaign.\n"
                + state.getLeagueName() + " will be played over " + state.getTotalRounds() + " rounds with " + teamCount + " clubs in the race.\n"
                + "Board expectation: " + buildBoardExpectation(userTeam) + "\n"
                + "Club desk: budget €" + formatMoney(userTeam.getBudget()) + ", reputation " + safeInt(userTeam.getReputation())
                + ", stadium " + safeText(userTeam.getStadiumName(), userTeam.getName() + " Stadium")
                + " (" + safeInt(userTeam.getStadiumCapacity()) + ").\n"
                + openingLine + "\n"
                + pick(
                        "Supporters want a season with substance, discipline and a few memorable afternoons.",
                        "The local press expects a competitive side that can make the division take notice.",
                        "The board room message is simple: build momentum quickly and make home matches count."
                );
    }

    private String buildMatchInboxText(int round, CSMatchResult result, Long userTeamId) {
        boolean userHome = Objects.equals(result.getHomeTeamId(), userTeamId);
        String userTeamName = userHome ? result.getHomeTeamName() : result.getAwayTeamName();
        String opponent = userHome ? result.getAwayTeamName() : result.getHomeTeamName();
        int goalsFor = userHome ? result.getHomeGoals() : result.getAwayGoals();
        int goalsAgainst = userHome ? result.getAwayGoals() : result.getHomeGoals();

        String verdict = goalsFor > goalsAgainst
                ? pick(
                        "A strong result keeps spirits high in the dressing room.",
                        "Three points safely filed away after a professional shift.",
                        "Your side got the job done and the fans leave satisfied."
                )
                : goalsFor == goalsAgainst
                ? pick(
                        "The points were shared after a tight contest.",
                        "Neither side found the final push to turn one point into three.",
                        "A draw felt fair after a match of swings and counter-swings."
                )
                : pick(
                        "A difficult result that will invite questions before the next kickoff.",
                        "Your team comes away empty-handed after a frustrating afternoon.",
                        "There will be work to do on the training ground before the next round."
                );

        String scorersLine = summarizeScorers(result, userTeamName);
        return "Round " + round + " report: " + result.getSummary() + ". "
                + verdict + " Opposition: " + opponent + ". "
                + scorersLine;
    }

    private String buildSeasonFinishMessage(CleanSheetGameState state, CSTableEntry champion) {
        List<CSTableEntry> table = state.getLeagueTable() == null ? List.of() : state.getLeagueTable();
        CSTableEntry userEntry = table.stream()
                .filter(e -> Objects.equals(e.getTeamId(), state.getUserTeam().getId()))
                .findFirst()
                .orElse(null);
        int userPosition = userEntry == null ? -1 : table.indexOf(userEntry) + 1;

        String championLine = Objects.equals(champion.getTeamId(), state.getUserTeam().getId())
                ? "Season finished: you are champions of " + state.getLeagueName() + "."
                : "Season finished: champion is " + champion.getTeamName() + " with " + champion.getPoints() + " points.";

        String userLine = userEntry == null
                ? "Final placing for your club is not available."
                : "Your final position: " + ordinal(userPosition) + " place with " + userEntry.getPoints() + " points and goal difference "
                + userEntry.getGoalsScored() + ":" + userEntry.getGoalsConceded() + ".";

        return championLine + " " + userLine + " "
                + pick(
                        "Press Next Round to open the file for the new season.",
                        "The boardroom is already preparing the next campaign. Press Next Round to continue.",
                        "A new set of fixtures awaits as soon as you advance into the next season."
                );
    }

    private String buildBoardExpectation(CSTeam team) {
        int reputation = safeInt(team.getReputation());
        if (reputation >= 70) {
            return "Push near the title race and make the club relevant at the top end.";
        }
        if (reputation >= 58) {
            return "Finish in the upper half and stay in touch with the promotion conversation.";
        }
        if (reputation >= 46) {
            return "Establish a stable mid-table season and keep home form reliable.";
        }
        return "Stay clear of the relegation fight and build a tougher identity week by week.";
    }

    private String summarizeScorers(CSMatchResult result, String teamName) {
        List<String> scorers = (result.getEvents() == null ? List.<CSMatchEvent>of() : result.getEvents()).stream()
                .filter(e -> e.getEventType() == CSEventType.GOAL)
                .filter(e -> Objects.equals(teamName, e.getTeamName()))
                .map(CSMatchEvent::getPlayerName)
                .filter(Objects::nonNull)
                .toList();
        if (scorers.isEmpty()) {
            return pick(
                    teamName + " could not make the better spells count in front of goal.",
                    "There was no scorer's line to celebrate for " + teamName + ".",
                    teamName + " lacked the final touch when chances appeared."
            );
        }
        return "Scorers: " + String.join(", ", scorers) + ".";
    }

    private String formatMoney(Number value) {
        long amount = value == null ? 0L : value.longValue();
        return String.format(Locale.US, "%,d", amount);
    }

    private int safeInt(Number value) {
        return value == null ? 0 : value.intValue();
    }

    private String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String ordinal(int n) {
        if (n <= 0) return "Unknown";
        if (n % 100 >= 11 && n % 100 <= 13) return n + "th";
        return switch (n % 10) {
            case 1 -> n + "st";
            case 2 -> n + "nd";
            case 3 -> n + "rd";
            default -> n + "th";
        };
    }

    private String pick(String... options) {
        if (options == null || options.length == 0) {
            return "";
        }
        return options[random.nextInt(options.length)];
    }

    private void recoverFatigueBetweenRounds(CleanSheetGameState state) {
        for (List<CSPlayer> roster : state.getAllTeamRosters().values()) {
            for (CSPlayer p : roster) {
                double recovery = 1.0 + new Random().nextDouble() * 1.5;
                p.setFatigue(Math.max(0, p.getFatigue() - recovery));
            }
        }
    }

    private void rolloverToNextSeason(CleanSheetGameState state) {
        List<CSTableEntry> finalTable = new ArrayList<>(state.getLeagueTable());
        if (finalTable.isEmpty()) {
            state.setCurrentRound(1);
            state.setSchedule(leagueManager.generateSchedule(state.getAllTeams()));
            return;
        }

        CSTableEntry champion = finalTable.getFirst();
        CSTableEntry relegated = findWorstNonUser(finalTable, state.getUserTeam().getId(), Set.of());
        CSTableEntry playoff = findWorstNonUser(finalTable, state.getUserTeam().getId(),
                relegated != null ? Set.of(relegated.getTeamId()) : Set.of());

        CSTeam promotedDirect = createGeneratedTeam("FK " + pickPromotedName());
        CSTeam playoffChallenger = createGeneratedTeam("OFK " + pickPromotedName());
        boolean playoffChallengerWins = random.nextDouble() < 0.52;

        List<String> promotedNames = new ArrayList<>();
        promotedNames.add(promotedDirect.getName());
        if (playoffChallengerWins) {
            promotedNames.add(playoffChallenger.getName());
        }

        if (relegated != null) {
            replaceTeamInLeague(state, relegated.getTeamId(), promotedDirect, createGeneratedRoster(promotedDirect.getId()));
        }
        if (playoff != null && playoffChallengerWins) {
            replaceTeamInLeague(state, playoff.getTeamId(), playoffChallenger, createGeneratedRoster(playoffChallenger.getId()));
        }

        CSSeasonRecord record = CSSeasonRecord.builder()
                .seasonYear(state.getSeasonYear())
                .leagueName(state.getLeagueName())
                .champion(champion.getTeamName())
                .relegatedTeam(relegated != null ? relegated.getTeamName() : "n/a")
                .playoffTeam(playoff != null ? playoff.getTeamName() : "n/a")
                .playoffOutcome(playoff == null
                        ? "No playoff"
                        : (playoffChallengerWins ? playoffChallenger.getName() + " won playoff" : playoff.getTeamName() + " stayed in the league"))
                .promotedTeams(promotedNames)
                .build();
        state.getSeasonHistory().add(record);

        state.setSeasonYear(state.getSeasonYear() + 1);
        state.setCurrentRound(1);
        state.setSchedule(leagueManager.generateSchedule(state.getAllTeams()));
        state.setLeagueTable(leagueManager.initializeTable(state.getAllTeams()));
        state.getMatchHistory().clear();

        for (List<CSPlayer> roster : state.getAllTeamRosters().values()) {
            for (CSPlayer p : roster) {
                p.setGoals(0);
                p.setAssists(0);
                p.setForm(5.0);
                p.setFatigue(Math.max(0.5, p.getFatigue() * 0.35));
            }
        }

        state.setRoster(state.getAllTeamRosters().getOrDefault(state.getUserTeam().getId(), List.of()));

        String playoffMsg = playoff == null
                ? "No playoff was required."
                : (playoffChallengerWins
                    ? playoffChallenger.getName() + " beat " + playoff.getTeamName() + " in playoff and got promoted."
                    : playoff.getTeamName() + " survived playoff against " + playoffChallenger.getName() + ".");

        state.addInboxMessage("info",
                "Season transition: champion " + champion.getTeamName() + ". " +
                        (relegated != null ? relegated.getTeamName() + " relegated. " : "") +
                        playoffMsg +
                        " New season " + state.getSeasonYear() + "/" + (state.getSeasonYear() + 1) + " started.");
    }

    private CSTableEntry findWorstNonUser(List<CSTableEntry> table, Long userTeamId, Set<Long> excluded) {
        for (int i = table.size() - 1; i >= 0; i--) {
            CSTableEntry e = table.get(i);
            if (Objects.equals(e.getTeamId(), userTeamId)) continue;
            if (excluded.contains(e.getTeamId())) continue;
            return e;
        }
        return null;
    }

    private void replaceTeamInLeague(CleanSheetGameState state, Long outgoingTeamId, CSTeam incomingTeam, List<CSPlayer> incomingRoster) {
        for (int i = 0; i < state.getAllTeams().size(); i++) {
            if (Objects.equals(state.getAllTeams().get(i).getId(), outgoingTeamId)) {
                state.getAllTeams().set(i, incomingTeam);
                break;
            }
        }
        state.getAllTeamRosters().remove(outgoingTeamId);
        state.getAllTeamRosters().put(incomingTeam.getId(), incomingRoster);
    }

    private CSTeam createGeneratedTeam(String name) {
        return CSTeam.builder()
                .id(generatedTeamId.getAndDecrement())
                .name(name)
                .budget(420_000 + random.nextInt(220_000))
                .reputation(38 + random.nextInt(22))
                .stadiumName(name + " Arena")
                .stadiumCapacity(4000 + random.nextInt(7000))
                .formation("4-4-2")
                .build();
    }

    private List<CSPlayer> createGeneratedRoster(Long teamId) {
        List<CSPlayer> players = new ArrayList<>();
        players.add(createGeneratedPlayer(teamId, "GK", 60));
        for (int i = 0; i < 4; i++) players.add(createGeneratedPlayer(teamId, "DEF", 58));
        for (int i = 0; i < 4; i++) players.add(createGeneratedPlayer(teamId, "MID", 60));
        for (int i = 0; i < 2; i++) players.add(createGeneratedPlayer(teamId, "WNG", 60));
        for (int i = 0; i < 3; i++) players.add(createGeneratedPlayer(teamId, "ATT", 61));
        return players;
    }

    private CSPlayer createGeneratedPlayer(Long teamId, String pos, int baseRating) {
        int rating = Math.max(44, Math.min(78, baseRating + random.nextInt(13) - 6));
        String name = pickFirstName() + " " + pickLastName();
        int age = 18 + random.nextInt(16);
        int stamina = clampSkill(6 + random.nextInt(12));
        int goalkeeper = "GK".equals(pos) ? clampSkill(10 + random.nextInt(10)) : clampSkill(1 + random.nextInt(6));
        int defending = "DEF".equals(pos) ? clampSkill(10 + random.nextInt(10)) : clampSkill(2 + random.nextInt(10));
        int pace = clampSkill(4 + random.nextInt(14));
        int technique = clampSkill(4 + random.nextInt(13));
        int playmaker = clampSkill(4 + random.nextInt(13));
        int passing = clampSkill(4 + random.nextInt(13));
        int shooting = "ATT".equals(pos) ? clampSkill(10 + random.nextInt(10)) : clampSkill(2 + random.nextInt(10));

        return CSPlayer.builder()
                .id(generatedPlayerId.getAndDecrement())
                .name(name)
                .position(pos)
                .age(age)
                .rating(rating)
                .form(4.6 + random.nextDouble() * 1.8)
                .fatigue(0.8 + random.nextDouble() * 1.8)
                .talent(3.0 + random.nextDouble() * 6.0)
                .stamina(stamina)
                .goalkeeper(goalkeeper)
                .defending(defending)
                .pace(pace)
                .technique(technique)
                .playmaker(playmaker)
                .passing(passing)
                .shooting(shooting)
                .goals(0)
                .assists(0)
                .value(80_000 + random.nextInt(420_000))
                .earnings(550 + random.nextInt(3400))
                .height(1.70 + random.nextDouble() * 0.25)
                .weight(64 + random.nextDouble() * 22)
                .build();
    }

    private int clampSkill(int s) {
        return Math.max(1, Math.min(20, s));
    }

    private String pickFirstName() {
        String[] first = {"Marko", "Lazar", "Nikola", "Vuk", "Stefan", "Milan", "Nemanja", "Boris", "Aleksa", "Dusan"};
        return first[random.nextInt(first.length)];
    }

    private String pickLastName() {
        String[] last = {"Jovanovic", "Petrovic", "Milosavljevic", "Nikolic", "Pavlovic", "Ilic", "Markovic", "Stankovic", "Kovacevic", "Mitrovic"};
        return last[random.nextInt(last.length)];
    }

    private String pickPromotedName() {
        String[] names = {"Backa", "Jadar", "Morava", "Drina", "Sloga", "Mladost", "Hajduk", "Jedinstvo", "Buducnost", "Bratstvo"};
        return names[random.nextInt(names.length)] + " " + (10 + random.nextInt(90));
    }

    private void generateInternationalInbox(CleanSheetGameState state, int round) {
        if (random.nextDouble() > 0.55) {
            return;
        }
        String stage = random.nextDouble() < 0.28 ? "World Cup Qualifiers" : "International Friendlies";
        List<String> nations = new ArrayList<>(List.of("Serbia", "Croatia", "Romania", "Bulgaria", "Hungary", "Greece", "Slovakia", "Austria", "Sweden", "Denmark"));
        Collections.shuffle(nations, random);
        if (!nations.contains("Serbia")) nations.add(0, "Serbia");

        List<String> lines = new ArrayList<>();
        lines.add(pick(
                "International desk - " + stage + " (Round " + round + ")",
                stage + " bulletin - match window around Round " + round,
                stage + " watch - results filed during Round " + round
        ));

        int fixtures = 3 + random.nextInt(2);
        for (int i = 0; i < fixtures; i++) {
            String home = (i == 0) ? "Serbia" : nations.get((i * 2) % nations.size());
            String away = nations.get((i * 2 + 1) % nations.size());
            if (home.equals(away)) continue;

            int hg = random.nextInt(4);
            int ag = random.nextInt(4);
            boolean serbiaHome = "Serbia".equals(home);
            boolean serbiaAway = "Serbia".equals(away);
            int serbiaGoals = serbiaHome ? hg : (serbiaAway ? ag : 0);

            String line = home + " " + hg + ":" + ag + " " + away + ". "
                    + pick(
                            "A lively contest.",
                            "Another useful scouting note for the inbox.",
                            "The result drew a fair amount of attention on the wire."
                    );
            if (serbiaGoals > 0) {
                line += " Serbia scorers: " + String.join(", ", pickInternationalScorers(state, serbiaGoals)) + ".";
            }
            lines.add(line);
        }
        lines.add(pick(
                "European scouts continue to circulate after a busy round of international fixtures.",
                "Reports from abroad suggest several players have enhanced their stock.",
                "Clubs across the region will be comparing notes after this window."
        ));
        state.addInboxMessage("international", String.join("\n", lines));
    }

    private List<String> pickInternationalScorers(CleanSheetGameState state, int goals) {
        List<CSPlayer> pool = state.getAllTeamRosters().values().stream()
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .toList();
        if (pool.isEmpty()) {
            return List.of("Unknown");
        }

        List<CSPlayer> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, random);

        List<String> scorers = shuffled.stream()
                .map(CSPlayer::getName)
                .filter(Objects::nonNull)
                .distinct()
                .limit(Math.max(1, goals))
                .collect(Collectors.toCollection(ArrayList::new));

        if (scorers.isEmpty()) {
            scorers.add("Unknown");
        }
        return scorers;
    }

    private void generateRumorInbox(CleanSheetGameState state, int round) {
        if (state.getRoster() == null || state.getRoster().isEmpty()) {
            return;
        }
        if (random.nextDouble() < 0.32) {
            CSPlayer own = state.getRoster().get(random.nextInt(state.getRoster().size()));
            state.addInboxMessage("message",
                    pick(
                            "Agent note: " + own.getName() + " wants to sit down after Round " + round + " and revisit contract terms.",
                            "Representation office message: " + own.getName() + " is open to extension talks following Round " + round + ".",
                            "Private word from the dressing room: " + own.getName() + " expects a contract discussion after the latest fixture."
                    ));
        }

        if (random.nextDouble() < 0.28) {
            CSPlayer target = pickRandomLeaguePlayer(state);
            CSTeam linkedTeam = pickRandomOtherTeam(state, state.getUserTeam().getId());
            if (target != null && linkedTeam != null) {
                state.addInboxMessage("message",
                        pick(
                                "Media rumor: " + linkedTeam.getName() + " are keeping tabs on " + target.getName() + ".",
                                "Scout whisper: " + linkedTeam.getName() + " have asked for fresh reports on " + target.getName() + ".",
                                "Back-page line: " + target.getName() + " has been linked with interest from " + linkedTeam.getName() + "."
                        ));
            }
        }

        if (random.nextDouble() < 0.26) {
            CSPlayer own = state.getRoster().get(random.nextInt(state.getRoster().size()));
            CSTeam linkedTeam = pickRandomOtherTeam(state, state.getUserTeam().getId());
            if (linkedTeam != null) {
                state.addInboxMessage("message",
                        pick(
                                "Journalists link " + own.getName() + " with a possible move to " + linkedTeam.getName() + ".",
                                "Transfer gossip: " + linkedTeam.getName() + " are mentioned alongside " + own.getName() + ".",
                                "Column talk suggests " + linkedTeam.getName() + " may test your resolve for " + own.getName() + "."
                        ));
            }
        }
    }

    private CSTeam pickRandomOtherTeam(CleanSheetGameState state, Long excludedTeamId) {
        List<CSTeam> options = state.getAllTeams().stream()
                .filter(t -> !Objects.equals(t.getId(), excludedTeamId))
                .toList();
        if (options.isEmpty()) return null;
        return options.get(random.nextInt(options.size()));
    }

    private CSPlayer pickRandomLeaguePlayer(CleanSheetGameState state) {
        List<CSPlayer> pool = state.getAllTeamRosters().values().stream()
                .flatMap(List::stream)
                .toList();
        if (pool.isEmpty()) return null;
        return pool.get(random.nextInt(pool.size()));
    }

    private String pickRandomLeaguePlayerName(CleanSheetGameState state) {
        CSPlayer p = pickRandomLeaguePlayer(state);
        return p != null ? p.getName() : null;
    }

    // ========== CLUB MOOD & FINANCIAL HEALTH HELPERS ==========
    
    /**
     * Calculate financial health score (0-100) based on budget size and recent performance.
     * Healthy budget > 1.5M gives 100, struggling < 500k gives low scores.
     */
    private int calculateFinancialHealth(CleanSheetGameState state) {
        CSTeam team = state.getUserTeam();
        if (team == null) return 50;
        
        double budget = team.getBudget();
        CSSeasonStats stats = state.getSeasonStats();
        
        // Base score from budget: 0-2M scale
        // 2M+ = 100 points, 0 = 0 points
        int baseScore = (int) Math.min(100, (budget / 2_000_000.0) * 100);
        
        // Recent performance affects income (wins = more revenue)
        if (stats != null) {
            int winStreak = Math.min(5, stats.getCurrentWinStreak());
            baseScore += winStreak * 5; // +25 max
            
            int lossStreak = Math.min(5, stats.getCurrentLossStreak());
            baseScore -= lossStreak * 8; // -40 max
        }
        
        // Position bonus: top 3 = +10, bottom 3 = -10
        CSTableEntry entry = state.getLeagueTable().stream()
            .filter(t -> t.getTeamId().equals(team.getId()))
            .findFirst()
            .orElse(null);
        if (entry != null) {
            int position = entry.getPosition();
            int totalTeams = state.getLeagueTable().size();
            if (position <= 3) baseScore += 10;
            else if (position > totalTeams - 3) baseScore -= 10;
        }
        
        return clamp(baseScore, 0, 100);
    }
    
    /**
     * Update the moodLabel based on overall club mood scores.
     */
    private void updateMoodLabel(CleanSheetGameState state) {
        CSClubMood mood = state.getClubMood();
        if (mood == null) return;
        
        int avg = (mood.getBoardConfidence() + mood.getFanMood() + 
                   mood.getMediaPressure() + mood.getSquadMorale() + 
                   mood.getFinancialHealth()) / 5;
        
        String label;
        if (avg >= 85) label = "Excellent";
        else if (avg >= 70) label = "Good";
        else if (avg >= 50) label = "Stable";
        else if (avg >= 30) label = "Concerning";
        else label = "Crisis";
        
        mood.setMoodLabel(label);
    }
    
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

}
