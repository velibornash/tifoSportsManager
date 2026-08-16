package org.example.footballmanager.demo;

/**
 * ActionCandidate — EXTENSION POINT za buducu evaluaciju akcija.
 *
 * Buduci sistem ce trebati koncepte: kandidat za akciju, podobnost (suitability),
 * score i odluku. Ovaj vrednosni objekat je MESTO gde ce to ziveti.
 *
 * OVAJ SPRINT: klasa se NE KORISTI u izvrsenju. {@link ActionEngine} i dalje
 * bira PASS/CARRY/SHOT istim nasumicnim mehanizmom kao pre. Nema scoringa,
 * nema AI — samo je spremljen prostor za sledeci sprint.
 */
public record ActionCandidate(
        Action.Type type,
        Player actingPlayer,
        double suitability) {

    /** Neutralni kandidat (suitability = 0) — placeholder bez logike. */
    public static ActionCandidate neutral(Action.Type type, Player actingPlayer) {
        return new ActionCandidate(type, actingPlayer, 0.0);
    }
}
