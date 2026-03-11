package org.example.footballmanager.cleanSheet.engine;

import org.example.footballmanager.cleanSheet.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Cista Java simulacija meca — bez JPA, bez repozitorijuma.
 * Radi iskljucivo sa CS POJO modelima.
 */
public class CSMatchSimulator {

    private final Random rnd = new Random();

    public CSMatchResult simulate(CSTeam home, List<CSPlayer> homePlayers, List<CSPlayer> homeBench,
                                  CSTeam away, List<CSPlayer> awayPlayers, List<CSPlayer> awayBench,
                                  CSTactics homeTactics, CSTactics awayTactics,
                                  int round) {

        // Create mutable copies to track on-field players
        List<CSPlayer> homeOnField = new ArrayList<>(homePlayers);
        List<CSPlayer> awayOnField = new ArrayList<>(awayPlayers);

        // Snaga se racuna na osnovu startnih 11 i taktike (formacija + stil)
        double homeStrength = calculateStrength(homePlayers, homeTactics, true);
        double awayStrength = calculateStrength(awayPlayers, awayTactics, false);
        double total = homeStrength + awayStrength;

        int homeGoals = generateGoals(homeStrength / total);
        int awayGoals = generateGoals(awayStrength / total);

        List<CSMatchEvent> events = new ArrayList<>();

        events.add(CSMatchEvent.builder()
                .minute(1)
                .eventType(CSEventType.MATCH_START)
                .description("Kick-off: " + home.getName() + " vs " + away.getName())
                .build());

        // Initialize minutes tracking for all potential players
        Map<Long, Integer> homeMinutes = new java.util.HashMap<>();
        Map<Long, Integer> awayMinutes = new java.util.HashMap<>();
        
        // Track who is currently on field (true = on field initially)
        Map<Long, Boolean> homeOnFieldStatus = new java.util.HashMap<>();
        Map<Long, Boolean> awayOnFieldStatus = new java.util.HashMap<>();
        
        homePlayers.forEach(p -> {
            homeMinutes.put(p.getId(), 90);
            homeOnFieldStatus.put(p.getId(), true);
        });
        homeBench.forEach(p -> {
            homeMinutes.put(p.getId(), 0);
            homeOnFieldStatus.put(p.getId(), false);
        });
        
        awayPlayers.forEach(p -> {
            awayMinutes.put(p.getId(), 90);
            awayOnFieldStatus.put(p.getId(), true);
        });
        awayBench.forEach(p -> {
            awayMinutes.put(p.getId(), 0);
            awayOnFieldStatus.put(p.getId(), false);
        });

        // Apply substitutions BEFORE generating events
        applySubstitutions(events, home, homeOnField, homeBench, homeMinutes, homeOnFieldStatus);
        applySubstitutions(events, away, awayOnField, awayBench, awayMinutes, awayOnFieldStatus);

        // Generate goals and stats with the final on-field players
        generateGoalEvents(events, home, homeOnField, away, awayOnField, homeGoals, awayGoals);
        generateStats(events, home, homeOnField, away, awayOnField, homeGoals, awayGoals);

        events.add(CSMatchEvent.builder()
                .minute(90)
                .eventType(CSEventType.MATCH_END)
                .description("Full-time: " + home.getName() + " " + homeGoals + ":" + awayGoals + " " + away.getName())
                .build());

        events.sort((a, b) -> Integer.compare(a.getMinute(), b.getMinute()));

        // Create full player lists for rating assignment (includes bench players who never played)
        List<CSPlayer> homeAll = new ArrayList<>(homePlayers);
        homeAll.addAll(homeBench);
        List<CSPlayer> awayAll = new ArrayList<>(awayPlayers);
        awayAll.addAll(awayBench);

        List<CSPlayerMatchStats> homeStats = assignRatings(homeAll, events, home.getName(), homeMinutes);
        List<CSPlayerMatchStats> awayStats = assignRatings(awayAll, events, away.getName(), awayMinutes);

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
    public CSMatchResult simulateQuick(CSTeam home, List<CSPlayer> homePlayers, List<CSPlayer> homeBench,
                                       CSTeam away, List<CSPlayer> awayPlayers, List<CSPlayer> awayBench,
                                       int round) {
        CSTactics defaultTactics = CSTactics.builder().build();
        return simulate(home, homePlayers, homeBench, away, awayPlayers, awayBench, defaultTactics, defaultTactics, round);
    }

    public List<CSPlayer> pickStartingEleven(List<CSPlayer> roster, List<Long> preferredStarterIds) {
        if (roster == null || roster.isEmpty()) return List.of();
        List<CSPlayer> picks = new ArrayList<>();
        if (preferredStarterIds != null) {
            for (Long id : preferredStarterIds) {
                if (id == null) continue;
                CSPlayer player = roster.stream().filter(p -> id.equals(p.getId())).findFirst().orElse(null);
                if (player != null && picks.stream().noneMatch(p -> p.getId().equals(player.getId()))) {
                    picks.add(player);
                    if (picks.size() >= 11) break;
                }
            }
        }
        if (picks.size() < 11) {
            List<CSPlayer> fallback = new ArrayList<>(roster);
            fallback.sort((a, b) -> Integer.compare(b.getRating(), a.getRating()));
            for (CSPlayer player : fallback) {
                if (picks.stream().noneMatch(p -> p.getId().equals(player.getId()))) {
                    picks.add(player);
                    if (picks.size() >= 11) break;
                }
            }
        }
        return picks;
    }

    public List<CSPlayer> pickBenchPlayers(List<CSPlayer> roster, List<CSPlayer> starters, List<Long> preferredBenchIds) {
        if (roster == null || roster.isEmpty()) return List.of();
        java.util.Set<Long> starterIds = starters.stream().map(CSPlayer::getId).collect(java.util.stream.Collectors.toSet());
        List<CSPlayer> bench = new ArrayList<>();
        if (preferredBenchIds != null) {
            for (Long id : preferredBenchIds) {
                if (id == null) continue;
                CSPlayer player = roster.stream().filter(p -> id.equals(p.getId()) && !starterIds.contains(p.getId())).findFirst().orElse(null);
                if (player != null && bench.stream().noneMatch(p -> p.getId().equals(player.getId()))) {
                    bench.add(player);
                    if (bench.size() >= 7) break;
                }
            }
        }
        if (bench.size() < 7) {
            List<CSPlayer> fallback = new ArrayList<>(roster);
            fallback.sort((a, b) -> Integer.compare(b.getRating(), a.getRating()));
            for (CSPlayer player : fallback) {
                if (starterIds.contains(player.getId())) continue;
                if (bench.stream().anyMatch(p -> p.getId().equals(player.getId()))) continue;
                bench.add(player);
                if (bench.size() >= 7) break;
            }
        }
        return bench;
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
     * VAZNO: Samo igraci koji su stvarno igrali (minutesPlayed > 0) dobijaju rating.
     * Igraci sa klupe koji nisu usli u igru imaju minutesPlayed = 0 i NE dobijaju rating.
     * Igraci koji su izasli (substituted out) i igraci koji su usli (substituted in) 
     * DOBIJAJU rating i upisuju im se golovi/asistencije.
     */
    private List<CSPlayerMatchStats> assignRatings(List<CSPlayer> players,
                                                   List<CSMatchEvent> events,
                                                   String teamName,
                                                   java.util.Map<Long, Integer> minutesByPlayer) {
        List<CSPlayerMatchStats> stats = new ArrayList<>();
        long teamGoals = events.stream()
                .filter(e -> e.getEventType() == CSEventType.GOAL && teamName.equals(e.getTeamName()))
                .count();
        long concededGoals = events.stream()
                .filter(e -> e.getEventType() == CSEventType.GOAL && !teamName.equals(e.getTeamName()))
                .count();
        boolean cleanSheet = concededGoals == 0;

        for (CSPlayer p : players) {
            int goalsInMatch = 0;
            int assistsInMatch = 0;
            if (events != null) {
                for (CSMatchEvent e : events) {
                    if (e.getEventType() == CSEventType.GOAL) {
                        if (p.getName().equals(e.getPlayerName())) {
                            goalsInMatch++;
                        }
                        if (p.getName().equals(e.getAssistName())) {
                            assistsInMatch++;
                        }
                    }
                }
            }
            int minutesPlayed = minutesByPlayer.getOrDefault(p.getId(), 0);

            double base;
            if (goalsInMatch >= 2 || assistsInMatch >= 2) {
                base = 7.0 + rnd.nextDouble() * 1.5;
            } else if (goalsInMatch >= 1 || assistsInMatch >= 1) {
                base = 6.5 + rnd.nextDouble() * 1.2;
            } else {
                base = 5.5 + rnd.nextDouble() * 1.0;
            }

            if ("GK".equals(p.getPosition())) {
                base += cleanSheet ? 1.0 : -0.5;
            } else if ("DEF".equals(p.getPosition())) {
                base += cleanSheet ? 0.5 : 0.0;
            }

            if (minutesPlayed >= 60) base += 0.2;
            else if (minutesPlayed <= 30) base -= 0.3;

            if (goalsInMatch >= 3) base += 0.4;
            else if (goalsInMatch == 2) base += 0.2;

            if (minutesPlayed >= 45) {
                if (cleanSheet) {
                    if ("GK".equals(p.getPosition())) base += 0.8;
                    else if ("DEF".equals(p.getPosition())) base += 0.5;
                }
                if (concededGoals >= 3) {
                    if ("GK".equals(p.getPosition())) base -= 0.6;
                    else if ("DEF".equals(p.getPosition())) base -= 0.4;
                }
            }

            if (teamGoals > concededGoals) base += 0.1;
            else if (teamGoals < concededGoals) base -= 0.1;

            // Extended stats
            int passesAttempted = 0, passesCompleted = 0, tackles = 0, interceptions = 0;
            int duelsWon = 0, duelsLost = 0, aerialDuelsWon = 0, keyPasses = 0;
            int dribblesCompleted = 0, dribblesLost = 0, saves = 0;
            double distanceCovered = 0.0;

            if (minutesPlayed > 0) {
                passesAttempted = (int) (minutesPlayed * (0.4 + rnd.nextDouble() * 0.4));
                passesCompleted = (int) (passesAttempted * (0.65 + rnd.nextDouble() * 0.25));
                distanceCovered = Math.round(minutesPlayed * (0.08 + rnd.nextDouble() * 0.04) * 10.0) / 10.0;

                switch (p.getPosition()) {
                    case "GK" -> {
                        saves = (int) (concededGoals == 0 ? rnd.nextInt(3) : rnd.nextInt(5) + 2);
                        duelsWon = (int) (rnd.nextDouble() * 2);
                        aerialDuelsWon = (int) (rnd.nextDouble() * 2);
                    }
                    case "DEF" -> {
                        tackles = (int) (minutesPlayed / 15.0 + rnd.nextInt(3));
                        interceptions = (int) (minutesPlayed / 20.0 + rnd.nextInt(2));
                        duelsWon = (int) (minutesPlayed / 10.0 + rnd.nextInt(4));
                        duelsLost = (int) (minutesPlayed / 20.0 + rnd.nextInt(3));
                        aerialDuelsWon = (int) (minutesPlayed / 12.0 + rnd.nextInt(3));
                    }
                    case "MID" -> {
                        tackles = (int) (minutesPlayed / 20.0 + rnd.nextInt(3));
                        interceptions = (int) (minutesPlayed / 18.0 + rnd.nextInt(3));
                        duelsWon = (int) (minutesPlayed / 12.0 + rnd.nextInt(4));
                        duelsLost = (int) (minutesPlayed / 15.0 + rnd.nextInt(4));
                        keyPasses = (int) (minutesPlayed / 25.0 + rnd.nextInt(3));
                        dribblesCompleted = (int) (minutesPlayed / 30.0 + rnd.nextInt(4));
                        dribblesLost = (int) (minutesPlayed / 40.0 + rnd.nextInt(3));
                    }
                    case "WNG" -> {
                        tackles = (int) (minutesPlayed / 25.0 + rnd.nextInt(2));
                        duelsWon = (int) (minutesPlayed / 10.0 + rnd.nextInt(5));
                        duelsLost = (int) (minutesPlayed / 12.0 + rnd.nextInt(4));
                        keyPasses = (int) (minutesPlayed / 20.0 + rnd.nextInt(4));
                        dribblesCompleted = (int) (minutesPlayed / 15.0 + rnd.nextInt(5));
                        dribblesLost = (int) (minutesPlayed / 20.0 + rnd.nextInt(4));
                    }
                    case "ATT" -> {
                        tackles = (int) (rnd.nextDouble() * 1);
                        duelsWon = (int) (minutesPlayed / 12.0 + rnd.nextInt(4));
                        duelsLost = (int) (minutesPlayed / 15.0 + rnd.nextInt(3));
                        keyPasses = (int) (minutesPlayed / 30.0 + rnd.nextInt(2));
                        dribblesCompleted = (int) (minutesPlayed / 18.0 + rnd.nextInt(4));
                        dribblesLost = (int) (minutesPlayed / 25.0 + rnd.nextInt(3));
                    }
                }
            }

            double rating = Math.min(10.0, Math.max(1.0, Math.round(base * 10.0) / 10.0));

            stats.add(CSPlayerMatchStats.builder()
                    .playerId(p.getId())
                    .playerName(p.getName())
                    .position(p.getPosition())
                    .rating(rating)
                    .goals((int) goalsInMatch)
                    .assists((int) assistsInMatch)
                    .minutesPlayed(minutesPlayed)
                    .passesAttempted(passesAttempted)
                    .passesCompleted(passesCompleted)
                    .tackles(tackles)
                    .interceptions(interceptions)
                    .duelsWon(duelsWon)
                    .duelsLost(duelsLost)
                    .aerialDuelsWon(aerialDuelsWon)
                    .keyPasses(keyPasses)
                    .dribblesCompleted(dribblesCompleted)
                    .dribblesLost(dribblesLost)
                    .distanceCovered(distanceCovered)
                    .saves(saves)
                    .cleanSheet(cleanSheet)
                    .goalsConceded((int) concededGoals)
                    .build());
        }
        return stats;
    }

    private void applySubstitutions(List<CSMatchEvent> events,
                                    CSTeam team,
                                    List<CSPlayer> onField,
                                    List<CSPlayer> bench,
                                    java.util.Map<Long, Integer> minutesByPlayer,
                                    java.util.Map<Long, Boolean> onFieldStatus) {
        if (onField.isEmpty() || bench.isEmpty()) return;
        int maxSubs = Math.min(3, bench.size());
        int subs = rnd.nextDouble() < 0.55 ? rnd.nextInt(maxSubs + 1) : 0;
        for (int i = 0; i < subs; i++) {
            int minute = 55 + rnd.nextInt(31);
            CSPlayer out = pickMostTired(onField);
            if (out == null) break;
            CSPlayer in = pickLikeForLike(bench, out.getPosition());
            if (in == null) break;
            onField.removeIf(p -> p.getId().equals(out.getId()));
            onField.add(in);
            bench.removeIf(p -> p.getId().equals(in.getId()));
            
            // Update minutes for substituted players
            minutesByPlayer.put(out.getId(), Math.max(1, minute));
            minutesByPlayer.put(in.getId(), Math.max(0, 91 - minute));
            
            // Update on-field status
            if (onFieldStatus != null) {
                onFieldStatus.put(out.getId(), false);
                onFieldStatus.put(in.getId(), true);
            }
            
            events.add(CSMatchEvent.builder()
                    .minute(minute)
                    .eventType(CSEventType.SUBSTITUTION)
                    .teamName(team.getName())
                    .playerOutName(out.getName())
                    .playerInName(in.getName())
                    .description(describeSubstitution(team.getName(), out.getName(), in.getName()))
                    .build());
        }
    }

    private CSPlayer pickMostTired(List<CSPlayer> starters) {
        return starters.stream().max(java.util.Comparator.comparingDouble(CSPlayer::getFatigue)).orElse(null);
    }

    private CSPlayer pickLikeForLike(List<CSPlayer> bench, String position) {
        return bench.stream().filter(p -> position.equals(p.getPosition())).findFirst().orElse(bench.isEmpty() ? null : bench.getFirst());
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
            if (scorer != null && assist != null && scorer.getId().equals(assist.getId())) {
                assist = null;
            }

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
                    .description(describeGoal(scoringTeam.getName(), scorer.getName(), assist != null ? assist.getName() : null, scoreAfter))
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

        int homeFouls = rnd.nextInt(4) + 2;
        int awayFouls = rnd.nextInt(4) + 2;
        for (int i = 0; i < homeFouls; i++) {
            events.add(buildStatEvent(CSEventType.FOUL, home, randomPlayer(homePlayers)));
        }
        for (int i = 0; i < awayFouls; i++) {
            events.add(buildStatEvent(CSEventType.FOUL, away, randomPlayer(awayPlayers)));
        }

        int homeOffsides = rnd.nextInt(3);
        int awayOffsides = rnd.nextInt(3);
        for (int i = 0; i < homeOffsides; i++) {
            events.add(buildStatEvent(CSEventType.OFFSIDE, home, pickScorer(homePlayers)));
        }
        for (int i = 0; i < awayOffsides; i++) {
            events.add(buildStatEvent(CSEventType.OFFSIDE, away, pickScorer(awayPlayers)));
        }

        int homeFreeKicks = rnd.nextInt(3) + 1;
        int awayFreeKicks = rnd.nextInt(3) + 1;
        for (int i = 0; i < homeFreeKicks; i++) {
            events.add(buildStatEvent(CSEventType.FREE_KICK, home, randomPlayer(homePlayers)));
        }
        for (int i = 0; i < awayFreeKicks; i++) {
            events.add(buildStatEvent(CSEventType.FREE_KICK, away, randomPlayer(awayPlayers)));
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
                    .description(describePenalty(taker != null ? taker.getName() : "?", home.getName(), scored))
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
                    .description(describePenalty(taker != null ? taker.getName() : "?", away.getName(), scored))
                    .build());
        }

        if (rnd.nextDouble() < 0.12) {
            boolean homeIncident = rnd.nextBoolean();
            CSTeam incidentTeam = homeIncident ? home : away;
            List<CSPlayer> incidentPlayers = homeIncident ? homePlayers : awayPlayers;
            events.add(buildStatEvent(CSEventType.INJURY, incidentTeam, randomPlayer(incidentPlayers)));
        }
        if (rnd.nextDouble() < 0.16) {
            boolean homeIncident = rnd.nextBoolean();
            CSTeam incidentTeam = homeIncident ? home : away;
            List<CSPlayer> incidentPlayers = homeIncident ? homePlayers : awayPlayers;
            events.add(buildStatEvent(CSEventType.VAR_REVIEW, incidentTeam, randomPlayer(incidentPlayers)));
        }
    }

    private CSMatchEvent buildStatEvent(CSEventType type, CSTeam team, CSPlayer player) {
        return CSMatchEvent.builder()
                .minute(rnd.nextInt(90) + 1)
                .eventType(type)
                .playerName(player != null ? player.getName() : "?")
                .teamName(team.getName())
                .description(describeStatEvent(type, team.getName(), player != null ? player.getName() : "?"))
                .build();
    }

    private String describeGoal(String teamName, String scorerName, String assistName, String scoreAfter) {
        String assistText = assistName == null || assistName.isBlank() ? "" : " Assist: " + assistName + ".";
        String scoreText = scoreAfter == null || scoreAfter.isBlank() ? "" : " [" + scoreAfter + "]";
        return pick(
                scorerName + " applies the finish for " + teamName + "." + assistText + scoreText,
                "Goal for " + teamName + ": " + scorerName + " converts the move." + assistText + scoreText,
                scorerName + " finds the net for " + teamName + "." + assistText + scoreText
        );
    }

    private String describeSubstitution(String teamName, String playerOut, String playerIn) {
        return pick(
                teamName + " make a change: " + playerOut + " off, " + playerIn + " on.",
                "Tactical switch for " + teamName + " as " + playerIn + " replaces " + playerOut + ".",
                teamName + " send on " + playerIn + " for " + playerOut + "."
        );
    }

    private String describePenalty(String takerName, String teamName, boolean scored) {
        return scored
                ? pick(
                        takerName + " converts the penalty for " + teamName + ".",
                        "Penalty scored by " + takerName + " for " + teamName + ".",
                        takerName + " keeps his nerve from the spot for " + teamName + "."
                )
                : pick(
                        takerName + " misses the penalty for " + teamName + ".",
                        "Penalty wasted by " + takerName + " for " + teamName + ".",
                        takerName + " fails from the spot for " + teamName + "."
                );
    }

    private String describeStatEvent(CSEventType type, String teamName, String playerName) {
        return switch (type) {
            case SHOT_ON_TARGET -> pick(
                    playerName + " forces a save for " + teamName + ".",
                    teamName + " work a shot on target through " + playerName + ".",
                    playerName + " tests the goalkeeper for " + teamName + "."
            );
            case SHOT_OFF_TARGET -> pick(
                    playerName + " fires wide for " + teamName + ".",
                    teamName + " see " + playerName + " miss the target.",
                    playerName + " cannot keep the effort down for " + teamName + "."
            );
            case CORNER -> pick(
                    "Corner kick to " + teamName + ".",
                    teamName + " win a corner.",
                    "Set-piece chance for " + teamName + "."
            );
            case YELLOW_CARD -> pick(
                    playerName + " goes into the book.",
                    "Yellow card shown to " + playerName + ".",
                    playerName + " is cautioned for " + teamName + "."
            );
            case RED_CARD -> pick(
                    playerName + " is sent off for " + teamName + ".",
                    "Red card for " + playerName + ".",
                    teamName + " are reduced to ten men after " + playerName + " sees red."
            );
            case FOUL -> pick(
                    playerName + " concedes a foul for " + teamName + ".",
                    "Free kick given against " + playerName + ".",
                    playerName + " arrives late and the whistle goes."
            );
            case OFFSIDE -> pick(
                    playerName + " is caught offside.",
                    "The flag goes up against " + playerName + ".",
                    playerName + " strays beyond the last line for " + teamName + "."
            );
            case FREE_KICK -> pick(
                    "Free kick to " + teamName + ".",
                    teamName + " earn a set-piece chance.",
                    "Dead-ball opportunity for " + teamName + "."
            );
            case INJURY -> pick(
                    playerName + " needs treatment.",
                    "Medical staff are called for " + playerName + ".",
                    "There is an injury concern involving " + playerName + "."
            );
            case VAR_REVIEW -> pick(
                    "VAR is checking an incident for " + teamName + ".",
                    "The referee pauses for a VAR review.",
                    "A short VAR delay interrupts play."
            );
            default -> type.name() + " - " + playerName;
        };
    }

    private String pick(String... variants) {
        if (variants == null || variants.length == 0) {
            return "";
        }
        return variants[rnd.nextInt(variants.length)];
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
