package org.example.footballmanager.newLogic.engine_v1;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;
import org.example.footballmanager.newLogic.repository.*;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.example.footballmanager.newLogic.util.match.MatchRatingCalculator;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchStatisticEngine {
    private final Random random = new Random();
    private final MatchPlayerStatsRepository matchPlayerStatsRepository;
    private final MatchRepository matchRepository;
    private final MatchEventRepository matchEventRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final CompetitionEntryRepository competitionEntryRepository;

    public List<Player> assignRatings(List<Player> players, List<GoalEvent> allGoals) {
        for (Player player : players) {
            player.setRating(MatchRatingCalculator.calculate(player, player.getTeam(), allGoals));
        }
        return players;
    }
    public void simulateInjuriesAndCards(List<Player> players, Match match) {
        for (Player player : players) {
            if (random.nextDouble() < 0.05) {
                int minute = random.nextInt(90) + 1;
                String teamSide = resolveTeamSide(player, match);
                InjuryEvent injury = new InjuryEvent(minute, 0, player.getId(), player.getName(), teamSide);
                log.info("Injury at {}' for {} ({})", minute, player.getName(), teamSide);
            }
            if (random.nextDouble() < 0.1) {
                String teamSide = resolveTeamSide(player, match);
                CardEvent yc = new CardEvent(random.nextInt(90) + 1, 0,
                        player.getId(), player.getName(), teamSide, CardEvent.CardType.YELLOW);
                matchEventRepository.save(yc);
            }
        }
    }

    private String resolveTeamSide(Player player, Match match) {
        if (player == null || match == null) return "HOME";
        if (match.getHomeTeam() != null && player.getTeam() != null && match.getHomeTeam().getId().equals(player.getTeam().getId())) {
            return "HOME";
        }
        return "AWAY";
    }

    private Long resolveScorerTeamId(GoalEvent goal, Match match) {
        if (goal == null || match == null) return null;
        if ("HOME".equals(goal.teamSide())) {
            return match.getHomeTeam() != null ? match.getHomeTeam().getId() : null;
        } else if ("AWAY".equals(goal.teamSide())) {
            return match.getAwayTeam() != null ? match.getAwayTeam().getId() : null;
        }
        return null;
    }
    public void savePlayerStats(Match match,
                                List<Player> players,
                                List<GoalEvent> allGoals,
                                List<CardEvent> allCards,
                                Map<Long, Integer> minutesByPlayerId) {
        savePlayerStats(match, players, allGoals, allCards, minutesByPlayerId, null);
    }

    public void savePlayerStats(Match match,
                                List<Player> players,
                                List<GoalEvent> allGoals,
                                List<CardEvent> allCards,
                                Map<Long, Integer> minutesByPlayerId,
                                List<MatchEvent> preloadedMatchEvents) {
        if (players == null || players.isEmpty()) {
            return;
        }

        Team team = players.stream().findFirst().map(Player::getTeam).orElse(null);
        List<MatchEvent> matchEvents = preloadedMatchEvents != null ? preloadedMatchEvents : matchEventRepository.findByMatch(match);
        Map<Long, Integer> interceptionsByPlayerId = buildInterceptionsByPlayer(matchEvents);
        Map<Long, Integer> shotsOnTargetByTeamId = buildShotsOnTargetByTeam(matchEvents, match);
        Map<Long, MatchPlayerStats> existingStatsByPlayerId = Optional.ofNullable(matchPlayerStatsRepository.findByMatchId(match.getId()))
                .orElseGet(List::of)
                .stream()
                .filter(stats -> stats.getPlayer() != null && stats.getPlayer().getId() != null)
                .collect(Collectors.toMap(
                        stats -> stats.getPlayer().getId(),
                        stats -> stats,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<Long, Integer> goalkeeperMinutes = players.stream()
                .filter(player -> player.getPositionEnum() == Position.GK)
                .collect(Collectors.toMap(
                        Player::getId,
                        player -> Math.max(0, minutesByPlayerId.getOrDefault(player.getId(), 90)),
                        Integer::sum,
                        LinkedHashMap::new
                ));

        int teamGoals = team == null ? 0 : (int) allGoals.stream()
                .filter(goal -> team.getId() != null
                        && team.getId().equals(resolveScorerTeamId(goal, match)))
                .count();
        int concededGoals = team == null ? 0 : (int) allGoals.stream()
                .filter(goal -> team.getId() != null
                        && !team.getId().equals(resolveScorerTeamId(goal, match)))
                .count();

        Long teamId = team != null ? team.getId() : null;
        Long opponentTeamId = resolveOpponentTeamId(match, teamId);
        int opponentShotsOnTarget = opponentTeamId == null ? 0 : shotsOnTargetByTeamId.getOrDefault(opponentTeamId, 0);
        int totalGoalkeeperMinutes = goalkeeperMinutes.values().stream().mapToInt(Integer::intValue).sum();
        List<MatchPlayerStats> statsToSave = new ArrayList<>(players.size());

        for (Player player : players) {
            long goals = allGoals.stream()
                    .filter(g -> g.scorerId() == player.getId())
                    .count();

            long assists = allGoals.stream()
                    .filter(g -> g.assistantId() != null && g.assistantId() == player.getId())
                    .count();
            long yellowCards = allCards.stream().filter(c -> c.cardType() == CardEvent.CardType.YELLOW && c.playerId() == player.getId()).count();
            long redCards = allCards.stream().filter(c -> c.cardType() == CardEvent.CardType.RED && c.playerId() == player.getId()).count();

            int minutesPlayed = Math.max(0, minutesByPlayerId.getOrDefault(player.getId(), 90));
            boolean cleanSheet = concededGoals == 0
                    && minutesPlayed >= 60
                    && (player.getPositionEnum() == Position.GK || player.getPositionEnum() == Position.DEF);
            int interceptions = resolveInterceptions(player, minutesPlayed, concededGoals, interceptionsByPlayerId);
            int saves = resolveSaves(player, minutesPlayed, concededGoals, opponentShotsOnTarget, goalkeeperMinutes, totalGoalkeeperMinutes);
            int calculatedRating = MatchRatingCalculator.calculate(
                    player,
                    (int) goals,
                    (int) assists,
                    interceptions,
                    saves,
                    cleanSheet,
                    (int) yellowCards,
                    (int) redCards,
                    teamGoals,
                    concededGoals,
                    minutesPlayed
            );

            player.setRating(calculatedRating);

            MatchPlayerStats stats = existingStatsByPlayerId.getOrDefault(player.getId(), new MatchPlayerStats());
            stats.setMatch(match);
            stats.setPlayer(player);
            stats.setGoals((int) goals);
            stats.setAssists((int) assists);
            stats.setYellowCards((int) yellowCards);
            stats.setRedCards((int) redCards);
            stats.setMinutesPlayed(minutesPlayed);
            stats.setInterceptions(interceptions);
            stats.setSaves(saves);
            stats.setCleanSheet(cleanSheet);
            stats.setRating(calculatedRating);
            statsToSave.add(stats);
        }

        if (!statsToSave.isEmpty()) {
            matchPlayerStatsRepository.saveAll(statsToSave);
        }
    }
    public String generateMatchReport(Match match, MatchRuntime rt, List<Player> homePlayers, List<Player> awayPlayers) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s %d - %d %s%n%n",
                match.getHomeTeam().getName(),
                rt.homeGoals,
                rt.awayGoals,
                match.getAwayTeam().getName()));
        // --- Strelci ---
        sb.append("Strelci:\n");
        // koristimo HashSet da ne dupliramo iste golove (minute+scorer)
        Set<String> addedGoals = new HashSet<>();
        rt.runtimeGoals.forEach(g -> {
            String scorerName = g.scorerName() != null ? g.scorerName() : "N/A";
            String assistName = g.assistantName();

            String desc;
            if (assistName != null) {
                desc = String.format("%d' ⚽ %s (asistencija: %s)", g.minute(), scorerName, assistName);
            } else {
                desc = String.format("%d' ⚽ %s", g.minute(), scorerName);
            }
            if (!addedGoals.contains(desc)) {
                sb.append(desc).append("\n");
                addedGoals.add(desc);
            }
        });
        sb.append("\n");

        sb.append("Ocene igraca - ").append(match.getHomeTeam().getName()).append("\n");
        appendPlayerRatings(sb, homePlayers, rt.runtimeGoals);
        sb.append("\nOcene igraca - ").append(match.getAwayTeam().getName()).append("\n");
        appendPlayerRatings(sb, awayPlayers, rt.runtimeGoals);
        return sb.toString();
    }
    public void appendPlayerRatings(StringBuilder sb, List<Player> players, List<GoalEvent> allGoals) {
        for (Player player : players) {
            long goals = allGoals.stream()
                    .filter(g -> g.scorerId() == player.getId())
                    .count();

            long assists = allGoals.stream()
                    .filter(g -> g.assistantId() != null && g.assistantId() == player.getId())
                    .count();

            sb.append(String.format("- %s: %d (golova: %d, asistencija: %d)%n",
                    player.getName(),
                    player.getRating(),
                    goals,
                    assists));
        }
    }
    public void updateLeagueTableForMatchDay(Competition league, Season season) {
        SeasonCompetition sc = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, season.getSeasonYear())
                .orElseThrow(() -> new RuntimeException("Season not found for league"));

        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(sc);
        if (entries.isEmpty()) {
            log.warn("No competition entries found for league {} season {}", league.getName(), season.getSeasonYear());
            return;
        }

        Map<Long, CompetitionEntry> byTeamId = entries.stream()
                .filter(e -> e.getTeam() != null && e.getTeam().getId() != null)
                .collect(Collectors.toMap(e -> e.getTeam().getId(), e -> e));

        // Recalculate from scratch to avoid duplicate counting across multiple calls.
        entries.forEach(e -> {
            e.setPoints(0);
            e.setGoalsScored(0);
            e.setGoalsConceded(0);
            e.setWins(0);
            e.setDraws(0);
            e.setLosses(0);
        });

        List<Match> playedMatches = matchRepository
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(league.getId(), season.getSeasonYear())
                .stream()
                .filter(Match::isPlayed)
                .filter(m -> m.getHomeTeam() != null && m.getAwayTeam() != null)
                .filter(m -> m.getRoundNumber() == null || m.getRoundNumber() <= SeasonService.LEAGUE_ROUNDS)
                .toList();

        log.info("Recalculating table from {} played matches in league {}", playedMatches.size(), league.getName());

        for (Match match : playedMatches) {
            CompetitionEntry homeEntry = byTeamId.get(match.getHomeTeam().getId());
            CompetitionEntry awayEntry = byTeamId.get(match.getAwayTeam().getId());
            if (homeEntry == null || awayEntry == null) continue;

            int homeG = match.getHomeGoals();
            int awayG = match.getAwayGoals();

            homeEntry.setGoalsScored(homeEntry.getGoalsScored() + homeG);
            homeEntry.setGoalsConceded(homeEntry.getGoalsConceded() + awayG);
            awayEntry.setGoalsScored(awayEntry.getGoalsScored() + awayG);
            awayEntry.setGoalsConceded(awayEntry.getGoalsConceded() + homeG);

            if (homeG > awayG) {
                homeEntry.setWins(homeEntry.getWins() + 1);
                awayEntry.setLosses(awayEntry.getLosses() + 1);
                homeEntry.setPoints(homeEntry.getPoints() + 3);
            } else if (awayG > homeG) {
                awayEntry.setWins(awayEntry.getWins() + 1);
                homeEntry.setLosses(homeEntry.getLosses() + 1);
                awayEntry.setPoints(awayEntry.getPoints() + 3);
            } else {
                homeEntry.setDraws(homeEntry.getDraws() + 1);
                awayEntry.setDraws(awayEntry.getDraws() + 1);
                homeEntry.setPoints(homeEntry.getPoints() + 1);
                awayEntry.setPoints(awayEntry.getPoints() + 1);
            }
        }

        List<CompetitionEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(CompetitionEntry::getPoints, Comparator.reverseOrder())
                .thenComparing(e -> e.getGoalsScored() - e.getGoalsConceded(), Comparator.reverseOrder())
                .thenComparing(CompetitionEntry::getGoalsScored, Comparator.reverseOrder()));

        for (int pos = 0; pos < sorted.size(); pos++) {
            sorted.get(pos).setPosition(pos + 1);
        }
        competitionEntryRepository.saveAll(sorted);

        log.info("League table recalculated for {} teams", sorted.size());
    }

    public void generateFakeAdditionalStats(Match match, List<Player> homePlayers, List<Player> awayPlayers, int homeGoals, int awayGoals, Random rnd) {

        Team home = match.getHomeTeam();
        Team away = match.getAwayTeam();

        // 1. Realistični brojevi statistike
        // Šutevi: suženi opseg da ne beže u nerealne dvocifrene vrednosti po timu bez razloga.
        int homeAttackBias = homeGoals > awayGoals ? 1 : 0;
        int awayAttackBias = awayGoals > homeGoals ? 1 : 0;
        int homeShotsTotal = Math.max(homeGoals + 2, rnd.nextInt(4) + 4 + Math.min(homeGoals, 3) + homeAttackBias);
        int awayShotsTotal = Math.max(awayGoals + 2, rnd.nextInt(4) + 4 + Math.min(awayGoals, 3) + awayAttackBias);

        // Shots on target: blago veći udeo nego ranije, da odnos golovi/šutevi izgleda zdravije.
        int homeOnTargetBase = (int) Math.round(homeShotsTotal * (0.40 + rnd.nextDouble() * 0.16));
        int awayOnTargetBase = (int) Math.round(awayShotsTotal * (0.40 + rnd.nextDouble() * 0.16));
        int homeShotsOnTarget = Math.min(homeShotsTotal, Math.max(homeGoals, homeOnTargetBase));
        int awayShotsOnTarget = Math.min(awayShotsTotal, Math.max(awayGoals, awayOnTargetBase));

        int homeShotsOffTarget = Math.max(0, homeShotsTotal - homeShotsOnTarget);
        int awayShotsOffTarget = Math.max(0, awayShotsTotal - awayShotsOnTarget);

        // Korneri: 2â€“12 po timu, viÅ¡e kod boljeg tima
        int homeCorners        = rnd.nextInt(11) + 2 + (homeGoals > awayGoals ? 2 : 0);
        int awayCorners        = rnd.nextInt(11) + 2 + (awayGoals > homeGoals ? 2 : 0);

        // Faulovi: 8â€“25 po timu
        int homeFouls          = rnd.nextInt(18) + 8;
        int awayFouls          = rnd.nextInt(18) + 8;

        // Ofsajdi: 0â€“8
        int homeOffsides       = rnd.nextInt(9);
        int awayOffsides       = rnd.nextInt(9);

        // Penali: 0â€“2 po meÄu, veÄ‡a Å¡ansa ako je mnogo faulova u Å¡esnaestercu
        int totalPenalties     = rnd.nextInt(3); // 0â€“2
        boolean homePenalty    = totalPenalties > 0 && rnd.nextBoolean();
        boolean awayPenalty    = totalPenalties > 1 || (totalPenalties == 1 && !homePenalty);

        // Kartoni
        int homeYellows        = rnd.nextInt(5) + (homeFouls > 18 ? 1 : 0); // viÅ¡e faulova â†’ viÅ¡e kartona
        int awayYellows        = rnd.nextInt(5) + (awayFouls > 18 ? 1 : 0);
        int homeReds           = rnd.nextInt(2);
        int awayReds           = rnd.nextInt(2);

        // 2. Snimanje događaja u bazu (samo deo, da ne zatrpamo bazu)

        // Korneri â€“ snimamo ~40â€“60% da izgleda realno
        int homeCornersToSave  = (int)(homeCorners * (0.4 + rnd.nextDouble() * 0.2));
        int awayCornersToSave  = (int)(awayCorners * (0.4 + rnd.nextDouble() * 0.2));

        for (int i = 0; i < homeCornersToSave; i++) {
            Player taker = getRandomPlayerByPosition(homePlayers, List.of(Position.WNG, Position.MID, Position.ATT), rnd);
            SetPieceEvent corner = new SetPieceEvent(rnd.nextInt(90) + 1, 0, "HOME",
                    taker != null ? taker.getId() : 0, taker != null ? taker.getName() : "Unknown",
                    SetPieceEvent.SetPieceType.CORNER, 50.0, 50.0);
            matchEventRepository.save(corner);
        }

        for (int i = 0; i < awayCornersToSave; i++) {
            Player taker = getRandomPlayerByPosition(awayPlayers, List.of(Position.WNG, Position.MID, Position.ATT), rnd);
            SetPieceEvent corner = new SetPieceEvent(rnd.nextInt(90) + 1, 0, "AWAY",
                    taker != null ? taker.getId() : 0, taker != null ? taker.getName() : "Unknown",
                    SetPieceEvent.SetPieceType.CORNER, 50.0, 50.0);
            matchEventRepository.save(corner);
        }

        // šutevi na gol â€“ snimamo deo šuteva u okvir
        for (int i = 0; i < homeShotsOnTarget; i++) {
            Player shooter = getRandomPlayerByPosition(homePlayers, List.of(Position.ATT, Position.MID, Position.WNG), rnd);
            ShotEvent shot = ShotEvent.onTarget(rnd.nextInt(90) + 1, 0,
                    shooter != null ? shooter.getId() : 0, shooter != null ? shooter.getName() : "Unknown",
                    "HOME", 0.3, 50 + rnd.nextDouble() * 30, 30 + rnd.nextDouble() * 40);
            matchEventRepository.save(shot);
        }

        for (int i = 0; i < awayShotsOnTarget; i++) {
            Player shooter = getRandomPlayerByPosition(awayPlayers, List.of(Position.ATT, Position.MID, Position.WNG), rnd);
            ShotEvent shot = ShotEvent.onTarget(rnd.nextInt(90) + 1, 0,
                    shooter != null ? shooter.getId() : 0, shooter != null ? shooter.getName() : "Unknown",
                    "AWAY", 0.3, 50 + rnd.nextDouble() * 30, 30 + rnd.nextDouble() * 40);
            matchEventRepository.save(shot);
        }

        // Å utevi na gol â€“ snimamo deo Å¡uteva van okvira
        for (int i = 0; i < homeShotsOffTarget; i++) {
            Player shooter = getRandomPlayerByPosition(homePlayers, List.of(Position.ATT, Position.MID, Position.WNG), rnd);
            ShotEvent shot = ShotEvent.missed(rnd.nextInt(90) + 1, 0,
                    shooter != null ? shooter.getId() : 0, shooter != null ? shooter.getName() : "Unknown",
                    "HOME", 0.1, 50 + rnd.nextDouble() * 30, 30 + rnd.nextDouble() * 40);
            matchEventRepository.save(shot);
        }

        for (int i = 0; i < awayShotsOffTarget; i++) {
            Player shooter = getRandomPlayerByPosition(awayPlayers, List.of(Position.ATT, Position.MID, Position.WNG), rnd);
            ShotEvent shot = ShotEvent.missed(rnd.nextInt(90) + 1, 0,
                    shooter != null ? shooter.getId() : 0, shooter != null ? shooter.getName() : "Unknown",
                    "AWAY", 0.1, 50 + rnd.nextDouble() * 30, 30 + rnd.nextDouble() * 40);
            matchEventRepository.save(shot);
        }

        // Penali (ako ih ima)
        if (homePenalty) {
            Player taker = getRandomPlayerByPosition(homePlayers, List.of(Position.ATT, Position.MID), rnd);
            PenaltyEvent p = new PenaltyEvent(rnd.nextInt(90) + 1, 0,
                    taker != null ? taker.getId() : 0, taker != null ? taker.getName() : "Unknown",
                    "HOME", rnd.nextDouble() < 0.75, false, 0.76);
            matchEventRepository.save(p);
        }

        if (awayPenalty) {
            Player taker = getRandomPlayerByPosition(awayPlayers, List.of(Position.ATT, Position.MID), rnd);
            PenaltyEvent p = new PenaltyEvent(rnd.nextInt(90) + 1, 0,
                    taker != null ? taker.getId() : 0, taker != null ? taker.getName() : "Unknown",
                    "AWAY", rnd.nextDouble() < 0.75, false, 0.76);
            matchEventRepository.save(p);
        }

        int homeYellowsToSave = (int)(homeYellows * 0.6);
        for (int i = 0; i < homeYellowsToSave; i++) {
            Player offender = getRandomPlayerByPosition(homePlayers, null, rnd);
            CardEvent yc = new CardEvent(rnd.nextInt(90) + 1, 0,
                    offender != null ? offender.getId() : 0, offender != null ? offender.getName() : "Unknown",
                    "HOME", CardEvent.CardType.YELLOW);
            matchEventRepository.save(yc);
        }

        int awayYellowsToSave = (int)(awayYellows * 0.6);
        for (int i = 0; i < awayYellowsToSave; i++) {
            Player offender = getRandomPlayerByPosition(awayPlayers, null, rnd);
            CardEvent yc = new CardEvent(rnd.nextInt(90) + 1, 0,
                    offender != null ? offender.getId() : 0, offender != null ? offender.getName() : "Unknown",
                    "AWAY", CardEvent.CardType.YELLOW);
            matchEventRepository.save(yc);
        }

        for (int i = 0; i < homeReds; i++) {
            Player offender = getRandomPlayerByPosition(homePlayers, null, rnd);
            CardEvent rc = new CardEvent(rnd.nextInt(90) + 1, 0,
                    offender != null ? offender.getId() : 0, offender != null ? offender.getName() : "Unknown",
                    "HOME", CardEvent.CardType.RED);
            matchEventRepository.save(rc);
        }

        for (int i = 0; i < awayReds; i++) {
            Player offender = getRandomPlayerByPosition(awayPlayers, null, rnd);
            CardEvent rc = new CardEvent(rnd.nextInt(90) + 1, 0,
                    offender != null ? offender.getId() : 0, offender != null ? offender.getName() : "Unknown",
                    "AWAY", CardEvent.CardType.RED);
            matchEventRepository.save(rc);
        }

        log.info("Generisana fake statistika za simulirani meč {}:{} {} vs {} â€“ Å¡utevi {}/{}, korneri {}/{}, kartoni {}/{}",
                home.getName(), homeGoals, awayGoals, away.getName(),
                homeShotsTotal, awayShotsTotal, homeCorners, awayCorners, homeYellows, awayYellows);
    }

    public Player getRandomPlayerByPosition(List<Player> players, List<Position> preferredPositions, Random rnd) {
        if (preferredPositions == null || preferredPositions.isEmpty()) {
            return players.get(rnd.nextInt(players.size()));
        }

        List<Player> candidates = players.stream()
                .filter(p -> preferredPositions.contains(p.getPosition()))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            return players.get(rnd.nextInt(players.size())); // fallback
        }

        return candidates.get(rnd.nextInt(candidates.size()));
    }

    private Map<Long, Integer> buildInterceptionsByPlayer(List<MatchEvent> matchEvents) {
        Map<Long, Integer> interceptions = new HashMap<>();
        matchEvents.stream()
                .filter(PassInterceptedEvent.class::isInstance)
                .map(PassInterceptedEvent.class::cast)
                .forEach(event -> interceptions.merge(event.interceptorId(), 1, Integer::sum));
        return interceptions;
    }

    private Map<Long, Integer> buildShotsOnTargetByTeam(List<MatchEvent> matchEvents, Match match) {
        Map<Long, Integer> shotsOnTarget = new HashMap<>();
        if (match == null) return shotsOnTarget;
        Long homeId = match.getHomeTeam() != null ? match.getHomeTeam().getId() : null;
        Long awayId = match.getAwayTeam() != null ? match.getAwayTeam().getId() : null;
        matchEvents.stream()
                .filter(e -> e instanceof ShotEvent se && se.onTarget())
                .map(e -> (ShotEvent) e)
                .forEach(event -> {
                    Long teamId = "HOME".equals(event.teamSide()) ? homeId : awayId;
                    if (teamId != null) {
                        shotsOnTarget.merge(teamId, 1, Integer::sum);
                    }
                });
        return shotsOnTarget;
    }

    private Long resolveOpponentTeamId(Match match, Long teamId) {
        if (teamId == null || match == null) {
            return null;
        }
        if (match.getHomeTeam() != null && Objects.equals(match.getHomeTeam().getId(), teamId)) {
            return match.getAwayTeam() != null ? match.getAwayTeam().getId() : null;
        }
        if (match.getAwayTeam() != null && Objects.equals(match.getAwayTeam().getId(), teamId)) {
            return match.getHomeTeam() != null ? match.getHomeTeam().getId() : null;
        }
        return null;
    }

    private int resolveInterceptions(Player player,
                                     int minutesPlayed,
                                     int concededGoals,
                                     Map<Long, Integer> interceptionsByPlayerId) {
        int actualInterceptions = interceptionsByPlayerId.getOrDefault(player.getId(), 0);
        if (actualInterceptions > 0) {
            return actualInterceptions;
        }

        double minuteFactor = Math.max(0.25, Math.min(1.0, minutesPlayed / 90.0));
        Position position = player.getPositionEnum() != null ? player.getPositionEnum() : Position.MID;
        double base = switch (position) {
            case DEF -> 2.4;
            case MID -> 1.8;
            case WNG -> 1.1;
            case ATT, GK -> 0.2;
        };
        double skillLift = (player.getSkills().getDefender() * 0.20
                + player.getSkills().getPlaymaker() * 0.08
                + player.getSkills().getStamina() * 0.07) / 2.8;
        double cleanBonus = concededGoals == 0 && (position == Position.DEF || position == Position.MID) ? 0.8 : 0.0;
        return Math.max(0, Math.min(9, (int) Math.round((base + skillLift + cleanBonus) * minuteFactor)));
    }

    private int resolveSaves(Player player,
                             int minutesPlayed,
                             int concededGoals,
                             int opponentShotsOnTarget,
                             Map<Long, Integer> goalkeeperMinutes,
                             int totalGoalkeeperMinutes) {
        if (player.getPositionEnum() != Position.GK) {
            return 0;
        }

        int totalSaves = Math.max(0, opponentShotsOnTarget - concededGoals);
        if (totalSaves == 0) {
            return 0;
        }
        if (goalkeeperMinutes.size() <= 1 || totalGoalkeeperMinutes <= 0) {
            return totalSaves;
        }

        int playerGoalkeeperMinutes = goalkeeperMinutes.getOrDefault(player.getId(), minutesPlayed);
        double share = playerGoalkeeperMinutes / (double) totalGoalkeeperMinutes;
        return Math.max(0, (int) Math.round(totalSaves * share));
    }
}
