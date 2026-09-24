package org.example.footballmanager.newLogic.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record MatchTeamStats(
    int homeGoals,
    int awayGoals,
    double homePossession,
    double awayPossession,
    double homeExpectedGoals,
    double awayExpectedGoals,
    int homeShots,
    int awayShots,
    int homeShotsOnTarget,
    int awayShotsOnTarget,
    int homePassesAttempted,
    int homePassesCompleted,
    int awayPassesAttempted,
    int awayPassesCompleted,
    int homeCorners,
    int awayCorners,
    int homeOffsides,
    int awayOffsides,
    int homeYellowCards,
    int awayYellowCards,
    int homeRedCards,
    int awayRedCards,
    int homeFouls,
    int awayFouls,
    double homeAvgRating,
    double awayAvgRating
) {

    public double homePassAccuracy() {
        return pct(homePassesAttempted, homePassesCompleted);
    }

    public double awayPassAccuracy() {
        return pct(awayPassesAttempted, awayPassesCompleted);
    }

    public double homeDominance() {
        return Math.max(0.0, Math.min(100.0, homePossession));
    }

    public double awayDominance() {
        return Math.max(0.0, Math.min(100.0, awayPossession));
    }

    private static double pct(int attempted, int completed) {
        if (attempted <= 0) return 0.0;
        return Math.round(1000.0 * completed / attempted) / 10.0;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("homePossession", Math.round(homePossession * 10.0) / 10.0);
        stats.put("awayPossession", Math.round(awayPossession * 10.0) / 10.0);
        stats.put("homeExpectedGoals", Math.round(homeExpectedGoals * 10.0) / 10.0);
        stats.put("awayExpectedGoals", Math.round(awayExpectedGoals * 10.0) / 10.0);
        stats.put("homeShotsOnTarget", homeShotsOnTarget);
        stats.put("awayShotsOnTarget", awayShotsOnTarget);
        stats.put("homeShotsOffTarget", Math.max(0, homeShots - homeShotsOnTarget));
        stats.put("awayShotsOffTarget", Math.max(0, awayShots - awayShotsOnTarget));
        stats.put("homePassAccuracy", homePassAccuracy());
        stats.put("awayPassAccuracy", awayPassAccuracy());
        stats.put("homeCorners", homeCorners);
        stats.put("awayCorners", awayCorners);
        stats.put("homeOffsides", homeOffsides);
        stats.put("awayOffsides", awayOffsides);
        stats.put("homeYellowCards", homeYellowCards);
        stats.put("awayYellowCards", awayYellowCards);
        stats.put("homeRedCards", homeRedCards);
        stats.put("awayRedCards", awayRedCards);
        stats.put("homeFouls", homeFouls);
        stats.put("awayFouls", awayFouls);
        stats.put("homeDominance", round1(homeDominance()));
        stats.put("awayDominance", round1(awayDominance()));
        return stats;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}