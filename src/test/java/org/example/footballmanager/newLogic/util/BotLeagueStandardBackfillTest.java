package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.util.players.BotLeagueStandard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The standards the clubs already in the database are put on (owner, 2026-09-30).
 *
 * <p>Written against the <b>seeded world</b> rather than against fixtures this test creates, and that
 * is not a shortcut. The backfill runs in {@code REQUIRES_NEW} — which is the only reason it survives
 * the boot listener's transaction — so anything an {@code @Transactional} test inserts is invisible to
 * it. My first version of this test built two clubs, called the backfill, and asserted they had moved;
 * they had not, and three of its four tests were passing only because the backfill had correctly
 * ignored rows it could not see.
 *
 * <p>So this asserts on the 300-odd clubs the seeder actually built, which is the thing the owner is
 * looking at. The measured "before", straight out of Postgres before the fix landed:
 * tier 1 → 8.61, tier 2 → 8.71, tier 3 → 8.57, tier 4 → 8.56, tier 5 → 8.57.
 */
class BotLeagueStandardBackfillTest extends BaseTest {

    @Autowired private BotLeagueStandardBackfill backfill;
    @Autowired private BotLeagueStandard standard;
    @Autowired private TeamRepository teamRepository;
    @Autowired private PlayerRepository playerRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    /**
     * A read that owns its session.
     *
     * <p>{@code Team.competition} is a lazy proxy and these tests are deliberately not
     * {@code @Transactional} — the backfill commits in its own transaction, so a long-lived test
     * transaction would be reading a stale world. That leaves the proxy uninitialised, and the failure
     * is a LazyInitializationException rather than anything about the code under test.
     */
    private <T> T readInTransaction(java.util.function.Supplier<T> read) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return template.execute(status -> read.get());
    }

    @Test
    @DisplayName("the whole pyramid is put on its tier's standard")
    void everyTierLandsOnItsOwnNumber() {
        BotLeagueStandardBackfill.Result result = backfill.backfill();

        Map<Integer, Double> byTier = result.averageByTier();
        assertTrue(byTier.size() >= 5,
                "expected all five divisions, got " + byTier);

        for (int tier = 1; tier <= BotLeagueStandard.LOWEST_TIER; tier++) {
            Double actual = byTier.get(tier);
            assertTrue(actual != null, "no figure for tier " + tier + " in " + byTier);
            int expected = standard.skillAverageForTier(tier);
            assertTrue(Math.abs(actual - expected) <= 0.6,
                    "tier " + tier + " came out at " + actual + ", expected " + expected + " ± 0.6");
        }

        // The assertion that matters. The old world had tier 1 at 8.61 and tier 2 at 8.71 — the second
        // tier was the strongest in the pyramid, and all five sat inside a 0.15 band of each other.
        for (int tier = 2; tier <= BotLeagueStandard.LOWEST_TIER; tier++) {
            double above = byTier.get(tier - 1);
            double below = byTier.get(tier);
            assertTrue(above > below,
                    "tier " + (tier - 1) + " (" + above + ") is not stronger than tier "
                            + tier + " (" + below + ") — the pyramid is upside down");
            assertTrue(above - below >= 0.8,
                    "tier " + (tier - 1) + " → " + tier + " differs by only " + (above - below)
                            + ", which one season of results would wash out");
        }

        // The standing rule: a job must be shown to have changed data, not just to have run.
        assertTrue(result.clubs() > 250, "only " + result.clubs() + " clubs were re-standardised");
        assertTrue(result.players() > 5000, "only " + result.players() + " players were re-standardised");
    }

    @Test
    @DisplayName("the manager's own club and the hand-authored one are left alone")
    void humanClubsAreNeverTouched() {
        Map<String, String> before = readHumanSquads();
        assertFalse(before.isEmpty(),
                "the seeded world has no human club, so this test would pass without proving anything");

        backfill.backfill();

        assertEquals(before, readHumanSquads(),
                "a hand-authored squad was re-rolled. Omladinac is the manager's own team and its "
                        + "players have written skill rows — the one squad in the game that was not "
                        + "generated, and the one nobody can afford to have changed by a backfill");
    }

    @Test
    @DisplayName("re-running it on the next boot changes nothing the second time")
    void backfillIsIdempotent() {
        backfill.backfill();
        Map<Integer, Double> afterFirst = backfill.backfill().averageByTier();

        // Read every bot player, not the summary: the summary could be steady while individual squads
        // were quietly re-rolled underneath it.
        Map<String, String> fingerprint = readBotSquads();

        backfill.backfill();

        assertEquals(afterFirst, backfill.backfill().averageByTier(),
                "the tier averages drifted on a repeat run");
        assertEquals(fingerprint, readBotSquads(),
                "a second run produced different squads. This runs on every boot, so a "
                        + "non-idempotent backfill would slowly re-roll the world out from under the "
                        + "manager");
    }

    @Test
    @DisplayName("a club is only re-standardised inside a league")
    void cupsAndNationalSidesAreLeftAlone() {
        // The national sides come from BotSquadGenerator on the owner's fixed skill-12 number, and cup
        // entrants have no division to standard to. Re-rolling either would change a standard the
        // owner set deliberately.
        backfill.backfill();

        List<String> leaked = readInTransaction(() -> {
            List<String> found = new ArrayList<>();
            for (Team team : teamRepository.findAll()) {
                if (team.getCompetition() == null) {
                    continue;
                }
                CompetitionType type = team.getCompetition().getType();
                if (type == CompetitionType.LEAGUE) {
                    continue;
                }
                for (Player player : playerRepository.findByTeamId(team.getId())) {
                    if (player.getSkills() == null) {
                        continue;
                    }
                    // A national side is generated at skill 12 and is not tiered, so its players sit at
                    // the top-flight number. Anything far above that came from this backfill.
                    double average = averageOf(player);
                    if (average > standard.TIER_ONE_AVERAGE + 2) {
                        found.add(team.getName() + " (" + type + ") at " + average);
                        break;
                    }
                }
            }
            return found;
        });
        assertTrue(leaked.isEmpty(), "non-league clubs were re-standardised: " + leaked);
    }

    // --- helpers ---

    private double averageOf(Player player) {
        int sum = 0;
        for (SkillName skill : BotLeagueStandard.FOOTBALL_SKILLS.keySet()) {
            sum += player.getSkills().visibleInt(skill);
        }
        return sum / (double) BotLeagueStandard.FOOTBALL_SKILLS.size();
    }

    /** A stable, order-independent fingerprint of the human clubs' skills. */
    private Map<String, String> readHumanSquads() {
        return readInTransaction(() -> {
            Map<String, String> fingerprint = new LinkedHashMap<>();
            for (Team team : teamRepository.findAll()) {
                if (!team.isHumanControlled()) {
                    continue;
                }
                fingerprint.put(team.getName(), squadFingerprint(team));
            }
            return fingerprint;
        });
    }

    private Map<String, String> readBotSquads() {
        return readInTransaction(() -> {
            Map<String, String> fingerprint = new LinkedHashMap<>();
            for (Team team : teamRepository.findAll()) {
                if (team.isHumanControlled() || team.getCompetition() == null
                        || team.getCompetition().getType() != CompetitionType.LEAGUE) {
                    continue;
                }
                fingerprint.put(team.getName(), squadFingerprint(team));
            }
            return fingerprint;
        });
    }

    private String squadFingerprint(Team team) {
        StringBuilder key = new StringBuilder();
        List<Player> squad = playerRepository.findByTeamId(team.getId());
        squad.stream()
                .sorted((left, right) -> left.getName().compareTo(right.getName()))
                .forEach(player -> {
                    key.append(player.getName()).append('=');
                    for (SkillName skill : BotLeagueStandard.FOOTBALL_SKILLS.keySet()) {
                        key.append(player.getSkills().visibleInt(skill)).append(',');
                    }
                    key.append(';');
                });
        return key.toString();
    }
}
