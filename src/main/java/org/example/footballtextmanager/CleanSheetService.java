package org.example.footballtextmanager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballtextmanager.engine.CSLeagueManager;
import org.example.footballtextmanager.engine.CSMatchSimulator;
import org.example.footballtextmanager.engine.CSMatchReportGenerator;
import org.example.footballtextmanager.engine.CSInboxGenerator;
import org.example.footballtextmanager.model.*;
import org.example.footballtextmanager.state.CleanSheetGameState;
import org.example.footballtextmanager.repository.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class CleanSheetService {

    private final CSCompetitionRepository CSCompetitionRepository;
    private final CSSeasonCompetitionRepository CSSeasonCompetitionRepository;
    private final CSCompetitionEntryRepository CSCompetitionEntryRepository;
    private final CSPlayerRepository CSPlayerRepository;

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
    public CleanSheetGameState startNewGame(Long userId, CTeam userCTeamEntity) {
        log.info("Starting new Clean Sheet game for user {} with team {}", userId, userCTeamEntity.getName());

        // 1. Nadji ligu u kojoj je korisnikov tim
        CSCompetition league = userCTeamEntity.getCSCompetition();
        if (league == null) {
            league = CSCompetitionRepository.findById(1L)
                    .orElseThrow(() -> new RuntimeException("League not found"));
        }

        int seasonYear = Calendar.getInstance().get(Calendar.YEAR);
        CSCompetition finalLeague = league;
        CSSeasonCompetition sc = CSSeasonCompetitionRepository
                .findByCsCompetitionAndSeasonYear(league, seasonYear)
                .orElseGet(() -> CSSeasonCompetitionRepository
                        .findByCsCompetitionAndSeasonYear(finalLeague, seasonYear - 1)
                        .orElseThrow(() -> new RuntimeException("CSSeasonCompetition not found")));

        // 2. Ucitaj sve timove u ligi
        List<CSCompetitionEntry> entries = CSCompetitionEntryRepository.findByCsSeasonCompetition(sc);
        List<CTeam> teamsInLeague = entries.stream()
                .map(CSCompetitionEntry::getCTeam)
                .filter(Objects::nonNull)
                .toList();

        // 3. Mapiraj timove
        List<CSTeam> csTeams = teamsInLeague.stream()
                .map(CSMapper::toCSTeam)
                .toList();

        CSTeam userTeam = csTeams.stream()
                .filter(t -> t.getId().equals(userCTeamEntity.getId()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("User team not found in league"));

        // 4. Mapiraj igrace svih timova
        Map<Long, List<CSPlayer>> allRosters = new HashMap<>();
        for (CTeam CTeam : teamsInLeague) {
            List<CPlayer> CPlayers = CSPlayerRepository.findByCTeam(CTeam);
            allRosters.put(CTeam.getId(), CSMapper.toCSPlayers(CPlayers));
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
        initializeInternationalCompetition(state);
        initializeTransferMarket(state);
        initializeBoardObjective(state);
        initializeClubDesk(state);

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
            response.put("internationalCompetitionName", state.getInternationalCompetitionName());
            response.put("internationalMatchday", state.getInternationalMatchday());
            response.put("internationalTable", state.getInternationalTable());
            response.put("internationalWindows", state.getInternationalWindows());
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
        updateTransferMarket(state, round);
        updateLeagueWire(state, round, allResults, userResult);
        
        // Generate periodic special messages
        generatePeriodicInbox(state, round);
        generateStrategicClubEvents(state, round);
        applyRoundFinances(state, userFixture, userResult, round);
        runBoardReview(state, round);

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
        response.put("internationalCompetitionName", state.getInternationalCompetitionName());
        response.put("internationalMatchday", state.getInternationalMatchday());
        response.put("internationalTable", state.getInternationalTable());
        response.put("internationalWindows", state.getInternationalWindows());
        response.put("transferMarket", state.getTransferMarket());
        response.put("affiliateClubName", state.getAffiliateClubName());
        response.put("affiliateClubCountry", state.getAffiliateClubCountry());
        response.put("affiliateClubNote", state.getAffiliateClubNote());
        response.put("lastRoundIncome", state.getLastRoundIncome());
        response.put("lastRoundExpenses", state.getLastRoundExpenses());
        response.put("weeklyWageBill", state.getWeeklyWageBill());
        response.put("boardReviewTitle", state.getBoardReviewTitle());
        response.put("boardReviewText", state.getBoardReviewText());
        response.put("notableNews", state.getNotableNews());
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

    private void generateStrategicClubEvents(CleanSheetGameState state, int round) {
        if (state.getAffiliateClubName() == null && round >= 4 && round % 4 == 0 && random.nextDouble() < 0.24) {
            String[] clubs = {
                    "Manchester City|England", "Arsenal|England", "Liverpool|England", "Chelsea|England", "Manchester United|England", "Tottenham|England",
                    "Bayern Munich|Germany",
                    "Real Madrid|Spain", "Barcelona|Spain", "Atletico Madrid|Spain",
                    "Paris Saint-Germain|France", "Marseille|France",
                    "Inter|Italy", "Milan|Italy", "Juventus|Italy", "Napoli|Italy", "Roma|Italy",
                    "Borussia Dortmund|Germany", "Sevilla|Spain", "Lazio|Italy"
            };
            String[] pick = clubs[random.nextInt(clubs.length)].split("\\|");
            state.setAffiliateClubName(pick[0]);
            state.setAffiliateClubCountry(pick[1]);
            state.setAffiliateClubNote("Technical partnership focused on youth exposure, scouting contacts and seasonal development support.");
            state.addInboxMessage("board", "BOARD STRATEGY UPDATE\nAffiliate agreement reached with " + pick[0] + " (" + pick[1] + ").\n\nThe club expects stronger scouting reach, occasional loan opportunities and a small prestige boost from the relationship.");
            state.getUserTeam().setReputation(state.getUserTeam().getReputation() + 2);
        }

        if (state.getAffiliateClubName() != null && round % 5 == 0 && random.nextDouble() < 0.40) {
            CSPlayer loanee = createAffiliateProspect(state);
            state.getRoster().add(loanee);
            state.getAllTeamRosters().put(state.getUserTeam().getId(), state.getRoster());
            state.addInboxMessage("youth", "AFFILIATE TALENT ARRIVAL\n" + state.getAffiliateClubName() + " have sent " + loanee.getName() + " (" + loanee.getPosition() + ", age " + loanee.getAge() + ", rating " + loanee.getRating() + ") for a development spell.\n\nStaff note: the player arrives with higher upside than most of the current academy cycle.");
        }
    }

    private void initializeBoardObjective(CleanSheetGameState state) {
        double reputation = state.getUserTeam().getReputation();
        if (reputation >= 72) {
            state.setBoardObjectiveTitle("Promotion push");
            state.setBoardObjectiveText("Stay in the upper third, push toward automatic promotion places and avoid wasting the wage bill on short-term gambles.");
        } else if (reputation >= 55) {
            state.setBoardObjectiveTitle("Top-half stability");
            state.setBoardObjectiveText("Finish safely in the top half, keep the atmosphere steady and show enough progress to justify further investment.");
        } else {
            state.setBoardObjectiveTitle("Survival first");
            state.setBoardObjectiveText("Stay clear of relegation trouble, keep finances under control and build a younger, more resilient squad.");
        }
    }

    private void initializeClubDesk(CleanSheetGameState state) {
        state.setBoardReviewTitle("Opening brief");
        state.setBoardReviewText("The board expects disciplined spending, a coherent first XI and enough weekly progress to keep the season on a stable track.");
        state.setNotableNews(new ArrayList<>(List.of(
                "League Wire: scouts expect the early rounds to expose who is promotion-ready and who is carrying thin depth.",
                "Board note: budgets are being watched closely across the division after a costly summer window.",
                "Press room: supporters are already circling the first derby dates on the fixture list."
        )));
    }

    private void applyRoundFinances(CleanSheetGameState state, CSFixture userFixture, CSMatchResult userResult, int round) {
        double wageBill = Math.round(state.getRoster().stream().mapToDouble(CSPlayer::getEarnings).sum() * 0.58);
        double stadiumCosts = 3500 + Math.round(state.getUserTeam().getStadiumCapacity() * 0.28);
        double travelCosts = userFixture != null && !Objects.equals(userFixture.getHomeTeamId(), state.getUserTeam().getId()) ? 6500 : 2200;
        double scoutingAndOps = 2600 + random.nextInt(1800);

        double sponsorIncome = 10000 + Math.round(state.getUserTeam().getReputation() * 180);
        double gateIncome = 0;
        if (userFixture != null && Objects.equals(userFixture.getHomeTeamId(), state.getUserTeam().getId())) {
            double fillRatio = Math.min(0.95, 0.42 + state.getUserTeam().getReputation() / 220.0 + (state.getClubMood().getFanMood() / 260.0));
            int attendance = (int) Math.round(state.getUserTeam().getStadiumCapacity() * fillRatio);
            gateIncome = attendance * (8 + random.nextInt(5));
        }
        double performanceBonus = 0;
        if (userResult != null) {
            Long userTeamId = state.getUserTeam().getId();
            boolean userHome = Objects.equals(userResult.getHomeTeamId(), userTeamId);
            int gf = userHome ? userResult.getHomeGoals() : userResult.getAwayGoals();
            int ga = userHome ? userResult.getAwayGoals() : userResult.getHomeGoals();
            performanceBonus = gf > ga ? 7000 : gf == ga ? 2500 : 0;
        }
        double affiliateIncome = state.getAffiliateClubName() != null ? 3500 : 0;

        double income = sponsorIncome + gateIncome + performanceBonus + affiliateIncome;
        double expenses = wageBill + stadiumCosts + travelCosts + scoutingAndOps;
        state.setWeeklyWageBill(wageBill);
        state.setLastRoundIncome(income);
        state.setLastRoundExpenses(expenses);
        state.getUserTeam().setBudget(Math.max(25_000, state.getUserTeam().getBudget() + income - expenses));

        if (round == 1 || round % 3 == 0 || state.getUserTeam().getBudget() < 150_000) {
            long net = Math.round(income - expenses);
            state.addInboxMessage("finance", "FINANCE OFFICE // ROUND " + round + "\nIncome: €" + formatMoney((long) income) + "\nExpenses: €" + formatMoney((long) expenses) + "\nWeekly wage bill: €" + formatMoney((long) wageBill) + "\nNet movement: " + (net >= 0 ? "+" : "-") + "€" + formatMoney(Math.abs(net)) + "\nCurrent budget: €" + formatMoney((long) state.getUserTeam().getBudget()));
        }
    }

    private void updateLeagueWire(CleanSheetGameState state, int round, List<CSMatchResult> allResults, CSMatchResult userResult) {
        List<String> wire = new ArrayList<>();
        wire.add("Round " + round + ": " + buildLeagueWireLead(state, allResults, userResult));

        CSTableEntry userEntry = state.getLeagueTable().stream()
                .filter(entry -> Objects.equals(entry.getTeamId(), state.getUserTeam().getId()))
                .findFirst()
                .orElse(null);
        if (userEntry != null) {
            wire.add("Table watch: " + state.getUserTeam().getName() + " sit " + ordinal(userEntry.getPosition())
                    + " with " + userEntry.getPoints() + " pts and GD " + signedValue(userEntry.getGoalDifference()) + ".");
        }

        List<CSPlayer> hottestScorers = state.getRoster().stream()
                .sorted(Comparator.comparingInt(CSPlayer::getGoals).reversed().thenComparingInt(CSPlayer::getRating).reversed())
                .limit(2)
                .toList();
        if (!hottestScorers.isEmpty()) {
            String scorerLine = hottestScorers.stream()
                    .map(player -> player.getName() + " (" + player.getGoals() + ")")
                    .collect(Collectors.joining(", "));
            wire.add("CPlayer watch: top in-house scorers right now are " + scorerLine + ".");
        }

        double budget = state.getUserTeam().getBudget();
        if (budget < 140_000) {
            wire.add("Finance watch: room is tightening and the board may freeze late-window business unless results improve.");
        } else if (budget > 420_000) {
            wire.add("Finance watch: healthy reserves could support a targeted move if the board feels promotion momentum building.");
        }

        state.setNotableNews(wire.stream().limit(5).collect(Collectors.toCollection(ArrayList::new)));
    }

    private String buildLeagueWireLead(CleanSheetGameState state, List<CSMatchResult> allResults, CSMatchResult userResult) {
        List<CSMatchResult> pool = new ArrayList<>();
        if (allResults != null) {
            pool.addAll(allResults.stream().filter(Objects::nonNull).toList());
        }
        if (userResult != null && pool.stream().noneMatch(result ->
                Objects.equals(result.getHomeTeamId(), userResult.getHomeTeamId())
                        && Objects.equals(result.getAwayTeamId(), userResult.getAwayTeamId()))) {
            pool.add(userResult);
        }
        if (pool.isEmpty()) {
            return "The fixture desk had no major swings to flag.";
        }

        CSMatchResult standout = pool.stream()
                .max(Comparator.comparingInt(result -> Math.abs(result.getHomeGoals() - result.getAwayGoals()) * 10 + result.getHomeGoals() + result.getAwayGoals()))
                .orElse(pool.get(0));
        return standout.getHomeTeamName() + " " + standout.getHomeGoals() + ":" + standout.getAwayGoals() + " " + standout.getAwayTeamName()
                + " delivered the loudest scoreline on the board.";
    }

    private void runBoardReview(CleanSheetGameState state, int round) {
        int totalRounds = Math.max(1, state.getTotalRounds());
        if (round != Math.max(2, totalRounds / 2) && round != Math.max(3, (totalRounds * 2) / 3)) {
            return;
        }

        CSTableEntry userEntry = state.getLeagueTable().stream()
                .filter(entry -> Objects.equals(entry.getTeamId(), state.getUserTeam().getId()))
                .findFirst()
                .orElse(null);
        int position = userEntry != null ? userEntry.getPosition() : state.getLeagueTable().size();
        int teamCount = Math.max(1, state.getLeagueTable().size());
        double budget = state.getUserTeam().getBudget();
        double adjustment = 0;
        String reviewTitle;
        String reviewText;

        if (position <= Math.max(2, teamCount / 4) && budget > 180_000) {
            adjustment = 45_000 + random.nextInt(30_001);
            reviewTitle = "Board review: backing the push";
            reviewText = "Promotion form has convinced the board to release an extra €" + formatMoney((long) adjustment).replace("€", "")
                    + " for selective recruitment and wage flexibility.";
        } else if (position >= Math.max(4, teamCount - 2) || budget < 120_000) {
            adjustment = -(20_000 + random.nextInt(15_001));
            reviewTitle = "Board review: cost controls";
            reviewText = "Results and cash flow triggered a budget correction of -€" + formatMoney((long) Math.abs(adjustment)).replace("€", "")
                    + ". The expectation is fewer risks and smarter squad management.";
        } else {
            reviewTitle = "Board review: hold course";
            reviewText = "The board sees enough stability to keep current plans intact. No fresh budget swing has been approved this time.";
        }

        if (adjustment != 0) {
            state.getUserTeam().setBudget(Math.max(25_000, state.getUserTeam().getBudget() + adjustment));
        }
        state.setBoardReviewTitle(reviewTitle);
        state.setBoardReviewText(reviewText + " Current transfer budget: €" + formatMoney((long) state.getUserTeam().getBudget()).replace("€", "") + ".");
        state.addInboxMessage("board", reviewTitle.toUpperCase(Locale.ROOT) + "\n" + state.getBoardReviewText());
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

    public void markInboxRead(Long userId, int index) {
        getStateOrThrow(userId).markInboxMessageRead(index);
    }

    public Map<String, Object> getTransferCentre(Long userId) {
        CleanSheetGameState state = getStateOrThrow(userId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("budget", state.getUserTeam().getBudget());
        payload.put("market", state.getTransferMarket());
        payload.put("listedPlayers", state.getTransferMarket().stream()
                .filter(item -> Objects.equals(item.getSellerTeamId(), state.getUserTeam().getId()))
                .toList());
        payload.put("availableTargets", state.getTransferMarket().stream()
                .filter(item -> !Objects.equals(item.getSellerTeamId(), state.getUserTeam().getId()))
                .toList());
        payload.put("incomingOffers", state.getTransferMarket().stream()
                .filter(item -> Objects.equals(item.getSellerTeamId(), state.getUserTeam().getId()))
                .filter(item -> item.getBestOffer() != null && item.getBestOfferClub() != null)
                .toList());
        return payload;
    }

    public CSTransferListing listPlayerForTransfer(Long userId, Long playerId, double askingPrice) {
        CleanSheetGameState state = getStateOrThrow(userId);
        CSPlayer player = state.getRoster().stream()
                .filter(p -> Objects.equals(p.getId(), playerId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("CPlayer not found in your squad."));

        state.getTransferMarket().removeIf(item -> Objects.equals(item.getPlayerId(), playerId));
        CSTransferListing listing = buildTransferListing(state.getUserTeam(), player, normalizeAskingPrice(player, askingPrice));
        state.getTransferMarket().add(0, listing);
        state.addInboxMessage("transfer", "Transfer desk: " + player.getName() + " has been listed for €" + formatMoney((long) listing.getAskingPrice()) + ".");
        return listing;
    }

    public void removePlayerFromTransferList(Long userId, Long playerId) {
        CleanSheetGameState state = getStateOrThrow(userId);
        boolean removed = state.getTransferMarket().removeIf(item ->
                Objects.equals(item.getPlayerId(), playerId) && Objects.equals(item.getSellerTeamId(), state.getUserTeam().getId()));
        if (removed) {
            state.addInboxMessage("transfer", "Transfer desk: " + findPlayerName(state, playerId) + " has been removed from the market.");
        }
    }

    public Map<String, Object> buyListedPlayer(Long userId, Long playerId) {
        CleanSheetGameState state = getStateOrThrow(userId);
        CSTransferListing listing = state.getTransferMarket().stream()
                .filter(item -> Objects.equals(item.getPlayerId(), playerId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Listing not found."));
        if (Objects.equals(listing.getSellerTeamId(), state.getUserTeam().getId())) {
            throw new RuntimeException("You cannot buy your own listed player.");
        }
        if (state.getUserTeam().getBudget() < listing.getAskingPrice()) {
            throw new RuntimeException("Budget too low for this deal.");
        }
        return executeTransfer(state, listing, listing.getAskingPrice());
    }

    public Map<String, Object> bidForPlayer(Long userId, Long playerId, double offer) {
        CleanSheetGameState state = getStateOrThrow(userId);
        CSTransferListing listing = state.getTransferMarket().stream()
                .filter(item -> Objects.equals(item.getPlayerId(), playerId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Listing not found."));
        if (Objects.equals(listing.getSellerTeamId(), state.getUserTeam().getId())) {
            throw new RuntimeException("You cannot negotiate for your own player.");
        }
        if (offer <= 0) {
            throw new RuntimeException("Offer must be above zero.");
        }
        if (state.getUserTeam().getBudget() < offer) {
            throw new RuntimeException("Budget too low for that offer.");
        }

        double ask = listing.getAskingPrice();
        double ratio = offer / Math.max(1.0, ask);
        Map<String, Object> payload = new HashMap<>();

        if (ratio >= 0.97 || (ratio >= 0.92 && random.nextDouble() < 0.45)) {
            Map<String, Object> completed = executeTransfer(state, listing, offer);
            completed.put("outcome", "accepted");
            completed.put("message", listing.getSellerTeamName() + " accepted your bid for " + listing.getPlayerName() + " at €" + formatMoney((long) offer) + ".");
            return completed;
        }

        if (ratio >= 0.82) {
            double counter = Math.min(ask, Math.round(Math.max(offer + (ask - offer) * 0.55, ask * 0.9)));
            listing.setAskingPrice(counter);
            state.addInboxMessage("transfer", "Negotiation note: " + listing.getSellerTeamName() + " want €" + formatMoney((long) counter) + " for " + listing.getPlayerName() + ".");
            payload.put("outcome", "counter");
            payload.put("counterPrice", counter);
            payload.put("message", listing.getSellerTeamName() + " rejected €" + formatMoney((long) offer) + " and came back at €" + formatMoney((long) counter) + ".");
            payload.put("market", state.getTransferMarket());
            payload.put("budget", state.getUserTeam().getBudget());
            return payload;
        }

        state.addInboxMessage("transfer", "Bid rejected: " + listing.getSellerTeamName() + " dismissed your €" + formatMoney((long) offer) + " offer for " + listing.getPlayerName() + ".");
        payload.put("outcome", "rejected");
        payload.put("message", listing.getSellerTeamName() + " rejected the bid for " + listing.getPlayerName() + " without opening serious talks.");
        payload.put("market", state.getTransferMarket());
        payload.put("budget", state.getUserTeam().getBudget());
        return payload;
    }

    public Map<String, Object> acceptIncomingOffer(Long userId, Long playerId) {
        CleanSheetGameState state = getStateOrThrow(userId);
        CSTransferListing listing = state.getTransferMarket().stream()
                .filter(item -> Objects.equals(item.getPlayerId(), playerId))
                .filter(item -> Objects.equals(item.getSellerTeamId(), state.getUserTeam().getId()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Listing not found."));
        if (listing.getBestOffer() == null || listing.getBestOfferClub() == null) {
            throw new RuntimeException("No active offer to accept.");
        }
        CSTeam buyer = state.getAllTeams().stream()
                .filter(team -> Objects.equals(team.getName(), listing.getBestOfferClub()))
                .findFirst()
                .orElseGet(() -> createGeneratedTeam(listing.getBestOfferClub()));
        completeOutgoingTransfer(state, listing, buyer, listing.getBestOffer());

        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", true);
        payload.put("budget", state.getUserTeam().getBudget());
        payload.put("roster", state.getRoster());
        payload.put("market", state.getTransferMarket());
        payload.put("message", "Offer accepted. " + listing.getPlayerName() + " leaves for " + listing.getBestOfferClub() + " at €" + formatMoney(listing.getBestOffer().longValue()) + ".");
        return payload;
    }

    public Map<String, Object> rejectIncomingOffer(Long userId, Long playerId) {
        CleanSheetGameState state = getStateOrThrow(userId);
        CSTransferListing listing = state.getTransferMarket().stream()
                .filter(item -> Objects.equals(item.getPlayerId(), playerId))
                .filter(item -> Objects.equals(item.getSellerTeamId(), state.getUserTeam().getId()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Listing not found."));
        String club = listing.getBestOfferClub();
        listing.setBestOffer(null);
        listing.setBestOfferClub(null);
        if (club != null) {
            state.addInboxMessage("transfer", "Offer rejected: " + club + " were turned away in talks for " + listing.getPlayerName() + ".");
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", true);
        payload.put("market", state.getTransferMarket());
        payload.put("budget", state.getUserTeam().getBudget());
        payload.put("message", "Offer rejected.");
        return payload;
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
                .orElseThrow(() -> new RuntimeException("CTeam not found: " + teamId));
    }

    public Map<String, Object> getTeamInfo(Long userId, Long teamId) {
        CleanSheetGameState state = getStateOrThrow(userId);
        CSTeam team = state.getAllTeams().stream()
                .filter(t -> t.getId().equals(teamId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("CTeam not found: " + teamId));
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
                + ", stadium " + safeText(userTeam.getStadiumName(), userTeam.getName() + " CSStadium")
                + " (" + safeInt(userTeam.getStadiumCapacity()) + ").\n"
                + openingLine + "\n"
                + pick(
                        "Supporters want a season with substance, discipline and a few memorable afternoons.",
                        "The local press expects a competitive side that can make the division take notice.",
                        "The board room message is simple: build momentum quickly and make home matches count."
                )
                + "\n\n⚠ NO-CHEAT MODE: This game has no save feature. Returning to the main menu resets all progress. Every decision counts — manage like it's real life.";
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

    private String signedValue(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
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

    private void initializeTransferMarket(CleanSheetGameState state) {
        state.setTransferMarket(new ArrayList<>());
        List<CSPlayer> candidates = state.getAllTeamRosters().entrySet().stream()
                .filter(entry -> !Objects.equals(entry.getKey(), state.getUserTeam().getId()))
                .flatMap(entry -> entry.getValue().stream())
                .filter(player -> player.getAge() <= 30)
                .sorted(Comparator.comparingInt(CSPlayer::getRating).reversed())
                .limit(18)
                .toList();

        candidates.stream()
                .filter(player -> random.nextDouble() < 0.45)
                .limit(8)
                .forEach(player -> {
                    CSTeam team = findTeamByPlayer(state, player.getId());
                    if (team != null) {
                        state.getTransferMarket().add(buildTransferListing(team, player, normalizeAskingPrice(player, 0)));
                    }
                });
    }

    private void updateTransferMarket(CleanSheetGameState state, int round) {
        List<CSTransferListing> market = state.getTransferMarket();
        market.stream()
                .filter(item -> !Objects.equals(item.getSellerTeamId(), state.getUserTeam().getId()))
                .forEach(item -> {
                    if (random.nextDouble() < 0.22) {
                        CSTeam club = pickRandomOtherTeam(state, item.getSellerTeamId());
                        if (club != null && item.getInterestedClubs().stream().noneMatch(club.getName()::equals)) {
                            item.getInterestedClubs().add(club.getName());
                        }
                    }
                });

        if (round % 2 == 1 && market.stream().filter(item -> !Objects.equals(item.getSellerTeamId(), state.getUserTeam().getId())).count() < 10) {
            CSPlayer target = pickRandomLeaguePlayer(state);
            if (target != null) {
                CSTeam seller = findTeamByPlayer(state, target.getId());
                boolean valid = seller != null
                        && !Objects.equals(seller.getId(), state.getUserTeam().getId())
                        && market.stream().noneMatch(item -> Objects.equals(item.getPlayerId(), target.getId()))
                        && target.getAge() <= 31;
                if (valid) {
                    market.add(buildTransferListing(seller, target, normalizeAskingPrice(target, 0)));
                }
            }
        }

        List<CSTransferListing> userListings = market.stream()
                .filter(item -> Objects.equals(item.getSellerTeamId(), state.getUserTeam().getId()))
                .toList();
        for (CSTransferListing listing : userListings) {
            if (random.nextDouble() < 0.28) {
                CSTeam club = pickRandomOtherTeam(state, state.getUserTeam().getId());
                if (club != null && listing.getInterestedClubs().stream().noneMatch(club.getName()::equals)) {
                    listing.getInterestedClubs().add(club.getName());
                    state.addInboxMessage("transfer", "Interest received: " + club.getName() + " have started tracking " + listing.getPlayerName() + ".");
                }
            }
            if (listing.getBestOffer() == null && !listing.getInterestedClubs().isEmpty() && random.nextDouble() < 0.18) {
                String clubName = listing.getInterestedClubs().get(random.nextInt(listing.getInterestedClubs().size()));
                double offer = Math.round(listing.getAskingPrice() * (0.78 + random.nextDouble() * 0.2));
                listing.setBestOffer(offer);
                listing.setBestOfferClub(clubName);
                state.addInboxMessage("transfer", "Incoming offer: " + clubName + " bid €" + formatMoney((long) offer) + " for " + listing.getPlayerName() + ".");
            }
        }
    }

    private CSTransferListing buildTransferListing(CSTeam seller, CSPlayer player, double askingPrice) {
        return CSTransferListing.builder()
                .playerId(player.getId())
                .playerName(player.getName())
                .position(player.getPosition())
                .age(player.getAge())
                .rating(player.getRating())
                .marketValue(player.getValue())
                .askingPrice(askingPrice)
                .sellerTeamId(seller.getId())
                .sellerTeamName(seller.getName())
                .listedAt(java.time.LocalDateTime.now().toString())
                .bestOffer(null)
                .bestOfferClub(null)
                .interestedClubs(new ArrayList<>())
                .build();
    }

    private double normalizeAskingPrice(CSPlayer player, double askingPrice) {
        double base = Math.max(50_000, player.getValue() > 0 ? player.getValue() : player.getRating() * 18_000.0);
        if (askingPrice > 0) {
            return Math.max(25_000, askingPrice);
        }
        return Math.round(base * (1.08 + random.nextDouble() * 0.28));
    }

    private CSTeam findTeamByPlayer(CleanSheetGameState state, Long playerId) {
        for (Map.Entry<Long, List<CSPlayer>> entry : state.getAllTeamRosters().entrySet()) {
            if (entry.getValue().stream().anyMatch(player -> Objects.equals(player.getId(), playerId))) {
                return state.getAllTeams().stream()
                        .filter(team -> Objects.equals(team.getId(), entry.getKey()))
                        .findFirst()
                        .orElse(null);
            }
        }
        return null;
    }

    private String findPlayerName(CleanSheetGameState state, Long playerId) {
        return state.getAllTeamRosters().values().stream()
                .flatMap(List::stream)
                .filter(player -> Objects.equals(player.getId(), playerId))
                .map(CSPlayer::getName)
                .findFirst()
                .orElse("CPlayer");
    }

    private CSPlayer createAffiliateProspect(CleanSheetGameState state) {
        String[] positions = {"DEF", "MID", "WNG", "ATT"};
        String position = positions[random.nextInt(positions.length)];
        int rating = 66 + random.nextInt(10);
        double value = 900_000 + random.nextInt(1_400_000);
        return CSPlayer.builder()
                .id(generatedPlayerId.getAndDecrement())
                .name(pickFirstName() + " " + pickLastName())
                .position(position)
                .age(17 + random.nextInt(3))
                .rating(rating)
                .form(6.2)
                .fatigue(1.2)
                .talent(2.5 + random.nextDouble() * 1.2)
                .stamina(clampSkill(12 + random.nextInt(6)))
                .goalkeeper(position.equals("GK") ? clampSkill(13 + random.nextInt(5)) : clampSkill(3 + random.nextInt(3)))
                .defending(clampSkill(position.equals("DEF") ? 14 + random.nextInt(5) : 8 + random.nextInt(5)))
                .pace(clampSkill(position.equals("WNG") || position.equals("ATT") ? 14 + random.nextInt(5) : 10 + random.nextInt(5)))
                .technique(clampSkill(12 + random.nextInt(6)))
                .playmaker(clampSkill(position.equals("MID") ? 14 + random.nextInt(5) : 9 + random.nextInt(5)))
                .passing(clampSkill(11 + random.nextInt(6)))
                .shooting(clampSkill(position.equals("ATT") ? 14 + random.nextInt(5) : 9 + random.nextInt(5)))
                .goals(0)
                .assists(0)
                .value(value)
                .earnings(2200 + random.nextInt(2200))
                .height(174 + random.nextInt(18))
                .weight(67 + random.nextInt(11))
                .build();
    }

    private Map<String, Object> executeTransfer(CleanSheetGameState state, CSTransferListing listing, double agreedFee) {
        CSTeam seller = findTeam(state, listing.getSellerTeamId());
        List<CSPlayer> sellerRoster = state.getAllTeamRosters().getOrDefault(seller.getId(), new ArrayList<>());
        CSPlayer player = sellerRoster.stream()
                .filter(p -> Objects.equals(p.getId(), listing.getPlayerId()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Selling club no longer has this player."));

        sellerRoster.remove(player);
        state.getRoster().add(player);
        state.getAllTeamRosters().put(state.getUserTeam().getId(), state.getRoster());
        state.getAllTeamRosters().put(seller.getId(), sellerRoster);

        state.getUserTeam().setBudget(state.getUserTeam().getBudget() - agreedFee);
        seller.setBudget(seller.getBudget() + agreedFee);
        state.getTransferMarket().removeIf(item -> Objects.equals(item.getPlayerId(), listing.getPlayerId()));
        state.addInboxMessage("transfer", "Deal completed: " + player.getName() + " joins from " + seller.getName() + " for €" + formatMoney((long) agreedFee) + ".");

        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", true);
        payload.put("budget", state.getUserTeam().getBudget());
        payload.put("roster", state.getRoster());
        payload.put("market", state.getTransferMarket());
        return payload;
    }

    private void completeOutgoingTransfer(CleanSheetGameState state, CSTransferListing listing, CSTeam buyer, double agreedFee) {
        CSPlayer player = state.getRoster().stream()
                .filter(p -> Objects.equals(p.getId(), listing.getPlayerId()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("CPlayer not found in your squad."));

        state.getRoster().remove(player);
        state.getAllTeamRosters().put(state.getUserTeam().getId(), state.getRoster());
        List<CSPlayer> buyerRoster = new ArrayList<>(state.getAllTeamRosters().getOrDefault(buyer.getId(), new ArrayList<>()));
        buyerRoster.add(player);
        state.getAllTeamRosters().put(buyer.getId(), buyerRoster);

        state.getUserTeam().setBudget(state.getUserTeam().getBudget() + agreedFee);
        buyer.setBudget(Math.max(25_000, buyer.getBudget() - agreedFee));
        state.getTransferMarket().removeIf(item -> Objects.equals(item.getPlayerId(), listing.getPlayerId()));
        state.addInboxMessage("transfer", "Transfer completed: " + player.getName() + " sold to " + buyer.getName() + " for €" + formatMoney((long) agreedFee) + ".");
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
        initializeInternationalCompetition(state);
        initializeTransferMarket(state);
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
        boolean scheduledWindow = round % 2 == 0;
        if (!scheduledWindow || state.getInternationalMatchday() > 6) {
            return;
        }
        List<CSMatchResult> results = simulateInternationalMatchday(state);
        if (results.isEmpty()) {
            return;
        }
        String bulletin = buildInternationalBulletin(state, round, results);
        state.getInternationalWindows().add(CSInternationalWindow.builder()
                .round(round)
                .matchday(state.getInternationalMatchday() - 1)
                .competitionName(state.getInternationalCompetitionName())
                .results(results)
                .bulletin(bulletin)
                .build());
        state.addInboxMessage("international", bulletin);
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

    private void initializeInternationalCompetition(CleanSheetGameState state) {
        state.setInternationalCompetitionName("World Cup Qualifying Group D");
        state.setInternationalMatchday(1);
        state.setInternationalWindows(new ArrayList<>());
        state.setInternationalTable(new ArrayList<>(List.of(
                CSTableEntry.builder().teamId(-1001L).teamName("Serbia").points(0).wins(0).draws(0).losses(0).goalsScored(0).goalsConceded(0).played(0).position(1).build(),
                CSTableEntry.builder().teamId(-1002L).teamName("Romania").points(0).wins(0).draws(0).losses(0).goalsScored(0).goalsConceded(0).played(0).position(2).build(),
                CSTableEntry.builder().teamId(-1003L).teamName("Austria").points(0).wins(0).draws(0).losses(0).goalsScored(0).goalsConceded(0).played(0).position(3).build(),
                CSTableEntry.builder().teamId(-1004L).teamName("Greece").points(0).wins(0).draws(0).losses(0).goalsScored(0).goalsConceded(0).played(0).position(4).build()
        )));
    }

    private List<CSMatchResult> simulateInternationalMatchday(CleanSheetGameState state) {
        List<String[]> fixtures = switch (state.getInternationalMatchday()) {
            case 1 -> List.of(new String[]{"Serbia", "Romania"}, new String[]{"Austria", "Greece"});
            case 2 -> List.of(new String[]{"Greece", "Serbia"}, new String[]{"Romania", "Austria"});
            case 3 -> List.of(new String[]{"Serbia", "Austria"}, new String[]{"Romania", "Greece"});
            case 4 -> List.of(new String[]{"Romania", "Serbia"}, new String[]{"Greece", "Austria"});
            case 5 -> List.of(new String[]{"Serbia", "Greece"}, new String[]{"Austria", "Romania"});
            case 6 -> List.of(new String[]{"Austria", "Serbia"}, new String[]{"Greece", "Romania"});
            default -> List.of();
        };
        if (fixtures.isEmpty()) {
            return List.of();
        }

        List<CSMatchResult> results = new ArrayList<>();
        for (String[] fixture : fixtures) {
            String home = fixture[0];
            String away = fixture[1];
            int homeGoals = weightedInternationalGoals(home);
            int awayGoals = weightedInternationalGoals(away);

            if (home.equals("Serbia") && homeGoals == awayGoals && random.nextDouble() < 0.45) {
                homeGoals += 1;
            }

            List<String> homeScorers = pickInternationalScorers(state, Math.max(1, homeGoals));
            List<String> awayScorers = pickInternationalScorers(state, Math.max(1, awayGoals));
            String summary = home + " " + homeGoals + ":" + awayGoals + " " + away;
            if (homeGoals > 0) {
                summary += ". " + home + " scorers: " + String.join(", ", homeScorers.stream().limit(homeGoals).toList()) + ".";
            }
            if (awayGoals > 0) {
                summary += " " + away + " scorers: " + String.join(", ", awayScorers.stream().limit(awayGoals).toList()) + ".";
            }

            CSMatchResult result = CSMatchResult.builder()
                    .homeTeamName(home)
                    .awayTeamName(away)
                    .homeGoals(homeGoals)
                    .awayGoals(awayGoals)
                    .round(state.getInternationalMatchday())
                    .summary(summary)
                    .report(summary)
                    .events(new ArrayList<>())
                    .build();
            results.add(result);
            applyInternationalResult(state, result);
        }

        state.setInternationalMatchday(state.getInternationalMatchday() + 1);
        sortInternationalTable(state);
        return results;
    }

    private int weightedInternationalGoals(String nation) {
        int base = switch (nation) {
            case "Serbia" -> 2;
            case "Austria" -> 1;
            case "Romania" -> 1;
            default -> 1;
        };
        return Math.max(0, base + random.nextInt(3) - random.nextInt(2));
    }

    private void applyInternationalResult(CleanSheetGameState state, CSMatchResult result) {
        CSTableEntry home = findInternationalEntry(state, result.getHomeTeamName());
        CSTableEntry away = findInternationalEntry(state, result.getAwayTeamName());
        if (home == null || away == null) return;

        home.setPlayed(home.getPlayed() + 1);
        away.setPlayed(away.getPlayed() + 1);
        home.setGoalsScored(home.getGoalsScored() + result.getHomeGoals());
        home.setGoalsConceded(home.getGoalsConceded() + result.getAwayGoals());
        away.setGoalsScored(away.getGoalsScored() + result.getAwayGoals());
        away.setGoalsConceded(away.getGoalsConceded() + result.getHomeGoals());

        if (result.getHomeGoals() > result.getAwayGoals()) {
            home.setWins(home.getWins() + 1);
            home.setPoints(home.getPoints() + 3);
            away.setLosses(away.getLosses() + 1);
        } else if (result.getHomeGoals() < result.getAwayGoals()) {
            away.setWins(away.getWins() + 1);
            away.setPoints(away.getPoints() + 3);
            home.setLosses(home.getLosses() + 1);
        } else {
            home.setDraws(home.getDraws() + 1);
            away.setDraws(away.getDraws() + 1);
            home.setPoints(home.getPoints() + 1);
            away.setPoints(away.getPoints() + 1);
        }
    }

    private void sortInternationalTable(CleanSheetGameState state) {
        List<CSTableEntry> sorted = state.getInternationalTable().stream()
                .sorted(Comparator.comparingInt(CSTableEntry::getPoints).reversed()
                        .thenComparingInt(CSTableEntry::getGoalDifference).reversed()
                        .thenComparingInt(CSTableEntry::getGoalsScored).reversed()
                        .thenComparing(CSTableEntry::getTeamName))
                .collect(Collectors.toCollection(ArrayList::new));
        for (int i = 0; i < sorted.size(); i++) {
            sorted.get(i).setPosition(i + 1);
        }
        state.setInternationalTable(sorted);
    }

    private CSTableEntry findInternationalEntry(CleanSheetGameState state, String teamName) {
        return state.getInternationalTable().stream()
                .filter(entry -> Objects.equals(entry.getTeamName(), teamName))
                .findFirst()
                .orElse(null);
    }

    private String buildInternationalBulletin(CleanSheetGameState state, int round, List<CSMatchResult> results) {
        List<String> lines = new ArrayList<>();
        int matchday = Math.max(1, state.getInternationalMatchday() - 1);
        lines.add("INTERNATIONAL DESK // " + state.getInternationalCompetitionName() + " // MATCHDAY " + matchday);
        lines.add("HEADLINE");
        CSTableEntry leader = state.getInternationalTable().isEmpty() ? null : state.getInternationalTable().getFirst();
        lines.add(pick(
                "The group picture tightened again during the latest international break.",
                "Another qualifying window has added shape to the race for the top spot.",
                "Scouts and analysts were pulled into another meaningful matchday abroad."
        ));
        if (leader != null) {
            lines.add("Current leaders: " + leader.getTeamName() + " with " + leader.getPoints() + " points after round " + round + ".");
        }
        lines.add("");
        lines.add("SERBIA WATCH");
        results.stream()
                .filter(r -> "Serbia".equals(r.getHomeTeamName()) || "Serbia".equals(r.getAwayTeamName()))
                .forEach(r -> lines.add("- " + r.getSummary()));
        lines.add("");
        lines.add("REGIONAL RESULTS");
        results.stream()
                .filter(r -> !"Serbia".equals(r.getHomeTeamName()) && !"Serbia".equals(r.getAwayTeamName()))
                .forEach(r -> lines.add("- " + r.getSummary()));
        lines.add("");
        lines.add("QUALIFYING TABLE SNAPSHOT");
        state.getInternationalTable().forEach(entry ->
                lines.add(entry.getPosition() + ". " + entry.getTeamName() + " - " + entry.getPoints() + " pts (GD " + entry.getGoalDifference() + ")"));
        lines.add("");
        lines.add("SCOUT RADAR");
        lines.add("- " + pick(
                "Domestic clubs are comparing notes on players who handled the bigger stage well.",
                "Several performances from this window have already triggered fresh scouting requests.",
                "Managers back home will welcome their internationals back with new reputations attached."
        ));
        List<String> names = results.stream()
                .flatMap(r -> Stream.concat(extractScorers(r.getSummary(), r.getHomeTeamName()).stream(),
                        extractScorers(r.getSummary(), r.getAwayTeamName()).stream()))
                .distinct()
                .limit(3)
                .toList();
        if (!names.isEmpty()) {
            lines.add("- Names repeated on the wire: " + String.join(", ", names) + ".");
        }
        return String.join("\n", lines);
    }

    private List<String> extractScorers(String summary, String nation) {
        String marker = nation + " scorers:";
        int start = summary.indexOf(marker);
        if (start < 0) return List.of();
        int end = summary.indexOf('.', start);
        String segment = end > start ? summary.substring(start + marker.length(), end) : summary.substring(start + marker.length());
        return Arrays.stream(segment.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
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
        
        // CSPosition bonus: top 3 = +10, bottom 3 = -10
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
