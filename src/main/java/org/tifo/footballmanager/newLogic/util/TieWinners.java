package org.tifo.footballmanager.newLogic.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tifo.footballmanager.newLogic.model.Match;
import org.tifo.footballmanager.newLogic.model.MatchFixture;
import org.tifo.footballmanager.newLogic.model.Team;

/**
 * The winning side of a played knockout tie, or null when it genuinely is not knowable.
 *
 * <p>Same rule in all three draws — the national cup, the continental cups and the national-team
 * tournaments — and it was written out three times, near-identically, in
 * {@code CupFixtureSeeder}, {@code InternationalClubCupDraw} and {@code NationalTournamentSeeder}. A
 * level tie is settled from the spot when the match is persisted, and the result lives in the match's own
 * penalty columns; a tie that is level <i>and</i> has no shootout recorded stops the bracket instead of
 * being settled by a coin toss. <b>A cup that invents a winner is worse than a cup that is one team short
 * and says so.</b>
 *
 * <p>The scoreline is not touched by the shootout, and that is deliberate: a tie that finished 1-1 and was
 * won 4-3 on penalties is a 1-1 match, and folding the kicks into the goals would report it as 5-4 to the
 * table, the replay and the scoreline on the page.
 *
 * <p><b>Not the honours rule.</b> {@code HonourService} has its own, simpler version that ignores the
 * penalty columns, so a final decided on penalties currently crowns nobody. Consolidating it here would
 * change who is awarded a trophy, which is a decision about the game and not a piece of housekeeping — see
 * the note in the P1-11 entry.
 */
public final class TieWinners {

    private static final Logger log = LoggerFactory.getLogger(TieWinners.class);

    private TieWinners() {
    }

    public static Team winnerOf(MatchFixture tie) {
        Match played = tie == null ? null : tie.getPlayedMatch();
        if (played == null) {
            return null;
        }
        int home = played.getHomeGoals();
        int away = played.getAwayGoals();
        if (home == away) {
            Integer homePens = played.getHomePenaltyGoals();
            Integer awayPens = played.getAwayPenaltyGoals();
            if (homePens == null || awayPens == null || homePens.equals(awayPens)) {
                log.warn("{} tie {} finished level at {}-{} with no shootout recorded; no winner taken.",
                        tie.getCompetition() == null ? "?" : tie.getCompetition().getName(),
                        tie.getId(), home, away);
                return null;
            }
            return homePens > awayPens ? tie.getHomeTeam() : tie.getAwayTeam();
        }
        return home > away ? tie.getHomeTeam() : tie.getAwayTeam();
    }
}
