package org.example.footballmanager.newLogic.model;

/**
 * What a section is built out of (owner, 2026-10-07).
 *
 * <p>Cheaper and less comfortable at one end, dearer and more comfortable at the other. The order of
 * the constants is the owner's list: a terrace, a row of benches, ordinary seats, and heated seats.
 *
 * <p>Each type has a cost factor relative to the "SEATS" baseline and a comfort level on the game's
 * 1-20 scale. A heated-seat section is slower to fill — but it charges more for it — and a standing end
 * full of the home fans can be the loudest and cheapest sold.
 */
public enum SeatingType {
    STANDING("Standing", 0.55, 4, 0),
    BENCHES("Benches", 0.75, 9, 0),
    SEATS("Seats", 1.00, 14, 0),
    HEATED("Heated seats", 1.55, 19, 1);

    private final String label;
    private final double costFactor;
    private final int comfort;
    private final int roofBonusWeeks;

    SeatingType(String label, double costFactor, int comfort, int roofBonusWeeks) {
        this.label = label;
        this.costFactor = costFactor;
        this.comfort = comfort;
        this.roofBonusWeeks = roofBonusWeeks;
    }

    public String label() {
        return label;
    }

    public double costFactor() {
        return costFactor;
    }

    /**
     * How comfortable this is on the game's 1-20 scale, which is what the ground's own
     * {@code seatQuality} column now reports as the capacity-weighted average of its sections.
     *
     * <p>That column was previously set by hand by the world builder and read by nothing but the stadium
     * page's own tile — a decoration. Reading it as the average of what was actually built means the
     * number says something, and a club that fills its ground with standing ends reads as a terrace club.
     */
    public int comfort() {
        return comfort;
    }

    /**
     * A roof over heated seats takes longer: the heating and the roof go up together, so the section is
     * closed for an extra week. It is the only case where roofing changes the schedule, and it is why the
     * roof is not the same labour as the seats.
     */
    public int roofBonusWeeks() {
        return roofBonusWeeks;
    }
}
