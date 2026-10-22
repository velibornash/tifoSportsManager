package org.tifo.footballmanager.newLogic.service;

import java.util.List;

/**
 * What the world holds, and what that means — the verdict, separated from the counting.
 *
 * <p><b>Why this is a record and not a pile of booleans in a service.</b> The judgement here is the
 * thing that was wrong: the readout said <em>"World is whole"</em> for a world with no clubs in it,
 * because it counted the countries and the national squads and the legacy rows and <b>not one
 * club, player or fixture</b>. A verdict worth being right about should be readable in one place,
 * and a verdict that is only reachable through a live database is a verdict nobody can test.
 *
 * <p>So the counts are gathered by {@link WorldIntegrityService} and the judgement is made here, on
 * a value that can be written down in a test in one line.
 *
 * <p><b>Two questions, two repairs.</b> The catalogue — the countries, their sides, the squads on
 * them, the legacy rows — is what <b>Repair world</b> puts back. The pyramid — the clubs, the players
 * in them, the fixtures — is what <b>Initialize DB</b> builds. They are asked separately because
 * the answer names a button, and the two buttons do not do the same thing.
 *
 * @param countries           country rows in the world
 * @param expectedCountries   country rows the catalogue says there should be
 * @param nationalSides       senior and U-21 sides together
 * @param seniorSides         senior sides
 * @param seniorSidesWithSquad senior sides that have at least one player on them
 * @param clubs               clubs in the whole world
 * @param activeCountries     the names of the countries that are played, or {@code null} if there are none
 * @param activeClubs         clubs in the played countries
 * @param activePlayers       players on those clubs' teams
 * @param activeFixtures      fixtures of those countries' competitions
 * @param sidesWithoutSquad   the senior sides with no players, named
 * @param legacyRows          the ISO codes from before the catalogue
 */
public record WorldState(
        int countries,
        int expectedCountries,
        int nationalSides,
        int seniorSides,
        int seniorSidesWithSquad,
        long clubs,
        String activeCountries,
        long activeClubs,
        long activePlayers,
        long activeFixtures,
        List<String> sidesWithoutSquad,
        List<String> legacyRows) {

    public WorldState {
        sidesWithoutSquad = List.copyOf(sidesWithoutSquad);
        legacyRows = List.copyOf(legacyRows);
    }

    /**
     * The catalogue is whole: every country exists, every senior side has a squad, no legacy rows.
     *
     * <p><b>This is what the readout used to call the whole world.</b> It says nothing about a club,
     * a player or a fixture — a country with a name and a national side and no football in it passes
     * this, and did pass it, and the panel then said "World is whole".
     */
    public boolean catalogueOk() {
        return countries == expectedCountries
                && seniorSides == expectedCountries
                && seniorSidesWithSquad == expectedCountries
                && legacyRows.isEmpty();
    }

    /**
     * The pyramid is whole: the played country has clubs, those clubs have players, and there are
     * fixtures to play.
     *
     * <p>Three numbers, because each of them alone is a way to have a world that cannot play a
     * match. Clubs with no players is the one the owner's own data produced: a club whose every
     * player was rated zero sat in the world and the readout said it was fine.
     */
    public boolean pyramidOk() {
        return activeClubs > 0 && activePlayers > 0 && activeFixtures > 0;
    }

    /** Whether the world can be played as it stands. */
    public boolean healthy() {
        return catalogueOk() && pyramidOk();
    }

    /**
     * The button that builds what is missing, or {@code null} when nothing is missing.
     *
     * <p><b>The order is the point.</b> {@code Initialize DB} seeds the catalogue before it builds
     * the pyramid, so it covers both — and it is the only one of the two that can build a world from
     * nothing. {@code Repair world} tops up the catalogue and the national squads and builds no
     * clubs, so it is the right answer only when the pyramid is already standing.
     *
     * <p>The values are the panel's button names: {@code "initialize"}, {@code "repair"}.
     */
    public String nextStep() {
        if (!pyramidOk()) {
            return "initialize";
        }
        if (!catalogueOk()) {
            return "repair";
        }
        return null;
    }
}
