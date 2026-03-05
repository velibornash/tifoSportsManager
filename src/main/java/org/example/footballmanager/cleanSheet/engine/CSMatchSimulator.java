package org.example.footballmanager.cleanSheet.engine;

import org.example.footballmanager.cleanSheet.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Cista Java simulacija meca — bez JPA, bez repozitorijuma.
 * Radi iskljucivo sa CS POJO modelima.
 */
public class CSMatchSimulator {

    private final Random rnd = new Random();

    public CSMatchResult simulate(CSTeam home, List<CSPlayer> homePlayers,
                                  CSTeam away, List<CSPlayer> awayPlayers,
                                  CSTactics homeTactics, CSTactics awayTactics,
                                  int round) {

        double homeStrength = calculateStrength(homePlayers, homeTactics, true);
        double awayStrength = calculateStrength(awayPlayers, awayTactics, false);
        double total = homeStrength + awayStrength;

        int homeGoals = generateGoals(homeStrength / total);
        int awayGoals = generateGoals(awayStrength / total);

        List<CSMatchEvent> events = new ArrayList<>();

        events.add(CSMatchEvent.builder()
                .minute(1)
                .eventType(CSEventType.MATCH_START)
                .description(home.getName() + " vs " + away.getName())
                .build());

        generateGoalEvents(events, home, homePlayers, away, awayPlayers, homeGoals, awayGoals);
        generateStats(events, home, homePlayers, away, awayPlayers, homeGoals, awayGoals);

        events.add(CSMatchEvent.builder()
                .minute(90)
                .eventType(CSEventType.MATCH_END)
                .description("Kraj meca: " + home.getName() + " " + homeGoals + ":" + awayGoals + " " + away.getName())
                .build());

        events.sort((a, b) -> Integer.compare(a.getMinute(), b.getMinute()));

        List<CSPlayerMatchStats> homeStats = assignRatings(homePlayers, events, home.getName());
        List<CSPlayerMatchStats> awayStats = assignRatings(awayPlayers, events, away.getName());

        updateFatigueAfterMatch(homePlayers);
        updateFatigueAfterMatch(awayPlayers);

        return CSMatchResult.builder()
                .homeTeamName(home.getName())
                .awayTeamName(away.getName())
                .homeTeamId(home.getId())
                .awayTeamId(away.getId())
                .homeGoals(homeGoals)
                .awayGoals(awayGoals)
                .round(round)
                .events(events)
                .summary(home.getName() + " " + homeGoals + ":" + awayGoals + " " + away.getName())
                .homePlayerStats(homeStats)
                .awayPlayerStats(awayStats)
                .build();
    }

    /**
     * Simulacija za ostale meceve u kolu — sada takodje sa punom statistikom.
     */
    public CSMatchResult simulateQuick(CSTeam home, List<CSPlayer> homePlayers,
                                       CSTeam away, List<CSPlayer> awayPlayers,
                                       int round) {
        CSTactics defaultTactics = CSTactics.builder().build();
        return simulate(home, homePlayers, away, awayPlayers, defaultTactics, defaultTactics, round);
    }

    /**
     * Racuna snagu tima na osnovu individualnih skillova po poziciji,
     * ratinga, forme, umora i taktike.
     */
    private double calculateStrength(List<CSPlayer> players, CSTactics tactics, boolean isHome) {
        if (players.isEmpty()) return 30.0;

        double avgRating = players.stream()
                .mapToInt(CSPlayer::getRating)
                .average()
                .orElse(50.0);

        double avgForm = players.stream()
                .mapToDouble(CSPlayer::getForm)
                .average()
                .orElse(5.0);

        double avgFatigue = players.stream()
                .mapToDouble(CSPlayer::getFatigue)
                .average()
                .orElse(3.0);

        // Skill-based component: each player contributes their positional skill
        double skillComponent = players.stream()
                .mapToDouble(this::getPositionalSkill)
                .average()
                .orElse(50.0);

        double base = avgRating * 0.4 + skillComponent * 0.3 + avgForm * 3.5 - avgFatigue * 2.0;

        if (isHome) base += 5.0;

        double styleBonus = switch (tactics.getStyle()) {
            case ATTACKING -> 3.0;
            case COUNTER -> 1.5;
            case BALANCED -> 0.0;
            case DEFENSIVE -> -2.0;
        };
        base += styleBonus;

        base += rnd.nextDouble() * 10.0 - 5.0;

        return Math.max(10.0, base);
    }

    /**
     * Vraca najbitniji skill igraca u zavisnosti od pozicije.
     */
    private double getPositionalSkill(CSPlayer p) {
        return switch (p.getPosition()) {
            case "GK" -> p.getGoalkeeper() * 1.5 + p.getPace() * 0.3;
            case "DEF" -> p.getDefending() * 1.2 + p.getPace() * 0.5 + p.getPassing() * 0.3;
            case "MID" -> p.getPlaymaker() * 1.0 + p.getPassing() * 0.8 + p.getTechnique() * 0.5;
            case "WNG" -> p.getPace() * 1.0 + p.getTechnique() * 0.7 + p.getPassing() * 0.5;
            case "ATT" -> p.getShooting() * 1.2 + p.getTechnique() * 0.5 + p.getPace() * 0.5;
            default -> (p.getTechnique() + p.getPassing()) * 0.5;
        };
    }

    /**
     * Dodeljuje ocene igracima na osnovu dogadjaja u mecu.
     * Bazna ocena 6.0-7.0 + bonus za gol/asist, mali random.
     */
    private List<CSPlayerMatchStats> assignRatings(List<CSPlayer> players,
                                                    List<CSMatchEvent> events,
                                                    String teamName) {
        List<CSPlayerMatchStats> stats = new ArrayList<>();
        long teamGoals = events.stream()
                .filter(e -> e.getEventType() == CSEventType.GOAL && teamName.equals(e.getTeamName()))
                .count();
        long concededGoals = events.stream()
                .filter(e -> e.getEventType() == CSEventType.GOAL && !teamName.equals(e.getTeamName()))
                .count();
        boolean cleanSheet = concededGoals == 0;

        for (CSPlayer p : players) {
            long goalsInMatch = events.stream()
                    .filter(e -> e.getEventType() == CSEventType.GOAL
                            && p.getName().equals(e.getPlayerName())
                            && teamName.equals(e.getTeamName()))
                    .count();
            long assistsInMatch = events.stream()
                    .filter(e -> e.getEventType() == CSEventType.GOAL
                            && p.getName().equals(e.getAssistName())
                            && teamName.equals(e.getTeamName()))
                    .count();

            // Wider baseline variance for less flat match grades.
            double base = 5.8 + rnd.nextDouble() * 1.2; // 5.8 - 7.0
            // Stronger attacking impact.
            base += goalsInMatch * 1.2;
            base += assistsInMatch * 0.6;
            // Form impact.
            base += (p.getForm() - 5.0) * 0.12;

            if (goalsInMatch >= 3) {
                base += 0.4; // Hat-trick bonus
            } else if (goalsInMatch == 2) {
                base += 0.2;
            }

            // Defensive contribution
            if (cleanSheet) {
                if ("GK".equals(p.getPosition())) {
                    base += 0.8;
                } else if ("DEF".equals(p.getPosition())) {
                    base += 0.5;
                }
            }
            if (concededGoals >= 3) {
                if ("GK".equals(p.getPosition())) {
                    base -= 0.6;
                } else if ("DEF".equals(p.getPosition())) {
                    base -= 0.4;
                }
            }

            // Small team result modifier
            if (teamGoals > concededGoals) {
                base += 0.1;
            } else if (teamGoals < concededGoals) {
                base -= 0.1;
            }

            double rating = Math.min(10.0, Math.max(1.0, Math.round(base * 10.0) / 10.0));

            stats.add(CSPlayerMatchStats.builder()
                    .playerId(p.getId())
                    .playerName(p.getName())
                    .position(p.getPosition())
                    .rating(rating)
                    .goals((int) goalsInMatch)
                    .assists((int) assistsInMatch)
                    .build());
        }
        return stats;
    }

    private int generateGoals(double strengthRatio) {
        // Poisson-like distribucija: ocekivani golovi na osnovu snage
        double lambda = strengthRatio * 3.0; // prosecno ~1.5 gola po timu
        int goals = 0;
        double p = Math.exp(-lambda);
        double cumulative = p;
        double uniform = rnd.nextDouble();
        while (uniform > cumulative && goals < 8) {
            goals++;
            p *= lambda / goals;
            cumulative += p;
        }
        return goals;
    }

    private void generateGoalEvents(List<CSMatchEvent> events,
                                    CSTeam home, List<CSPlayer> homePlayers,
                                    CSTeam away, List<CSPlayer> awayPlayers,
                                    int homeGoals, int awayGoals) {
        int remainingHome = homeGoals;
        int remainingAway = awayGoals;
        int lastMinute = 0;
        int currentHomeScore = 0;
        int currentAwayScore = 0;

        while (remainingHome > 0 || remainingAway > 0) {
            boolean isHome;
            if (remainingHome == 0) isHome = false;
            else if (remainingAway == 0) isHome = true;
            else isHome = rnd.nextDouble() < ((double) remainingHome / (remainingHome + remainingAway) + 0.1);

            CSTeam scoringTeam = isHome ? home : away;
            List<CSPlayer> scoringPlayers = isHome ? homePlayers : awayPlayers;

            // Scorer — bias ka napadacima
            CSPlayer scorer = pickScorer(scoringPlayers);
            CSPlayer assist = pickAssist(scoringPlayers, scorer);

            int remaining = remainingHome + remainingAway - 1;
            int minMinute = lastMinute + 1;
            int maxMinute = 90 - remaining * 3;
            if (maxMinute < minMinute) maxMinute = minMinute;
            if (maxMinute > 90) maxMinute = 90;
            int minute = minMinute + rnd.nextInt(Math.max(1, maxMinute - minMinute + 1));

            if (isHome) { remainingHome--; currentHomeScore++; }
            else { remainingAway--; currentAwayScore++; }

            String scoreAfter = currentHomeScore + ":" + currentAwayScore;

            // Update sezonski golovi/asistencije
            scorer.setGoals(scorer.getGoals() + 1);
            if (assist != null) assist.setAssists(assist.getAssists() + 1);

            events.add(CSMatchEvent.builder()
                    .minute(minute)
                    .eventType(CSEventType.GOAL)
                    .playerName(scorer.getName())
                    .assistName(assist != null ? assist.getName() : null)
                    .teamName(scoringTeam.getName())
                    .description(scorer.getName() + " (" + scoringTeam.getName() + ")")
                    .scoreAfterGoal(scoreAfter)
                    .build());

            lastMinute = minute;
        }
    }

    private CSPlayer pickScorer(List<CSPlayer> players) {
        if (players.isEmpty()) return null;

        // Tezinski izbor — napadaci i krilni imaju vecu sansu
        List<CSPlayer> weighted = new ArrayList<>();
        for (CSPlayer p : players) {
            int weight = switch (p.getPosition()) {
                case "ATT" -> 5;
                case "WNG" -> 3;
                case "MID" -> 2;
                case "DEF" -> 1;
                default -> 0; // GK
            };
            for (int i = 0; i < weight; i++) weighted.add(p);
        }
        if (weighted.isEmpty()) return players.get(rnd.nextInt(players.size()));
        return weighted.get(rnd.nextInt(weighted.size()));
    }

    private CSPlayer pickAssist(List<CSPlayer> players, CSPlayer scorer) {
        if (players.size() < 2) return null;
        if (rnd.nextDouble() < 0.3) return null; // 30% sansa nema asista

        List<CSPlayer> candidates = players.stream()
                .filter(p -> !p.getId().equals(scorer.getId()))
                .toList();
        if (candidates.isEmpty()) return null;

        // Bias ka playmakerima i krilnim
        List<CSPlayer> weighted = new ArrayList<>();
        for (CSPlayer p : candidates) {
            int weight = switch (p.getPosition()) {
                case "MID" -> 4;
                case "WNG" -> 3;
                case "ATT" -> 2;
                case "DEF" -> 1;
                default -> 1;
            };
            for (int i = 0; i < weight; i++) weighted.add(p);
        }
        return weighted.get(rnd.nextInt(weighted.size()));
    }

    private void generateStats(List<CSMatchEvent> events,
                               CSTeam home, List<CSPlayer> homePlayers,
                               CSTeam away, List<CSPlayer> awayPlayers,
                               int homeGoals, int awayGoals) {

        // Sutevi u okvir
        int homeShotsOn = homeGoals + rnd.nextInt(5) + 1;
        int awayShotsOn = awayGoals + rnd.nextInt(5) + 1;
        for (int i = 0; i < homeShotsOn; i++) {
            events.add(buildStatEvent(CSEventType.SHOT_ON_TARGET, home, pickScorer(homePlayers)));
        }
        for (int i = 0; i < awayShotsOn; i++) {
            events.add(buildStatEvent(CSEventType.SHOT_ON_TARGET, away, pickScorer(awayPlayers)));
        }

        // Sutevi van okvira
        int homeShotsOff = rnd.nextInt(6) + 2;
        int awayShotsOff = rnd.nextInt(6) + 2;
        for (int i = 0; i < homeShotsOff; i++) {
            events.add(buildStatEvent(CSEventType.SHOT_OFF_TARGET, home, pickScorer(homePlayers)));
        }
        for (int i = 0; i < awayShotsOff; i++) {
            events.add(buildStatEvent(CSEventType.SHOT_OFF_TARGET, away, pickScorer(awayPlayers)));
        }

        // Korneri
        int homeCorners = rnd.nextInt(10) + 2;
        int awayCorners = rnd.nextInt(10) + 2;
        for (int i = 0; i < homeCorners; i++) {
            events.add(buildStatEvent(CSEventType.CORNER, home, randomPlayer(homePlayers)));
        }
        for (int i = 0; i < awayCorners; i++) {
            events.add(buildStatEvent(CSEventType.CORNER, away, randomPlayer(awayPlayers)));
        }

        // Zuti kartoni
        int homeYellows = rnd.nextInt(4);
        int awayYellows = rnd.nextInt(4);
        for (int i = 0; i < homeYellows; i++) {
            events.add(buildStatEvent(CSEventType.YELLOW_CARD, home, randomPlayer(homePlayers)));
        }
        for (int i = 0; i < awayYellows; i++) {
            events.add(buildStatEvent(CSEventType.YELLOW_CARD, away, randomPlayer(awayPlayers)));
        }

        // Crveni kartoni (retki)
        if (rnd.nextDouble() < 0.08) {
            events.add(buildStatEvent(CSEventType.RED_CARD, home, randomPlayer(homePlayers)));
        }
        if (rnd.nextDouble() < 0.08) {
            events.add(buildStatEvent(CSEventType.RED_CARD, away, randomPlayer(awayPlayers)));
        }

        // Penali
        if (rnd.nextDouble() < 0.12) {
            CSPlayer taker = pickScorer(homePlayers);
            boolean scored = rnd.nextDouble() < 0.75;
            events.add(CSMatchEvent.builder()
                    .minute(rnd.nextInt(90) + 1)
                    .eventType(CSEventType.PENALTY)
                    .playerName(taker != null ? taker.getName() : "?")
                    .teamName(home.getName())
                    .penaltyScored(scored)
                    .description((scored ? "Gol" : "Promasen") + " penal - " + (taker != null ? taker.getName() : "?"))
                    .build());
        }
        if (rnd.nextDouble() < 0.12) {
            CSPlayer taker = pickScorer(awayPlayers);
            boolean scored = rnd.nextDouble() < 0.75;
            events.add(CSMatchEvent.builder()
                    .minute(rnd.nextInt(90) + 1)
                    .eventType(CSEventType.PENALTY)
                    .playerName(taker != null ? taker.getName() : "?")
                    .teamName(away.getName())
                    .penaltyScored(scored)
                    .description((scored ? "Gol" : "Promasen") + " penal - " + (taker != null ? taker.getName() : "?"))
                    .build());
        }
    }

    private CSMatchEvent buildStatEvent(CSEventType type, CSTeam team, CSPlayer player) {
        return CSMatchEvent.builder()
                .minute(rnd.nextInt(90) + 1)
                .eventType(type)
                .playerName(player != null ? player.getName() : "?")
                .teamName(team.getName())
                .description(type.name() + " - " + (player != null ? player.getName() : "?"))
                .build();
    }

    private CSPlayer randomPlayer(List<CSPlayer> players) {
        if (players.isEmpty()) return null;
        return players.get(rnd.nextInt(players.size()));
    }

    private void updateFatigueAfterMatch(List<CSPlayer> players) {
        for (CSPlayer p : players) {
            double increase = 1.5 + rnd.nextDouble() * 1.5; // +1.5 do 3.0
            p.setFatigue(Math.min(10.0, p.getFatigue() + increase));

            // Forma se blago menja
            double formChange = (rnd.nextDouble() - 0.5) * 1.0;
            p.setForm(Math.max(1.0, Math.min(10.0, p.getForm() + formChange)));
        }
    }
}
