package org.example.footballmanager.service;

import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Stadium;
import org.example.footballmanager.model.Team;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class AttendanceService {

    public int ensureAttendance(Match match) {
        if (match == null) {
            return 0;
        }
        if (match.getStadium() == null && match.getHomeTeam() != null) {
            match.setStadium(match.getHomeTeam().getStadium());
        }
        int attendance = estimateAttendance(match);
        match.setAttendance(attendance);
        return attendance;
    }

    public int estimateAttendance(Match match) {
        if (match == null || match.getHomeTeam() == null) {
            return 0;
        }

        Team homeTeam = match.getHomeTeam();
        Team awayTeam = match.getAwayTeam();
        Stadium stadium = match.getStadium() != null ? match.getStadium() : homeTeam.getStadium();
        int capacity = Math.max(2500, stadium != null && stadium.getCapacity() != null ? stadium.getCapacity() : 6000);

        double homeReputation = clamp01(resolveReputation(homeTeam, 54.0) / 100.0);
        double awayReputation = clamp01(resolveReputation(awayTeam, 50.0) / 100.0);
        double budgetFactor = clamp(resolveBudgetFactor(homeTeam), 0.0, 0.06);
        double roundFactor = resolveRoundFactor(match.getRoundNumber());
        double rivalryFactor = resolveRivalryFactor(homeTeam, awayTeam);
        double noise = resolveNoise(match);

        double fill = 0.34
                + (homeReputation * 0.25)
                + (awayReputation * 0.13)
                + budgetFactor
                + roundFactor
                + rivalryFactor
                + noise;

        fill = clamp(fill, 0.18, 0.98);
        int attendance = (int) Math.round(capacity * fill);
        attendance = Math.max(600, Math.min(capacity, attendance));
        return roundToNearestTen(attendance);
    }

    private double resolveReputation(Team team, double fallback) {
        if (team == null || team.getReputation() == null) {
            return fallback;
        }
        return clamp(team.getReputation(), 20.0, 100.0);
    }

    private double resolveBudgetFactor(Team team) {
        if (team == null || team.getBudget() == null) {
            return 0.0;
        }
        double budget = Math.max(0.0, team.getBudget());
        return Math.min(0.06, budget / 25_000_000.0);
    }

    private double resolveRoundFactor(Integer roundNumber) {
        int round = roundNumber != null ? roundNumber : 1;
        if (round >= 24) return 0.08;
        if (round >= 18) return 0.05;
        if (round >= 10) return 0.02;
        return 0.0;
    }

    private double resolveRivalryFactor(Team homeTeam, Team awayTeam) {
        if (homeTeam == null || awayTeam == null) {
            return 0.0;
        }
        String home = safe(homeTeam.getName());
        String away = safe(awayTeam.getName());
        if (home.isBlank() || away.isBlank()) {
            return 0.0;
        }
        if (home.charAt(0) == away.charAt(0)) {
            return 0.025;
        }
        double reputationGap = Math.abs(resolveReputation(homeTeam, 54.0) - resolveReputation(awayTeam, 50.0));
        return reputationGap <= 12.0 ? 0.02 : 0.0;
    }

    private double resolveNoise(Match match) {
        LocalDateTime matchDate = match.getMatchDate();
        long seed = Objects.hash(
                match.getHomeTeam() != null ? match.getHomeTeam().getId() : 0L,
                match.getAwayTeam() != null ? match.getAwayTeam().getId() : 0L,
                match.getSeasonYear(),
                match.getRoundNumber(),
                matchDate != null ? matchDate.getDayOfYear() : 0
        );
        long bucket = Math.abs(seed % 1000L);
        return ((bucket / 999.0) - 0.5) * 0.10;
    }

    private int roundToNearestTen(int value) {
        return Math.max(0, ((int) Math.round(value / 10.0)) * 10);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}