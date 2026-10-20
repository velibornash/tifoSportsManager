package org.tifo.footballmanager.newLogic.service;

import org.tifo.footballmanager.BaseTest;
import org.tifo.commonmanager.model.User;
import org.tifo.commonmanager.model.UserRole;
import org.tifo.footballmanager.newLogic.model.StaffMember;
import org.tifo.footballmanager.newLogic.model.StaffRole;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.StaffMemberRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.commonmanager.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The staff market: a candidate is a staff row with no club, and a club decides whether to sign him
 * (owner, 2026-10-18).
 *
 * <p>Three rules the feature is built on, each stated as a test so a change to any one of them fails
 * loudly rather than silently:
 * <ol>
 *   <li>a candidate on the market has no club;</li>
 *   <li>signing him sets the club and removes him from the market;</li>
 *   <li>a role that is already full cannot be signed into without firing first.</li>
 * </ol>
 */
class StaffRecruitingTest extends BaseTest {

    @Autowired private StaffRecruitingService recruiting;
    @Autowired private StaffMemberRepository staff;
    @Autowired private TeamRepository teams;
    @Autowired private UserRepository users;

    private Team club() {
        Team t = new Team();
        t.setName("ZZ Recruit " + UUID.randomUUID());
        t.setReputation(50.0);
        t.setHumanControlled(true);
        t.setBudget(100000.0);
        return teams.save(t);
    }

    private User managerOf(Team club) {
        User u = new User();
        u.setUsername("manager-" + UUID.randomUUID() + "@test.local");
        u.setEmail(u.getUsername());
        u.setPassword("not-a-real-hash");
        u.setDisplayName("ZZ Manager");
        u.setRole(UserRole.REGULAR);
        u.setPlusSubscription(false);
        u.setFootballTeam(club);
        return users.save(u);
    }

    private StaffMember candidate(StaffRole role) {
        StaffMember m = new StaffMember();
        m.setRole(role);
        m.setName("ZZ Candidate " + UUID.randomUUID());
        m.setAge(40);
        m.setWeeklyWage(2000.0);
        m.setDevelopment(10);
        m.setTactical(10);
        m.setMotivation(10);
        m.setGoalkeeping(10);
        m.setFitness(10);
        m.setScouting(10);
        m.setSkillStamina(10);
        m.setSkillGoalkeeper(10);
        m.setSkillDefender(10);
        m.setSkillPace(10);
        m.setSkillTechnique(10);
        m.setSkillPlaymaker(10);
        m.setSkillPassing(10);
        m.setSkillStriker(10);
        // team left null -> he is on the market
        return staff.save(m);
    }

    @Test
    @Transactional
    @DisplayName("a candidate on the market has no club")
    void aCandidateHasNoClub() {
        StaffMember c = candidate(StaffRole.HEAD_COACH);
        assertTrue(recruiting.market().stream().anyMatch(m -> m.getId().equals(c.getId())),
                "a candidate with no club must appear on the market");
        assertTrue(c.getTeam() == null, "a candidate is, by definition, not employed");
    }

    @Test
    @Transactional
    @DisplayName("signing a candidate sets his club and removes him from the market")
    void signingSetsTheClub() {
        Team club = club();
        User manager = managerOf(club);
        StaffMember c = candidate(StaffRole.HEAD_COACH);

        StaffMember signed = recruiting.accept(manager, c.getId());
        assertNotNull(signed.getTeam(), "a signed man belongs to the club that signed him");
        assertEquals(club.getId(), signed.getTeam().getId());

        assertTrue(recruiting.market().stream().noneMatch(m -> m.getId().equals(c.getId())),
                "a signed man is no longer on the market");
    }

    @Test
    @Transactional
    @DisplayName("a full role cannot be signed into without firing first")
    void aFullRoleRefusesASigning() {
        Team club = club();
        User manager = managerOf(club);

        // Fill the head-coach slot.
        StaffMember existing = candidate(StaffRole.HEAD_COACH);
        existing.setTeam(club);
        staff.save(existing);

        StaffMember newcomer = candidate(StaffRole.HEAD_COACH);
        StaffRecruitingService.StaffRecruitingException ex =
                assertThrows(StaffRecruitingService.StaffRecruitingException.class,
                        () -> recruiting.accept(manager, newcomer.getId()));
        assertEquals("slot_full", ex.reason(),
                "the refusal must say WHY, so the UI can tell a manager his squad is full rather than "
                        + "that the candidate vanished");
    }

    @Test
    @Transactional
    @DisplayName("refusing a candidate deletes him")
    void refusingDeletesTheCandidate() {
        StaffMember c = candidate(StaffRole.PHYSIO);
        Long id = c.getId();
        recruiting.refuse(id);
        assertTrue(staff.findById(id).isEmpty(), "a refused candidate is gone, which is what refusing means");
    }

    @Test
    @Transactional
    @DisplayName("firing a member charges one week's wage as severance and deletes him")
    void firingChargesSeverance() {
        Team club = club();
        User manager = managerOf(club);

        StaffMember m = candidate(StaffRole.ASSISTANT);
        m.setTeam(club);
        m.setWeeklyWage(2500.0);
        m = staff.save(m);

        double before = club.getBudget();
        recruiting.fire(manager, club.getId(), m.getId());

        assertTrue(staff.findById(m.getId()).isEmpty(), "the fired man is gone");
        // severance = one week's wage
        Team after = teams.findById(club.getId()).orElseThrow();
        assertEquals(before - 2500.0, after.getBudget(), 0.001,
                "firing costs one week's wage, and the ledger is the witness");
    }
}
