package org.example.basketballmanager.engine;

import org.example.basketballmanager.model.BbPlayer;
import org.example.basketballmanager.model.BbTeam;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class BbMatchEngine {

    private static final int POSS_PER_Q = 35;
    private static final int MIN_PER_Q = 10;
    private static final int OT_MIN = 5;
    private static final int OT_POSS = 18;

    private final Random random = new Random();

    public BbMatchResult simulate(BbTeam home, BbTeam away) {
        List<BbPlayer> homePlayers = new ArrayList<>(home.getPlayers());
        List<BbPlayer> awayPlayers = new ArrayList<>(away.getPlayers());

        Collections.shuffle(homePlayers, random);
        Collections.shuffle(awayPlayers, random);

        Map<Long, BbPlayerGameStats> homeStats = initStatsMap(homePlayers);
        Map<Long, BbPlayerGameStats> awayStats = initStatsMap(awayPlayers);
        List<String> events = new ArrayList<>();

        List<Integer> homeQS = new ArrayList<>();
        List<Integer> awayQS = new ArrayList<>();

        // 4 regulation quarters
        for (int q = 0; q < 4; q++) {
            playPeriod(homePlayers, awayPlayers, homeStats, awayStats, events, home, away,
                    q, false, 0, homeQS, awayQS);
        }

        // Check for overtime
        int otCount = 0;
        while (true) {
            int homeTotal = homeStats.values().stream().mapToInt(BbPlayerGameStats::getPoints).sum();
            int awayTotal = awayStats.values().stream().mapToInt(BbPlayerGameStats::getPoints).sum();
            if (homeTotal != awayTotal) break;

            otCount++;
            events.add("OT" + otCount + " — End of regulation. Score tied at " + homeTotal + "-" + awayTotal + ". Overtime!");

            int qIndex = 3 + otCount; // OT1 = index 4, OT2 = index 5, etc.
            playPeriod(homePlayers, awayPlayers, homeStats, awayStats, events, home, away,
                    qIndex, true, otCount, homeQS, awayQS);
        }

        int homeScore = homeStats.values().stream().mapToInt(BbPlayerGameStats::getPoints).sum();
        int awayScore = awayStats.values().stream().mapToInt(BbPlayerGameStats::getPoints).sum();

        String homeQSstr = homeQS.stream().map(String::valueOf).collect(Collectors.joining("-"));
        String awayQSstr = awayQS.stream().map(String::valueOf).collect(Collectors.joining("-"));

        assignMinutes(homeStats, homePlayers.size());
        assignMinutes(awayStats, awayPlayers.size());

        int homeFouls = homeStats.values().stream().mapToInt(BbPlayerGameStats::getFouls).sum();
        int awayFouls = awayStats.values().stream().mapToInt(BbPlayerGameStats::getFouls).sum();

        return BbMatchResult.builder()
                .homeTeamId(home.getId())
                .homeTeamName(home.getName())
                .awayTeamId(away.getId())
                .awayTeamName(away.getName())
                .homeScore(homeScore)
                .awayScore(awayScore)
                .homeQuarterScores(homeQSstr)
                .awayQuarterScores(awayQSstr)
                .homeFouls(homeFouls)
                .awayFouls(awayFouls)
                .homePlayerStats(new ArrayList<>(homeStats.values()))
                .awayPlayerStats(new ArrayList<>(awayStats.values()))
                .events(events)
                .build();
    }

    private void playPeriod(List<BbPlayer> homePlayers, List<BbPlayer> awayPlayers,
                             Map<Long, BbPlayerGameStats> homeStats, Map<Long, BbPlayerGameStats> awayStats,
                             List<String> events, BbTeam home, BbTeam away,
                             int qIndex, boolean isOt, int otNum,
                             List<Integer> homeQS, List<Integer> awayQS) {
        int possInQ = isOt ? OT_POSS : POSS_PER_Q;
        int minsInQ = isOt ? OT_MIN : MIN_PER_Q;
        int secPerPos = (minsInQ * 60) / possInQ;

        int homeQScore = 0;
        int awayQScore = 0;
        boolean homePossession = (qIndex % 2 == 0);

        for (int p = 0; p < possInQ; p++) {
            int gameSec = p * secPerPos;
            int min = gameSec / 60;
            int sec = gameSec % 60;
            String periodLabel = isOt ? "OT" + otNum : "Q" + (qIndex + 1);
            String timeStr = periodLabel + " " + min + ":" + String.format("%02d", sec);

            if (homePossession) {
                BbPlayer shooter = selectShooter(homePlayers, homeStats);
                int pts = runPossession(shooter, homePlayers, awayPlayers, homeStats, awayStats, events, timeStr, home, away, true);
                homeQScore += pts;
            } else {
                BbPlayer shooter = selectShooter(awayPlayers, awayStats);
                int pts = runPossession(shooter, awayPlayers, homePlayers, awayStats, homeStats, events, timeStr, home, away, false);
                awayQScore += pts;
            }

            homePossession = !homePossession;
        }

        homeQS.add(homeQScore);
        awayQS.add(awayQScore);
    }

    private int runPossession(BbPlayer shooter, List<BbPlayer> teammates,
                               List<BbPlayer> defenders,
                               Map<Long, BbPlayerGameStats> offStats,
                               Map<Long, BbPlayerGameStats> defStats,
                               List<String> events, String timeStr,
                               BbTeam home, BbTeam away, boolean isHome) {
        BbTeam offTeam = isHome ? home : away;
        BbTeam defTeam = isHome ? away : home;
        BbPlayerGameStats sg = stats(offStats, shooter);
        String shooterName = shooter.getName();
        int shooterId = shooter.getId().intValue();

        double stealChance = 0.015 + defenders.stream()
                .mapToInt(BbPlayer::getSkillSteals).average().orElse(5) / 600.0;
        if (random.nextDouble() < stealChance) {
            sg.setTurnovers(sg.getTurnovers() + 1);
            BbPlayer stealer = defenders.get(random.nextInt(defenders.size()));
            BbPlayerGameStats ds = stats(defStats, stealer);
            ds.setSteals(ds.getSteals() + 1);
            events.add(timeStr + "|TO|" + shooterId + "|" + shooterName + "|" + stealer.getId().intValue() + "|" + stealer.getName());
            return 0;
        }

        double turnoverChance = 0.04 - shooter.getSkillPlaymaking() / 600.0;
        if (random.nextDouble() < turnoverChance) {
            sg.setTurnovers(sg.getTurnovers() + 1);
            events.add(timeStr + "|TO|" + shooterId + "|" + shooterName + "|0|turnover");
            return 0;
        }

        boolean isThree = decideShotType(shooter);
        double makePct = isThree
                ? 0.22 + shooter.getSkillThreePtShot() / 20.0 * 0.55
                : 0.38 + shooter.getSkillTwoPtShot() / 20.0 * 0.50;

        double blockChance = defenders.stream()
                .mapToInt(BbPlayer::getSkillBlocks).average().orElse(5) / 300.0;
        boolean blocked = random.nextDouble() < blockChance;

        if (blocked) {
            BbPlayer blocker = defenders.get(random.nextInt(defenders.size()));
            BbPlayerGameStats bs = stats(defStats, blocker);
            bs.setBlocks(bs.getBlocks() + 1);
            events.add(timeStr + "|BLK|" + shooterId + "|" + shooterName + "|" + blocker.getId().intValue() + "|" + blocker.getName());
        }

        // Increase foul probability for more realistic games (~20-25 fouls per team per game)
        double foulProb = isThree ? 0.15 : 0.12;
        boolean foul = !blocked && random.nextDouble() < foulProb;
        int ftShots = foul ? (isThree ? 3 : 2) : 0;
        // Select fouler from defenders who haven't fouled out (less than 5 fouls)
        List<BbPlayer> eligibleFoulers = defenders.stream()
                .filter(d -> stats(defStats, d).getFouls() < 5)
                .collect(Collectors.toList());
        BbPlayer fouler = (foul && !eligibleFoulers.isEmpty()) ? eligibleFoulers.get(random.nextInt(eligibleFoulers.size())) : null;
        BbPlayerGameStats fls = foul ? stats(defStats, fouler) : null;
        if (foul) fls.setFouls(fls.getFouls() + 1);

        boolean made = !blocked && random.nextDouble() < makePct;

        if (made) {
            int pts;
            if (isThree) {
                sg.setThreePtMade(sg.getThreePtMade() + 1);
                sg.setThreePtAttempted(sg.getThreePtAttempted() + 1);
                pts = 3;
            } else {
                sg.setTwoPtMade(sg.getTwoPtMade() + 1);
                sg.setTwoPtAttempted(sg.getTwoPtAttempted() + 1);
                pts = 2;
            }
            sg.setPoints(sg.getPoints() + pts);

            String assistStr = "";
            int passerId = 0;
            String passerName = "";
            if (random.nextDouble() < 0.40) {
                BbPlayer passer = selectPasser(teammates, shooter.getId(), offStats);
                BbPlayerGameStats ps = stats(offStats, passer);
                ps.setAssists(ps.getAssists() + 1);
                assistStr = " (ast: " + passer.getName() + ")";
                passerId = passer.getId().intValue();
                passerName = passer.getName();
            }

            if (foul) {
                events.add(timeStr + "|AND1|" + shooterId + "|" + shooterName + "|" + pts + "|" + passerId + "|" + passerName + "|" + fouler.getId().intValue() + "|" + fouler.getName() + "| and-1");
                sg.setPoints(sg.getPoints() + 1);
                return pts + 1;
            }

            events.add(timeStr + "|MADE|" + shooterId + "|" + shooterName + "|" + pts + "|" + passerId + "|" + passerName + "|" + pts + "pts" + assistStr);
            return pts;
        }

        if (!blocked) {
            if (isThree) sg.setThreePtAttempted(sg.getThreePtAttempted() + 1);
            else sg.setTwoPtAttempted(sg.getTwoPtAttempted() + 1);
            events.add(timeStr + "|MISS|" + shooterId + "|" + shooterName + "|" + (isThree ? 3 : 2) + "|0|missed " + (isThree ? "3pt" : "2pt"));
        }

        if (foul) {
            int madeFt = 0;
            for (int i = 0; i < ftShots; i++) {
                double ftPct = 0.50 + shooter.getSkillFreeThrows() / 20.0 * 0.45;
                if (random.nextDouble() < ftPct) madeFt++;
            }
            sg.setFtMade(sg.getFtMade() + madeFt);
            sg.setFtAttempted(sg.getFtAttempted() + ftShots);
            sg.setPoints(sg.getPoints() + madeFt);
            events.add(timeStr + "|FT|" + shooterId + "|" + shooterName + "|" + madeFt + "|" + ftShots + "|" + fouler.getId().intValue() + "|" + fouler.getName());
            return madeFt;
        }

        double offRebChance = 0.28;
        if (random.nextDouble() < offRebChance) {
            BbPlayer rebounder = selectRebounder(teammates, offStats);
            BbPlayerGameStats rs = stats(offStats, rebounder);
            rs.setRebounds(rs.getRebounds() + 1);
            events.add(timeStr + "|OREB|" + rebounder.getId().intValue() + "|" + rebounder.getName() + "|0|offensive rebound");
            return 0;
        }

        BbPlayer rebounder = selectRebounder(defenders, defStats);
        BbPlayerGameStats rs = stats(defStats, rebounder);
        rs.setRebounds(rs.getRebounds() + 1);
        events.add(timeStr + "|DREB|" + rebounder.getId().intValue() + "|" + rebounder.getName() + "|0|defensive rebound");
        return 0;
    }

    private BbPlayer selectShooter(List<BbPlayer> players, Map<Long, BbPlayerGameStats> statsMap) {
        List<BbPlayer> eligible = players.stream()
                .filter(p -> statsMap.get(p.getId()).getFouls() < 5)
                .collect(Collectors.toList());
        if (eligible.isEmpty()) eligible = players;
        double totalWeight = eligible.stream()
                .mapToDouble(p -> 1.0 + (p.getSkillTwoPtShot() + p.getSkillThreePtShot()) / 30.0)
                .sum();
        double r = random.nextDouble() * totalWeight;
        double cumulative = 0;
        for (BbPlayer p : eligible) {
            cumulative += 1.0 + (p.getSkillTwoPtShot() + p.getSkillThreePtShot()) / 30.0;
            if (r <= cumulative) return p;
        }
        return eligible.get(eligible.size() - 1);
    }

    private BbPlayer selectRebounder(List<BbPlayer> players, Map<Long, BbPlayerGameStats> statsMap) {
        List<BbPlayer> eligible = players.stream()
                .filter(p -> statsMap.get(p.getId()).getFouls() < 5)
                .collect(Collectors.toList());
        if (eligible.isEmpty()) eligible = players;
        double totalWeight = eligible.stream()
                .mapToDouble(p -> 1.0 + p.getSkillRebounding() / 20.0)
                .sum();
        double r = random.nextDouble() * totalWeight;
        double cumulative = 0;
        for (BbPlayer p : eligible) {
            cumulative += 1.0 + p.getSkillRebounding() / 20.0;
            if (r <= cumulative) return p;
        }
        return eligible.get(eligible.size() - 1);
    }

    private BbPlayer selectPasser(List<BbPlayer> candidates, Long shooterId, Map<Long, BbPlayerGameStats> statsMap) {
        List<BbPlayer> filtered = candidates.stream()
                .filter(p -> !p.getId().equals(shooterId))
                .filter(p -> statsMap.get(p.getId()).getFouls() < 5)
                .collect(Collectors.toList());
        if (filtered.isEmpty()) {
            // Fallback: any non-shooter
            filtered = candidates.stream()
                    .filter(p -> !p.getId().equals(shooterId))
                    .collect(Collectors.toList());
            if (filtered.isEmpty()) return candidates.get(0);
        }
        double totalWeight = filtered.stream()
                .mapToDouble(p -> 1.0 + p.getSkillPlaymaking() / 20.0)
                .sum();
        double r = random.nextDouble() * totalWeight;
        double cumulative = 0;
        for (BbPlayer p : filtered) {
            cumulative += 1.0 + p.getSkillPlaymaking() / 20.0;
            if (r <= cumulative) return p;
        }
        return filtered.get(filtered.size() - 1);
    }

    private boolean decideShotType(BbPlayer player) {
        double threePtProb = switch (player.getPosition()) {
            case PG -> 0.48;
            case SG -> 0.42;
            case SF -> 0.32;
            case PF -> 0.18;
            case C -> 0.08;
        };
        return random.nextDouble() < threePtProb;
    }

    private void assignMinutes(Map<Long, BbPlayerGameStats> statsMap, int playerCount) {
        List<BbPlayerGameStats> list = new ArrayList<>(statsMap.values());
        int[] mins = {30, 28, 26, 24, 22, 16, 14, 12, 10, 8, 6, 4};
        for (int i = 0; i < list.size() && i < mins.length; i++) {
            list.get(i).setMinutes(mins[i]);
        }
    }

    private BbPlayerGameStats stats(Map<Long, BbPlayerGameStats> map, BbPlayer player) {
        return map.get(player.getId());
    }

    private static Map<Long, BbPlayerGameStats> initStatsMap(List<BbPlayer> players) {
        Map<Long, BbPlayerGameStats> map = new LinkedHashMap<>();
        for (BbPlayer p : players) {
            map.put(p.getId(), BbPlayerGameStats.builder()
                    .playerId(p.getId())
                    .playerName(p.getName())
                    .position(p.getPosition().name())
                    .build());
        }
        return map;
    }
}
