package org.tifo.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tifo.footballmanager.newLogic.model.Player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A week settles the form, and the standing pressures are applied.
 *
 * <p><b>What this exists for.</b> The owner's week carries a day for it — *"day 6 · form and morale"* —
 * and nothing ran there. The work already existed and had no caller:
 * {@code MoraleService.applyStanding} ("is he being paid fairly, is he in the side, is he being
 * listed") had never been called by anything, and the weekly settle of the form did not exist at
 * all. A player's form only moved when he played, so a good week was kept for ever and a bad one
 * with it.
 */
class MoraleServiceSettleWeekTest {

    private final MoraleService morale = new MoraleService(null, null);

    @Test
    @DisplayName("the form drifts back toward its natural level")
    void theFormSettles() {
        Player hot = playerWithForm(10.0);

        morale.settleWeek(hot, true, false, 1, 5);

        // The settle is a quarter of the distance, and the week's small wander is on top of it, so
        // the number is a tenth either side of the arithmetic.
        assertTrue(hot.getForm() > 8.9 && hot.getForm() < 9.1,
                "a player on ten loses a quarter of the four points above the natural level, so a good "
                        + "run takes about four weeks to fade. A reset to six would throw the week "
                        + "away the moment it happened. He is on " + hot.getForm() + ".");
    }

    @Test
    @DisplayName("a bad week recovers by the same rule")
    void aBadWeekRecovers() {
        Player cold = playerWithForm(2.0);

        morale.settleWeek(cold, true, false, 1, 5);

        assertTrue(cold.getForm() > 2.9 && cold.getForm() < 3.1,
                "a player on two gains a quarter of the four points below the natural level. He is on "
                        + cold.getForm() + ".");
    }

    @Test
    @DisplayName("a player whose form was never set is left at the natural level")
    void anUnsetFormIsTheNaturalLevel() {
        Player fresh = new Player();
        fresh.setMorale(60.0);

        morale.settleWeek(fresh, true, false, 1, 5);

        assertTrue(Math.abs(fresh.getForm() - MoraleService.NATURAL_FORM) <= MoraleService.WEEKLY_FORM_RANDOM,
                "a player with no form has not been in a week yet, and the natural level is where he "
                        + "belongs - the only thing that may move him is the week's small wander. He "
                        + "is on " + fresh.getForm() + ".");
    }

    @Test
    @DisplayName("the form wanders a little, and the same way every time")
    void theFormWanders() {
        Player first = playerWithForm(8.0);
        first.setId(4242L);
        Player second = playerWithForm(8.0);
        second.setId(4242L);
        Player otherWeek = playerWithForm(8.0);
        otherWeek.setId(4242L);
        Player otherPlayer = playerWithForm(8.0);
        otherPlayer.setId(9999L);

        morale.settleWeek(first, true, false, 1, 5);
        morale.settleWeek(second, true, false, 1, 5);
        morale.settleWeek(otherWeek, true, false, 1, 6);
        morale.settleWeek(otherPlayer, true, false, 1, 5);

        assertEquals(first.getForm(), second.getForm(), 0.0001,
                "the same player in the same week gets the same wander - the world rebuilds to the "
                        + "same state every time, and a form that wandered differently on every "
                        + "rebuild would make every measurement of the game unrepeatable");
        assertTrue(Math.abs(otherWeek.getForm() - first.getForm()) > 0.0001,
                "a different week is a different wander");
        assertTrue(Math.abs(otherPlayer.getForm() - first.getForm()) > 0.0001,
                "and so is a different player");
        // The settle alone takes him from eight to 7.5 - a quarter of the two points above the
        // natural level. What is left over is the wander.
        double settledAlone = 8.0 + (MoraleService.NATURAL_FORM - 8.0) * MoraleService.WEEKLY_FORM_DECAY;
        assertTrue(Math.abs(first.getForm() - settledAlone) <= MoraleService.WEEKLY_FORM_RANDOM,
                "and it is small: a wander of more than " + MoraleService.WEEKLY_FORM_RANDOM
                        + " would be the story rather than the noise. The settle alone takes him to "
                        + settledAlone + " and he is on " + first.getForm() + ".");
    }

    @Test
    @DisplayName("a player who is out of his side loses morale")
    void outOfTheSideCostsMorale() {
        Player benched = playerWithForm(6.0);
        benched.setMorale(60.0);

        morale.settleWeek(benched, false, false, 1, 5);

        assertEquals(58.8, benched.getMorale(), 0.001,
                "being left out of the side is one of the standing pressures — 1.2 morale");
    }

    @Test
    @DisplayName("a player in his side keeps the pressure of being out of it")
    void inTheSideCostsNothing() {
        Player regular = playerWithForm(6.0);
        regular.setMorale(60.0);

        morale.settleWeek(regular, true, false, 1, 5);

        assertEquals(60.0, regular.getMorale(), 0.001,
                "a player in his side is not out of it, and the pressure does not apply");
    }

    @Test
    @DisplayName("a player on the market loses more morale than one who is merely benched")
    void beingListedCostsMore() {
        Player listed = playerWithForm(6.0);
        listed.setMorale(60.0);
        Player benched = playerWithForm(6.0);
        benched.setMorale(60.0);

        morale.settleWeek(listed, true, true, 1, 5);
        morale.settleWeek(benched, false, false, 1, 5);

        assertEquals(56.0, listed.getMorale(), 0.001,
                "being put on the market is four points of morale — more than being benched");
        assertTrue(listed.getMorale() < benched.getMorale(),
                "and more than the player who was simply left out of the side");
    }

    @Test
    @DisplayName("the form never leaves its scale")
    void theFormStaysInItsScale() {
        Player perfect = playerWithForm(10.0);
        Player hopeless = playerWithForm(0.0);

        for (int week = 0; week < 20; week++) {
            morale.settleWeek(perfect, true, false, 1, 5);
            morale.settleWeek(hopeless, true, false, 1, 5);
        }

        assertTrue(perfect.getForm() <= 10.0, "form never goes above ten: " + perfect.getForm());
        assertTrue(hopeless.getForm() >= 0.0, "and never below zero: " + hopeless.getForm());
    }

    private Player playerWithForm(double form) {
        Player player = new Player();
        player.setForm(form);
        player.setMorale(60.0);
        // A wage and a value that are neither underpaid nor well paid, so the pay pressure does not
        // join the standing pressures in these assertions.
        //   underpaid:  wage * 52 <  value * 0.010   ->  52,000 <  20,000  is false
        //   well paid:  wage * 52 >  value * 0.030   ->  52,000 >  60,000  is false
        player.setEarnings(1_000);
        player.setPlayerValue(2_000_000);
        return player;
    }
}
