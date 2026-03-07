package org.example.footballmanager.engines;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.*;
import org.example.footballmanager.model.event.*;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.util.match.MatchRatingCalculator;
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
                InjuryEvent injury = new InjuryEvent();
                injury.setMinute(random.nextInt(90) + 1);
                injury.setPlayer(player);
                injury.setMatch(match);
                injury.apply();
            }
            if (random.nextDouble() < 0.1) {
                YellowCardEvent yc = new YellowCardEvent();
                yc.setMinute(random.nextInt(90) + 1);
                yc.setPlayer(player);
                yc.setMatch(match);
                yc.apply();
            }
        }
    }
    public void savePlayerStats(Match match,
                                List<Player> players,
                                List<GoalEvent> allGoals,
                                List<YellowCardEvent> allYellows,
                                List<RedCardEvent> allReds,
                                Map<Long, Integer> minutesByPlayerId) {
        for (Player player : players) {
            long goals = allGoals.stream()
                    .filter(g -> g.getScorer() != null && g.getScorer().equals(player))
                    .count();

            long assists = allGoals.stream().filter(g -> g.getAssistant() != null && g.getAssistant().equals(player)).count();
            long yellowCards = allYellows.stream().filter(y -> y.getPlayer() != null && y.getPlayer().equals(player)).count();
            long redCards = allReds.stream().filter(r -> r.getPlayer() != null && r.getPlayer().equals(player)).count();

            MatchPlayerStats stats = new MatchPlayerStats();
            stats.setMatch(match);
            stats.setPlayer(player);
            stats.setGoals((int) goals);
            stats.setAssists((int) assists);
            stats.setYellowCards((int) yellowCards);
            stats.setRedCards((int) redCards);
            stats.setMinutesPlayed(Math.max(0, minutesByPlayerId.getOrDefault(player.getId(), 90)));
            stats.setRating(player.getRating());
            matchPlayerStatsRepository.save(stats);
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
            String scorerName = g.getScorer() != null ? g.getScorer().getName() : "N/A";
            String assistName = g.getAssistant() != null ? g.getAssistant().getName() : null;

            String desc;
            if (assistName != null) {
                desc = String.format("%d' ⚽ %s (asistencija: %s)", g.getMinute(), scorerName, assistName);
            } else {
                desc = String.format("%d' ⚽ %s", g.getMinute(), scorerName);
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
                    .filter(g -> g.getScorer() != null && g.getScorer().equals(player))
                    .count();

            long assists = allGoals.stream()
                    .filter(g -> g.getAssistant() != null && g.getAssistant().equals(player))
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

        // 1. RealistiÄni brojevi statistike
        // Å utevi: pobednik / bolji tim ima viÅ¡e
        int homeShotsTotal     = homeGoals + rnd.nextInt(6) + 4;           // 4â€“9 + golovi
        int awayShotsTotal     = awayGoals + rnd.nextInt(6) + 4;

        // Shots on target: keep realistic ratio, but never below valid goals.
        int homeOnTargetBase = (int)(homeShotsTotal * (0.35 + rnd.nextDouble() * 0.2));
        int awayOnTargetBase = (int)(awayShotsTotal * (0.35 + rnd.nextDouble() * 0.2));
        int homeShotsOnTarget  = Math.min(homeShotsTotal, Math.max(homeGoals, homeOnTargetBase));
        int awayShotsOnTarget  = Math.min(awayShotsTotal, Math.max(awayGoals, awayOnTargetBase));

        int homeShotsOffTarget = homeShotsTotal-homeShotsOnTarget;
        int awayShotsOffTarget = awayShotsTotal-awayShotsOnTarget;

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
            CornerEvent c = new CornerEvent();
            c.setMatch(match);
            c.setTeam(home);
            c.setMinute(rnd.nextInt(90) + 1);
            c.setPlayer(getRandomPlayerByPosition(homePlayers, List.of(Position.WNG, Position.MID, Position.ATT), rnd));
            matchEventRepository.save(c);
        }

        for (int i = 0; i < awayCornersToSave; i++) {
            CornerEvent c = new CornerEvent();
            c.setMatch(match);
            c.setTeam(away);
            c.setMinute(rnd.nextInt(90) + 1);
            c.setPlayer(getRandomPlayerByPosition(awayPlayers, List.of(Position.WNG, Position.MID, Position.ATT), rnd));
            matchEventRepository.save(c);
        }

        // šutevi na gol â€“ snimamo deo šuteva u okvir
        for (int i = 0; i < homeShotsOnTarget; i++) {
            ShotOnTargetEvent s = new ShotOnTargetEvent();
            s.setMatch(match);
            s.setTeam(home);
            s.setMinute(rnd.nextInt(90) + 1);
            s.setShooter(getRandomPlayerByPosition(homePlayers, List.of(Position.ATT, Position.MID, Position.WNG), rnd));
            matchEventRepository.save(s);
        }

        for (int i = 0; i < awayShotsOnTarget; i++) {
            ShotOnTargetEvent s = new ShotOnTargetEvent();
            s.setMatch(match);
            s.setTeam(away);
            s.setMinute(rnd.nextInt(90) + 1);
            s.setShooter(getRandomPlayerByPosition(awayPlayers, List.of(Position.ATT, Position.MID, Position.WNG), rnd));
            matchEventRepository.save(s);
        }

        // Å utevi na gol â€“ snimamo deo Å¡uteva van okvira
        for (int i = 0; i < homeShotsOffTarget; i++) {
            ShotOffTargetEvent s = new ShotOffTargetEvent();
            s.setMatch(match);
            s.setTeam(home);
            s.setMinute(rnd.nextInt(90) + 1);
            s.setShooter(getRandomPlayerByPosition(homePlayers, List.of(Position.ATT, Position.MID, Position.WNG), rnd));
            matchEventRepository.save(s);
        }

        for (int i = 0; i < awayShotsOffTarget; i++) {
            ShotOffTargetEvent s = new ShotOffTargetEvent();
            s.setMatch(match);
            s.setTeam(away);
            s.setMinute(rnd.nextInt(90) + 1);
            s.setShooter(getRandomPlayerByPosition(awayPlayers, List.of(Position.ATT, Position.MID, Position.WNG), rnd));
            matchEventRepository.save(s);
        }

        // Penali (ako ih ima)
        if (homePenalty) {
            PenaltyEvent p = new PenaltyEvent();
            p.setMatch(match);
            p.setTeam(home);
            p.setMinute(rnd.nextInt(90) + 1);
            p.setTaker(getRandomPlayerByPosition(homePlayers, List.of(Position.ATT, Position.MID), rnd));
            p.setScored(rnd.nextDouble() < 0.75); // ~75% uspešnosti penala
            matchEventRepository.save(p);
        }

        if (awayPenalty) {
            PenaltyEvent p = new PenaltyEvent();
            p.setMatch(match);
            p.setTeam(away);
            p.setMinute(rnd.nextInt(90) + 1);
            p.setTaker(getRandomPlayerByPosition(awayPlayers, List.of(Position.ATT, Position.MID), rnd));
            p.setScored(rnd.nextDouble() < 0.75);
            matchEventRepository.save(p);
        }

        // žuti kartoni â€“ snimamo ~60% da ne bude previše
        int homeYellowsToSave = (int)(homeYellows * 0.6);
        for (int i = 0; i < homeYellowsToSave; i++) {
            Player offender = getRandomPlayerByPosition(homePlayers, null, rnd); // bilo ko
            YellowCardEvent yc = new YellowCardEvent();
            yc.setMatch(match);
            yc.setTeam(home);
            yc.setPlayer(offender);
            yc.setMinute(rnd.nextInt(90) + 1);
            matchEventRepository.save(yc);
        }

        // Isto za goste...
        int awayYellowsToSave = (int)(awayYellows * 0.6);
        for (int i = 0; i < awayYellowsToSave; i++) {
            Player offender = getRandomPlayerByPosition(awayPlayers, null, rnd);
            YellowCardEvent yc = new YellowCardEvent();
            yc.setMatch(match);
            yc.setTeam(away);
            yc.setPlayer(offender);
            yc.setMinute(rnd.nextInt(90) + 1);
            matchEventRepository.save(yc);
        }

        // Crveni kartoni â€“ retki, snimamo sve
        for (int i = 0; i < homeReds; i++) {
            Player offender = getRandomPlayerByPosition(homePlayers, null, rnd);
            RedCardEvent rc = new RedCardEvent();
            rc.setMatch(match);
            rc.setTeam(home);
            rc.setPlayer(offender);
            rc.setMinute(rnd.nextInt(90) + 1);
            matchEventRepository.save(rc);
        }

        // Isto za goste
        for (int i = 0; i < awayReds; i++) {
            Player offender = getRandomPlayerByPosition(awayPlayers, null, rnd);
            RedCardEvent rc = new RedCardEvent();
            rc.setMatch(match);
            rc.setTeam(away);
            rc.setPlayer(offender);
            rc.setMinute(rnd.nextInt(90) + 1);
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
}

