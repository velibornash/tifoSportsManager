package org.example.footballmanager.cleanSheet.engine;

import org.example.footballmanager.cleanSheet.model.CSEventType;
import org.example.footballmanager.cleanSheet.model.CSMatchEvent;
import org.example.footballmanager.cleanSheet.model.CSMatchResult;
import org.example.footballmanager.cleanSheet.model.CSPlayerMatchStats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Builds a richer textual report from simulated match events.
 * This report is intended for inbox reading in the CS/TIFO mode.
 */
public class CSMatchReportGenerator {

    private final Random rnd = new Random();

    public String buildDetailedReport(CSMatchResult result) {
        if (result == null) return "No report available.";

        List<CSMatchEvent> sorted = new ArrayList<>(result.getEvents() == null ? List.of() : result.getEvents());
        sorted.sort(Comparator.comparingInt(CSMatchEvent::getMinute));
        List<CSMatchEvent> timeline = sorted.stream()
                .filter(e -> e.getEventType() != CSEventType.MATCH_START && e.getEventType() != CSEventType.MATCH_END)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("MATCH DAY FILE // ROUND ").append(result.getRound()).append("\n");
        sb.append(result.getHomeTeamName()).append(" vs ").append(result.getAwayTeamName()).append("\n");
        sb.append("Final score: ").append(result.getHomeTeamName()).append(' ')
                .append(result.getHomeGoals()).append(':').append(result.getAwayGoals()).append(' ')
                .append(result.getAwayTeamName()).append("\n");
        sb.append(buildScoreHeadline(result)).append("\n");

        CSPlayerMatchStats motm = findManOfTheMatch(result);
        if (motm != null) {
            sb.append("Man of the match: ").append(safeName(motm.getPlayerName()))
                    .append(" (rating ").append(String.format(java.util.Locale.US, "%.1f", motm.getRating())).append(")")
                    .append("\n");
        }
        sb.append("Desk note: ").append(buildMiniSummaryLine(result)).append("\n");
        sb.append("\nTACTICAL BOARD\n");
        sb.append(buildTacticalBoard(result, sorted)).append("\n");
        sb.append("\nTURNING POINT\n");
        sb.append(buildTurningPoint(timeline)).append("\n");

        if (timeline.isEmpty()) {
            sb.append("\nThe game never truly opened up and the notebook stayed almost empty.\n");
        } else {
            sb.append("\nKey incidents\n");
            for (CSMatchEvent e : timeline) {
                String line = toNarration(e);
                if (!line.isBlank()) {
                    sb.append(e.getMinute()).append("' ").append(line).append("\n");
                }
            }
        }

        appendHalfSummary(sb, result, sorted, 1, 45, "Half-time");
        appendHalfSummary(sb, result, sorted, 46, 90, "Second half");
        appendStandoutPerformers(sb, result);
        appendMatchDesk(sb, result, sorted, motm);

        sb.append("\nFull-time verdict: ").append(buildClosingVerdict(result));
        return sb.toString().replaceAll("[ \t]+", " ").trim();
    }

    public String buildRoundReport(int round, List<CSMatchResult> results, Long userTeamId) {
        if (results == null || results.isEmpty()) return "Round " + round + ": no match data available.";

        List<CSMatchResult> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.<CSMatchResult, Boolean>comparing(r -> !(Objects.equals(r.getHomeTeamId(), userTeamId) || Objects.equals(r.getAwayTeamId(), userTeamId)))
                .thenComparing(CSMatchResult::getHomeTeamName));

        int totalGoals = sorted.stream().mapToInt(r -> r.getHomeGoals() + r.getAwayGoals()).sum();
        CSMatchResult biggestWin = sorted.stream()
                .max(Comparator.comparingInt(r -> Math.abs(r.getHomeGoals() - r.getAwayGoals())))
                .orElse(sorted.getFirst());
        CSMatchResult highestScoring = sorted.stream()
                .max(Comparator.comparingInt(r -> r.getHomeGoals() + r.getAwayGoals()))
                .orElse(sorted.getFirst());

        StringBuilder sb = new StringBuilder();
        sb.append("ROUND ").append(round).append(" REVIEW\n");
        sb.append(buildRoundHeadline(totalGoals, sorted.size(), biggestWin, highestScoring)).append("\n\n");
        sb.append("LEAGUE DESK\n");
        sb.append(buildRoundDesk(round, sorted, totalGoals, userTeamId)).append("\n\n");
        sb.append("SCORELINES\n");

        for (CSMatchResult r : sorted) {
            boolean userMatch = Objects.equals(r.getHomeTeamId(), userTeamId) || Objects.equals(r.getAwayTeamId(), userTeamId);
            sb.append(r.getHomeTeamName()).append(' ').append(r.getHomeGoals()).append(':').append(r.getAwayGoals())
                    .append(' ').append(r.getAwayTeamName())
                    .append(userMatch ? " [YOUR MATCH]" : "")
                    .append("\n");
            sb.append(buildMiniSummaryLine(r)).append("\n\n");
        }

        sb.append("Headline result: ").append(biggestWin.getHomeTeamName()).append(' ').append(biggestWin.getHomeGoals())
                .append(':').append(biggestWin.getAwayGoals()).append(' ').append(biggestWin.getAwayTeamName())
                .append(" carried the strongest final margin.");
        return sb.toString().trim();
    }

    private String buildTacticalBoard(CSMatchResult result, List<CSMatchEvent> events) {
        int homeShots = countEvents(events, CSEventType.SHOT_ON_TARGET, result.getHomeTeamName(), 1, 90)
                + countEvents(events, CSEventType.SHOT_OFF_TARGET, result.getHomeTeamName(), 1, 90);
        int awayShots = countEvents(events, CSEventType.SHOT_ON_TARGET, result.getAwayTeamName(), 1, 90)
                + countEvents(events, CSEventType.SHOT_OFF_TARGET, result.getAwayTeamName(), 1, 90);
        int homeSetPieces = countEvents(events, CSEventType.CORNER, result.getHomeTeamName(), 1, 90)
                + countEvents(events, CSEventType.FREE_KICK, result.getHomeTeamName(), 1, 90);
        int awaySetPieces = countEvents(events, CSEventType.CORNER, result.getAwayTeamName(), 1, 90)
                + countEvents(events, CSEventType.FREE_KICK, result.getAwayTeamName(), 1, 90);

        String controlTeam = homeShots + homeSetPieces >= awayShots + awaySetPieces ? result.getHomeTeamName() : result.getAwayTeamName();
        String pressureTeam = homeSetPieces != awaySetPieces
                ? (homeSetPieces > awaySetPieces ? result.getHomeTeamName() : result.getAwayTeamName())
                : controlTeam;

        return pick(
                controlTeam + " spent longer dictating territory, while " + pressureTeam + " kept the set-piece pressure alive.",
                "The control phases leaned toward " + controlTeam + ", but much of the real danger came whenever " + pressureTeam + " forced dead-ball situations.",
                controlTeam + " looked the cleaner side in open play and " + pressureTeam + " repeatedly tried to shift the match through restarts and second balls."
        );
    }

    private String buildTurningPoint(List<CSMatchEvent> timeline) {
        if (timeline.isEmpty()) {
            return "No single flashpoint emerged, so the result was shaped more by general control than by one dramatic swing.";
        }

        CSMatchEvent turning = timeline.stream()
                .filter(e -> e.getEventType() == CSEventType.GOAL
                        || e.getEventType() == CSEventType.RED_CARD
                        || e.getEventType() == CSEventType.PENALTY
                        || e.getEventType() == CSEventType.INJURY
                        || e.getEventType() == CSEventType.SUBSTITUTION)
                .findFirst()
                .orElse(timeline.get(Math.max(0, timeline.size() / 2)));

        return switch (turning.getEventType()) {
            case GOAL -> safeName(turning.getPlayerName()) + " changed the mood in " + turning.getMinute()
                    + "' when " + safeTeam(turning.getTeamName()) + " finally found daylight on the scoreboard.";
            case RED_CARD -> "The dismissal in " + turning.getMinute() + "' forced " + safeTeam(turning.getTeamName())
                    + " into survival mode and rewrote the flow of the contest.";
            case PENALTY -> "Everything tightened around the penalty in " + turning.getMinute()
                    + "', the one phase that genuinely made both dugouts hold their breath.";
            case INJURY -> "The injury pause in " + turning.getMinute()
                    + "' disrupted the rhythm and left both benches recalculating their route through the game.";
            case SUBSTITUTION -> "The substitution on " + turning.getMinute()
                    + "' shifted the balance, giving the final half-hour a noticeably different shape.";
            default -> "The key swing arrived around " + turning.getMinute()
                    + "', when the pace of the game tilted in a way neither side could fully undo.";
        };
    }

    private void appendStandoutPerformers(StringBuilder sb, CSMatchResult result) {
        List<CSPlayerMatchStats> topPerformers = allPlayerStats(result).stream()
                .sorted(Comparator.comparingDouble(CSPlayerMatchStats::getRating).reversed()
                        .thenComparingInt(CSPlayerMatchStats::getGoals).reversed()
                        .thenComparingInt(CSPlayerMatchStats::getAssists).reversed())
                .limit(3)
                .toList();
        if (topPerformers.isEmpty()) {
            return;
        }

        sb.append("\n\nSTANDOUT PERFORMERS\n");
        for (CSPlayerMatchStats stat : topPerformers) {
            sb.append(safeName(stat.getPlayerName()))
                    .append(" - rating ")
                    .append(String.format(java.util.Locale.US, "%.1f", stat.getRating()))
                    .append(", goals ").append(stat.getGoals())
                    .append(", assists ").append(stat.getAssists())
                    .append(", key passes ").append(stat.getKeyPasses())
                    .append(".\n");
        }
    }

    private String buildRoundDesk(int round, List<CSMatchResult> results, int totalGoals, Long userTeamId) {
        long draws = results.stream().filter(r -> r.getHomeGoals() == r.getAwayGoals()).count();
        long awayWins = results.stream().filter(r -> r.getAwayGoals() > r.getHomeGoals()).count();
        CSMatchResult userMatch = results.stream()
                .filter(r -> Objects.equals(r.getHomeTeamId(), userTeamId) || Objects.equals(r.getAwayTeamId(), userTeamId))
                .findFirst()
                .orElse(null);

        String userLine = userMatch == null
                ? "Your club had no dedicated line in this review."
                : "Your file: " + buildMiniSummaryLine(userMatch);

        return "Round " + round + " produced " + totalGoals + " goals, " + draws + " draws and "
                + awayWins + " away wins. " + userLine;
    }

    private void appendHalfSummary(StringBuilder sb, CSMatchResult result, List<CSMatchEvent> events,
                                   int fromMinute, int toMinute, String label) {
        int homeGoals = countEvents(events, CSEventType.GOAL, result.getHomeTeamName(), fromMinute, toMinute);
        int awayGoals = countEvents(events, CSEventType.GOAL, result.getAwayTeamName(), fromMinute, toMinute);
        int homeShotsOn = countEvents(events, CSEventType.SHOT_ON_TARGET, result.getHomeTeamName(), fromMinute, toMinute);
        int awayShotsOn = countEvents(events, CSEventType.SHOT_ON_TARGET, result.getAwayTeamName(), fromMinute, toMinute);
        int totalShots = homeShotsOn + awayShotsOn;
        int homeShare = totalShots == 0 ? 50 : Math.round((homeShotsOn * 100f) / totalShots);

        sb.append("\n\n").append(label).append(": ")
                .append(result.getHomeTeamName()).append(' ').append(homeGoals).append(':').append(awayGoals).append(' ')
                .append(result.getAwayTeamName()).append(". ")
                .append(pick(
                        "The tempo stayed honest and the midfield battle never really cooled.",
                        "The spell had an old-school league grind to it.",
                        "There was enough bite in the contest to keep every duel meaningful."
                ))
                .append(' ').append("Control estimate: ")
                .append(result.getHomeTeamName()).append(' ').append(homeShare).append("%, ")
                .append(result.getAwayTeamName()).append(' ').append(100 - homeShare).append("%.");
    }

    private void appendMatchDesk(StringBuilder sb, CSMatchResult result, List<CSMatchEvent> events, CSPlayerMatchStats motm) {
        int homeShots = countEvents(events, CSEventType.SHOT_ON_TARGET, result.getHomeTeamName(), 1, 90)
                + countEvents(events, CSEventType.SHOT_OFF_TARGET, result.getHomeTeamName(), 1, 90);
        int awayShots = countEvents(events, CSEventType.SHOT_ON_TARGET, result.getAwayTeamName(), 1, 90)
                + countEvents(events, CSEventType.SHOT_OFF_TARGET, result.getAwayTeamName(), 1, 90);
        int homeCards = countEvents(events, CSEventType.YELLOW_CARD, result.getHomeTeamName(), 1, 90)
                + countEvents(events, CSEventType.RED_CARD, result.getHomeTeamName(), 1, 90);
        int awayCards = countEvents(events, CSEventType.YELLOW_CARD, result.getAwayTeamName(), 1, 90)
                + countEvents(events, CSEventType.RED_CARD, result.getAwayTeamName(), 1, 90);

        sb.append("\n\nMatch desk\n");
        sb.append(result.getHomeTeamName()).append(" shots: ").append(homeShots)
                .append(" | ").append(result.getAwayTeamName()).append(" shots: ").append(awayShots).append("\n");
        sb.append(result.getHomeTeamName()).append(" disciplinary count: ").append(homeCards)
                .append(" | ").append(result.getAwayTeamName()).append(" disciplinary count: ").append(awayCards).append("\n");
        if (motm != null) {
            sb.append("Notebook star: ").append(safeName(motm.getPlayerName())).append(" led the headlines.\n");
        }
    }

    private String buildScoreHeadline(CSMatchResult result) {
        int diff = Math.abs(result.getHomeGoals() - result.getAwayGoals());
        if (result.getHomeGoals() == result.getAwayGoals()) {
            return pick(
                    "A finely-balanced contest ended with honours shared.",
                    "Neither side could fully break the other over ninety minutes.",
                    "The fixture stayed in the balance right to the closing whistle."
            );
        }
        String winner = result.getHomeGoals() > result.getAwayGoals() ? result.getHomeTeamName() : result.getAwayTeamName();
        return diff >= 3
                ? winner + " produced a statement win and left little room for debate."
                : diff == 2
                ? winner + " found a decisive edge once the key moment arrived."
                : winner + " edged a competitive match by the narrowest convincing margin.";
    }

    private String buildClosingVerdict(CSMatchResult result) {
        if (result.getHomeGoals() == result.getAwayGoals()) {
            return pick(
                    "A point each and plenty for both managers to review before the next round.",
                    "The draw goes into the books after a match that rarely drifted away from balance.",
                    "A result that settles nothing, but keeps both clubs moving."
            );
        }
        String winner = result.getHomeGoals() > result.getAwayGoals() ? result.getHomeTeamName() : result.getAwayTeamName();
        return pick(
                winner + " take the points and the better mood into the next week.",
                winner + " leave with the stronger story and the healthier dressing room.",
                winner + " close the file as deserved winners on the day."
        );
    }

    private String buildRoundHeadline(int totalGoals, int matches, CSMatchResult biggestWin, CSMatchResult highestScoring) {
        return pick(
                "The round returned " + totalGoals + " goals across " + matches + " fixtures, with " + highestScoring.getHomeTeamName() + " vs " + highestScoring.getAwayTeamName() + " among the livelier files.",
                "A busy round produced " + totalGoals + " goals, while " + biggestWin.getHomeTeamName() + " vs " + biggestWin.getAwayTeamName() + " delivered the clearest winning margin.",
                totalGoals + " total goals were logged this round, and several coaches will spend the week replaying the bigger moments."
        );
    }

    private String toNarration(CSMatchEvent e) {
        String player = safeName(e.getPlayerName());
        String team = safeTeam(e.getTeamName());
        return switch (e.getEventType()) {
            case GOAL -> {
                String assistText = e.getAssistName() != null && !e.getAssistName().isBlank() ? " Assist: " + e.getAssistName() + "." : "";
                String scoreText = e.getScoreAfterGoal() != null ? " [" + e.getScoreAfterGoal() + "]" : "";
                yield pick(
                        "GOAL - " + player + " finishes for " + team + "." + assistText + scoreText,
                        player + " gets on the end of the move and scores for " + team + "." + assistText + scoreText,
                        "Clinical work from " + player + " puts " + team + " in business." + assistText + scoreText
                );
            }
            case SHOT_ON_TARGET -> pick(player + " tests the goalkeeper.", player + " forces a save from the keeper.", team + " work the ball for a shot on target by " + player + ".");
            case SHOT_OFF_TARGET -> pick(player + " drags the effort off target.", player + " misses the frame from a promising position.", team + " threaten, but " + player + " cannot keep the finish down.");
            case CORNER -> pick("Corner to " + team + ".", team + " win a set-piece in a dangerous area.", "Another corner is earned by " + team + ".");
            case YELLOW_CARD -> pick(player + " goes into the book.", "The referee shows yellow to " + player + ".", player + " is cautioned after a late challenge.");
            case RED_CARD -> pick("Red card - " + player + " is dismissed.", player + " sees red and leaves " + team + " short-handed.", "The match turns sharply as " + player + " is sent off.");
            case PENALTY -> e.isPenaltyScored()
                    ? pick(player + " keeps his nerve from the spot.", "Penalty converted by " + player + ".", player + " makes no mistake with the penalty.")
                    : pick(player + " fails to convert the penalty.", "Penalty missed by " + player + ".", "The spot-kick is wasted by " + player + ".");
            case FOUL -> pick(player + " gives away a foul.", "Referee penalises " + player + ".", player + " arrives late and the whistle follows.");
            case OFFSIDE -> pick(player + " is flagged offside.", "The move breaks down with " + player + " beyond the line.", "Offside against " + player + ".");
            case FREE_KICK -> pick("Free kick for " + team + ".", team + " have a set-piece chance.", "Dangerous dead-ball situation for " + team + ".");
            case VAR_REVIEW -> pick("VAR takes a closer look at the previous action.", "The referee pauses play for a VAR check.", "A short VAR delay adds tension to the stadium.");
            case SUBSTITUTION -> pick(team + " change it up: " + safeName(e.getPlayerOutName()) + " off, " + safeName(e.getPlayerInName()) + " on.",
                    "Tactical switch from " + team + " as " + safeName(e.getPlayerInName()) + " replaces " + safeName(e.getPlayerOutName()) + ".",
                    "Fresh legs for " + team + " with " + safeName(e.getPlayerInName()) + " entering the game.");
            case INJURY -> pick(player + " stays down and the physio is called.", "There is concern for " + player + " after a heavy moment.", "An injury pause interrupts the flow for " + player + ".");
            case MATCH_START, MATCH_END -> "";
        };
    }

    private String buildMiniSummaryLine(CSMatchResult r) {
        List<CSMatchEvent> goals = (r.getEvents() == null ? List.<CSMatchEvent>of() : r.getEvents()).stream()
                .filter(e -> e.getEventType() == CSEventType.GOAL)
                .sorted(Comparator.comparingInt(CSMatchEvent::getMinute))
                .toList();
        if (goals.isEmpty()) return pick(
                "A tight file dominated by structure, shape and defended spaces.",
                "Chances were scarce and the back lines stayed in charge.",
                "One for the purists: organisation first, fireworks second."
        );
        CSMatchEvent decisive = goals.getLast();
        return pick(
                "Defining moment: " + decisive.getMinute() + "' and " + safeName(decisive.getPlayerName()) + " supplied it.",
                "The key strike arrived in " + decisive.getMinute() + "' through " + safeName(decisive.getPlayerName()) + ".",
                safeName(decisive.getPlayerName()) + " wrote the main headline with the crucial goal in minute " + decisive.getMinute() + "."
        );
    }

    private CSPlayerMatchStats findManOfTheMatch(CSMatchResult result) {
        return allPlayerStats(result).stream()
                .max(Comparator.comparingDouble(CSPlayerMatchStats::getRating)
                        .thenComparingInt(CSPlayerMatchStats::getGoals)
                        .thenComparingInt(CSPlayerMatchStats::getAssists))
                .orElse(null);
    }

    private List<CSPlayerMatchStats> allPlayerStats(CSMatchResult result) {
        List<CSPlayerMatchStats> stats = new ArrayList<>();
        if (result.getHomePlayerStats() != null) stats.addAll(result.getHomePlayerStats());
        if (result.getAwayPlayerStats() != null) stats.addAll(result.getAwayPlayerStats());
        return stats;
    }

    private int countEvents(List<CSMatchEvent> events, CSEventType type, String team, int fromMinute, int toMinute) {
        return (int) events.stream()
                .filter(e -> e.getEventType() == type)
                .filter(e -> e.getMinute() >= fromMinute && e.getMinute() <= toMinute)
                .filter(e -> Objects.equals(team, e.getTeamName()))
                .count();
    }

    private String safeName(String value) {
        return value == null || value.isBlank() ? "Unknown player" : value;
    }

    private String safeTeam(String value) {
        return value == null || value.isBlank() ? "Unknown team" : value;
    }

    private String pick(String... variants) {
        if (variants == null || variants.length == 0) return "";
        return variants[rnd.nextInt(variants.length)];
    }
}
