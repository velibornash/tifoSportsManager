package org.tifo.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.dto.PlayerDTO;
import org.tifo.footballmanager.newLogic.dto.TeamMedicalOverviewDTO;
import org.tifo.footballmanager.newLogic.exception.ApiException;
import org.tifo.footballmanager.newLogic.model.FinanceCategory;
import org.tifo.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.SkillName;
import org.tifo.footballmanager.newLogic.model.Skills;
import org.tifo.footballmanager.newLogic.model.StaffMember;
import org.tifo.footballmanager.newLogic.model.StaffRole;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.tifo.footballmanager.newLogic.repository.PlayerRepository;
import org.tifo.footballmanager.newLogic.repository.StaffMemberRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The medical centre can halve an absence, and the club pays for it.
 *
 * <p><b>Owner, 2026-10-22:</b> <em>"stavi da medical center (uz neko placanje koje i nije
 * najjeftinije) moze da se prepolovi vreme. koliko moze i koliko kosta zavisi od doktora, bez doktora
 * ne moze."</em> — the medical centre can halve the time, at a payment that is not the cheapest; how
 * much it can and how much it costs depends on the doctor, and without a doctor it cannot.
 *
 * <p>That is one sentence with three rules in it, and this test holds all three:
 * <ul>
 *   <li>the absence is cut by a share of what is left, and the share comes from the doctor;</li>
 *   <li>the bill comes from the doctor too, and it is not a small one;</li>
 *   <li>a club without a doctor cannot have the operation at all, and is told why.</li>
 * </ul>
 */
class TeamMedicalSurgeryTest extends BaseTest {

    @Autowired TeamMedicalService medical;
    @Autowired TeamRepository teams;
    @Autowired PlayerRepository players;
    @Autowired StaffMemberRepository staff;
    @Autowired FinanceLedgerEntryRepository ledger;
    @Autowired SeasonService seasons;

    @Test
    @Transactional
    @DisplayName("the best doctor halves the absence, and the club pays for it")
    void theBestDoctorHalvesTheAbsence() {
        Team club = aClub(5_000_000.0);
        aDoctor(club, 20);
        Player hurt = anInjuredPlayer(club, 20);
        double budgetBefore = club.getBudget();

        TeamMedicalOverviewDTO after = medical.applySurgery(club.getId(), hurt.getId());

        assertNotNull(after, "the operation returns the medical centre as it is afterwards");
        Player healed = players.findById(hurt.getId()).orElseThrow();
        assertEquals(10, healed.getInjuryDaysRemaining(),
                "the best doctor removes half of what is left: twenty days of rehab became ten. He is "
                        + "on " + healed.getInjuryDaysRemaining() + ".");
        assertTrue(healed.isInjurySurgeryDone(),
                "and the injury remembers that it was operated on, so the button does not offer itself "
                        + "a second time for the same absence");

        double cost = 40_000.0 + 20 * 2_500.0;
        assertEquals(budgetBefore - cost, club.getBudget(), 0.01,
                "the club paid for it, and the operation is not cheap");

        FinanceLedgerEntry line = theMedicalLine(club);
        assertNotNull(line,
                "the money went and nothing says so - the club's ledger has to carry the operation "
                        + "like it carries a stadium");
        assertEquals(-cost, line.getAmount(), 0.01,
                "the line carries the cost as a cost, and the same cost the budget lost");
        assertTrue(line.getNote().contains(hurt.getName()),
                "and the line says whose operation it was. It says: " + line.getNote());
    }

    @Test
    @Transactional
    @DisplayName("without a doctor there is no operation, and the club is told why")
    void withoutADoctorThereIsNoOperation() {
        Team club = aClub(5_000_000.0);
        Player hurt = anInjuredPlayer(club, 20);
        double budgetBefore = club.getBudget();

        ApiException refused = assertThrows(ApiException.class,
                () -> medical.applySurgery(club.getId(), hurt.getId()),
                "a club with no doctor must not be able to have the operation - the owner said 'bez "
                        + "doktora ne moze', without a doctor it cannot");

        assertEquals("NO_DOCTOR", refused.getCode(),
                "and the refusal says which of the three rules it is. It says: " + refused.getMessage());
        assertEquals(20, players.findById(hurt.getId()).orElseThrow().getInjuryDaysRemaining(),
                "the absence is untouched");
        assertEquals(budgetBefore, club.getBudget(), 0.01,
                "and so is the budget - a refused operation costs nothing");
    }

    @Test
    @Transactional
    @DisplayName("how much it can do and how much it costs both come from the doctor")
    void bothTheShareAndThePriceComeFromTheDoctor() {
        Team cheap = aClub(5_000_000.0);
        aDoctor(cheap, 4);
        Player cheapHurt = anInjuredPlayer(cheap, 20);

        Team good = aClub(5_000_000.0);
        aDoctor(good, 20);
        Player goodHurt = anInjuredPlayer(good, 20);

        double cheapBudget = cheap.getBudget();
        double goodBudget = good.getBudget();
        medical.applySurgery(cheap.getId(), cheapHurt.getId());
        medical.applySurgery(good.getId(), goodHurt.getId());

        int cheapDays = players.findById(cheapHurt.getId()).orElseThrow().getInjuryDaysRemaining();
        int goodDays = players.findById(goodHurt.getId()).orElseThrow().getInjuryDaysRemaining();

        assertTrue(goodDays < cheapDays,
                "the better doctor cuts more of it: the good one left " + goodDays + " days and the "
                        + "weak one left " + cheapDays);
        assertTrue(goodBudget - good.getBudget() > cheapBudget - cheap.getBudget(),
                "and the better doctor is the dearer one, because his skill is what the club is buying");
    }

    @Test
    @Transactional
    @DisplayName("one injury, one operation")
    void oneInjuryOneOperation() {
        Team club = aClub(5_000_000.0);
        aDoctor(club, 20);
        Player hurt = anInjuredPlayer(club, 20);

        medical.applySurgery(club.getId(), hurt.getId());

        ApiException refused = assertThrows(ApiException.class,
                () -> medical.applySurgery(club.getId(), hurt.getId()),
                "the operation halves what is left, and halving it twice is not a medical decision "
                        + "but a money sink");
        assertEquals("ALREADY_OPERATED", refused.getCode(),
                "and the refusal says so. It says: " + refused.getMessage());
    }

    @Test
    @Transactional
    @DisplayName("the medical page says what the operation would cost, and why it cannot be done")
    void theMedicalPageSaysWhatItCosts() {
        Team club = aClub(5_000_000.0);
        aDoctor(club, 20);
        Player hurt = anInjuredPlayer(club, 20);

        TeamMedicalOverviewDTO overview = medical.buildOverview(club.getId());
        PlayerDTO row = overview.getRecoveryQueue().stream()
                .filter(p -> p.getId().equals(hurt.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the injured player is not in the recovery queue"));

        assertTrue(row.isSurgeryAvailable(),
                "a club with a doctor and an injured player can have the operation");
        assertEquals(90_000.0, row.getSurgeryCost(), 0.01,
                "and the page says what it costs, because a manager cannot decide on a button that "
                        + "does not say the price");

        Team noDoctor = aClub(5_000_000.0);
        Player noDoctorHurt = anInjuredPlayer(noDoctor, 20);
        TeamMedicalOverviewDTO without = medical.buildOverview(noDoctor.getId());
        PlayerDTO noRow = without.getRecoveryQueue().stream()
                .filter(p -> p.getId().equals(noDoctorHurt.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the injured player is not in the recovery queue"));

        assertTrue(!noRow.isSurgeryAvailable(),
                "a club without a doctor cannot have the operation");
        assertTrue(noRow.getSurgeryNote() != null && noRow.getSurgeryNote().toLowerCase().contains("doctor"),
                "and the page says why, rather than hiding the button. It says: " + noRow.getSurgeryNote());
    }

    // ── a club, a doctor and a hurt player of this test's own ──────────────────────────────────

    private Team aClub(double budget) {
        Team team = new Team();
        team.setName("Medical Club " + UUID.randomUUID().toString().substring(0, 6));
        team.setFormation("4-4-2");
        team.setBudget(budget);
        return teams.save(team);
    }

    private StaffMember aDoctor(Team club, int fitness) {
        StaffMember doctor = new StaffMember();
        doctor.setTeam(club);
        doctor.setRole(StaffRole.DOCTOR);
        doctor.setName("Dr. " + UUID.randomUUID().toString().substring(0, 4));
        doctor.setAge(40);
        doctor.setFitness(fitness);
        return staff.save(doctor);
    }

    private Player anInjuredPlayer(Team club, int days) {
        Player player = new Player();
        player.setName("Hurt Player " + UUID.randomUUID().toString().substring(0, 4));
        player.setAge(24);
        player.setTeam(club);
        Skills skills = new Skills();
        for (SkillName skill : SkillName.values()) {
            skills.setSkill(skill, 12);
        }
        skills.initializeExactFromVisibleIfNeeded();
        player.setSkills(skills);
        player.setInjured(true);
        player.setInjuryDaysRemaining(days);
        player.setInjuryCause("in a duel, and he was already exhausted");
        return players.save(player);
    }

    private FinanceLedgerEntry theMedicalLine(Team club) {
        List<FinanceLedgerEntry> lines = ledger
                .findByTeamIdAndSeasonYearOrderByWeekNumberAsc(club.getId(), seasons.getActiveSeasonYear());
        return lines.stream()
                .filter(e -> e.getCategory() == FinanceCategory.MEDICAL)
                .findFirst()
                .orElse(null);
    }
}
