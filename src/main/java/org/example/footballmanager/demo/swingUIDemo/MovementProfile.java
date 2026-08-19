package org.example.footballmanager.demo.swingUIDemo;

/**
 * MovementProfile — EXTENSION POINT za buduce sposobnosti kretanja.
 *
 * Vrednosni objekat koji ce jednog dana odgovarati na pitanja tipa:
 *  - maksimalna brzina (maxSpeed)
 *  - ubrzanje (acceleration)
 *  - opsta pokretljivost / sposobnost kretanja (movementCapability)
 *
 * OVAJ SPRINT: profile se NE PRIMENJUJE na kretanje. Trenutne konstante i
 * ponasanje {@link MovementEngine} su i dalje autoritativni. {@link #standard()}
 * samo ogoljava postojece konstante u jedan objekat — nema ubrzanja, stamina,
 * pathfinding-a, kolizija ni novih mehanika.
 */
public record MovementProfile(
        double maxSpeed,
        double acceleration,
        double movementCapability) {

    /** Standardni profil — ogoljava trenutnu konstantu brzine {@link MovementEngine#PLAYER_SPEED}. */
    public static MovementProfile standard() {
        return new MovementProfile(MovementEngine.PLAYER_SPEED, 0.0, 1.0);
    }
}
