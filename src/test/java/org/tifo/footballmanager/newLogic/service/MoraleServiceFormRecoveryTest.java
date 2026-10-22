package org.tifo.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.TrainingIntensity;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How hard the club trains a player decides how fast his form comes back.
 *
 * <p><b>The manager's lever over a player in a bad run.</b> The owner's words: <em>"razmisli kako da
 * korisnik moze da utice na igracevu formu, pogotovo nisku (tako sto mu smanji / poveca broj
 * meceva, trening, nesto trece.. ne obavezno eksplicitno ali implicitno)"</em> — think about how the
 * user can affect a player's form, especially a low one: fewer or more matches, training, something
 * else. Not necessarily explicitly, but implicitly.
 *
 * <p>Playing him is the first answer. How hard the club trains him is the second, and it was already
 * in the codebase — {@code TrainingIntensity} has said since Sprint 4.2 that a light week is
 * <em>"a pre-season or an injury rehabilitation"</em> — but it had no effect on the form at all, and
 * no screen to set it with.
 */
class MoraleServiceFormRecoveryTest {

    private final MoraleService morale = new MoraleService(null, null);

    @Test
    @DisplayName("a light week brings a player out of a bad run twice as fast")
    void aLightWeekBringsHimBackTwiceAsFast() {
        Player light = playerWithForm(3.0);
        Player normal = playerWithForm(3.0);

        morale.settleWeek(light, true, false, 1, 5, TrainingIntensity.LIGHT);
        morale.settleWeek(normal, true, false, 1, 5, TrainingIntensity.NORMAL);

        assertTrue(light.getForm() > normal.getForm(),
                "the rested player is further back toward where he should be. He is on "
                        + light.getForm() + " and the normally-trained one on " + normal.getForm() + ".");
    }

    @Test
    @DisplayName("a very hard week leaves a player where he was")
    void aVeryHardWeekLeavesHimWhereHeWas() {
        Player hard = playerWithForm(3.0);
        Player normal = playerWithForm(3.0);

        morale.settleWeek(hard, true, false, 1, 5, TrainingIntensity.VERY_HARD);
        morale.settleWeek(normal, true, false, 1, 5, TrainingIntensity.NORMAL);

        assertTrue(hard.getForm() < normal.getForm(),
                "a week of maximum training does not leave a player sharper, it leaves him tired. He "
                        + "is on " + hard.getForm() + " and the normally-trained one on "
                        + normal.getForm() + ".");
    }

    @Test
    @DisplayName("a week with no intensity set is an ordinary week")
    void noIntensityIsAnOrdinaryWeek() {
        Player unset = playerWithForm(3.0);
        Player normal = playerWithForm(3.0);

        morale.settleWeek(unset, true, false, 1, 5, null);
        morale.settleWeek(normal, true, false, 1, 5, TrainingIntensity.NORMAL);

        assertTrue(Math.abs(unset.getForm() - normal.getForm()) < 0.0001,
                "a missing setting must not take a week of training out for three hundred clubs. He "
                        + "is on " + unset.getForm() + " and the ordinary week on " + normal.getForm() + ".");
    }

    private Player playerWithForm(double form) {
        Player player = new Player();
        player.setForm(form);
        player.setMorale(60.0);
        // Neither underpaid nor well paid, so the pay pressure stays out of it.
        player.setEarnings(1_000);
        player.setPlayerValue(2_000_000);
        return player;
    }
}
