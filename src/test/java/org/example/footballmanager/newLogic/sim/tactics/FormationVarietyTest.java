package org.example.footballmanager.newLogic.sim.tactics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The nine formations are nine formations (T1-6).
 *
 * <p><b>The claim being retired.</b> The archive records *"9 in catalog, 1 applied"*, and the board carried
 * it as an open item for a long time. It is not true. Every layout in the catalog carries a distinct set of
 * slot keys, {@code RealSquadFactory.slotOrderFor} returns each one unchanged, and two formations are
 * already in use in the world (4-4-2 and 3-4-3).
 *
 * <p><b>Why it needs a test to close rather than a reading.</b> A catalog can hold nine layouts and still
 * hand every club the same eleven if the lookup falls through — which is exactly what
 * {@code layouts.getOrDefault(..., layouts.get("4-4-2"))} would do for an unknown name. Asserting that
 * nine exist proves nothing; asserting that the nine produce nine different XIs is the claim.
 */
class FormationVarietyTest {

    private static final List<String> ALL = List.of(
            "4-4-2", "4-3-3", "4-2-3-1", "4-1-4-1", "3-5-2", "5-3-2", "3-4-3", "4-5-1", "5-4-1");

    @Test
    @DisplayName("the catalog holds all nine, and the engine's list agrees")
    void theCatalogHoldsNine() {
        FormationSlotCatalog catalog = new FormationSlotCatalog();

        assertEquals(ALL.size(), ALL.stream().distinct().count(),
                "the nine must be nine different formations, not one written nine times");

        for (String formation : ALL) {
            assertFalse(catalog.getSlots(formation).isEmpty(),
                    formation + " is in the list but the catalog has no slots for it — so it would silently "
                            + "fall through to 4-4-2, which is the defect this was filed for");
            assertEquals(11, org.example.footballmanager.newLogic.sim.RealSquadFactory
                            .slotOrderFor(formation).length,
                    formation + " does not field eleven players");
        }
    }

    @Test
    @DisplayName("each formation produces a different eleven")
    void eachFormationIsDistinct() {
        Map<String, String> byShape = new LinkedHashMap<>();

        for (String formation : ALL) {
            String shape = String.join(",", org.example.footballmanager.newLogic.sim.RealSquadFactory
                    .slotOrderFor(formation));
            String clash = byShape.put(shape, formation);
            assertTrue(clash == null,
                    formation + " and " + clash + " field exactly the same players in the same slots — two "
                            + "names for one shape, which is what '1 applied' would look like");
        }

        assertEquals(9, byShape.size(),
                "nine formations must produce nine distinct XIs, not fewer: " + byShape.values());
    }

    @Test
    @DisplayName("the formations actually differ from each other in shape")
    void theDifferencesAreReal() {
        // Spot-check two pairs the eye would call the same, because "4-4-2" and "4-5-1" differ only in the
        // middle band and a catalog that quietly widened one into the other would pass a count of nine.
        assertFalse(java.util.Arrays.equals(
                        org.example.footballmanager.newLogic.sim.RealSquadFactory.slotOrderFor("4-4-2"),
                        org.example.footballmanager.newLogic.sim.RealSquadFactory.slotOrderFor("4-5-1")),
                "4-4-2 and 4-5-1 field the same players in the same places");

        assertFalse(java.util.Arrays.equals(
                        org.example.footballmanager.newLogic.sim.RealSquadFactory.slotOrderFor("3-4-3"),
                        org.example.footballmanager.newLogic.sim.RealSquadFactory.slotOrderFor("3-5-2")),
                "3-4-3 and 3-5-2 field the same players in the same places");
    }

    @Test
    @DisplayName("an unknown formation falls back to 4-4-2 rather than to nothing")
    void anUnknownNameFallsBack() {
        FormationSlotCatalog catalog = new FormationSlotCatalog();

        assertEquals("4-4-2", catalog.normalizeFormation("9-9-9"));
        assertFalse(catalog.getSlots("9-9-9").isEmpty(),
                "a formation nobody typed must still field a team; the fallback is the point of having one");
    }
}