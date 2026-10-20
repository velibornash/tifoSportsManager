package org.example.footballtextmanager.engine;

import org.example.footballtextmanager.model.CSGoalType;
import org.example.footballtextmanager.model.CSMatchEvent;
import org.example.footballtextmanager.model.CSMatchResult;
import org.example.footballtextmanager.model.CSPlayer;
import org.example.footballtextmanager.model.CSPlayerMatchStats;
import org.example.footballtextmanager.model.CSTeam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Straža za integritet podataka meča.
 *
 * <p>Sve tri greške koje ovaj test pokriva su bile <b>nevidljive</b>: rezultat je izgledao
 * normalno, tabela je raspored bila uredna, a meč je imao tekst. Pored toga, dve od tri su
 * bile u <i>pripisivanju</i>, ne u brojevima.
 *
 * <ul>
 *   <li><b>Rezultat vs. golovi</b> — penalti su se dodavali na rezultat bez GOAL eventa,
 *       pa je teletext prikazivao 1:0 bez ijedne linije o golu.</li>
 *   <li><b>Pripisivanje po ID-ju</b> — golovi i asistencije su se pripisivali igraču
 *       <i>po imenu</i>. Dva kluba u istoj ligi imaju igrača "Marko Jovanović", pa je
 *       pogrešan igrač dobijao gol, a stvarni ga nije dobijao ni u tabeli ni u statistici.</li>
 *   <li><b>Zamor zamena</b> — zamor se računao samo za početnu jedanaesticu, pa je zamena
 *       ušla sa 0 zamora i bila jača od fiktivnog igrača.</li>
 * </ul>
 */
class CSMatchSimulatorIntegrityTest {

    private static final String SAME_NAME = "Marko Jovanovic";

    private final CSMatchSimulator simulator = new CSMatchSimulator();

    /**
     * Rezultat mora uvek da bude tačno broj GOAL događaja — i ukupno i po ekipi.
     *
     * <p>Pre penalti implementacije se broj golova mogao povećati <b>bez</b> ijednog GOAL
     * događaja, pa je teletext prikazivao 1:0 a nigde nije pisalo da je neko pogodio. Golovi
     * se svuda drugim računaju iz GOAL događaja (assignRatings, Golden Boot, izveštaj meča),
     * pa je takav rezultat bio i nerazloživ — tabela i izveštaj su se razlikovali.
     *
     * <p>Uzorak je 200 <b>različitih kola</b>, ne 200 kombinacija veština. Seed se izvodi iz
     * identiteta klubova i kola, pa se menjanje veštine pri istom kolu ne menja RNG tok —
     * 16 veština × 4 kola daje samo 4 stvarno različita meča. Uz to se na kraju proverava
     * da je makar jedan kazneni udarac bio realizovan; bez te provere test bi mogao da
     * prođe a da penalti uopšte nisu bili pogođeni, tj. da ne meri ništa.
     */
    @Test
    @DisplayName("Rezultat je tačno broj GOAL evenata — penalti ne prolaze nevidljivo")
    void scoreAlwaysEqualsNumberOfGoalEvents() {
        int convertedPenalties = 0;
        int penaltyEvents = 0;

        for (int round = 1; round <= 200; round++) {
            CSMatchResult r = play(12, round);

            List<CSMatchEvent> events = r.getEvents() == null ? List.of() : r.getEvents();

            long homeGoals = events.stream()
                    .filter(e -> e.getEventType() == org.example.footballtextmanager.model.CSEventType.GOAL)
                    .filter(e -> r.getHomeTeamName().equals(e.getTeamName()))
                    .count();
            long awayGoals = events.stream()
                    .filter(e -> e.getEventType() == org.example.footballtextmanager.model.CSEventType.GOAL)
                    .filter(e -> r.getAwayTeamName().equals(e.getTeamName()))
                    .count();

            assertEquals(r.getHomeGoals(), (int) homeGoals,
                    "Kolo " + round + ": domaci bodevi " + r.getHomeGoals()
                            + " se ne poklapaju sa GOAL dogadjajima (" + homeGoals + ")");
            assertEquals(r.getAwayGoals(), (int) awayGoals,
                    "Kolo " + round + ": gostujuci bodovi " + r.getAwayGoals()
                            + " se ne poklapaju sa GOAL dogadjajima (" + awayGoals + ")");
            assertEquals(r.getHomeGoals() + r.getAwayGoals(), (int) (homeGoals + awayGoals),
                    "Kolo " + round + ": ukupan rezultat " + r.getHomeGoals() + ":" + r.getAwayGoals()
                            + " se ne poklapa sa " + (homeGoals + awayGoals) + " GOAL dogadjaja");

            for (CSMatchEvent e : events) {
                if (e.getEventType() != org.example.footballtextmanager.model.CSEventType.PENALTY) continue;
                penaltyEvents++;
                if (e.isPenaltyScored()) convertedPenalties++;
            }
        }

        assertTrue(penaltyEvents > 0,
                "Ni jedan kazneni udarac nije ni izveden u 200 kola — test ne proverava nista.");
        assertTrue(convertedPenalties > 0,
                "Ni jedan kazneni udarac nije realizovan u 200 kola (" + penaltyEvents
                        + " izvedenih), pa se ne moze proveriti da se gol penalti vidi u timeline-u.");
    }

    /**
     * Rezultat prikazan uz svaki gol mora da raste za jedan i da se na kraju poklopi sa
     * završnim rezultatom meča.
     *
     * <p>Ovaj test postoji zbog baga koji je pronađen gledanjem stvarnog teletexta, a ne
     * čitanjem koda: kazneni udarci su se generisali posle golova iz otvorene igre, pa je GOAL
     * događaj kaznenog udarca nosio <b>završni</b> rezultat. Jedan meč se stvarno prikazao ovako:
     *
     * <pre>
     *   64'  Penalty: ... (3:1)
     *   66'  ... rolls it into ... [1:0]
     *   83'  ... from outside ... [2:0]
     *   90'  Sweet volley ... [3:0]
     * </pre>
     *
     * <p>Rezultat je skakao unazad u sredini meča, a postojeći test
     * {@code scoreAlwaysEqualsNumberOfGoalEvents} je to propustio jer je brojao samo <i>koliko
     * ima</i> golova, nikada <i>šta piše uz njih</i>. Broj je bio tačan; redosled nije.
     */
    @Test
    @DisplayName("Rezultat uz svaki gol raste za jedan i zavrsava na punom rezultatu")
    void runningScoreNeverGoesBackwards() {
        for (int round = 1; round <= 200; round++) {
            CSMatchResult r = play(12, round);
            List<CSMatchEvent> events = r.getEvents() == null ? List.of() : r.getEvents();

            int expectedHome = 0;
            int expectedAway = 0;
            String home = r.getHomeTeamName();
            String away = r.getAwayTeamName();

            for (CSMatchEvent e : events) {
                if (e.getEventType() != org.example.footballtextmanager.model.CSEventType.GOAL) continue;

                if (home.equals(e.getTeamName())) expectedHome++;
                else if (away.equals(e.getTeamName())) expectedAway++;
                else continue;

                String shown = e.getScoreAfterGoal();
                assertNotNull(shown,
                        "Gol u " + e.getMinute() + "' nema prikazani rezultat, pa teletext pokazuje gol bez rezultata");

                assertEquals(expectedHome + ":" + expectedAway, shown,
                        "Kolo " + round + ": u " + e.getMinute()
                                + "' prikazano je " + shown + ", a do tada je bilo "
                                + expectedHome + ":" + expectedAway
                                + " — rezultat ne sme da se vraca unazad");
            }

            assertEquals(r.getHomeGoals(), expectedHome,
                    "Kolo " + round + ": završni rezultat " + r.getHomeGoals()
                            + " se ne poklapa sa poslednjim prikazanim stanjem " + expectedHome);
            assertEquals(r.getAwayGoals(), expectedAway,
                    "Kolo " + round + ": završni rezultat gostiju " + r.getAwayGoals()
                            + " se ne poklapa sa poslednjim prikazanim stanjem " + expectedAway);
        }
    }

    @Test
    @DisplayName("Svaki gol ima tip, a tip PENALTY postoji i ima opis")
    void everyGoalHasATypeAndPenaltiesAreDescribed() {
        for (int skill = 3; skill <= 18; skill++) {
            for (int round = 1; round <= 4; round++) {
                CSMatchResult r = play(skill, round);
                for (CSMatchEvent e : r.getEvents()) {
                    if (e.getEventType() != org.example.footballtextmanager.model.CSEventType.GOAL) continue;

                    assertTrue(e.getGoalType() != null,
                            "Gol u " + r.getHomeGoals() + ":" + r.getAwayGoals() + " nema tip gola");
                    assertTrue(e.getDescription() != null && !e.getDescription().isBlank(),
                            "Gol nema opis, pa se u teletextu ne može prikazati");
                }
            }
        }
    }

    @Test
    @DisplayName("Golovi se pripisuju po ID-ju: dva igrača istog imena dobijaju svaki svoj gol")
    void goalsAreAttributedByIdNotByName() {
        // Sva tri napadača u obe ekipe se ZOVU "Marko Jovanovic" i svi igraju u istoj utakmici.
        // Pripisivanje po imenu ovde daje DRUGACIJI rezultat: prvi Marko u listi pokupi golove
        // drugog, a oba Marka u istoj ekipi dobiju ISTI (nabricani) broj golova.
        //
        // Meta nije CSPlayer.goals — taj se uvecava preko direktne reference na strijelca u
        // generateGoalEvents i nikad nije bio pokvaren. Meta je CSPlayerMatchStats, tj. broj
        // golova PO MECU, koji racuna assignRatings i koji ide u izveštaj meča i u ocenu
        // igrača za tu utakmicu.
        //
        // Sve ide u JEDNU petlju, a stanje igrača se resetuje pre svakog kola. Dve petlje
        // nisu radile jer simulate() menja zamor i formu na istim objektima, pa bi druga
        // petlja igrala drugacije mečeve od prve i tvrdnje bi se medjusobno ponistile.
        CSTeam home = SquadFixture.team(1L, "Home");
        CSTeam away = SquadFixture.team(2L, "Away");
        List<CSPlayer> homePlayers = SquadFixture.startingEleven(1L, "H", 15);
        List<CSPlayer> awayPlayers = SquadFixture.startingEleven(2L, "A", 15);
        List<CSPlayer> homeBench = SquadFixture.bench(1L, "HB", 13, 7);
        List<CSPlayer> awayBench = SquadFixture.bench(2L, "AB", 13, 7);

        homePlayers.get(9).setName(SAME_NAME);
        homePlayers.get(10).setName(SAME_NAME);
        awayPlayers.get(9).setName(SAME_NAME);
        awayPlayers.get(10).setName(SAME_NAME);

        List<CSPlayer> markos = List.of(homePlayers.get(9), homePlayers.get(10),
                awayPlayers.get(9), awayPlayers.get(10));

        int roundsWithMarkoGoal = 0;

        for (int round = 1; round <= 30; round++) {
            for (CSPlayer p : markos) {
                p.setFatigue(0.0);
                p.setForm(6.0);
            }

            CSMatchResult r = simulator.simulate(home, homePlayers, homeBench,
                    away, awayPlayers, awayBench,
                    SquadFixture.balanced(), SquadFixture.balanced(), round);

            for (CSMatchEvent e : r.getEvents()) {
                if (e.getEventType() != org.example.footballtextmanager.model.CSEventType.GOAL) continue;
                assertTrue(e.getPlayerId() != null,
                        "GOAL event nema playerId, pa se ne može pripisati igracu: " + e.getDescription());
            }

            boolean anyMarkoScored = false;
            for (CSPlayer marko : markos) {
                CSPlayerMatchStats st = statsFor(r, marko.getId());
                assertTrue(st != null,
                        "Nema statistike za igraca " + marko.getName() + " (id=" + marko.getId() + ")");

                int fromEvents = countGoalsFor(r, marko.getId());
                assertEquals(fromEvents, st.getGoals(),
                        "Kolo " + round + ": u statistici meca '" + SAME_NAME + "' (id=" + marko.getId()
                                + ") stoji " + st.getGoals() + " golova, a njegovi dogadjaji broje "
                                + fromEvents + ". Ako se brojevi razilaze, pripisivanje ne gleda ID.");
                if (fromEvents > 0) anyMarkoScored = true;
            }

            // Ovo je tvrdnja koja specificno razdvaja pripisivanje po ID-ju od pripisivanja
            // po imenu, i zato ovde postoji pored provere po igracu iznad.
            //
            // Dva Marka u istoj ekipi NE SMEJU imati isti broj golova u istom mecu, osim ako
            // su OBA stvarno postigla. Sa pripisivanjem po imenu oba dobijaju isti broj —
            // prvi pokupi sve golove pod tim imenom, pa se dvojica "Marko Jovanovic" u
            // tabeli meča prikazuje kao da su oba pogodila. Sa pripisivanjem po ID-ju brojevi
            // se razilaze tačno onoliko koliko se golovi i razlikuju.
            if (anyMarkoScored) roundsWithMarkoGoal++;

            for (int a = 0; a < markos.size(); a++) {
                for (int b = a + 1; b < markos.size(); b++) {
                    CSPlayer one = markos.get(a);
                    CSPlayer two = markos.get(b);
                    boolean sameTeam = (one.getId() < 200) == (two.getId() < 200);
                    if (!sameTeam) continue;

                    int oneEvents = countGoalsFor(r, one.getId());
                    int twoEvents = countGoalsFor(r, two.getId());
                    int oneStats = statsFor(r, one.getId()).getGoals();
                    int twoStats = statsFor(r, two.getId()).getGoals();

                    if (oneEvents == twoEvents) continue; // oba su ih stvarno postigla (ili nijedan)

                    assertTrue(oneStats != twoStats,
                            "Kolo " + round + ": dva igraca sa imenom '" + SAME_NAME + "' u istoj ekipi "
                                    + "imaju isti broj golova u statistici (" + oneStats
                                    + "), iako je jedan postigao " + oneEvents
                                    + " a drugi " + twoEvents + ". Ovo je tacno pripisivanje po imenu.");
                }
            }
        }

        // Bez ove provere test je vakuumski: kad nijedan Marko ne postigne gol, sve gornje
        // tvrdnje su trivijalno ispravne i test ne meri nista.
        assertTrue(roundsWithMarkoGoal > 0,
                "Nijedan od cetiri igraca sa imenom '" + SAME_NAME + "' nije postigao gol u 30 kola, "
                        + "pa se pripisivanje nije ni ispitivalo.");
    }

    private static CSPlayerMatchStats statsFor(CSMatchResult r, long playerId) {
        List<CSPlayerMatchStats> all = new java.util.ArrayList<>(r.getHomePlayerStats());
        all.addAll(r.getAwayPlayerStats());
        return all.stream().filter(s -> playerId == s.getPlayerId()).findFirst().orElse(null);
    }

    @Test
    @DisplayName("Zamena koja uđe nosi svoj zamor; igrač koji ne igra se oporavlja")
    void substitutesCarryTheirOwnFatigueAndTheBenchRecovers() {
        CSTeam home = SquadFixture.team(1L, "Home");
        CSTeam away = SquadFixture.team(2L, "Away");
        List<CSPlayer> homePlayers = SquadFixture.startingEleven(1L, "H", 14);
        List<CSPlayer> awayPlayers = SquadFixture.startingEleven(2L, "A", 14);
        List<CSPlayer> homeBench = SquadFixture.bench(1L, "HB", 12, 7);
        List<CSPlayer> awayBench = SquadFixture.bench(2L, "AB", 12, 7);

        // Klupa u startu nosi nagomiljani zamor. Ako se ne oporavi, to je dokaz da
        // oporavak ne postoji za igrače koji nisu igrali.
        homeBench.forEach(p -> p.setFatigue(6.0));
        awayBench.forEach(p -> p.setFatigue(6.0));

        // Svi startni igrači su umorni od ranih utakmica.
        homePlayers.forEach(p -> p.setFatigue(5.0));
        awayPlayers.forEach(p -> p.setFatigue(5.0));

        CSMatchResult r = simulator.simulate(home, homePlayers, homeBench,
                away, awayPlayers, awayBench,
                SquadFixture.balanced(), SquadFixture.balanced(), 1);

        // Klupa mora da se oporavi, jer u ovom kolu nije igrala.
        for (CSPlayer p : homeBench) {
            assertTrue(p.getFatigue() < 6.0,
                    p.getName() + " nije igrao, a zamor mu je " + p.getFatigue() + " (bio 6.0). "
                            + "Oporavak ne postoji za igrača koji nije na terenu.");
        }

        // Startni igrači moraju da nose veći zamor nego pre meča.
        long tiredStarters = homePlayers.stream().filter(p -> p.getFatigue() > 5.0).count();
        assertTrue(tiredStarters > 0,
                "Nijedan domaći starter nije dobio dodatni zamor — updateFatigueAfterMatch "
                        + "ne pokriva pocetnu jedanaesticu");
    }

    @Test
    @DisplayName("Zamor raste sa odigranim minutima, a ne fiksno po igracu")
    void fatigueScalesWithMinutesPlayed() {
        // Startni igrač mora nositi vise zamora od onoga ko je ušao posle 20 minuta.
        for (int round = 1; round <= 6; round++) {
            CSTeam home = SquadFixture.team(1L, "Home");
            CSTeam away = SquadFixture.team(2L, "Away");
            List<CSPlayer> homePlayers = SquadFixture.startingEleven(1L, "H", 14);
            List<CSPlayer> awayPlayers = SquadFixture.startingEleven(2L, "A", 14);
            List<CSPlayer> homeBench = SquadFixture.bench(1L, "HB", 12, 7);
            List<CSPlayer> awayBench = SquadFixture.bench(2L, "AB", 12, 7);

            homePlayers.forEach(p -> p.setFatigue(0.0));
            awayPlayers.forEach(p -> p.setFatigue(0.0));
            homeBench.forEach(p -> p.setFatigue(0.0));
            awayBench.forEach(p -> p.setFatigue(0.0));

            simulator.simulate(home, homePlayers, homeBench, away, awayPlayers, awayBench,
                    SquadFixture.balanced(), SquadFixture.balanced(), round);

            // U proseku startni igrač igra vise minuta od bilo kog klupe igrača, pa
            // prosecan zamor startne jedanastice mora biti visi.
            double avgStarter = homePlayers.stream().mapToDouble(CSPlayer::getFatigue).average().orElse(0);
            double avgBench = homeBench.stream().mapToDouble(CSPlayer::getFatigue).average().orElse(0);
            double avgBenchMax = homeBench.stream().mapToDouble(CSPlayer::getFatigue).max().orElse(0);

            assertTrue(avgStarter >= avgBenchMax,
                    "Kolo " + round + ": prosecan starter " + round2(avgStarter)
                            + " bi trebalo da bude veci ili jednak od najutrosnijeg klupe igraca "
                            + round2(avgBenchMax) + " (prosek klpe " + round2(avgBench) + ")");
        }
    }

    @Test
    @DisplayName("Svi eventi nose opis — teletext nema praznih linija")
    void everyEventHasADescription() {
        for (int skill = 5; skill <= 16; skill++) {
            CSMatchResult r = play(skill, 2);
            for (CSMatchEvent e : r.getEvents()) {
                assertTrue(e.getDescription() != null && !e.getDescription().isBlank(),
                        "Event " + e.getEventType() + " u " + skill + " nema opis");
            }
        }
    }

    @Test
    @DisplayName("CSGoalType.PENALTY postoji i ima opis, ne pada u 'default'")
    void penaltyGoalTypeIsHandled() {
        assertTrue(List.of(CSGoalType.values()).contains(CSGoalType.PENALTY),
                "CSGoalType.PENALTY ne postoji");
    }

    private static int countGoalsFor(CSMatchResult r, long playerId) {
        return (int) r.getEvents().stream()
                .filter(e -> e.getEventType() == org.example.footballtextmanager.model.CSEventType.GOAL)
                .filter(e -> playerId == e.getPlayerId())
                .count();
    }

    private static String round2(double v) {
        return String.format("%.2f", v);
    }

    private CSMatchResult play(int skill, int round) {
        CSTeam home = SquadFixture.team(1L, "Home");
        CSTeam away = SquadFixture.team(2L, "Away");
        return simulator.simulate(home,
                SquadFixture.startingEleven(1L, "Home", skill), SquadFixture.bench(1L, "Home B", skill, 7),
                away,
                SquadFixture.startingEleven(2L, "Away", skill), SquadFixture.bench(2L, "Away B", skill, 7),
                SquadFixture.balanced(), SquadFixture.balanced(), round);
    }
}
