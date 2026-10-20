package org.example.americanfootballmanager.engine;

import org.example.americanfootballmanager.model.AfPlayer;
import org.example.americanfootballmanager.model.AfTeam;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class AfMatchEngine {

    private static final int DRIVES_PER_Q = 3;
    private static final int MIN_PER_Q = 15;
    private static final int OT_DRIVES = 2;
    private static final int OT_MIN = 10;

    private final Random random = new Random();

    public AfMatchResult simulate(AfTeam home, AfTeam away) {
        List<AfPlayer> homePlayers = new ArrayList<>(home.getPlayers());
        List<AfPlayer> awayPlayers = new ArrayList<>(away.getPlayers());

        Collections.shuffle(homePlayers, random);
        Collections.shuffle(awayPlayers, random);

        Map<Long, AfPlayerGameStats> homeStats = initStatsMap(homePlayers);
        Map<Long, AfPlayerGameStats> awayStats = initStatsMap(awayPlayers);
        List<String> events = new ArrayList<>();

        List<Integer> homeQS = new ArrayList<>();
        List<Integer> awayQS = new ArrayList<>();

        // 4 regulation quarters
        for (int q = 0; q < 4; q++) {
            playQuarter(homePlayers, awayPlayers, homeStats, awayStats, events, home, away,
                    q, false, 0, homeQS, awayQS);
        }

        int otCount = 0;
        while (true) {
            int homeTotal = getScore(homeStats);
            int awayTotal = getScore(awayStats);
            if (homeTotal != awayTotal) break;

            otCount++;
            events.add("OT" + otCount + " — End of regulation. Score tied at " + homeTotal + "-" + awayTotal + ". Overtime!");
            playQuarter(homePlayers, awayPlayers, homeStats, awayStats, events, home, away,
                    3 + otCount, true, otCount, homeQS, awayQS);
        }

        int homeScore = getScore(homeStats);
        int awayScore = getScore(awayStats);

        String homeQSstr = homeQS.stream().map(String::valueOf).collect(Collectors.joining("-"));
        String awayQSstr = awayQS.stream().map(String::valueOf).collect(Collectors.joining("-"));

        assignMinutes(homeStats, homePlayers.size());
        assignMinutes(awayStats, awayPlayers.size());

        return AfMatchResult.builder()
                .homeTeamId(home.getId())
                .homeTeamName(home.getName())
                .awayTeamId(away.getId())
                .awayTeamName(away.getName())
                .homeScore(homeScore)
                .awayScore(awayScore)
                .homeQuarterScores(homeQSstr)
                .awayQuarterScores(awayQSstr)
                .homeFouls(0)
                .awayFouls(0)
                .homePlayerStats(new ArrayList<>(homeStats.values()))
                .awayPlayerStats(new ArrayList<>(awayStats.values()))
                .events(events)
                .build();
    }

    private void playQuarter(List<AfPlayer> homePlayers, List<AfPlayer> awayPlayers,
                              Map<Long, AfPlayerGameStats> homeStats, Map<Long, AfPlayerGameStats> awayStats,
                              List<String> events, AfTeam home, AfTeam away,
                              int qIndex, boolean isOt, int otNum,
                              List<Integer> homeQS, List<Integer> awayQS) {
        int drives = isOt ? OT_DRIVES : DRIVES_PER_Q;
        int secPerDrive = (isOt ? OT_MIN : MIN_PER_Q) * 60 / drives;

        int homeQScore = 0;
        int awayQScore = 0;

        for (int d = 0; d < drives; d++) {
            int gameSec = d * secPerDrive;
            int min = gameSec / 60;
            int sec = gameSec % 60;
            String periodLabel = isOt ? "OT" + otNum : "Q" + (qIndex + 1);
            String timeStr = periodLabel + " " + min + ":" + String.format("%02d", sec);

            // Home possession drive
            homeQScore += runDrive(homePlayers, awayPlayers, homeStats, awayStats, events, timeStr, home, away, true, d);

            // Away possession drive
            awayQScore += runDrive(awayPlayers, homePlayers, awayStats, homeStats, events, timeStr, home, away, false, d);
        }

        homeQS.add(homeQScore);
        awayQS.add(awayQScore);
    }

    private int runDrive(List<AfPlayer> offense, List<AfPlayer> defense,
                          Map<Long, AfPlayerGameStats> offStats, Map<Long, AfPlayerGameStats> defStats,
                          List<String> events, String timeStr,
                          AfTeam home, AfTeam away, boolean isHome, int driveNum) {
        int yards = 0;
        int downs = 0;
        int score = 0;

        // Find QB
        AfPlayer qb = findQB(offense);
        AfPlayer kicker = findKicker(offense);
        if (qb == null) qb = offense.get(0);

        int qbId = qb.getId().intValue();

        // Drive: up to 4 downs, gain at least 10 yards
        while (downs < 4) {
            downs++;

            // Random play result
            double playType = random.nextDouble();
            int gain;

            if (playType < 0.45) {
                // Run play
                AfPlayer runner = selectRunner(offense);
                gain = 1 + random.nextInt(8 + runner.getSkillRunning());
                AfPlayerGameStats rs = stats(offStats, runner);
                rs.setRushingYards(rs.getRushingYards() + gain);
                events.add(timeStr + "|RUN|" + runner.getId().intValue() + "|" + runner.getName() + "|" + gain + "|rush " + gain + " yds");
            } else if (playType < 0.85) {
                // Pass play
                AfPlayer receiver = selectReceiver(offense, qb.getId());
                int passComp = (qb.getSkillPassing() * 4 + receiver.getSkillRunning()) / 5;
                if (random.nextInt(20) < passComp) {
                    gain = 3 + random.nextInt(5 + receiver.getSkillRunning());
                    AfPlayerGameStats qbs = stats(offStats, qb);
                    qbs.setPassingYards(qbs.getPassingYards() + gain);
                    AfPlayerGameStats rs = stats(offStats, receiver);
                    rs.setReceivingYards(rs.getReceivingYards() + gain);
                    events.add(timeStr + "|PASS|" + qbId + "|" + qb.getName() + "|" + receiver.getId().intValue()
                            + "|" + receiver.getName() + "|" + gain + "|pass " + gain + " yds");
                } else {
                    gain = 0;
                    events.add(timeStr + "|INC|" + qbId + "|" + qb.getName() + "|0|incomplete pass");
                }
            } else {
                // Turnover / Sack
                int loss = 1 + random.nextInt(5);
                gain = -loss;
                AfPlayerGameStats qbs = stats(offStats, qb);
                qbs.setFumbles(qbs.getFumbles() + 1);
                events.add(timeStr + "|SACK|" + qbId + "|" + qb.getName() + "|" + loss + "|sacked for " + loss + " yds");
            }

            yards += gain;
            if (yards >= 10) {
                yards = 0;
                downs = 0; // Reset downs, first down gained
                events.add(timeStr + "|FIRST|0|First Down|0|first down");
            }

            if (downs >= 4 && yards < 10) {
                // Punt or FG attempt
                if (yards < 0 && kicker != null && random.nextDouble() < 0.3) {
                    // FG attempt
                    int fgDist = 20 + random.nextInt(40);
                    int fgSkill = kicker.getSkillShooting() * 5;
                    boolean made = random.nextInt(100) < fgSkill;
                    AfPlayerGameStats ks = stats(offStats, kicker);
                    ks.setFieldGoalsAttempted(ks.getFieldGoalsAttempted() + 1);
                    if (made) {
                        ks.setFieldGoalsMade(ks.getFieldGoalsMade() + 1);
                        score = 3;
                        events.add(timeStr + "|FG|" + kicker.getId().intValue() + "|" + kicker.getName() + "|3|FG " + fgDist + " yds GOOD");
                    } else {
                        events.add(timeStr + "|FG|" + kicker.getId().intValue() + "|" + kicker.getName() + "|0|FG " + fgDist + " yds MISSED");
                    }
                } else {
                    events.add(timeStr + "|PUNT|0|Punt|0|punt away");
                }
                break;
            }
        }

        // Check for touchdown (simplified: ~5% chance per drive + skill factor)
        if (random.nextDouble() < 0.05 + (qb.getSkillPlaymaking() / 200.0)) {
            score = 7; // TD + 1pt
            AfPlayer scorer = selectReceiver(offense, qb.getId());
            AfPlayerGameStats ss = stats(offStats, scorer);
            ss.setTouchdowns(ss.getTouchdowns() + 1);
            ss.setReceivingTouchdowns(ss.getReceivingTouchdowns() + 1);
            AfPlayerGameStats qbs = stats(offStats, qb);
            qbs.setPassingTouchdowns(qbs.getPassingTouchdowns() + 1);
            events.add(timeStr + "|TD|" + scorer.getId().intValue() + "|" + scorer.getName() + "|7|TOUCHDOWN! " + scorer.getName() + " scores");
        }

        return score;
    }

    private AfPlayer findQB(List<AfPlayer> players) {
        return players.stream().filter(p -> p.getPosition() == AfPlayer.Position.QB).findFirst().orElse(null);
    }

    private AfPlayer findKicker(List<AfPlayer> players) {
        return players.stream().filter(p -> p.getPosition() == AfPlayer.Position.K).findFirst().orElse(null);
    }

    private AfPlayer selectRunner(List<AfPlayer> players) {
        List<AfPlayer> runners = players.stream()
                .filter(p -> p.getPosition() == AfPlayer.Position.RB || p.getPosition() == AfPlayer.Position.QB)
                .collect(Collectors.toList());
        if (runners.isEmpty()) return players.get(0);
        double totalWeight = runners.stream()
                .mapToDouble(p -> 1.0 + p.getSkillRunning() / 10.0)
                .sum();
        double r = random.nextDouble() * totalWeight;
        double cum = 0;
        for (AfPlayer p : runners) {
            cum += 1.0 + p.getSkillRunning() / 10.0;
            if (r <= cum) return p;
        }
        return runners.get(runners.size() - 1);
    }

    private AfPlayer selectReceiver(List<AfPlayer> players, Long excludeId) {
        List<AfPlayer> candidates = players.stream()
                .filter(p -> !p.getId().equals(excludeId) && p.getPosition() != AfPlayer.Position.OL
                        && p.getPosition() != AfPlayer.Position.DE && p.getPosition() != AfPlayer.Position.DT
                        && p.getPosition() != AfPlayer.Position.LB && p.getPosition() != AfPlayer.Position.CB
                        && p.getPosition() != AfPlayer.Position.S && p.getPosition() != AfPlayer.Position.K
                        && p.getPosition() != AfPlayer.Position.P)
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            candidates = players.stream().filter(p -> !p.getId().equals(excludeId)).collect(Collectors.toList());
        }
        if (candidates.isEmpty()) return players.get(0);
        double totalWeight = candidates.stream()
                .mapToDouble(p -> 1.0 + p.getSkillRunning() / 10.0)
                .sum();
        double r = random.nextDouble() * totalWeight;
        double cum = 0;
        for (AfPlayer p : candidates) {
            cum += 1.0 + p.getSkillRunning() / 10.0;
            if (r <= cum) return p;
        }
        return candidates.get(candidates.size() - 1);
    }

    private int getScore(Map<Long, AfPlayerGameStats> statsMap) {
        int points = 0;
        for (AfPlayerGameStats s : statsMap.values()) {
            points += s.getTouchdowns() * 7;
            points += s.getFieldGoalsMade() * 3;
        }
        return points;
    }

    private void assignMinutes(Map<Long, AfPlayerGameStats> statsMap, int playerCount) {
        List<AfPlayerGameStats> list = new ArrayList<>(statsMap.values());
        int[] mins = {60, 55, 50, 45, 40, 35, 30, 25, 20, 15, 10, 5, 5, 5, 5, 5, 5, 5};
        for (int i = 0; i < list.size() && i < mins.length; i++) {
            list.get(i).setMinutes(mins[i]);
        }
    }

    private AfPlayerGameStats stats(Map<Long, AfPlayerGameStats> map, AfPlayer player) {
        return map.get(player.getId());
    }

    private Map<Long, AfPlayerGameStats> initStatsMap(List<AfPlayer> players) {
        Map<Long, AfPlayerGameStats> map = new LinkedHashMap<>();
        for (AfPlayer p : players) {
            map.put(p.getId(), AfPlayerGameStats.builder()
                    .playerId(p.getId())
                    .playerName(p.getName())
                    .position(p.getPosition().name())
                    .build());
        }
        return map;
    }
}
