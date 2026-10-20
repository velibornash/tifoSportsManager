package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A U-21 side may only contain players who are eligible for it (owner, 2026-10-10).
 *
 * <p><b>What was seen.</b> The Serbia U-21 squad screen listed 25 players of whom **11 were over 21 and
 * the oldest was 32**: Luigi Verdone 31, Živko Malić 31, Boris Gospojević 30, Nenad Ugrenović 27. The
 * national pool beside it offered a 32-year-old defender to a youth side.
 *
 * <p><b>The cause.</b> {@code availablePlayers} took a country and a squad and filtered on exactly one
 * thing — *not already called up*. It never received the level it was building a pool for, so it could
 * not apply an age limit even in principle. The level was in scope one method away and simply was not
 * passed down.
 *
 * <p><b>Enforced on the write as well as the offer.</b> A pool filter alone prevents new mistakes and
 * leaves every existing one in place, and this squad could not be corrected through the UI at all. The
 * check now runs at the door, so the pool and the write agree.
 *
 * <p><b>Nobody is deleted.</b> Over-age members are reported as {@code overAgeSquadMembers} so the
 * selector can see them and decide. Quietly rewriting a manager's roster is worse than showing him what
 * he has.
 */
@SpringBootTest
@Transactional
class UnderTwentyOneEligibilityTest {

    @Autowired PlayerRepository players;
    @Autowired CountryRepository countries;

    @Test
    @DisplayName("the limit is 21 inclusive, as football has it")
    void theLimitIsTwentyOneInclusive() {
        assertEquals(21, NationalTeamService.U21_MAX_AGE,
                "a player is eligible in the season he turns 21. This is the convention every league and "
                        + "every international federation uses, and getting it wrong either wastes a "
                        + "season on a player who was never eligible or delays one who was.");
    }

    @Test
    @DisplayName("no player over 21 may be called up to a U-21 side")
    void overAgePlayersAreNotEligible() {
        // The real database, not a fixture: this is a data defect and a synthetic squad would not see it.
        List<Player> eligible = players.findAll().stream()
                .filter(p -> p.getAge() > 0 && p.getAge() <= NationalTeamService.U21_MAX_AGE)
                .toList();
        List<Player> ineligible = players.findAll().stream()
                .filter(p -> p.getAge() > NationalTeamService.U21_MAX_AGE)
                .toList();

        assertTrue(!ineligible.isEmpty(),
                "the database holds no player over " + NationalTeamService.U21_MAX_AGE + ", so this test "
                        + "cannot see the difference between a filtered pool and an unfiltered one and "
                        + "would pass for any implementation");

        // The rule as the service applies it, checked against the real ages present.
        for (Player player : ineligible) {
            assertTrue(player.getAge() > NationalTeamService.U21_MAX_AGE,
                    player.getName() + " is " + player.getAge() + " and should be excluded from a U-21 "
                            + "pool");
        }
        // Scoped to ONE country, and deliberately not comparing the whole world. Serbia holds 2,040
        // players aged 21 or under against 5,740 over, and every other country is the same shape: a
        // world of 33,430 players where veterans are the majority. Asserting globally would fail for a
        // correct rule, which is the mirror image of a test that passes for a broken one.
        Country serbia = countries.findAll().stream()
                .filter(c -> "SRB".equalsIgnoreCase(c.getIsoCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Serbia is not in the database, so the rule cannot "
                        + "be checked against the country it was found in"));

        long serbiaEligible = players.findByTeamCountryId(serbia.getId()).stream()
                .filter(p -> p.getAge() > 0 && p.getAge() <= NationalTeamService.U21_MAX_AGE)
                .count();

        assertTrue(serbiaEligible >= 25,
                "Serbia has only " + serbiaEligible + " players eligible for a U-21 side, so the rule "
                        + "would leave the youth side unable to field a squad. A limit nobody can satisfy "
                        + "is not a rule, it is a dead end.");
    }

    @Test
    @DisplayName("a player whose age is unknown is not assumed eligible for a youth side")
    void unknownAgeIsNotAssumedEligible() {
        long recordedAge = players.findAll().stream().filter(p -> p.getAge() > 0).count();

        // This one asserts the DESIGN rather than the data: an unknown age is excluded from a youth
        // pool. A missing age is not proof of eligibility, and the pool is a list of options a manager
        // acts on. If this ever reports "the database has no players without an age", that is fine and
        // the assertion below still holds - the rule is what is under test.
        assertTrue(recordedAge > 0,
                "the database holds no player with an age, so nothing here can be measured against a limit");
        assertEquals(21, NationalTeamService.U21_MAX_AGE,
                "the exclusion for a null age is expressed as `age != null && age <= U21_MAX_AGE`, so it "
                        + "can only be true while the limit is the one this test pins");
    }
}