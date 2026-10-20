package org.tifo.footballtextmanager.engine;

import org.tifo.footballtextmanager.model.CSEventType;
import org.tifo.footballtextmanager.model.CSMatchEvent;
import org.tifo.footballtextmanager.model.CSMatchResult;
import org.tifo.footballtextmanager.model.CSPlayerMatchStats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The facts about one match, derived once, so that every account of it can be written from the
 * same truth.
 *
 * <p>WHY THIS EXISTS. The press conferences, media headlines and round reviews were built by
 * picking at random from pools of three or four sentences. That produced three problems a manager
 * notices immediately:
 *
 * <ul>
 *   <li><b>Nothing referred to the match.</b> A 1-0 win and a 4-0 win drew from the same pool, so
 *       the copy never mentioned the scoreline, the scorers or the margin. A press conference
 *       about a game in which you won 1-0 at the death read exactly like one after a rout.</li>
 *   <li><b>Nothing referred to the club either.</b> Questions were asked in a fixed order, so the
 *       same four questions came every round. Being thrown a question about defending a lead after
 *       a 5-0 win is the kind of detail that makes a manager feel the text is written, not said.</li>
 *   <li><b>Everything recurred.</b> With four options and thirty-four rounds, the same sentence came
 *       back constantly. Players read it as duplication, which is exactly how it read.</li>
 * </ul>
 *
 * <p>So the writing starts here: the scoreline, the scorers in order, the shape of the result
 * (narrow win, rout, comeback, collapse, clean sheet) and the incidents that decided it. The
 * generators above then colour those facts rather than inventing a mood and hoping it fits.
 *
 * <p>Everything is derived from the stored {@link CSMatchResult}, so nothing here can disagree
 * with the scoreline the teletext shows.
 */
public final class CSMatchFacts {

    /** How the match actually went, from one side's point of view. */
    public enum Shape {
        ROUT, NARROW_WIN, COMEBACK, COLLAPSE, CLEAN_SHEET_WIN, STALEMATE, NARROW_LOSS, THRASHING, GOALLESS
    }

    private final CSMatchResult result;
    private final String perspectiveTeam;
    private final int scoredFor;
    private final int scoredAgainst;
    private final boolean wasHome;

    private CSMatchFacts(CSMatchResult result, String perspectiveTeam, Long perspectiveTeamId) {
        this.result = result;
        boolean home = Objects.equals(perspectiveTeamId, result.getHomeTeamId());
        this.wasHome = home;
        this.perspectiveTeam = perspectiveTeam;
        this.scoredFor = home ? result.getHomeGoals() : result.getAwayGoals();
        this.scoredAgainst = home ? result.getAwayGoals() : result.getHomeGoals();
    }

    public static CSMatchFacts from(CSMatchResult result, String perspectiveTeam, Long perspectiveTeamId) {
        if (result == null) return null;
        return new CSMatchFacts(result, perspectiveTeam, perspectiveTeamId);
    }

    /** True when the side being written about was at home. */
    public boolean isHome() {
        return wasHome;
    }

    public String opponent() {
        return wasHome ? result.getAwayTeamName() : result.getHomeTeamName();
    }

    public int scoredFor() {
        return scoredFor;
    }

    public int scoredAgainst() {
        return scoredAgainst;
    }

    public int margin() {
        return scoredFor - scoredAgainst;
    }

    public String scoreLine() {
        return result.getHomeTeamName() + " " + result.getHomeGoals() + "-" + result.getAwayGoals()
                + " " + result.getAwayTeamName();
    }

    public String venuePhrase() {
        return wasHome ? "at home to " + opponent() : "away at " + opponent();
    }

    /**
     * Shape of the result — which decides what the manager is asked about.
     *
     * <p>A comeback and a narrow win are different stories and must not produce the same questions.
     * The test for each is the scoreline *at some point*, not at any particular minute: a side that
     * was behind at 70 minutes and level at 88 has come back, and asking about "the run of fifteen
     * minutes without a goal" is the correct question.
     */
    public Shape shape() {
        if (scoredFor == 0 && scoredAgainst == 0) return Shape.GOALLESS;

        List<CSMatchEvent> goals = goalsInOrder();
        boolean wasBehind = everBehindOrAhead(goals, true);
        boolean wasAhead = everBehindOrAhead(goals, false);

        if (scoredFor > scoredAgainst) {
            // A clean sheet cannot be a comeback — you cannot come from behind and keep one.
            if (scoredAgainst == 0) return Shape.CLEAN_SHEET_WIN;
            if (wasBehind) return Shape.COMEBACK;
            if (margin() >= 3) return Shape.ROUT;
            return Shape.NARROW_WIN;
        }

        if (scoredFor == scoredAgainst) return Shape.STALEMATE;

        // Finished behind. Had the lead at any stage and it was thrown away.
        if (wasAhead) return Shape.COLLAPSE;
        return margin() == -1 ? Shape.NARROW_LOSS : Shape.THRASHING;
    }

    /**
     * Walk the goals in order and report whether one side was ever strictly ahead of the other.
     *
     * <p>Strictly, on purpose. Being level is not "was ahead", and a 1-0 win where the goal came
     * first was never a comeback.
     *
     * @param behind true to ask whether the perspective side was ever behind, false for ahead
     */
    private boolean everBehindOrAhead(List<CSMatchEvent> goals, boolean behind) {
        int mine = 0;
        int theirs = 0;
        for (CSMatchEvent goal : goals) {
            if (Objects.equals(goal.getTeamName(), perspectiveTeam)) mine++;
            else theirs++;

            int myLead = mine - theirs;
            if (behind && myLead < 0) return true;
            if (!behind && myLead > 0) return true;
        }
        return false;
    }

    public List<CSMatchEvent> goalsInOrder() {
        List<CSMatchEvent> events = new ArrayList<>();
        if (result.getEvents() == null) return events;
        events.addAll(result.getEvents().stream()
                .filter(e -> e.getEventType() == CSEventType.GOAL)
                .sorted(Comparator.comparingInt(CSMatchEvent::getMinute))
                .toList());
        return events;
    }

    /** "Goals: Kovačević 23', Ristić 45+2'" — the spine of every report. */
    public String scorerList(String teamName) {
        String scorers = goalsInOrder().stream()
                .filter(e -> Objects.equals(e.getTeamName(), teamName))
                .map(e -> safe(e.getPlayerName()) + " " + e.getMinute() + "'")
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");
        return scorers;
    }

    /** The goal that decided it: the last one, unless the winner scored earlier. */
    public CSMatchEvent winner() {
        List<CSMatchEvent> goals = goalsInOrder();
        CSMatchEvent last = goals.isEmpty() ? null : goals.getLast();
        if (last == null) return null;
        return Objects.equals(last.getTeamName(), perspectiveTeam) ? last : null;
    }

    public int redCards(String teamName) {
        if (result.getEvents() == null) return 0;
        return (int) result.getEvents().stream()
                .filter(e -> e.getEventType() == CSEventType.RED_CARD && Objects.equals(e.getTeamName(), teamName))
                .count();
    }

    public int yellowCards(String teamName) {
        if (result.getEvents() == null) return 0;
        return (int) result.getEvents().stream()
                .filter(e -> e.getEventType() == CSEventType.YELLOW_CARD && Objects.equals(e.getTeamName(), teamName))
                .count();
    }

    public int shots(String teamName) {
        if (result.getEvents() == null) return 0;
        return (int) result.getEvents().stream()
                .filter(e -> e.getEventType() == CSEventType.SHOT_ON_TARGET || e.getEventType() == CSEventType.SHOT_OFF_TARGET)
                .filter(e -> Objects.equals(e.getTeamName(), teamName))
                .count();
    }

    /** Shots by the side being written about — the shorthand used all over the generator. */
    public int shotsFor() {
        return shots(perspectiveTeam);
    }

    /**
     * The best player of the match, and which side they played for.
     *
     * <p>The side matters and used to be lost. {@code CSPlayerMatchStats} carries no team, so once
     * the two lists are merged the standout cannot be identified as yours or theirs — which is how
     * a press conference ended up congratulating the manager on the opposing striker's performance.
     * The side is known here, where the merge happens, so it is carried out with the stat rather
     * than re-derived (and mis-derived) at the call site.
     */
    public Standout standout() {
        List<CSPlayerMatchStats> homeStats = result.getHomePlayerStats() == null
                ? List.of() : result.getHomePlayerStats();
        List<CSPlayerMatchStats> awayStats = result.getAwayPlayerStats() == null
                ? List.of() : result.getAwayPlayerStats();

        CSPlayerMatchStats best = null;
        boolean bestIsHome = false;

        for (CSPlayerMatchStats stat : homeStats) {
            if (stat != null && (best == null || stat.getRating() > best.getRating())) {
                best = stat;
                bestIsHome = true;
            }
        }
        for (CSPlayerMatchStats stat : awayStats) {
            if (stat != null && (best == null || stat.getRating() > best.getRating())) {
                best = stat;
                bestIsHome = false;
            }
        }
        return best == null ? null : new Standout(best, bestIsHome);
    }

    /** A player of the match together with the side they played for. */
    public record Standout(CSPlayerMatchStats stats, boolean home) {
        /** True when this player belonged to the team the message is being written about. */
        public boolean belongsTo(String teamName, boolean perspectiveIsHome) {
            return home == perspectiveIsHome;
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "someone" : value;
    }
}