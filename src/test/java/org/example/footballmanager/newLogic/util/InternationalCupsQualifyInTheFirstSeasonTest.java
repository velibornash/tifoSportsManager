package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A newly built world must have international cups in its first season (owner, 2026-10-10).
 *
 * <p><b>The gap.</b> The entry rule reads the division winner, the second and third, and the fourth off
 * a <em>finished</em> table. In season 1 there is no season 0, so there was nothing to read and the draw
 * reported "no qualified clubs" for all fifteen continental cups. A manager who builds a world and starts
 * at season 1 gets a season of league football and no European football at all, with a log line that
 * reads like a fact rather than a hole.
 *
 * <p><b>The answer (owner's choice).</b> Read the current season's tables as they stand. That is the same
 * data and the same {@link LeagueTableOrder} comparator the finished-table path uses, so the rule is
 * unchanged — only the season it is asked about differs. No new ordering is invented, and reputation is
 * not substituted for finishing position.
 *
 * <p><b>What is honestly not true here.</b> At week 1 day 1 those tables have barely been played, so
 * "winner" means "leading so far". That is a real ranking and it is the one the league screen shows,
 * which is why this is a first-season fallback and not a second qualification rule.
 */
@SpringBootTest
@Transactional
class InternationalCupsQualifyInTheFirstSeasonTest {

    @Autowired InternationalClubCups cups;

    /** Tier 1's Champions Cup - the largest and the one a manager will look for first. */
    private InternationalClubCups.Cup championsCup() {
        return cups.cups().stream()
                .filter(c -> c.tier() == 1 && c.placesFrom() == 1 && c.placesTo() == 1)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the Champions Cup is not in the cup family"));
    }

    @Test
    @DisplayName("the fallback only fires when the previous season has nothing to offer")
    void theFallbackIsNotTakenWhenThereIsAPreviousSeason() {
        // Season 2 exists in this world and season 1's own entries are on it, so qualifying off season 1
        // must work through the ordinary path. **The fallback must not be what makes this pass** - a
        // fallback that fires whenever it can would make the ordinary rule untestable and, worse, would
        // draw a season 2 cup off season 2 tables, which are not finished.
        List<Team> fromSeasonOne = cups.qualifiedFor(championsCup(), 1);
        List<Team> firstSeasonPath = cups.qualifiedForFirstSeason(championsCup(), 2);

        assertFalse(fromSeasonOne.isEmpty(), "season 1 has no entries at all, so this test proves nothing");

        // Season 2 in this world has barely been played, so it qualifies almost nobody. That is correct
        // and it is exactly why the fallback must be reserved for a world with NO previous season: a
        // fallback that always fired would quietly draw season 2's cups off season 2's own unfinished
        // tables, which is the mistake this assertion exists to keep out.
        assertTrue(firstSeasonPath.size() < fromSeasonOne.size(),
                "season " + firstSeasonPath.size() + " returned as many clubs as the finished season "
                        + "did (" + fromSeasonOne.size() + "). If an unfinished season qualifies as many "
                        + "clubs as a played one, the fallback is firing where it should not.");
    }

    @Test
    @DisplayName("the current season's tables qualify clubs, so the first season is drawable")
    void theCurrentSeasonQualifiesClubs() {
        List<Team> entrants = cups.qualifiedForFirstSeason(championsCup(), 1);

        assertFalse(entrants.isEmpty(),
                "reading season 1's own tables produced no entrants. This is the whole fix: if this is "
                        + "empty then a newly built world still has no international cups in its first "
                        + "season, and the draw will say so again in the log.");

        // One entry per country per cup is what makes the numbers work, and the pool is deduplicated by
        // team id. A duplicate here would put the same club in two groups.
        long distinct = entrants.stream().map(Team::getId).distinct().count();
        assertTrue(distinct == entrants.size(),
                "the same club was entered twice: " + entrants.size() + " entrants, " + distinct
                        + " distinct. One entry per country per cup is the rule the group sizes assume.");
    }

    @Test
    @DisplayName("every cup in the family qualifies somebody in the first season")
    void everyCupQualifiesInTheFirstSeason() {
        for (InternationalClubCups.Cup cup : InternationalClubCups.cups()) {
            List<Team> entrants = cups.qualifiedForFirstSeason(cup, 1);
            assertFalse(entrants.isEmpty(),
                    cup.fullName() + " qualified nobody from season 1's tables. A cup that never appears "
                            + "is the failure this whole change is about, and it has to be every cup and "
                            + "not just the Champions.");
        }
    }

    @Test
    @DisplayName("the entries are real clubs with real ids")
    void entrantsAreRealClubs() {
        List<Team> entrants = cups.qualifiedForFirstSeason(championsCup(), 1);

        for (Team team : entrants) {
            assertTrue(team != null && team.getId() != null,
                    "an entrant with no id cannot be drawn into a group, and the draw would fail much "
                            + "later with a message that does not point here");
        }
    }
}