package org.example.footballmanager.cleanSheet.engine;

import org.example.footballmanager.cleanSheet.model.CSEventType;
import org.example.footballmanager.cleanSheet.model.CSMatchEvent;
import org.example.footballmanager.cleanSheet.model.CSMatchResult;

import java.util.*;

/**
 * Builds a richer textual report from simulated match events.
 * This report is intended for inbox reading in the CS/TIFO mode.
 */
public class CSMatchReportGenerator {

    private final Random rnd = new Random();

    public String buildDetailedReport(CSMatchResult result) {
        if (result == null) {
            return "No report available.";
        }

        List<CSMatchEvent> sorted = new ArrayList<>(result.getEvents() == null ? List.of() : result.getEvents());
        sorted.sort(Comparator.comparingInt(CSMatchEvent::getMinute));

        StringBuilder sb = new StringBuilder();
        sb.append("Round ").append(result.getRound()).append(".\n");
        sb.append(result.getHomeTeamName()).append(" vs ").append(result.getAwayTeamName()).append('\n');
        sb.append("Final score: ")
                .append(result.getHomeTeamName()).append(' ')
                .append(result.getHomeGoals()).append(':').append(result.getAwayGoals()).append(' ')
                .append(result.getAwayTeamName()).append("\n\n");

        List<CSMatchEvent> timeline = sorted.stream()
                .filter(e -> e.getEventType() != CSEventType.MATCH_START && e.getEventType() != CSEventType.MATCH_END)
                .toList();

        if (timeline.isEmpty()) {
            sb.append("A tactical and cautious match, with very few notable moments.\n");
        } else {
            int lastMinute = -1;
            int lineCounter = 0;
            for (CSMatchEvent e : timeline) {
                if (e.getMinute() != lastMinute) {
                    if (lineCounter > 0) {
                        sb.append('\n');
                    }
                    sb.append(e.getMinute()).append(" minute.\n");
                    lastMinute = e.getMinute();
                } else {
                    sb.append(" ");
                }
                sb.append(toNarration(e)).append(' ');
                lineCounter++;
            }
            sb.append("\n");
        }

        appendHalfSummary(sb, result, sorted, 1, 45, "Half-time");
        appendHalfSummary(sb, result, sorted, 46, 90, "Second half");

        sb.append("\nThe match ends: ")
                .append(result.getHomeTeamName()).append(' ')
                .append(result.getHomeGoals()).append(':').append(result.getAwayGoals()).append(' ')
                .append(result.getAwayTeamName())
                .append('.');

        return sb.toString().replaceAll("[ \\t]+", " ").trim();
    }

    public String buildRoundReport(int round, List<CSMatchResult> results, Long userTeamId) {
        if (results == null || results.isEmpty()) {
            return "Round " + round + ": no match data available.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Round ").append(round).append(" review.\n\n");

        List<CSMatchResult> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.comparing(CSMatchResult::getHomeTeamName));

        for (CSMatchResult r : sorted) {
            boolean userMatch = Objects.equals(r.getHomeTeamId(), userTeamId) || Objects.equals(r.getAwayTeamId(), userTeamId);
            String marker = userMatch ? " [YOUR MATCH]" : "";
            sb.append(r.getHomeTeamName()).append(' ')
                    .append(r.getHomeGoals()).append(':').append(r.getAwayGoals()).append(' ')
                    .append(r.getAwayTeamName())
                    .append(marker).append('\n');

            sb.append(buildMiniSummaryLine(r)).append("\n\n");
        }

        CSMatchResult biggestWin = sorted.stream()
                .max(Comparator.comparingInt(r -> Math.abs(r.getHomeGoals() - r.getAwayGoals())))
                .orElse(sorted.getFirst());
        sb.append("Headline: ")
                .append(biggestWin.getHomeTeamName()).append(' ')
                .append(biggestWin.getHomeGoals()).append(':').append(biggestWin.getAwayGoals()).append(' ')
                .append(biggestWin.getAwayTeamName())
                .append(" was one of the stories of the round.");
        return sb.toString().trim();
    }

    private void appendHalfSummary(StringBuilder sb,
                                   CSMatchResult result,
                                   List<CSMatchEvent> sorted,
                                   int fromMinute,
                                   int toMinute,
                                   String label) {
        int homeGoals = 0;
        int awayGoals = 0;
        int homeShotsOn = 0;
        int awayShotsOn = 0;

        for (CSMatchEvent e : sorted) {
            if (e.getMinute() < fromMinute || e.getMinute() > toMinute) {
                continue;
            }
            if (e.getEventType() == CSEventType.GOAL) {
                if (Objects.equals(e.getTeamName(), result.getHomeTeamName())) {
                    homeGoals++;
                } else if (Objects.equals(e.getTeamName(), result.getAwayTeamName())) {
                    awayGoals++;
                }
            } else if (e.getEventType() == CSEventType.SHOT_ON_TARGET) {
                if (Objects.equals(e.getTeamName(), result.getHomeTeamName())) {
                    homeShotsOn++;
                } else if (Objects.equals(e.getTeamName(), result.getAwayTeamName())) {
                    awayShotsOn++;
                }
            }
        }

        int totalShots = homeShotsOn + awayShotsOn;
        int homeShare = totalShots == 0 ? 50 : Math.round((homeShotsOn * 100f) / totalShots);
        int awayShare = 100 - homeShare;

        sb.append('\n').append('\n')
                .append(label).append(": ")
                .append(result.getHomeTeamName()).append(' ')
                .append(homeGoals).append(':').append(awayGoals).append(' ')
                .append(result.getAwayTeamName()).append(". ");

        String tempo = pick(
                "The tempo was intense.",
                "Both teams tried to keep the rhythm high.",
                "It was a tactical but lively period."
        );
        sb.append(tempo).append(' ')
                .append("Control estimate: ")
                .append(result.getHomeTeamName()).append(' ')
                .append(homeShare).append("%, ")
                .append(result.getAwayTeamName()).append(' ')
                .append(awayShare).append("%.");
    }

    private String toNarration(CSMatchEvent e) {
        String player = safeName(e.getPlayerName());
        String team = safeName(e.getTeamName());
        return switch (e.getEventType()) {
            case GOAL -> {
                String assistText = e.getAssistName() != null && !e.getAssistName().isBlank()
                        ? " Assist by " + e.getAssistName() + "."
                        : "";
                String scoreText = e.getScoreAfterGoal() != null ? " [" + e.getScoreAfterGoal() + "]" : "";
                yield pick(
                        "GOOOAAAL! " + player + " scores for " + team + "." + assistText + scoreText,
                        player + " finds the net with a precise finish for " + team + "." + assistText + scoreText,
                        "Clinical strike by " + player + " for " + team + "." + assistText + scoreText,
                        player + " attacks the space and buries the chance for " + team + "." + assistText + scoreText,
                        "A fast move ends with " + player + " scoring for " + team + "." + assistText + scoreText
                );
            }
            case SHOT_ON_TARGET -> pick(
                    player + " tests the goalkeeper from distance.",
                    "Strong effort on target by " + player + ".",
                    player + " unleashes a dangerous attempt on goal.",
                    player + " strikes low and forces a save.",
                    "Good buildup ends with a shot on target from " + player + "."
            );
            case SHOT_OFF_TARGET -> pick(
                    player + " shoots just wide of the post.",
                    "A big chance for " + player + ", but it goes off target.",
                    player + " cannot keep the shot on frame.",
                    "The attempt from " + player + " flies over the bar.",
                    player + " rushes the finish and misses the target."
            );
            case CORNER -> pick(
                    "Corner kick for " + team + ".",
                    team + " win a corner.",
                    "Set piece chance for " + team + "."
            );
            case YELLOW_CARD -> pick(
                    "Yellow card shown to " + player + ".",
                    player + " is booked.",
                    "Caution for " + player + "."
            );
            case RED_CARD -> pick(
                    "Red card! " + player + " is sent off.",
                    player + " receives a straight red card.",
                    "Dismissal for " + player + "."
            );
            case PENALTY -> {
                if (e.isPenaltyScored()) {
                    yield pick(
                            "Penalty scored by " + player + ".",
                            player + " converts from the spot.",
                            "Composed penalty finish by " + player + "."
                    );
                }
                yield pick(
                        "Penalty missed by " + player + ".",
                        player + " fails to score from the spot.",
                        "Saved penalty from " + player + "."
                );
            }
            case FOUL -> pick(
                    "Foul committed by " + player + ".",
                    "Referee whistles for a foul by " + player + ".",
                    player + " is late into the challenge."
            );
            case OFFSIDE -> pick(
                    "Offside against " + player + ".",
                    player + " is caught offside.",
                    "The flag is up for " + player + "."
            );
            case FREE_KICK -> pick(
                    "Free kick for " + team + ".",
                    "Dangerous free kick won by " + team + ".",
                    team + " prepare a set-piece situation.",
                    "Set-piece opportunity for " + team + ".",
                    "The referee awards a free kick to " + team + "."
            );
            case VAR_REVIEW -> pick(
                    "VAR is checking the previous incident.",
                    "Long VAR review underway.",
                    "The referee waits for VAR confirmation."
            );
            case SUBSTITUTION -> pick(
                    "Substitution made by " + team + ".",
                    team + " refreshes the lineup.",
                    "Tactical change from " + team + "."
            );
            case INJURY -> pick(
                    player + " is down and needs attention.",
                    "Medical team called for " + player + ".",
                    "Injury stoppage involving " + player + "."
            );
            case MATCH_START, MATCH_END -> "";
        };
    }

    private String buildMiniSummaryLine(CSMatchResult r) {
        List<CSMatchEvent> goals = (r.getEvents() == null ? List.<CSMatchEvent>of() : r.getEvents()).stream()
                .filter(e -> e.getEventType() == CSEventType.GOAL)
                .sorted(Comparator.comparingInt(CSMatchEvent::getMinute))
                .toList();
        if (goals.isEmpty()) {
            return pick(
                    "A compact match with little space and no breakthrough.",
                    "Both defenses kept full control and no side found a winner.",
                    "A tactical game where chances were limited."
            );
        }

        CSMatchEvent decisive = goals.getLast();
        String scorer = safeName(decisive.getPlayerName());
        return pick(
                "Key moment: " + decisive.getMinute() + "' - " + scorer + " changed the game.",
                "Turning point came in " + decisive.getMinute() + "' when " + scorer + " delivered.",
                "Most decisive action: " + scorer + " at " + decisive.getMinute() + "'.",
                "The defining play came at " + decisive.getMinute() + "' through " + scorer + ".",
                scorer + " produced the key finish in minute " + decisive.getMinute() + "."
        );
    }

    private String safeName(String value) {
        return value == null || value.isBlank() ? "Unknown player" : value;
    }

    private String pick(String... variants) {
        if (variants == null || variants.length == 0) {
            return "";
        }
        return variants[rnd.nextInt(variants.length)];
    }
}
