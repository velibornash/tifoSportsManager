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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-6 — a capped intake produces capped graduates.
 *
 * <p>Graduation was unconditional: every ACTIVE junior aged twenty in the entire world was turned into
 * a senior {@code Player} in one loop. {@code PlayerContractService.canRegister} could not stop it,
 * because graduation creates no contract and {@code canRegister} counts contracts — so a graduate was
 * invisible to the 25-senior cap, and then drew a wage for a full season before the backfill noticed.
 * A club's academy was an unlimited source of free players.
 *
 * <p><b>What is guaranteed here:</b>
 *
 * <ul>
 *   <li><b>The squad is the cap.</b> A club with no senior places releases the graduate instead of
 *       creating a twenty-sixth senior. That is the football answer, and it makes P2-7 bite: a club
 *       that refuses to let players go fills its own squad and blocks its own academy.</li>
 *   <li><b>Room is counted down per club as it is used</b>, so five due juniors and two places promote
 *       exactly two — not five and an overflow discovered later.</li>
 *   <li><b>Intake capacity bounds output.</b> The 10-junior cap is now a named constant, and the
 *       board's criterion — a capped intake produces capped graduates — is asserted end to end.</li>
 *   <li><b>An academy graduate is treated as a youth.</b> This is the defect P2-3 introduced and this
 *       task carries: a graduate has no contract, so the objection service fell back to a position
 *       switch that made a seventeen-year-old a STARTER (reluctance 0.75), rolling a near-41%
 *       objection on a player's first day.</li>
 * </ul>
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

    /** A junior past the graduation window, attached to this club. */
    private Junior anOverdueJunior(Team team, String name, double academySkill) {
        Junior j = new Junior();
        j.setName(name);
        j.setAge(YouthAcademyService.GRADUATION_MAX_AGE);
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
     * The cap itself: five graduates due, two places, two promoted.
     *
     * <p>Filled with real senior players rather than a mocked count, because the count is the thing
     * under test.
     */
    @Test
    @DisplayName("a club promotes only as many graduates as it has senior places for")
    void aFullClubCannotPromoteEveryone() {
        Team club = aClub("FullClub");
        int room = 2;
        for (int i = 0; i < PlayerContractService.MAX_SENIOR_SQUAD - room; i++) {
            aSenior(club, "Senior " + i);
        }
        for (int i = 0; i < 5; i++) {
            anOverdueJunior(club, "Graduate " + i, 8 + i);
        }
        long before = squadSize(club);

        academy.promoteJuniorsPastWindow(2, 2);

        assertEquals(before + room, squadSize(club),
                "the squad may not grow past " + PlayerContractService.MAX_SENIOR_SQUAD
                        + "; graduation was previously unconditional");
        assertTrue(squadSize(club) <= PlayerContractService.MAX_SENIOR_SQUAD,
                "and never past it, however many juniors came through the window");

        long released = juniors.findByStatus(JuniorStatus.RELEASED).stream()
                .filter(j -> club.getId().equals(j.getTeam() == null ? null : j.getTeam().getId()))
                .count();
        assertEquals(3, released,
                "the other three leave the club rather than occupy a place it cannot give them");
    }

    /** Room is consumed as it is used, not checked once and forgotten. */
    @Test
    @DisplayName("room is counted down per club, so one club's overspill cannot take another's")
    void roomIsCountedDownPerClub() {
        final Team roomy = aClub("RoomyClub");
        final Team full = aClub("BlockedClub");
        for (int i = 0; i < PlayerContractService.MAX_SENIOR_SQUAD; i++) {
            aSenior(full, "Filler " + i);
        }
        for (int i = 0; i < 3; i++) {
            anOverdueJunior(roomy, "Roomy " + i, 9 + i);
            anOverdueJunior(full, "Blocked " + i, 9 + i);
        }

        academy.promoteJuniorsPastWindow(3, 3);

        assertEquals(3, squadSize(roomy),
                "a club with a full squad of its own does not stop another club graduating");
        assertEquals(PlayerContractService.MAX_SENIOR_SQUAD, squadSize(full),
                "and the full club is held at its limit");
    }

    /**
     * The board's criterion, end to end.
     *
     * <p>Fourteen overdue juniors is a state the product cannot reach — intake stops at ten, so at most
     * ten can be ACTIVE and overdue at once. The fixture builds it anyway, on purpose: the sweep reads
     * junior rows directly, and fixtures and the seeder insert them without passing through intake, so
     * a pass that promoted fourteen first-team players out of a ten-place academy would be relying on
     * an invariant it does not itself enforce.
     */
    @Test
    @DisplayName("a capped intake cannot produce more graduates than it could hold")
    void aCappedIntakeProducesCappedGraduates() {
        Team club = aClub("CappedClub");
        assertEquals(10, YouthAcademyService.MAX_ACTIVE_JUNIORS,
                "the academy's own capacity, named rather than an inline literal");
        for (int i = 0; i < YouthAcademyService.MAX_ACTIVE_JUNIORS + 4; i++) {
            anOverdueJunior(club, "Overdue " + i, 7 + (i % 5));
        }
        long before = squadSize(club);

        academy.promoteJuniorsPastWindow(4, 4);

        long produced = squadSize(club) - before;
        assertTrue(produced <= YouthAcademyService.MAX_ACTIVE_JUNIORS,
                "fourteen overdue juniors produced " + produced + " seniors; an academy of ten "
                        + "cannot manufacture fourteen first-team players");
        assertTrue(produced <= PlayerContractService.MAX_SENIOR_SQUAD - before,
                "and never past the senior squad limit either");
    }

    /**
     * The defect P2-3 introduced, fixed here.
     *
     * <p>A graduate has no contract, so {@code ListingObjectionService.roleOf} used to fall back to a
     * position switch mapping {@code MID -> STARTER}. A seventeen-year-old on his first day was
     * therefore judged as a senior starter, at reluctance 0.75, and almost half of all graduations
     * drew an objection the club then had to pay 5% to clear.
     */
    @Test
    @DisplayName("an academy graduate is treated as a youth, not a senior starter")
    void aGraduateIsJudgedAsAYouth() {
        Team club = aClub("GraduateClub");
        Junior j = anOverdueJunior(club, "Seventeen", 8);
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