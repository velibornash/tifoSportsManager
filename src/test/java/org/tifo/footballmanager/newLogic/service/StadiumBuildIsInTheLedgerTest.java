package org.tifo.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.ProductionSource;
import org.tifo.footballmanager.newLogic.model.FinanceCategory;
import org.tifo.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.tifo.footballmanager.newLogic.model.SeatingType;
import org.tifo.footballmanager.newLogic.model.Stadium;
import org.tifo.footballmanager.newLogic.model.StandPosition;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.FinanceLedgerEntryRepository;
import org.tifo.footballmanager.newLogic.repository.StadiumRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stadium build has to show up where the club's money shows up.
 *
 * <p><b>Owner, 2026-10-22:</b> <em>"uradio sam nadogradnju stadiona, napravio nove stolice i krov -
 * ne pojavljuje se podatak o tome ni u inboxu u office (treba) ni na finance strani (treba)"</em> —
 * I upgraded the stadium, new seats and a roof, and it shows up neither in the Office inbox nor on
 * the Finance page.
 *
 * <p><b>What was wrong.</b> The build <b>spent the budget</b> — {@code team.setBudget(budget - cost)}
 * — and <b>wrote nothing to the ledger</b>. And the ledger is where both screens read from: the
 * Office inbox is {@code /finances/ledger} and the Finance page is {@code /finances/entries</a>}, and
 * both are the same rows. So the club's money simply went: a hundred thousand on a roof, and nothing
 * anywhere said so.
 *
 * <p><b>And there was no category for it at all</b> — the enum had no stadium line, so there was
 * nothing to write even if the code had wanted to. And the database carries a CHECK constraint
 * listing the categories, which {@code ddl-auto=update} never widens — so a line of a new kind is
 * refused by the database while the code believes it was written. The same trap as the notification
 * kinds and the staff roles, and the same repair.
 */
class StadiumBuildIsInTheLedgerTest extends BaseTest {

    @Autowired StadiumSectionService sections;
    @Autowired StadiumRepository stadiums;
    @Autowired TeamRepository teams;
    @Autowired FinanceLedgerEntryRepository ledger;
    @Autowired ResetService resetService;

    @Test
    @Transactional
    @DisplayName("a build writes a line, and the money is on it")
    void aBuildWritesALine() {
        Stadium ground = aGround(24_000);
        Team club = aTeamWithGround(ground);
        double budgetBefore = club.getBudget();

        Map<String, Object> built = sections.build(club, StandPosition.NORTH,
                SeatingType.SEATS, 4000, false, 1, 1);
        double spent = ((Number) built.get("spent")).doubleValue();

        List<FinanceLedgerEntry> lines = ledger
                .findByTeamIdAndSeasonYearOrderByWeekNumberAsc(club.getId(), 1);
        FinanceLedgerEntry line = lines.stream()
                .filter(e -> e.getCategory() == FinanceCategory.STADIUM_CONSTRUCTION)
                .findFirst()
                .orElse(null);

        assertNotNull(line,
                "the build spent the money and wrote nothing, so neither the Office inbox nor the "
                        + "Finance page shows that the club built anything");
        assertEquals(-spent, line.getAmount(), 0.01,
                "the line carries the cost as a cost, and the same cost the budget lost");
        assertEquals(budgetBefore - spent, club.getBudget(), 0.01,
                "and the budget really did go down by the same amount");
        assertTrue(line.getNote().contains("North"),
                "the line says which stand was built, because a manager reads it months later. It "
                        + "says: " + line.getNote());
        assertTrue(line.getNote().contains("4,000") || line.getNote().contains("4000"),
                "and how many seats went in. It says: " + line.getNote());
    }

    @Test
    @Transactional
    @DisplayName("a line of the new kind can be written, and the database takes it")
    void theConstraintAcceptsTheNewCategory() {
        // **The repair first**, because the test database carries the constraint the schema gave it and
        // the server's carries the old list. The repair is what has to run there.
        resetService.alignFinanceCategoryConstraint();

        Team club = aTeamWithGround(aGround(1_000));
        ledger.saveAndFlush(FinanceLedgerEntry.of(club, 1, 1,
                FinanceCategory.STADIUM_CONSTRUCTION, -1000, "probe"));

        boolean written = ledger.findByTeamIdAndSeasonYearOrderByWeekNumberAsc(club.getId(), 1).stream()
                .anyMatch(e -> e.getCategory() == FinanceCategory.STADIUM_CONSTRUCTION);
        assertTrue(written,
                "the ledger refused a line of the new kind - the database's CHECK constraint lists "
                        + "the categories by name and nothing widens it, so the club's money would "
                        + "go and the line would not");
    }

    @Test
    @DisplayName("the repair runs on the way up, before anything writes a line")
    void theRepairRunsAtBoot() throws IOException {
        String initializer = Files.readString(
                ProductionSource.file(org.tifo.footballmanager.newLogic.util.DatabaseInitializer.class),
                StandardCharsets.UTF_8);
        assertTrue(initializer.contains("resetService.alignFinanceCategoryConstraint()"),
                "the boot path does not repair the ledger's category constraint, so a line of a new "
                        + "kind is refused by the database on every world that was not rebuilt from "
                        + "scratch");
    }

    @Test
    @DisplayName("the category exists, and it is a cost")
    void theCategoryExists() {
        assertNotNull(FinanceCategory.STADIUM_CONSTRUCTION,
                "there is no category for stadium work, so a build has nothing to write itself as");
        assertEquals(false, FinanceCategory.STADIUM_CONSTRUCTION.isIncome(),
                "building on the ground costs money, and the sign convention is what the Finance page "
                        + "reads to decide which side of the ledger a line belongs to");
        assertEquals("Stadium construction", FinanceCategory.STADIUM_CONSTRUCTION.label(),
                "the label is what both screens show");
    }

    // ── a club and a ground of this test's own ──────────────────────────────────────────────────

    private Stadium aGround(int capacity) {
        Stadium s = new Stadium();
        s.setName("Ground " + UUID.randomUUID().toString().substring(0, 6));
        s.setCapacity(capacity);
        s.setExpandableTo(50_000);
        s.setSeatQuality(14);
        return stadiums.save(s);
    }

    private Team aTeamWithGround(Stadium ground) {
        Team team = new Team();
        team.setName("Club " + UUID.randomUUID().toString().substring(0, 6));
        team.setFormation("4-4-2");
        team = teams.save(team);
        team.setStadium(ground);
        team.setBudget(5_000_000.0);
        return teams.save(team);
    }
}
