package org.example.footballtextmanager.engine;

import org.example.footballtextmanager.model.CSPlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Straža za jedinstvenu skalu ocene.
 *
 * <p>Ovaj test je naročito važan jer je <b>pre njega postojao test koji je merio ništa</b>:
 * proveo je da je ocena u nekom opsegu, ali je opseg bio 0-100, a sistem je zapravo radio
 * na 1-10. Bio je zelen i nije značio ništa.
 *
 * <p>Ovdje su granice <b>uske</b> (1-10), pa test pada ako se negde vrati 0-100.
 */
class CSPlayerRatingScaleTest {

    @Test
    @DisplayName("calculateRating() ostaje na skali 1-10, nikako 0-100")
    void ratingIsOnTheOneToTenScale() {
        for (int skill = 1; skill <= 20; skill++) {
            CSPlayer p = SquadFixture.player(1L, "P", "ATT", skill);
            int rating = p.calculateRating();

            assertTrue(rating >= 1 && rating <= 10,
                    "Ocena za veštinu " + skill + " je " + rating + ", a očekivano 1-10. "
                            + "Ako se ovo pali, negde se vratila skala 0-100.");
        }
    }

    @Test
    @DisplayName("Klasa igrača menja ocenu — ocena nije konstanta")
    void ratingActuallyReactsToSkill() {
        int weak = SquadFixture.player(1L, "Weak", "ATT", 4).calculateRating();
        int strong = SquadFixture.player(2L, "Strong", "ATT", 17).calculateRating();

        assertTrue(strong > weak,
                "Bolji igrač (" + strong + ") mora imati višu ocenu od slabijeg (" + weak + "). "
                        + "Ako su jednake, ocena se ne izvodi iz veština i test ne meri ništa.");
    }

    @Test
    @DisplayName("Promovisani klub (skilla 4-18) ne dobija ocenu 44-78")
    void promotedClubScoresLikeEveryoneElse() {
        // createGeneratedPlayer je za promovisane klubove upisivao 44-78 u rating,
        // što je na skali 1-10 ekvivalent oceni 4.4-7.8 — zapisano u nepovezanoj koloni.
        for (int skill = 4; skill <= 18; skill++) {
            int rating = SquadFixture.player(1L, "Promoted", "MID", skill).calculateRating();
            assertTrue(rating >= 1 && rating <= 10,
                    "Promovisani igrač sa veštinom " + skill + " dobio je " + rating);
        }
    }
}
