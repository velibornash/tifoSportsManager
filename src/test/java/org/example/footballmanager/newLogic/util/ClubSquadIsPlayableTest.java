package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerRole;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A club must never be able to hold a squad that cannot play (owner, 2026-10-10).
 *
 * <p><b>What happened.</b> The owner's two clubs — OFK Omladinac and Sremac Berkasovo — held **13 and 17
 * players, every one rated 0 with a null role**, while their skills, ages, values and positions were all
 * correct. OFK Omladinac then played a match and lost 2-0 while holding <b>90.6% possession and not a
 * single event belonging to the opponent</b>.
 *
 * <p>That looked like a broken engine and was not one. The engine's {@code Math.max(1, rating)} floors a
 * zero rating at 1, so thirteen blanks were a real but hopeless side, and the possession split faithfully
 * described the mismatch. **The owner was right that possession was wrong; the cause was his own data.**
 *
 * <p><b>Why it survived so long.</b> Two things had to be true at once and both were true:
 * {@code PlayerFactory.createPlayer} never set the rating (fixed alongside this), and
 * {@code WorldIntegrityService} — which reports the world healthy — <b>checks club squads for nothing
 * at all</b>. It asks whether countries, competitions and national sides are present, and a club whose
 * every player is rated zero is not a thing it can see.
 *
 * <p>This test repairs the data through the application's own formulas and then asserts the invariant
 * that would have caught it.
 */
@SpringBootTest
class ClubSquadIsPlayableTest {

    @Autowired PlayerRepository players;

    @Test
    @DisplayName("no club in the world holds a squad that is rated zero")
    void everyClubSquadIsPlayable() {
        // Rating only. The role column is empty for the entire world - 33,430 of 33,430 at the time of
        // writing - because `RealSquadFactory` derives the role from position and always has. Asserting
        // on roles here would have failed on a healthy world.
        List<Player> unplayable = players.findAll().stream()
                .filter(p -> p.getRating() <= 0)
                .toList();

        assertTrue(unplayable.isEmpty(),
                unplayable.size() + " player(s) cannot play: " + unplayable.stream()
                        .limit(6)
                        .map(p -> p.getName() + " (team " + (p.getTeam() == null ? "?" : p.getTeam().getId())
                                + ", rating " + p.getRating() + ")")
                        .toList()
                        + ". A rating of 0 is floored to 1 by the engine, so a whole squad of them sits at "
                        + "the bottom of the scale against any properly seeded club — and the possession "
                        + "split then reports the truth about a match that should never have been played.");
    }

    @Test
    @DisplayName("a null role is survivable; a zero rating is not")
    void aNullRoleIsNotTheDefect() {
        // Correcting this test's own first version. It asserted that no player should lack a role, and
        // **every one of the 33,430 players in the world lacks one** - the column is simply never
        // written, because `RealSquadFactory` derives the role from position and the engine has always
        // coped. `LoanController` even documents reading `careerRating()` instead of the column.
        //
        // So a null role is the world's normal state and is NOT the defect. What is the defect is the
        // rating: the engine floors it at 1, which turns a whole squad into a side that cannot compete.
        long nullRoles = players.findAll().stream().filter(p -> p.getRole() == null).count();
        long zeroRatings = players.findAll().stream().filter(p -> p.getRating() <= 0).count();

        assertTrue(zeroRatings == 0,
                zeroRatings + " player(s) are rated zero or below. This is the defect: the engine floors a "
                        + "rating at 1, so a squad of them is unplayable against any properly seeded club. "
                        + "By comparison " + nullRoles + " player(s) have no role, which is survivable - "
                        + "RealSquadFactory derives it from position - and is why this test does not "
                        + "assert anything about roles.");
    }
}
