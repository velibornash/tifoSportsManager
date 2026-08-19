package org.example.footballmanager.demo.service.result;

/**
 * Goal detail — minute, scorer, assistant, score after goal.
 */
public record GoalDetail(
    int minute,
    String scorerId,
    String scorerName,
    String scorerTeam,
    String assistantId,
    String assistantName,
    int homeScoreAfter,
    int awayScoreAfter,
    String description
) {
    public String scoreString() {
        return homeScoreAfter + " - " + awayScoreAfter;
    }
}
