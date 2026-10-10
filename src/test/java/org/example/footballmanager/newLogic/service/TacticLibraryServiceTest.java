package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.sim.tactics.TacticsRules;
import org.example.footballmanager.newLogic.repository.TacticRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
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
}