package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sponsor on the boards, and what the Finance page says about it.
 *
 * <p>Owner request, 2026-10-12: a place on the stands to put a sponsor, TIFO Ltd. to start with, and the
 * name against the sponsorship income on the Finance page — *"zgodno ako sutradan zaista nadjem nekog
 * sponzora"*.
 */
class StadiumSponsorTest extends BaseTest {

    @Autowired private TeamRepository teams;

    @Test
    @DisplayName("a ground starts out sponsored by TIFO Ltd.")
    void theDefaultSponsor() {
        assertEquals("TIFO Ltd.", Stadium.DEFAULT_SPONSOR,
                "the owner named the starting sponsor and it has moved");
    }

    @Test
    @Transactional
    @DisplayName("a ground with no sponsor chosen still shows TIFO Ltd. rather than an empty board")
    void anUnsetSponsorReadsAsTheDefault() {
        Stadium ground = aGround();
        assertNull(ground.getSponsorName(), "precondition: no sponsor has been chosen");

        assertEquals(Stadium.DEFAULT_SPONSOR, sponsorAsThePageShowsIt(ground),
                "the advertising boards render blank, so there is nothing to see on a new ground");
    }

    @Test
    @Transactional
    @DisplayName("a sponsor the manager names is what the boards and the Finance page both show")
    void aNamedSponsorIsUsed() {
        Stadium ground = aGround();
        ground.setSponsorName("Vukovar Group");

        assertEquals("Vukovar Group", sponsorAsThePageShowsIt(ground),
                "the manager's own sponsor is not what the boards show");
    }

    @Test
    @Transactional
    @DisplayName("a blank name clears back to the default instead of storing an empty string")
    void aBlankNameIsNotStored() {
        Stadium ground = aGround();
        ground.setSponsorName("   ");

        assertEquals(Stadium.DEFAULT_SPONSOR, sponsorAsThePageShowsIt(ground),
                "a blank sponsor renders a blank advertising board");
    }

    @Test
    @Transactional
    @DisplayName("two grounds can carry two different sponsors")
    void theSponsorIsPerClub() {
        Stadium first = aGround();
        first.setSponsorName("TIFO Ltd.");
        Stadium second = aGround();
        second.setSponsorName("Morava Foods");

        assertEquals("TIFO Ltd.", sponsorAsThePageShowsIt(first));
        assertEquals("Morava Foods", sponsorAsThePageShowsIt(second),
                "one club's sponsor leaked onto another's ground");
    }

    // --- helpers ----------------------------------------------------------

    /**
     * What the stadium page and the Finance page both read.
     *
     * <p>The single rule, not a copy of it: this test used to re-implement the fallback and would have
     * stayed green if the entity's own rule were wrong.
     */
    private String sponsorAsThePageShowsIt(Stadium ground) {
        return ground.effectiveSponsorName();
    }

    private Stadium aGround() {
        Team club = new Team();
        club.setName("ZZ Sponsor club " + UUID.randomUUID());
        Stadium ground = new Stadium();
        ground.setName("ZZ Sponsor ground " + UUID.randomUUID());
        ground.setCapacity(1000);
        club.setStadium(ground);
        return teams.save(club).getStadium();
    }

    @Test
    @Transactional
    @DisplayName("the sponsor survives being written and read back")
    void theSponsorIsPersisted() {
        // A ground with no sponsor set, held by its club, so the round trip goes through the database
        // rather than through the object the previous helper already had in hand.
        Team club = new Team();
        club.setName("ZZ Sponsor round trip " + UUID.randomUUID());
        Stadium ground = new Stadium();
        ground.setName("ZZ Sponsor ground " + UUID.randomUUID());
        ground.setCapacity(1000);
        ground.setSponsorName("Drina Bank");
        club.setStadium(ground);
        teams.saveAndFlush(club);

        Stadium reloaded = teams.findByName(club.getName()).orElseThrow().getStadium();
        assertTrue("Drina Bank".equals(reloaded.getSponsorName()),
                "the sponsor did not survive the write, so the boards would come back blank");
    }
}
