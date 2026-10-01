package org.example.footballtextmanager.engine;

import org.example.footballtextmanager.model.CSPlayer;
import org.example.footballtextmanager.model.CSTactics;
import org.example.footballtextmanager.model.CSTeam;

import java.util.ArrayList;
import java.util.List;

/**
 * Pomoćni builder za testove: prave fiksne ekipe bez Spring konteksta.
 *
 * <p>Sve vrednosti su <b>eksplicitne</b>, bez {@code Math.random()}, da test koji tvrdi
 * "ista utakmica daje isti rezultat" zapravo dokazuje determinizam simulatora, a ne to
 * da je dva puta izvukao različite brojeve. Generički imena ("Home 1".."Home 11") su
 * namerna — cilj je i da se vidi da pripisivanje po ID-ju ne zavisi od imena.
 */
final class SquadFixture {

    private SquadFixture() {
    }

    /** 4-4-2: 1 GK, 4 DEF, 4 MID, 2 ATT. */
    static final String[] FORMATION_442 = {
            "GK", "DEF", "DEF", "DEF", "DEF", "MID", "MID", "MID", "MID", "ATT", "ATT"
    };

    static CSTeam team(long id, String name) {
        return CSTeam.builder()
                .id(id)
                .name(name)
                .budget(1_000_000.0)
                .reputation(50.0)
                .stadiumName(name + " Stadium")
                .stadiumCapacity(20_000)
                .formation("4-4-2")
                .build();
    }

    /**
     * Ekipa od 11 igrača na 4-4-2, sa svim veštinama na {@code skill}.
     *
     * @param skill 1-20, kao u stvarnom seederu
     */
    static List<CSPlayer> startingEleven(long teamId, String prefix, int skill) {
        List<CSPlayer> players = new ArrayList<>();
        for (int i = 0; i < FORMATION_442.length; i++) {
            players.add(player(teamId * 100 + i, prefix + " " + (i + 1), FORMATION_442[i], skill));
        }
        return players;
    }

    static List<CSPlayer> bench(long teamId, String prefix, int skill, int count) {
        List<CSPlayer> players = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            players.add(player(teamId * 100 + 20 + i, prefix + " Bench " + (i + 1), "MID", skill));
        }
        return players;
    }

    /** Igrač sa svim veštinama na {@code skill}, ocenom izvedenom iz njih, formom i zamorom 0. */
    static CSPlayer player(long id, String name, String position, int skill) {
        return CSPlayer.builder()
                .id(id)
                .name(name)
                .position(position)
                .age(24)
                .form(6.0)
                .fatigue(0.0)
                .talent(7.0)
                .stamina(skill)
                .goalkeeper(skill)
                .defending(skill)
                .pace(skill)
                .technique(skill)
                .playmaker(skill)
                .passing(skill)
                .shooting(skill)
                .rating(0) // simulator mora da koristi calculateRating(), ne stub
                .build();
    }

    static CSTactics balanced() {
        return CSTactics.builder()
                .formation("4-4-2")
                .style(org.example.footballtextmanager.model.CSPlayStyle.BALANCED)
                .build();
    }
}
