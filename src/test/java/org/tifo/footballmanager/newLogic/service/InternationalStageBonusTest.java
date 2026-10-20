package org.tifo.footballmanager.newLogic.service;

import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.model.InternationalStageBonus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Elo for reaching a stage of an international cup (owner, 2026-10-14).
 *
 * <p>The numbers are the owner's, written down here so a later reader can see they were chosen and not
 * derived. They are a game balance decision on a scale that runs roughly 1004 to 1584, which is why
 * they are asserted rather than left to a comment.
 */
class InternationalStageBonusTest extends BaseTest {

    @Test
    @DisplayName("the Champions ladder is the owner's, and it is cumulative")
    void championsLadder() {
        InternationalStageBonus champions = InternationalStageBonus.CHAMPIONS;

        // Entry is paid for qualifying at all.
        assertEquals(50, champions.bonusFor(0, false), "entered but drawn out before round 1");

        // Reached round 1 but not round 2.
        assertEquals(100, champions.bonusFor(1, false), "entry + round of 16");

        assertEquals(175, champions.bonusFor(2, false), "entry + 1/16 + 1/4");
        assertEquals(325, champions.bonusFor(3, false), "entry + 1/16 + 1/4 + 1/2");

        // There is no separate final stage: a finalist lost it, a winner won it.
        assertEquals(475, champions.bonusFor(4, false), "a finalist who lost the last round");
        assertEquals(525, champions.bonusFor(4, true), "a winner: 50+50+75+150+200");
    }

    @Test
    @DisplayName("the Masters and Challenge ladders are the owner's, smaller")
    void otherLadders() {
        InternationalStageBonus masters = InternationalStageBonus.MASTERS;
        assertEquals(30, masters.bonusFor(0, false));
        assertEquals(205, masters.bonusFor(4, false), "30+30+35+50+60");
        assertEquals(245, masters.bonusFor(4, true), "30+30+35+50+100 - winner replaces finalist");

        InternationalStageBonus challenge = InternationalStageBonus.CHALLENGE;
        assertEquals(20, challenge.bonusFor(0, false));
        assertEquals(155, challenge.bonusFor(4, false), "20+20+25+40+50");
        assertEquals(195, challenge.bonusFor(4, true), "20+20+25+40+90 - winner replaces finalist");
    }

    @Test
    @DisplayName("winning the last round is worth more than losing it, and only that round differs")
    void winningBeatsBeingAFinalist() {
        // Winner and finalist are alternatives at the last round - reaching it is the same either way,
        // and the last round is the only thing that differs. The exact amounts are pinned above; this
        // is the shape, so that a change to one ladder cannot silently break the others.
        for (InternationalStageBonus ladder : InternationalStageBonus.values()) {
            assertEquals(ladder.bonusFor(3, true), ladder.bonusFor(3, false),
                    "before the last round, winning it cannot have happened yet");
            assertTrue(ladder.bonusFor(4, true) > ladder.bonusFor(4, false),
                    ladder + ": winning the last round must beat losing it");
        }
    }

    @Test
    @DisplayName("every tier of a cup shares its ladder")
    void everyTierSharesTheLadder() {
        // There are six competition rows per cup - Champions, Masters, Challenge and their Tier 2 and
        // Tier 3 forms. Six ladders would be six places for them to drift apart.
        assertEquals(InternationalStageBonus.CHAMPIONS,
                InternationalStageBonus.forCup("Tier 2 Champions Cup"));
        assertEquals(InternationalStageBonus.CHAMPIONS,
                InternationalStageBonus.forCup("Tier 3 Champions Cup"));
        assertEquals(InternationalStageBonus.MASTERS,
                InternationalStageBonus.forCup("Masters Cup"));
        assertEquals(InternationalStageBonus.CHALLENGE,
                InternationalStageBonus.forCup("Challenge Cup"));
    }

    @Test
    @DisplayName("a competition that is not one of the three pays nothing")
    void unrelatedCompetitionsPayNothing() {
        assertNull(InternationalStageBonus.forCup("Superliga Srbije"),
                "a domestic league is not an international cup");
        assertNull(InternationalStageBonus.forCup(null));
    }
}
