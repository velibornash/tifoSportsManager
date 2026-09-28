package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.GameDay;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Stamps the day onto league fixtures created before the day existed (owner, 2026-09-29).
 *
 * <p>Every league fixture already in the database has a week but no day, and the day-3 and day-7
 * matchday jobs select by day - so without this they find nothing and the season plays no league
 * football at all. The generator now stamps it for new fixtures; this covers the ones already seeded,
 * so the fix does not require resetting a world someone has been playing.
 *
 * <p>Which day is which: a week holds two rounds, the fixture and its reverse leg. The first is the
 * day-3 round and the reverse is the day-7 round. Within a week that is decided by round order, not
 * by guessing.
 */
@Component
public class LeagueFixtureDayBackfill {

    private static final Logger log = LoggerFactory.getLogger(LeagueFixtureDayBackfill.class);

    private final MatchFixtureRepository fixtures;
    private final TransactionTemplate requiresNew;

    public LeagueFixtureDayBackfill(MatchFixtureRepository fixtures,
                                    PlatformTransactionManager transactionManager) {
        this.fixtures = fixtures;
        // Its own transaction, because this runs inside the boot listener's transaction. Joining it
        // meant the 2790 saves were rolled back with it: the log said "stamped 2790" and the table
        // stayed null. The same lesson as the JobRunner boundary - a save inside someone else's
        // transaction is a save that may never have happened.
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public int backfill() {
        List<MatchFixture> leagues = fixtures.findAll().stream()
                .filter(f -> f.getCompetition() != null
                        && f.getCompetition().getType() == CompetitionType.LEAGUE)
                .toList();

        Integer[] stamped = {0};
        requiresNew.executeWithoutResult(status -> {
            for (MatchFixture fixture : leagues) {
                if (fixture.getDayNumber() != null || fixture.getWeekNumber() == null) {
                    continue;
                }
                fixture.setDayNumber(dayFor(fixture, leagues));
                fixtures.save(fixture);
                stamped[0] = stamped[0] + 1;
            }
        });
        int count = stamped[0];
        if (count > 0) {
            log.info("Stamped the game day onto {} league fixture(s) created before the day existed.",
                    count);
        }
        return count;
    }

    /**
     * Day 3 for the earlier round of the week, day 7 for the later one.
     *
     * <p>Both legs of a tie share a week. Which is which is the lower round number, so the split is
     * decided by the data rather than by iteration order.
     */
    private int dayFor(MatchFixture fixture, List<MatchFixture> allLeagues) {
        // Scoped to the fixture's OWN competition, not just the week. A country has 31 leagues that
        // share week numbers, so a week can hold four distinct rounds - league A round 1 and league B
        // round 1. Comparing against the lowest round in the whole week put every league's first
        // round on day 3 and its second on day 7 but measured league A against league B, which gave
        // 775 on day 3 against 2015 on day 7 instead of a split.
        int lowest = allLeagues.stream()
                .filter(other -> other.getWeekNumber().equals(fixture.getWeekNumber()))
                .filter(other -> other.getCompetition() != null && fixture.getCompetition() != null
                        && other.getCompetition().getId().equals(fixture.getCompetition().getId()))
                .mapToInt(other -> other.getRoundNumber() == null ? Integer.MAX_VALUE : other.getRoundNumber())
                .min()
                .orElse(Integer.MAX_VALUE);
        int round = fixture.getRoundNumber() == null ? Integer.MAX_VALUE : fixture.getRoundNumber();
        return round == lowest ? GameDay.LEAGUE_FIRST_DAY : GameDay.LEAGUE_SECOND_DAY;
    }
}
