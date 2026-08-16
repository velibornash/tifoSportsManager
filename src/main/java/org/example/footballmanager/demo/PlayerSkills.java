package org.example.footballmanager.demo;

/**
 * PlayerSkills — EXTENSION POINT za buduce igraceve sposobnosti (skills).
 *
 * Cisto vrednosni objekat koji OBEZBEDJUJE MESTO u {@link Player} modelu za
 * atribute koji ce nam uskoro trebati: brzina, ubrzanje, kondicija, dodavanje,
 * sut, dribling, pozicioniranje i odlucivanje.
 *
 * OVAJ SPRINT: sve vrednosti su INERTNE. Nema izracunavanja, nema modifikatora
 * ponasanja, nema uticaja na kretanje, akcije ili AI. Simulacija se ponasa
 * tacno kao pre. Vrednosti su neutralne (1.0 = bez uticaja) i niko ih jos
 * ne cita — samo su spremne za sledeci sprint.
 */
public record PlayerSkills(
        double speed,
        double acceleration,
        double stamina,
        double passing,
        double shooting,
        double dribbling,
        double positioning,
        double decisionMaking) {

    /** Neutralne vrednosti (bez uticaja na ponasanje) — trenutni default. */
    public static PlayerSkills neutral() {
        return new PlayerSkills(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
    }
}
