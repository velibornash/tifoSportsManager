package org.example.footballtextmanager.engine;

import org.example.footballtextmanager.model.CSMatchEvent;
import org.example.footballtextmanager.model.CSMatchResult;
import org.example.footballtextmanager.model.CSTeam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Straža za determinizam simulacije.
 *
 * <p>Simulator je imao {@code private final Random rnd = new Random()} — bez seeda, deljen
 * između svih korisnika kao singleton bean. Posledica: ishod utakmice se menjao u zavisnosti
 * od toga šta je drugi korisnik radio u međuvremenu, a tabela se nije mogla ni ponoviti ni
 * kalibrisati. Za tabelu čiji ishod ne može da se reprodukuje nema smisla ni verifikovati.
 *
 * <p>Svaki test ovde mora da <b>padne</b> ako se seed vrati na nasumičan.
 */
class CSMatchSimulatorDeterminismTest {

    private final CSMatchSimulator simulator = new CSMatchSimulator();

    @Test
    @DisplayName("Ista utakmica daje isti rezultat svaki put")
    void sameFixtureGivesSameResult() {
        CSMatchResult first = playOnce();
        CSMatchResult second = playOnce();

        assertEquals(first.getHomeGoals(), second.getHomeGoals(),
                "Golovi domaćina su se promenili između dva pokretanja iste utakmice");
        assertEquals(first.getAwayGoals(), second.getAwayGoals(),
                "Golovi gostiju su se promenili između dva pokretanja iste utakmice");
        assertEquals(describe(first), describe(second),
                "Opis meča se razlikuje — šum nije kontrolisan");
    }

    @Test
    @DisplayName("Meč između dve različite utakmice se ne meša, redom kako se izvršavaju")
    void otherMatchesInBetweenDoNotContaminateTheResult() {
        CSMatchResult target = playOnce();

        // Umesto "kontaminacije" radimo potpuno drugu utakmicu — ekipe, rezultat, kolo.
        CSTeam otherHome = SquadFixture.team(90L, "Other Home");
        CSTeam otherAway = SquadFixture.team(91L, "Other Away");
        simulator.simulate(otherHome,
                SquadFixture.startingEleven(90L, "OH", 12), SquadFixture.bench(90L, "OHB", 12, 7),
                otherAway,
                SquadFixture.startingEleven(91L, "OA", 12), SquadFixture.bench(91L, "OAB", 12, 7),
                SquadFixture.balanced(), SquadFixture.balanced(), 1);

        CSMatchResult targetAgain = playOnce();

        assertEquals(describe(target), describe(targetAgain),
                "Utakmica se promenila zbog meča koji se izvršio između nje");
    }

    @Test
    @DisplayName("Različito kolo daje drugačiji meč")
    void differentRoundsAreNotIdentical() {
        // Ne tvrdi se da se MORA razlikovati, ali ako bi seed zavisio samo od parova klubova
        // bez kola, ceo raspored bi se ponavljao. Ovo je reverzna strana iste stvari:
        // različito kolo = različit seed.
        CSMatchResult round1 = playAtRound(1);
        CSMatchResult round2 = playAtRound(2);

        assertNotEquals(
                round1.getHomeGoals() + ":" + round1.getAwayGoals() + describeEvents(round1),
                round2.getHomeGoals() + ":" + round2.getAwayGoals() + describeEvents(round2),
                "Kola 1 i 2 dala su potpuno isti meč — seed ne uzima u obzir kolo");
    }

    private CSMatchResult playOnce() {
        return playAtRound(3);
    }

    private CSMatchResult playAtRound(int round) {
        CSTeam home = SquadFixture.team(1L, "Home");
        CSTeam away = SquadFixture.team(2L, "Away");
        return simulator.simulate(home,
                SquadFixture.startingEleven(1L, "Home", 12), SquadFixture.bench(1L, "Home B", 11, 7),
                away,
                SquadFixture.startingEleven(2L, "Away", 12), SquadFixture.bench(2L, "Away B", 11, 7),
                SquadFixture.balanced(), SquadFixture.balanced(), round);
    }

    private static String describe(CSMatchResult r) {
        return r.getHomeGoals() + "-" + r.getAwayGoals()
                + "|" + describeEvents(r)
                + "|" + r.getHomePossession() + "|" + r.getHomeXG()
                + "|" + r.getHomeShotsOnTarget() + "|" + r.getHomeCorners();
    }

    private static String describeEvents(CSMatchResult r) {
        List<CSMatchEvent> events = r.getEvents() == null ? List.of() : r.getEvents();
        List<String> out = new ArrayList<>();
        for (CSMatchEvent e : events) {
            out.add(e.getMinute() + ":" + e.getEventType() + ":" + e.getDescription());
        }
        return String.join(";", out);
    }
}
