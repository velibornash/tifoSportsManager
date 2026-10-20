package org.example.footballmanager.demo.service.result;

import java.util.List;

/**
 * Full match report — narrative summary, key moments, statistics.
 */
public record MatchReport(
    String headline,
    String summary,
    String homeTeamName,
    String awayTeamName,
    int homeGoals,
    int awayGoals,
    double homePossession,
    double awayPossession,
    List<String> keyEvents,
    String manOfTheMatch,
    String motivation
) {
    public String fullReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== MATCH REPORT ===\n\n");
        sb.append(headline).append("\n\n");
        sb.append(summary).append("\n\n");
        sb.append("--- STATISTICS ---\n");
        sb.append(String.format("Possession: %.0f%% - %.0f%%\n", homePossession, awayPossession));
        sb.append("\n--- KEY EVENTS ---\n");
        for (String event : keyEvents) {
            sb.append("  ").append(event).append("\n");
        }
        sb.append("\n--- MAN OF THE MATCH ---\n");
        sb.append("  ").append(manOfTheMatch).append("\n");
        return sb.toString();
    }
}
