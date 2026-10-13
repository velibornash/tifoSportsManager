package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.tactics.Tactic;
import org.example.footballmanager.newLogic.sim.tactics.TacticsRules;
import org.example.footballmanager.newLogic.repository.TacticRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A club holds more than one tactic, and the engine is told which one (T0-BE-1).
 *
 * <p><b>What was in the way.</b> {@code team_tactics_profile} carries a unique constraint on
 * {@code team_id}: exactly one profile per club. A club could not keep a second tactic, so the per-match
 * selection being built next had nothing to select from, and every match read the same shape whatever the
 * manager wanted.
 *
 * <p><b>The test that matters is {@link #aSecondTacticHasItsOwnRules()}.</b> It builds two tactics for one
 * club and asks the provider for each by its own id. A provider that ignored the tactic id and answered
 * with the club's default would pass any test that only ever asked one club one question.
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class TacticLibraryServiceTest {

    @Autowired TacticLibraryService library;
    @Autowired TacticsRulesProvider rulesProvider;
    @Autowired TacticRepository tactics;
    @Autowired TeamRepository teams;

    private Team club;

    @BeforeEach
    void setUp() {
        club = new Team();
        club.setName("Tactic Library Club " + System.nanoTime());
        club = teams.save(club);
    }

    // ── T-MATCH-3c: Paste, the cross-club half of Copy/Paste ──────────────────────────────────────────

    @Test
    @DisplayName("Paste brings another club's tactic in, as a copy and not a reference")
    void pasteTakesAnotherClubsTactic() {
        Team rival = new Team();
        rival.setName("Paste Rival " + System.nanoTime());
        rival = teams.save(rival);
        Tactic theirs = library.save(rival.getId(), "Rival 4-3-3", "4-3-3", "ATTACKING",
                rulesFor("CML", "STL"), null, true);

        Tactic pasted = library.paste(club.getId(), theirs.getId(), "Stolen 4-3-3");

        assertEquals("Stolen 4-3-3", pasted.getName());
        assertEquals("4-3-3", pasted.getFormation());
        assertEquals("ATTACKING", pasted.getStyle());
        assertEquals(club.getId(), pasted.getTeam().getId(), "the pasted tactic is not this club's");
        assertNotEquals(theirs.getId(), pasted.getId(),
                "paste stored a reference, so an edit on the other club's pitch would change ours");

        // The rules came across, and editing ours does not touch theirs.
        assertTrue(pasted.getRulesJson().contains("CML"), "the pasted tactic arrived without its rules");
        pasted.setName("Renamed by me");
        tactics.save(pasted);
        assertEquals("Rival 4-3-3", tactics.findById(theirs.getId()).orElseThrow().getName(),
                "editing the pasted tactic changed the original, so paste is a reference");
    }

    @Test
    @DisplayName("Paste takes the source's name when none is given")
    void pasteKeepsTheSourceName() {
        Team rival = new Team();
        rival.setName("Paste Rival2 " + System.nanoTime());
        rival = teams.save(rival);
        Tactic theirs = library.save(rival.getId(), "4-1-4-1", "4-1-4-1", "Balanced",
                rulesFor("CML"), null, true);

        assertEquals("4-1-4-1", library.paste(club.getId(), theirs.getId(), null).getName());
    }

    @Test
    @DisplayName("Paste refuses a name this club already uses")
    void pasteRefusesADuplicateName() {
        Team rival = new Team();
        rival.setName("Paste Rival3 " + System.nanoTime());
        rival = teams.save(rival);
        Tactic theirs = library.save(rival.getId(), "Their shape", "4-4-2", "Balanced",
                rulesFor("CML"), null, true);
        library.save(club.getId(), "My shape", "4-4-2", "Balanced", rulesFor("CML"), null, true);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> library.paste(club.getId(), theirs.getId(), "My shape"));
        assertTrue(refused.getMessage().contains("already has a tactic called"),
                "the refusal did not name the clash: " + refused.getMessage());
    }

    @Test
    @DisplayName("Paste of this club's own tactic points at Copy")
    void pasteRefusesYourOwnTactic() {
        Tactic mine = library.save(club.getId(), "Mine", "4-4-2", "Balanced", rulesFor("CML"), null, true);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> library.paste(club.getId(), mine.getId(), "Mine again"));
        assertTrue(refused.getMessage().contains("Use Copy"),
                "the refusal did not say which button to press instead: " + refused.getMessage());
    }

    @Test
    @DisplayName("Paste refuses when this club is at the cap of ten")
    void pasteRespectsTheCap() {
        Team rival = new Team();
        rival.setName("Paste Rival4 " + System.nanoTime());
        rival = teams.save(rival);
        Tactic theirs = library.save(rival.getId(), "One more", "4-4-2", "Balanced",
                rulesFor("CML"), null, true);
        for (int i = 0; i < TacticLibraryService.MAX_TACTICS_PER_CLUB; i++) {
            library.save(club.getId(), "Mine " + i, "4-4-2", "Balanced", rulesFor("CML"), null, i == 0);
        }

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> library.paste(club.getId(), theirs.getId(), "Eleventh"));
        assertTrue(refused.getMessage().contains("10"),
                "the refusal did not say the limit: " + refused.getMessage());
    }

    @Test
    @DisplayName("the Paste picker offers other clubs and never this one")
    void thePickerOffersOtherClubsOnly() {
        Team rival = new Team();
        rival.setName("Pickable Rival " + System.nanoTime());
        rival = teams.save(rival);
        Tactic theirs = library.save(rival.getId(), "Visible abroad", "4-4-2", "Balanced",
                rulesFor("CML"), null, true);
        Tactic mine = library.save(club.getId(), "Private to me", "4-4-2", "Balanced",
                rulesFor("CML"), null, true);

        var offered = library.findForeign(club.getId(), "");
        assertTrue(offered.stream().anyMatch(t -> t.getId().equals(theirs.getId())),
                "the rival's tactic is not offered, so there is nothing to paste");
        assertFalse(offered.stream().anyMatch(t -> t.getId().equals(mine.getId())),
                "this club's own tactic is offered, which invites the bigger button for the same job");
    }

    /**
     * A profile's rules, one per slot key.
     *
     * <p>Written in the shape {@code TacticsRules.parseRulesJson} actually reads — a flat list carrying
     * {@code slotKey}, {@code possessionContext}, {@code ballStateKey} and {@code targetCellKey}. A shape
     * that parser ignores would be a profile that silently produces no rules, and every assertion below
     * about rule counts would then be counting nothing.
     */
    private static String rulesFor(String... slotKeys) {
        return "[" + java.util.Arrays.stream(slotKeys)
                .map(k -> "{\"slotKey\":\"" + k + "\","
                        + "\"possessionContext\":\"WE_HAVE_BALL\","
                        + "\"ballStateKey\":\"POSSESSION\","
                        + "\"targetCellKey\":\"CELL_5_5\"}")
                .reduce((a, b) -> a + "," + b)
                .orElse("") + "]";
    }

    /**
     * Slot keys are the real ones for the formation.
     *
     * <p>{@code 4-4-2} is {@code CML/CMR/ML/STR} and {@code 4-3-3} is {@code CM/WL/ST} — not the tidy
     * abbreviations. The provider measures a profile against its own formation and refuses slot keys that
     * shape does not have, which is correct behaviour and which makes a profile written with the wrong
     * keys produce the bundled fallback rather than a silent mismatch.
     */
    private static String derbyRules() { return rulesFor("CML", "CMR", "ML", "STR"); }

    private static String cupRules() { return rulesFor("CM", "WL", "ST"); }

    @Test
    @DisplayName("a club holds several tactics, each with its own formation and rules")
    void aClubHoldsSeveralTactics() {
        library.save(club.getId(), "Derby 4-4-2", "4-4-2", "Balanced", derbyRules(), null, true);
        library.save(club.getId(), "Cup 4-3-3", "4-3-3", "Attacking", cupRules(), null, false);

        List<TacticLibraryService.TacticView> view = library.view(club.getId());

        assertEquals(2, view.size(), "the club should hold both tactics");
        assertEquals(List.of("Derby 4-4-2", "Cup 4-3-3"),
                view.stream().map(TacticLibraryService.TacticView::name).toList(),
                "a club's tactics should read in the order they were created");
        assertEquals("4-3-3", view.get(1).formation(), "each tactic keeps its own formation");
    }

    @Test
    @DisplayName("a second tactic has its own rules, not the default's")
    void aSecondTacticHasItsOwnRules() {
        var derby = library.save(club.getId(), "Derby 4-4-2", "4-4-2", "Balanced", derbyRules(), null, true);
        var cup = library.save(club.getId(), "Cup 4-3-3", "4-3-3", "Attacking", cupRules(), null, false);

        TacticsRules derbyLoaded = rulesProvider.forTactic(club.getId(), derby.getId());
        TacticsRules cupLoaded = rulesProvider.forTactic(club.getId(), cup.getId());

        // The tactic's own name is carried into the rules' source, so the two are told apart by something
        // other than their shape. A provider that ignored the tactic id would return the same source for
        // both and fail here.
        assertTrue(derbyLoaded.getSource().contains("Derby 4-4-2"),
                "the first tactic's own rules should have been returned, got: " + derbyLoaded.getSource());
        assertTrue(cupLoaded.getSource().contains("Cup 4-3-3"),
                "the second tactic's own rules should have been returned, got: " + cupLoaded.getSource()
                        + "\n\nThis is the assertion that makes the test worth having. Both tactics belong to "
                        + "one club, so a provider that ignored the tactic id would answer both questions "
                        + "with the same default and pass a test that only asked once.");
        assertNotEquals(derbyLoaded.getRuleCount(), cupLoaded.getRuleCount(),
                "the two tactics carry different numbers of rules; if these are equal the rules above are "
                        + "not distinguishing anything");
        assertEquals(4, derbyLoaded.getRuleCount(), "the 4-4-2 tactic carries four slot rules");
        assertEquals(3, cupLoaded.getRuleCount(), "the 4-3-3 tactic carries three");
    }

    @Test
    @DisplayName("a club's default tactic is what a match falls back to")
    void theDefaultIsWhatAMatchGets() {
        library.save(club.getId(), "Derby 4-4-2", "4-4-2", "Balanced", derbyRules(), null, true);
        library.save(club.getId(), "Cup 4-3-3", "4-3-3", "Attacking", cupRules(), null, false);

        assertTrue(rulesProvider.forTeam(club.getId()).getSource().contains("Derby 4-4-2"),
                "forTeam must resolve the club's default tactic, not the newest or the first");

        library.makeDefault(club.getId(), library.view(club.getId()).get(1).id());

        assertTrue(rulesProvider.forTeam(club.getId()).getSource().contains("Cup 4-3-3"),
                "after promoting the other tactic, the club's default must change without a restart");
    }

    @Test
    @DisplayName("exactly one tactic is the default, and setting one demotes the last")
    void thereIsExactlyOneDefault() {
        library.save(club.getId(), "Derby 4-4-2", "4-4-2", "Balanced", derbyRules(), null, true);
        library.save(club.getId(), "Cup 4-3-3", "4-3-3", "Attacking", cupRules(), null, true);

        List<TacticLibraryService.TacticView> view = library.view(club.getId());
        long defaults = view.stream().filter(TacticLibraryService.TacticView::isDefault).count();

        assertEquals(1, defaults, "a club must hold exactly one default tactic; " + view);
        assertTrue(view.get(1).isDefault(), "the tactic just marked default should be the default");
        assertFalse(view.get(0).isDefault(), "the previous default should have been demoted");
    }

    @Test
    @DisplayName("a club's first tactic is its default whatever the caller asked for")
    void theFirstTacticIsAlwaysTheDefault() {
        library.save(club.getId(), "Only 4-4-2", "4-4-2", "Balanced", derbyRules(), null, false);

        assertTrue(library.view(club.getId()).get(0).isDefault(),
                "a club with no default is a state the rest of the game has to defend against, so the first "
                        + "tactic is made the default regardless of what the caller passed");
    }

    @Test
    @DisplayName("deleting the default promotes a survivor rather than leaving the club with none")
    void deletingTheDefaultPromotesASurvivor() {
        var derby = library.save(club.getId(), "Derby 4-4-2", "4-4-2", "Balanced", derbyRules(), null, true);
        library.save(club.getId(), "Cup 4-3-3", "4-3-3", "Attacking", cupRules(), null, false);

        library.delete(club.getId(), derby.getId());

        List<TacticLibraryService.TacticView> view = library.view(club.getId());
        assertEquals(1, view.size());
        assertTrue(view.get(0).isDefault(),
                "a club left with no default would silently drop to the bundled fallback, and nobody would "
                        + "see why the shape changed");
    }

    @Test
    @DisplayName("a tactic belonging to another club is refused, not served")
    void aTacticFromAnotherClubIsRefused() {
        Team other = new Team();
        other.setName("Other Club " + System.nanoTime());
        other = teams.save(other);
        var theirs = library.save(other.getId(), "Theirs 4-4-2", "4-4-2", "Balanced", derbyRules(), null, true);

        assertThrows(IllegalArgumentException.class, () -> library.makeDefault(club.getId(), theirs.getId()),
                "club 1's tactic 1 and club 2's tactic 1 are both 1; serving another club's rules would be a "
                        + "manager's team playing somebody else's shape");
        assertThrows(IllegalArgumentException.class, () -> library.delete(club.getId(), theirs.getId()));
    }

    @Test
    @DisplayName("a tactic needs a name and a formation")
    void aTacticNeedsANameAndAFormation() {
        assertThrows(IllegalArgumentException.class, () -> library.save(
                club.getId(), "  ", "4-4-2", "Balanced", derbyRules(), null, false));
        assertThrows(IllegalArgumentException.class, () -> library.save(
                club.getId(), "No formation", "  ", "Balanced", derbyRules(), null, false));
    }

    @Test
    @DisplayName("saving the same name twice edits that tactic instead of duplicating it")
    void savingByNameEditsRatherThanDuplicates() {
        library.save(club.getId(), "Derby 4-4-2", "4-4-2", "Balanced", derbyRules(), null, true);
        library.save(club.getId(), "Derby 4-4-2", "4-3-3", "Attacking", cupRules(), null, false);

        List<TacticLibraryService.TacticView> view = library.view(club.getId());
        assertEquals(1, view.size(), "the club identified the tactic by its name, so it should be edited");
        assertEquals("4-3-3", view.get(0).formation(), "and the edit should have taken");
    }

    @Test
    @DisplayName("giving every club a default is idempotent")
    void seedingIsIdempotent() {
        Team second = new Team();
        second.setName("Second Seed Club " + System.nanoTime());
        second = teams.save(second);

        TacticLibraryService.SeedReport first = library.giveEveryClubADefaultTactic(
                "4-4-2", "4-4-2", "Balanced", derbyRules(), null);
        assertTrue(first.created() >= 1, "at least the club under test should have been given a tactic");
        assertTrue(library.defaultFor(club.getId()).isPresent(),
                "the club under test had no tactic and must have been given one");

        TacticLibraryService.SeedReport again = library.giveEveryClubADefaultTactic(
                "4-4-2", "4-4-2", "Balanced", derbyRules(), null);
        assertEquals(0, again.created(),
                "pressing the button twice must not duplicate the world; that is what makes it safe to "
                        + "press again after adding clubs");
        assertEquals(1, library.forTeam(club.getId()).size(),
                "and the club must still hold exactly one tactic, not two copies of the default");
        assertTrue(library.defaultFor(second.getId()).isPresent(),
                "every club should end up with a default, not only the one under test");
    }

    @Test
    @DisplayName("an edit is visible to the next match without a restart")
    void anEditEvictsTheCache() {
        var tactic = library.save(club.getId(), "Derby 4-4-2", "4-4-2", "Balanced", derbyRules(), null, true);

        assertEquals(4, rulesProvider.forTeam(club.getId()).getRuleCount(), "warm the cache");

        var stored = tactics.findById(tactic.getId()).orElseThrow();
        stored.setRulesJson(rulesFor("CML", "CMR", "ML", "STR", "MR"));
        tactics.save(stored);
        rulesProvider.evict(club.getId());

        assertEquals(5, rulesProvider.forTeam(club.getId()).getRuleCount(),
                "an edit that saved and did nothing is the failure this eviction exists to prevent");
    }

    // ---------- the owner's cap, and the operations a library needs (2026-10-12) ----------

    /**
     * The owner: *"imaš 10 taktika i 3 biram za meč"*. Ten per club, stated rather than enforced
     * silently.
     *
     * <p><b>Why ten and not "unlimited".</b> The engine reads one tactic per match and the manager
     * chooses three of the ten; a hundred would make the choice meaningless and would put the whole
     * library through the editor on every matchday. The number is the owner's and lives in one constant.
     */
    @Test
    @DisplayName("a club holds ten tactics and the eleventh is refused with the number in the message")
    void tenIsTheCapAndTheMessageSaysSo() {
        for (int i = 1; i <= 10; i++) {
            library.save(club.getId(), "Tactic " + i, "4-4-2", "Balanced", derbyRules(), null, i == 1);
        }
        assertEquals(10, library.forTeam(club.getId()).size(), "ten tactics should be allowed");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> library.save(club.getId(), "The eleventh", "4-4-2", "Balanced",
                        derbyRules(), null, false),
                "the eleventh tactic should be refused rather than silently accepted");

        assertTrue(refused.getMessage().contains("10"),
                "the message must name the cap, or the manager is left guessing at an arbitrary "
                        + "refusal: " + refused.getMessage());

        assertEquals(10, library.forTeam(club.getId()).size(), "a refused save must not leave a row behind");
    }

    @Test
    @DisplayName("saving over an existing name is an edit, not an eleventh tactic")
    void editingAnExistingTacticIsNotANewOne() {
        for (int i = 1; i <= 10; i++) {
            library.save(club.getId(), "Tactic " + i, "4-4-2", "Balanced", derbyRules(), null, i == 1);
        }

        // The whole point of a cap: a manager at the limit must still be able to work on his tenth tactic.
        library.save(club.getId(), "Tactic 10", "4-3-3", "Attacking", cupRules(), null, false);

        assertEquals(10, library.forTeam(club.getId()).size());
        assertEquals("4-3-3", library.forTeam(club.getId()).get(9).getFormation(),
                "the tenth tactic should have been edited in place");
    }

    @Test
    @DisplayName("copy creates a new tactic from an existing one - 'create based on'")
    void copyIsCreateBasedOn() {
        library.save(club.getId(), "Derby 4-4-2", "4-4-2", "Balanced", derbyRules(), null, true);

        Tactic copy = library.copy(club.getId(), library.forTeam(club.getId()).get(0).getId(),
                "Derby 4-4-2 wide");

        assertEquals("Derby 4-4-2 wide", copy.getName());
        assertEquals("4-4-2", copy.getFormation(), "a copy keeps the shape it was made from");
        assertEquals(derbyRules(), copy.getRulesJson(), "a copy keeps the rules it was made from");
        assertNotEquals(library.forTeam(club.getId()).get(0).getId(), copy.getId(),
                "a copy is a second tactic, not a rename of the first");
    }

    @Test
    @DisplayName("a copy at the cap is refused, naming the cap")
    void copyRespectsTheCap() {
        for (int i = 1; i <= 10; i++) {
            library.save(club.getId(), "Tactic " + i, "4-4-2", "Balanced", derbyRules(), null, i == 1);
        }
        Long first = library.forTeam(club.getId()).get(0).getId();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> library.copy(club.getId(), first, "One too many"));

        assertTrue(refused.getMessage().contains("10"), refused.getMessage());
    }

    @Test
    @DisplayName("a copy cannot be taken from another club's tactic")
    void copyIsRefusedAcrossClubs() {
        Team other = new Team();
        other.setName("Other Tactic Club " + System.nanoTime());
        other = teams.save(other);
        library.save(other.getId(), "Theirs", "4-4-2", "Balanced", derbyRules(), null, true);
        library.save(club.getId(), "Mine", "4-4-2", "Balanced", derbyRules(), null, true);

        Long theirs = library.forTeam(other.getId()).get(0).getId();

        // Two id spaces overlap: club 1's tactic 1 and club 2's tactic 1 are both 1.
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> library.copy(club.getId(), theirs, "Stolen"));

        assertTrue(refused.getMessage().contains(String.valueOf(theirs)), refused.getMessage());
    }

    @Test
    @DisplayName("mirror agrees with the perspective transformer the engine already uses")
    void mirrorAgreesWithTheEnginesOwnMirror() {
        // **The test this should have had from the start.**
        //
        // The first mirror implementation reflected about column 3 and left anything outside columns 1–3
        // untouched. Its test passed — because every cell in it was CELL_1_1, CELL_1_2 or CELL_1_3, the
        // three columns that formula happened to handle. The engine's own mirror is
        // `TacticalPerspectiveTransformer`: `8 - physical column`. Deriving the expectation from THAT
        // rather than from the implementation is what catches a mirror that is merely self-consistent.
        //
        // parseCell turns CELL_r_c into the physical centre (r + 1.5, c + 1.5), and the AWAY mirror is
        // `8 - column`, so mirroring an editor cell must land on 8 - (c + 1.5) - 1.5 = 5 - c.
        for (int column = 0; column <= 5; column++) {
            String key = "CELL_2_" + column;
            String mirrored = TacticLibraryService.mirrorRules(
                    "[{\"targetCellKey\":\"" + key + "\"}]");

            double physical = column + 1.5;
            double mirroredPhysical = 8.0 - physical;
            int expected = (int) Math.round(mirroredPhysical - 1.5);

            Assertions.assertThat(mirrored).as("mirroring %s", key).contains("CELL_2_" + expected);
            Assertions.assertThat(mirrored).as("a six-wide grid has no fixed column, so %s must move", key)
                    .doesNotContain("CELL_2_" + column);
        }
    }

    @Test
    @DisplayName("mirror is correct for every cell the editor actually offers")
    void mirrorIsCorrectForEveryCellTheEditorOffers() {
        // **The test whose absence cost the most.** The first mirror reflected about column 3 and left
        // columns outside 1–3 alone; its test used CELL_1_1, CELL_1_2 and CELL_1_3 — exactly the columns
        // that version handled — and was green.
        //
        // So this iterates the editor's REAL vocabulary, taken from FormationSlotCatalog rather than
        // written out here, and checks two things per cell against the engine's own mirror
        // (`TacticalPerspectiveTransformer`: 8 - physical column, with parseCell giving (c + 1.5)):
        //
        //   - it lands on the cell the engine would mirror it to;
        //   - it is a cell the editor could name.
        //
        // Adding a column to the editor without teaching the mirror about it fails here.
        List<String> offered = new org.example.footballmanager.demo.service.tactics
                .FormationSlotCatalog().getSupportedTargetCells();

        assertTrue(offered.size() >= 40, "the vocabulary should be the 7x6 grid, got " + offered.size());

        for (String cell : offered) {
            String[] parts = cell.split("_");
            int row = Integer.parseInt(parts[1]);
            int column = Integer.parseInt(parts[2]);

            String mirrored = TacticLibraryService.mirrorRules("[{\"targetCellKey\":\"" + cell + "\"}]");

            double physical = column + 1.5;
            int expected = (int) Math.round((8.0 - physical) - 1.5);

            Assertions.assertThat(mirrored)
                    .as("mirroring %s should reach CELL_%d_%d", cell, row, expected)
                    .contains("CELL_" + row + "_" + expected);

            Assertions.assertThat(offered)
                    .as("mirroring %s produced CELL_%d_%d, which the editor cannot name",
                            cell, row, expected)
                    .contains("CELL_" + row + "_" + expected);
        }
    }

    @Test
    @DisplayName("mirror swaps the corners, which carry the side in their name")
    void mirrorSwapsTheCorners() {
        // Corners are named states rather than cells, so a mirror that only touched CELL_r_c would leave
        // every corner rule pointing at the corner the manager was deliberately not using.
        String rules = "[{\"ballStateKey\":\"ATTACK_LEFT_CORNER\",\"targetCellKey\":\"CELL_2_1\"},"
                + "{\"ballStateKey\":\"DEFEND_RIGHT_CORNER\",\"targetCellKey\":\"CELL_4_4\"}]";

        String mirrored = TacticLibraryService.mirrorRules(rules);

        Assertions.assertThat(mirrored).contains("ATTACK_RIGHT_CORNER").doesNotContain("ATTACK_LEFT_CORNER");
        Assertions.assertThat(mirrored).contains("DEFEND_LEFT_CORNER").doesNotContain("DEFEND_RIGHT_CORNER");
        // And the cells inside those rules mirrored as well.
        Assertions.assertThat(mirrored).contains("CELL_2_4").contains("CELL_4_1");
    }

    @Test
    @DisplayName("mirror flips the flanks and leaves the rows alone")
    void mirrorFlipsTheFlanksAndLeavesTheRows() {
        String rules = "["
                + "{\"slotKey\":\"CML\",\"possessionContext\":\"WE_HAVE_BALL\","
                + "\"ballStateKey\":\"POSSESSION\",\"targetCellKey\":\"CELL_1_0\"},"
                + "{\"slotKey\":\"CMR\",\"possessionContext\":\"WE_HAVE_BALL\","
                + "\"ballStateKey\":\"POSSESSION\",\"targetCellKey\":\"CELL_1_5\"},"
                + "{\"slotKey\":\"ML\",\"possessionContext\":\"WE_HAVE_BALL\","
                + "\"ballStateKey\":\"POSSESSION\",\"targetCellKey\":\"CELL_3_2\"}]";
        library.save(club.getId(), "Left bias", "4-4-2", "Balanced", rules, null, true);

        Tactic mirrored = library.mirror(club.getId(), library.forTeam(club.getId()).get(0).getId());

        // The left touchline and the right touchline exchange; row 3 stays row 3, because this is a
        // left-right flip and not a goal-to-goal one.
        assertEquals("["
                        + "{\"slotKey\":\"CML\",\"possessionContext\":\"WE_HAVE_BALL\","
                        + "\"ballStateKey\":\"POSSESSION\",\"targetCellKey\":\"CELL_1_5\"},"
                        + "{\"slotKey\":\"CMR\",\"possessionContext\":\"WE_HAVE_BALL\","
                        + "\"ballStateKey\":\"POSSESSION\",\"targetCellKey\":\"CELL_1_0\"},"
                        + "{\"slotKey\":\"ML\",\"possessionContext\":\"WE_HAVE_BALL\","
                        + "\"ballStateKey\":\"POSSESSION\",\"targetCellKey\":\"CELL_3_3\"}]",
                mirrored.getRulesJson());
        assertEquals("4-4-2", mirrored.getFormation(), "mirroring changes the direction, not the shape");
    }

    @Test
    @DisplayName("mirroring twice is the original, and mirroring never touches the source")
    void mirrorIsInvolutiveAndLeavesTheSourceAlone() {
        String rules = "[{\"slotKey\":\"CML\",\"possessionContext\":\"WE_HAVE_BALL\","
                + "\"ballStateKey\":\"POSSESSION\",\"targetCellKey\":\"CELL_1_1\"}]";
        library.save(club.getId(), "Original", "4-4-2", "Balanced", rules, null, true);
        Tactic source = library.forTeam(club.getId()).get(0);

        Tactic once = library.mirror(club.getId(), source.getId());
        assertNotEquals(rules, once.getRulesJson(), "the mirror should differ from its source");

        Tactic twice = library.mirror(club.getId(), once.getId());
        assertEquals(rules, twice.getRulesJson(),
                "mirroring a mirror should land back on the original");

        assertEquals(rules, source.getRulesJson(),
                "mirroring must never edit the tactic it was taken from - it creates a new one");
    }
}
