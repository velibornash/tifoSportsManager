package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Junior;
import org.example.footballmanager.newLogic.model.JuniorStatus;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SquadRole;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.JuniorRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the squad limit applies to the academy, and where it deliberately does not.
 *
 * <p><b>This class changed its mind on 2026-10-08, and that is the point of keeping it.</b> It used to
 * assert that a full club resolved only as many expired juniors as it had places for and released the
 * rest. That guard was real — graduation creates no {@code PlayerContract}, and the old cap counted
 * contracts, so an academy genuinely was an unlimited source of players — but it answered the wrong
 * question. It stopped a club destroying its own asset, and paid the club nothing for the players it
 * had spent a season developing.
 *
 * <p>The owner's ruling: <i>"svi idu na TL i klub zaradjuje od prodaje"</i> — they all go on the market
 * and the club earns from the sale. So:
 *
 * <ul>
 *   <li><b>The forced paths do not check the room.</b> A tenure expiry and a school closure list every
 *       prospect, and the club may sit above 30 until they are sold. Being over the cap blocks signing
 *       and promotion; it does not block selling.</li>
 *   <li><b>The voluntary paths do check it.</b> A manager's own Promote or Transfer List is refused on a
 *       full squad, and a refused promotion leaves the junior untouched so the decision is still
 *       available. That is where the guard moved to, and it is now enforced by
 *       {@link SquadRegistrationService}, which counts players rather than contracts.</li>
 *   <li><b>An academy graduate is treated as a youth</b> by the listing objection service. This is the
 *       defect P2-3 introduced and it is unrelated to the cap: a graduate has no contract, so the
 *       service fell back to a position switch mapping {@code MID -> STARTER}, which made a
 *       seventeen-year-old a STARTER at reluctance 0.75 and rolled a near-41% objection on a player's
 *       first day.</li>
 * </ul>
 *
 * <p>The file name is kept because the P2-6 finding it records was real and is worth remembering; only
 * the answer changed.
 */
@SpringBootTest
@ActiveProfiles("test")
class GraduationRespectsTheSquadTest {

    @Autowired TeamRepository teams;
    @Autowired PlayerRepository players;
    @Autowired JuniorRepository juniors;
    @Autowired YouthAcademyService academy;
    @Autowired ListingObjectionService objections;
    @Autowired PlayerContractService contracts;
    @Autowired SquadRegistrationService registration;

    private Team aClub(String name) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        t.setBudget(5_000_000.0);
        t.setReputation(60.0);
        Stadium s = new Stadium();
        s.setName(name + " Ground");
        s.setCapacity(25_000);
        s.setTicketPrice(20.0);
        s.setPitchQuality(85.0);
        s.setPitchCondition(85);
        s.setMaintenanceRemaining(0);
        t.setStadium(s);
        return teams.save(t);
    }

    private Player aSenior(Team team, String name) {
        Player p = new Player();
        p.setName(name);
        p.setTeam(team);
        p.setAge(24);
        p.setRating(60);
        p.setPosition(Position.MID);
        p.setPlayerValue(500_000);
        p.setEarnings(8_000);
        p.setMorale(60.0);
        p.setForm(6.0);
        return players.save(p);
    }

    /**
     * A junior whose one academy season is over, attached to this club.
     *
     * <p>Arrived in season 1, so any sweep run from season 2 onwards expires him. The age is left at
     * a plausible mid-window value rather than pinned to the old deadline constant: since 2026-10-08
     * the sweep reads the arrival season, and a fixture that still set the age would keep passing
     * after the rule it was built to test had been deleted.
     */
    private Junior anExpiredJunior(Team team, String name, double academySkill) {
        Junior j = new Junior();
        j.setName(name);
        j.setAge(17);
        j.setTalent(6);
        j.setAcademySkillExact(academySkill);
        j.setAcademySkill((int) Math.floor(academySkill));
        j.setPosition(Position.MID);
        j.setStatus(JuniorStatus.ACTIVE);
        j.setTeam(team);
        j.setArrivalAge(15);
        j.setArrivalSeasonNumber(1);
        j.setArrivalWeekNumber(2);
        return juniors.save(j);
    }

    private long squadSize(Team club) {
        return players.findByTeamId(club.getId()).size();
    }

    /**
     * Owner, 2026-10-08: <i>"svi idu na TL i klub zaradjuje od prodaje"</i>.
     *
     * <p><b>This test used to assert the opposite.</b> It asserted that a club with two places left and
     * five expired juniors resolved exactly two and released three, and that the squad could never grow
     * past its limit. That was the P2-6 guard, and it was protecting the club from its own academy — at
     * the cost of destroying a player it had paid a season of upkeep to develop, and taking nothing for
     * him. The owner reversed it: every prospect goes on the market and the club earns the fee.
     *
     * <p>So the club <b>may</b> now sit above {@link SquadRegistrationService#MAX_CLUB_SQUAD} after a
     * season turn. Being over the cap blocks signing and promotion; it does not block selling. The way
     * out is the market, which is where the value came from.
     */
    @Test
    @DisplayName("every expired junior is listed, however full the squad already is")
    void everyExpiredJuniorIsListed() {
        Team club = aClub("FullClub");
        for (int i = 0; i < SquadRegistrationService.MAX_CLUB_SQUAD; i++) {
            aSenior(club, "Senior " + i);
        }
        assertEquals(SquadRegistrationService.MAX_CLUB_SQUAD, squadSize(club), "precondition: a full squad");
        for (int i = 0; i < 5; i++) {
            anExpiredJunior(club, "Graduate " + i, 8 + i);
        }

        academy.graduateExpiredJuniors(2);

        assertEquals(SquadRegistrationService.MAX_CLUB_SQUAD + 5, squadSize(club),
                "all five go on the market; the squad is allowed to exceed the cap and shrink by selling");
        assertEquals(5, juniors.findByStatus(JuniorStatus.TRANSFER_LISTED).stream()
                        .filter(j -> club.getId().equals(j.getTeam() == null ? null : j.getTeam().getId()))
                        .count(),
                "and none of them is thrown away");
    }

    /**
     * The guard did not move, it changed address.
     *
     * <p>The owner's rule is that a manager cannot promote a junior into a full squad. That is a
     * <b>voluntary</b> decision, so it is refused — and refusing it must leave the junior exactly as he
     * was, or a manager who pressed the button would have consumed his one decision for nothing.
     */
    @Test
    @DisplayName("a manager cannot promote into a full squad, and a refused promotion changes nothing")
    void promotionIntoAFullSquadIsRefused() {
        Team club = aClub("NoRoomClub");
        for (int i = 0; i < SquadRegistrationService.MAX_CLUB_SQUAD; i++) {
            aSenior(club, "Senior " + i);
        }
        Junior junior = anExpiredJunior(club, "No room", 9);
        junior.setArrivalSeasonNumber(1);
        long before = squadSize(club);

        var refused = org.junit.jupiter.api.Assertions.assertThrows(
                org.example.footballmanager.newLogic.exception.ApiException.class,
                () -> academy.promoteJunior(junior.getId(), 2, 1, false),
                "a full squad must refuse the promotion");

        assertEquals("SQUAD_FULL", refused.getCode());
        assertEquals(JuniorStatus.ACTIVE, juniors.findById(junior.getId()).orElseThrow().getStatus(),
                "the junior is untouched, so the decision is still available next week");
        assertEquals(before, squadSize(club), "and no player was created");
    }

    /** The counterpart: one place freed, and the same promotion now goes through. */
    @Test
    @DisplayName("freeing one place lets the promotion through")
    void aFreedPlaceLetsThePromotionThrough() {
        Team club = aClub("OneRoomClub");
        for (int i = 0; i < SquadRegistrationService.MAX_CLUB_SQUAD - 1; i++) {
            aSenior(club, "Senior " + i);
        }
        Junior junior = anExpiredJunior(club, "One place", 9);

        assertEquals(SquadRegistrationService.MAX_CLUB_SQUAD - 1, squadSize(club),
                "precondition: one place short of the limit");
        assertTrue(registration.canRegister(club.getId()).allowed(),
                "and one place is genuinely available");
        academy.promoteJunior(junior.getId(), 2, 1, false);

        assertEquals(JuniorStatus.PROMOTED, juniors.findById(junior.getId()).orElseThrow().getStatus());
        assertEquals(SquadRegistrationService.MAX_CLUB_SQUAD, squadSize(club));
    }

    /**
     * The defect P2-3 introduced, fixed here.
     *
     * <p>A senior made from a junior has no contract, so {@code ListingObjectionService.roleOf} used to fall back to a
     * position switch mapping {@code MID -> STARTER}. A seventeen-year-old on his first day was
     * therefore judged as a senior starter, at reluctance 0.75, and almost half of all graduations
     * drew an objection the club then had to pay 5% to clear.
     */
    @Test
    @DisplayName("a player made from the academy is treated as a youth, not a senior starter")
    void aGraduateIsJudgedAsAYouth() {
        Team club = aClub("GraduateClub");
        Junior j = anExpiredJunior(club, "Seventeen", 8);
        j.setAge(17);
        juniors.save(j);
        Player graduate = new Player();
        graduate.setName("Seventeen");
        graduate.setTeam(club);
        graduate.setAge(17);
        graduate.setPosition(Position.MID);
        graduate.setPlayerValue(22_500);
        graduate.setEarnings(1_000);
        players.save(graduate);

        assertEquals(SquadRole.YOUTH, contracts.inferRole(graduate),
                "precondition: inferRole already knows a cheap seventeen-year-old is a youth");
        double likelihood = objections.objectionLikelihood(graduate);
        assertTrue(likelihood < 0.2,
                "a graduate must not object at the rate of a senior starter; was " + likelihood
                        + ", which is STARTER-level (0.41) unless the fallback is fixed");
    }
}