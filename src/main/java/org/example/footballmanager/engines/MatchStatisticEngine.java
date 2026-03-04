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
    public void savePlayerStats(Match match, List<Player> players, List<GoalEvent> allGoals, List<YellowCardEvent> allYellows, List<RedCardEvent> allReds) {
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
            stats.setMinutesPlayed(90);
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
            // oblik: 45' ⚽ Igrač (asistencija: Igrač)
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
        // --- Ocene igrača ---
        sb.append("Ocene igrača - ").append(match.getHomeTeam().getName()).append("\n");
        appendPlayerRatings(sb, homePlayers, rt.runtimeGoals);
        sb.append("\nOcene igrača - ").append(match.getAwayTeam().getName()).append("\n");
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
// U metodi updateLeagueTableForMatchDay ili gde god treba

        SeasonCompetition sc = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, season.getSeasonYear())
                .orElseThrow(() -> new RuntimeException("Sezona nije pronađena za ligu"));

// Dohvati SVE CompetitionEntry za tu sezonu lige
        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(sc);

// Izvuci ID-ove timova
        List<Long> teamIds = entries.stream()
                .map(entry -> entry.getTeam().getId())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

// Sad koristi teamIds za pretragu mečeva
        List<Match> allLeagueMatches = matchRepository.findByHomeTeamIdInAndAwayTeamIdIn(teamIds, teamIds);

        log.info("Ukupno pronađeno {} mečeva u ligi {}", allLeagueMatches.size(), league.getName());

        if (allLeagueMatches.isEmpty()) {
            log.warn("Nema mečeva u ligi za ažuriranje tabele!");
            return;
        }

        // 2. Sortiraj OPADAJUĆE po datumu (najnoviji prvi)
        allLeagueMatches.sort(Comparator.comparing(Match::getMatchDate, Comparator.reverseOrder()));

        // 3. Uzmi samo poslednjih 5 (poslednje kolo)
        List<Match> lastMatches = allLeagueMatches.stream()
                .limit(5)
                .toList();

        log.info("Ažuriranje tabele na osnovu poslednjih {} mečeva (poslednje kolo)", lastMatches.size());

        // 5. Ažuriraj samo za poslednjih 5 mečeva
        for (Match match : lastMatches) {

            Team home = match.getHomeTeam();
            Team away = match.getAwayTeam();

            CompetitionEntry homeEntry = competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, home)
                    .stream().findFirst().orElse(null);
            CompetitionEntry awayEntry = competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, away)
                    .stream().findFirst().orElse(null);

            if (homeEntry == null || awayEntry == null) {
                log.warn("Nema entry-ja za timove u meču {} vs {}", home.getName(), away.getName());
                continue;
            }

            int homeG = match.getHomeGoals();
            int awayG = match.getAwayGoals();

            homeEntry.setWins(homeEntry.getWins() + (homeG > awayG ? 1 : 0));
            homeEntry.setDraws(homeEntry.getDraws() + (homeG == awayG ? 1 : 0));
            homeEntry.setLosses(homeEntry.getLosses() + (homeG < awayG ? 1 : 0));

            awayEntry.setWins(awayEntry.getWins() + (awayG > homeG ? 1 : 0));
            awayEntry.setDraws(awayEntry.getDraws() + (awayG == homeG ? 1 : 0));
            awayEntry.setLosses(awayEntry.getLosses() + (awayG < homeG ? 1 : 0));

            competitionEntryRepository.save(homeEntry);
            competitionEntryRepository.save(awayEntry);

            log.info("Ažuriran meč {} {}:{} {} → Home W/D/L: {}/{}/{} | Away W/D/L: {}/{}/{}",
                    home.getName(), homeG, awayG, away.getName(),
                    homeEntry.getWins(), homeEntry.getDraws(), homeEntry.getLosses(),
                    awayEntry.getWins(), awayEntry.getDraws(), awayEntry.getLosses());
        }

        // 6. Sortiraj i postavi pozicije
        List<CompetitionEntry> updatedEntries = competitionEntryRepository.findBySeasonCompetition(sc);
        updatedEntries.sort(Comparator.comparing(CompetitionEntry::getPoints, Comparator.reverseOrder())
                .thenComparing(e -> e.getGoalsScored() - e.getGoalsConceded(), Comparator.reverseOrder())
                .thenComparing(CompetitionEntry::getGoalsScored, Comparator.reverseOrder()));

        for (int pos = 0; pos < updatedEntries.size(); pos++) {
            CompetitionEntry entry = updatedEntries.get(pos);
            entry.setPosition(pos + 1);
            competitionEntryRepository.save(entry);
        }

        log.info("Tabela lige ažurirana na osnovu poslednjih {} mečeva (poslednje kolo)", lastMatches.size());
    }

    public void generateFakeAdditionalStats(Match match, List<Player> homePlayers, List<Player> awayPlayers, int homeGoals, int awayGoals, Random rnd) {

        Team home = match.getHomeTeam();
        Team away = match.getAwayTeam();

        // 1. Realistični brojevi statistike
        // Šutevi: pobednik / bolji tim ima više
        int homeShotsTotal     = homeGoals + rnd.nextInt(6) + 4;           // 4–9 + golovi
        int awayShotsTotal     = awayGoals + rnd.nextInt(6) + 4;

        // Šutevi u okvir: ~35–55% od ukupnih šuteva
        int homeShotsOnTarget  = Math.min(homeGoals + rnd.nextInt(5), (int)(homeShotsTotal * (0.35 + rnd.nextDouble() * 0.2)));
        int awayShotsOnTarget  = Math.min(awayGoals + rnd.nextInt(5), (int)(awayShotsTotal * (0.35 + rnd.nextDouble() * 0.2)));

        int homeShotsOffTarget = homeShotsTotal-homeShotsOnTarget;
        int awayShotsOffTarget = awayShotsTotal-awayShotsOnTarget;

        // Korneri: 2–12 po timu, više kod boljeg tima
        int homeCorners        = rnd.nextInt(11) + 2 + (homeGoals > awayGoals ? 2 : 0);
        int awayCorners        = rnd.nextInt(11) + 2 + (awayGoals > homeGoals ? 2 : 0);

        // Faulovi: 8–25 po timu
        int homeFouls          = rnd.nextInt(18) + 8;
        int awayFouls          = rnd.nextInt(18) + 8;

        // Ofsajdi: 0–8
        int homeOffsides       = rnd.nextInt(9);
        int awayOffsides       = rnd.nextInt(9);

        // Penali: 0–2 po meču, veća šansa ako je mnogo faulova u šesnaestercu
        int totalPenalties     = rnd.nextInt(3); // 0–2
        boolean homePenalty    = totalPenalties > 0 && rnd.nextBoolean();
        boolean awayPenalty    = totalPenalties > 1 || (totalPenalties == 1 && !homePenalty);

        // Kartoni
        int homeYellows        = rnd.nextInt(5) + (homeFouls > 18 ? 1 : 0); // više faulova → više kartona
        int awayYellows        = rnd.nextInt(5) + (awayFouls > 18 ? 1 : 0);
        int homeReds           = rnd.nextInt(2);
        int awayReds           = rnd.nextInt(2);

        // 2. Snimanje događaja u bazu (samo deo, da ne zatrpamo bazu)

        // Korneri – snimamo ~40–60% da izgleda realno
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

        // Šutevi na gol – snimamo deo šuteva u okvir
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

        // Šutevi na gol – snimamo deo šuteva van okvira
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

        // Žuti kartoni – snimamo ~60% da ne bude previše
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

        // Crveni kartoni – retki, snimamo sve
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

        log.info("Generisana fake statistika za simulirani meč {}:{} {} vs {} – šutevi {}/{}, korneri {}/{}, kartoni {}/{}",
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