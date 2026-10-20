package org.example.footballmanager.newLogic.model;

/**
 * What a club's supporters expect of the season, in the words they would use (P2-5).
 *
 * <p>Distinct from {@code BoardExpectationService.PositionStanding}, which is where the club actually
 * is. These are the expectations the mood implies: a confident support expects trophies, an angry one
 * expects nothing and shows fewer of them. A manager reading "we are content" learns something a league
 * position alone does not tell him — that the crowd is currently on his side, and that is worth
 * spending before it stops being true.
 */
public enum SupporterExpectation {

    /** We are winning things. Keep it up. */
    CHAMPIONS("We are winning things. Do not let this club get sloppy."),

    /** We are happy enough, and we expect the club to be honest with us. */
    CONTENT("We are happy enough, and we expect to be treated honestly."),

    /** We have not seen enough yet. Prove this is a real club. */
    PROVE_ITSELF("We have not seen enough yet. Prove this is a real club."),

    /** We are short with you, but we will come. For now. */
    PATIENT_ENDURANCE("We are short with you, but we will come. For now."),

    /** Do not talk to us about ambition. Show us something to believe in. */
    DISILLUSION("Do not talk to us about ambition. Show us something to believe in.");

    private final String label;

    SupporterExpectation(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}