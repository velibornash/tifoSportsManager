package org.example.footballmanager.newLogic.model;

import org.example.footballmanager.newLogic.model.event.MatchEvent;

import java.util.List;

public record MatchResult(
    long matchId,
    int homeGoals,
    int awayGoals,
    List<MatchEvent> events,
    List<TickSnapshot> tickHistory,
    int totalTicks,
    int ticksPerMinute,
    double homePossession,
    double awayPossession,
    int homeShots,
    int awayShots,
    int homeShotsOnTarget,
    int awayShotsOnTarget,
    int homeFouls,
    int awayFouls,
    int homeCorners,
    int awayCorners,
    int homeYellowCards,
    int awayYellowCards,
    int homeRedCards,
    int awayRedCards,
    double homeAvgRating,
    double awayAvgRating
) {
}
