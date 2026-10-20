package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.StaffMember;
import org.example.footballmanager.newLogic.model.StaffRole;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.StaffMemberRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The physio, who was named in a comment and did nothing (owner, 2026-10-18).
 *
 * <p>The owner: *"physio za recovery plus doctor za povrede u medical centru (moze da se regrutuje pa pomaze
 * a ne mora ..bez doktora nema smanjenja dana za povrede, bez fizija se sporije oporavljaju)"*.
 *
 * <p>The doctor half was already real. The physio half was not: {@code TeamMedicalService} carried the
 * sentence *"Physio handles fatigue, doctor handles injuries - two roles, two jobs"* above a fatigue
 * reduction that was a flat twelve for every club in the world, whether it employed a physio or not. **A
 * comment claiming a rule the code does not implement is worse than no comment**, which is why this test
 * exists rather than a note.
 *
 * <p>The test measures the number, not the intention: the same player at the same fatigue, once with a
 * physio and once without, and the two answers compared.
 */
class PhysioRecoveryTest extends BaseTest {

    private static final int START_FATIGUE = 50;
    /** RECOVERY_FATIGUE_REDUCTION (12) at NO_PHYSIO_FACTOR (0.6), rounded. */
    private static final int WITHOUT_PHYSIO = 7;
    /** The same twelve, whole, for a physio at the top of the scale. */
    private static final int WITH_BEST_PHYSIO = 12;

    @Autowired private TeamMedicalService medical;
    @Autowired private TeamRepository teams;
    @Autowired private PlayerRepository players;
    @Autowired private StaffMemberRepository staff;

    @Test
    @Transactional
    @DisplayName("a club with no physio recovers more slowly than one with a good one")
    void aPhysioRecoversMoreFatigue() {
        Team club = club();

        Player alone = player(club, START_FATIGUE);
        medical.applyRecovery(club.getId(), alone.getId());
        int withoutPhysio = fatigueOf(alone.getId());
        assertEquals(START_FATIGUE - WITHOUT_PHYSIO, withoutPhysio,
                "no physio is slow, not nothing: a club that has not hired must still recover");

        Player helped = player(club, START_FATIGUE);
        staff.save(physio(club, 20));
        medical.applyRecovery(club.getId(), helped.getId());
        int withPhysio = fatigueOf(helped.getId());
        assertEquals(START_FATIGUE - WITH_BEST_PHYSIO, withPhysio,
                "the best physio restores the whole reduction");

        assertTrue(withPhysio < withoutPhysio,
                "a physio has to recover more than nobody, or he is a wage with no effect: "
                        + withPhysio + " against " + withoutPhysio);
    }

    @Test
    @Transactional
    @DisplayName("a better physio recovers more than a worse one")
    void aBetterPhysioIsWorthMore() {
        Team club = club();

        Player withWeak = player(club, START_FATIGUE);
        StaffMember weak = staff.save(physio(club, 5));
        medical.applyRecovery(club.getId(), withWeak.getId());
        int weakResult = fatigueOf(withWeak.getId());

        // The same club, a better physio. The old one is released rather than kept: this fixture is about
        // which man is in the job, and two physios would be a different question.
        staff.delete(weak);
        staff.flush();
        staff.save(physio(club, 20));

        Player withGood = player(club, START_FATIGUE);
        medical.applyRecovery(club.getId(), withGood.getId());
        int goodResult = fatigueOf(withGood.getId());

        assertTrue(goodResult < weakResult,
                "a 20 physio must out-recover a 5: " + goodResult + " against " + weakResult);
    }

    // ------------------------------------------------------------------ fixture

    private int fatigueOf(Long playerId) {
        return players.findById(playerId).orElseThrow().getSkills().getFatigue();
    }

    private Team club() {
        Team team = new Team();
        team.setName("ZZ Physio " + UUID.randomUUID());
        team.setReputation(50.0);
        team.setHumanControlled(false);
        return teams.save(team);
    }

    private Player player(Team team, int fatigue) {
        Skills skills = new Skills();
        for (SkillName name : new SkillName[]{SkillName.PACE, SkillName.STAMINA, SkillName.GOALKEEPER,
                SkillName.DEFENDER, SkillName.TECHNIQUE, SkillName.PLAYMAKER, SkillName.PASSING,
                SkillName.STRIKER}) {
            skills.setSkill(name, 10);
        }
        skills.setSkill(SkillName.FATIGUE, fatigue);
        Player p = new Player();
        p.setName("ZZ Physio Player " + UUID.randomUUID());
        p.setPosition(Position.MID);
        p.setSkills(skills);
        p.setAge(21);
        p.setTeam(team);
        p.setRating(p.careerRating());
        return players.save(p);
    }

    /** A physio whose fitness knowledge is the given value. His other skills are set so his overall is not
     *  an accident of the legacy fallbacks. */
    private static StaffMember physio(Team team, int stamina) {
        StaffMember member = new StaffMember();
        member.setTeam(team);
        member.setRole(StaffRole.PHYSIO);
        member.setName("ZZ Physio " + stamina);
        for (SkillName name : new SkillName[]{SkillName.GOALKEEPER, SkillName.DEFENDER, SkillName.PACE,
                SkillName.TECHNIQUE, SkillName.PLAYMAKER, SkillName.PASSING, SkillName.STRIKER}) {
            member.setSkill(name, 10);
        }
        member.setSkill(SkillName.STAMINA, stamina);
        return member;
    }
}
