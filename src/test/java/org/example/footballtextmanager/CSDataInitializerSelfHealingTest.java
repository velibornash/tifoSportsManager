package org.example.footballtextmanager;

import org.example.footballmanager.BaseTest;
import org.example.footballtextmanager.model.CSCompetition;
import org.example.footballtextmanager.model.CSCompetitionEntry;
import org.example.footballtextmanager.model.CSCompetitionType;
import org.example.footballtextmanager.model.CSCompetitionTeamType;
import org.example.footballtextmanager.model.CSSeasonCompetition;
import org.example.footballtextmanager.model.CTeam;
import org.example.footballtextmanager.repository.CSCompetitionEntryRepository;
import org.example.footballtextmanager.repository.CSCompetitionRepository;
import org.example.footballtextmanager.repository.CSCountryRepository;
import org.example.footballtextmanager.repository.CSPlayerRepository;
import org.example.footballtextmanager.repository.CSSeasonCompetitionRepository;
import org.example.footballtextmanager.repository.CSTeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Year;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Da li tekst-mod sebe popravlja, umesto da se tiho preskoci.
 *
 * <p>Ovaj test postoji zbog jednog konkretnog scenarija, ne zbog lepog koda. Admin dugme
 * "Initialize DB" ({@code DatabaseInitializer.buildSerbianStructure}) stvara {@code CSCountry}
 * preko {@code serbiaForTextManager()} — taj klik ne seje tekst-ligu, ali ostavlja zemlju u
 * bazi. Prvi boot posle toga je naleteo na guard, preskocio seedovanje i logovao "already
 * seeded", a lige, igrača i takmičarske tabele nije bilo. {@code /api/cs/start} je padao na
 * "League not found" bez ikakve poruke o tome šta nedostaje.
 *
 * <p>Guard je imao dve iteracije i obe su bile {@code if (...) return;}: prva je gledala zemlju,
 * druga ligu, a nijedna nije mogla da dopuni svet — obe su mogle da preseku ceo seeding na
 * delimičnom svetu. Zato je guard uklonjen, a ne popravljen: {@code seed()} već zna da svaki
 * korak izvršava tek kad nešto nedostaje. Ove četiri provere su tvrdnja da ta tvrdnja stoji.
 *
 * <p>Napomena o obimu: svet se ovde <b>ne briše</b>. {@code APP_USER} ima FK na {@code CTEAM},
 * pa bi brisanje klubova oborilo kontekst, a deljeni H2 kontekst bi time pokvario i druge
 * testove. Zato se u {@code resetToPartialWorld()} ne briše nego <b>postavlja</b> poznat
 * delimičan oblik (liga i klubovi postoje, igrača i tabela nemа), pa svaki test meri svoju
 * tvrdnju, a ne redosled testova. Efekat koji je ovaj test tražio — "liga postoji, igrača
 * nema" — je upravo onaj koji se može bezbedno izazvati.
 */
class CSDataInitializerSelfHealingTest extends BaseTest {

    private static final int TEAMS = 16;
    private static final int PLAYERS_PER_TEAM = 15;

    @Autowired
    private CSDataInitializer initializer;
    @Autowired
    private CSCountryRepository countryRepository;
    @Autowired
    private CSCompetitionRepository competitionRepository;
    @Autowired
    private CSSeasonCompetitionRepository seasonRepository;
    @Autowired
    private CSCompetitionEntryRepository entryRepository;
    @Autowired
    private CSTeamRepository teamRepository;
    @Autowired
    private CSPlayerRepository playerRepository;

    /**
     * Svedi svet na poznat DELIMICAN oblik pre svakog testa: liga i klubovi postoje, igrača
     * i tabela nemа.
     *
     * <p>Ovo je bilo neophodno, ne kozmetički. Prva verzija testa nije brisala ništa i
     * zavisila je od toga koji test slučajno prvi radi: jedan brise sve igrače, drugi jedan
     * unos tabele, pa je sledeći test poceo od brojeva koje je ostavio prethodni. Testovi su
     * tako prolazili slučajno, a u testu sa namerno vracenom greskom izlazilo je
     * {@code players=0 entries=15} — dokaz da je nesto u testu merilo tudje stanje umesto
     * svog. Deljeni H2 kontekst ne čisti sam sebe, a brisanje klubova nije moguće jer
     * {@code APP_USER} drzi FK na {@code CTEAM}.
     *
     * <p>Zato se ovde ne brise nego <b>postavlja</b>: posle ovog poziva svet je uvek isti
     * delimičan oblik, pa svaki test meri svoju tvrdnju, a ne redosled testova.
     */
    @BeforeEach
    void resetToPartialWorld() {
        playerRepository.deleteAllInBatch();
        entryRepository.deleteAllInBatch();
        assertEquals(0, playerRepository.count(), "Preduslov: igrači moraju biti obrisani");
        assertEquals(0, entryRepository.count(), "Preduslov: tabela mora biti prazna");
    }

    /**
     * Posle jednog poziva tekst-mod mora imati sve delove na koje njegovi kontroleri zapravo
     * ciljaju. Ovo je bio asortiman tvrdnji koje log nije dokazivao — "1 country, N
     * competitions" izgleda je kao gotov svet i nije bio.
     */
    @Test
    @DisplayName("Jedan poziv dopunja delimičan svet: liga, 16 klubova, igrača, tabela")
    void oneCallCompletesAPartialWorld() {
        initializer.ensureCSDataOnStartup();

        List<CSCompetition> leagues = leagues();
        assertEquals(1, leagues.size(),
                "Teks-mod treba tacno jednu ligu, a ima " + competitions().size() + ": " + competitions());

        CSCompetition league = leagues.getFirst();
        assertEquals(TEAMS, teamRepository.countByCSCompetition(league),
                "Liga treba 16 klubova");

        CSSeasonCompetition season = seasonRepository
                .findByCsCompetitionAndSeasonYear(league, Year.now().getValue())
                .orElseThrow(() -> new AssertionError("Nema sezone za tekucnu godinu"));

        assertEquals(TEAMS, entryRepository.countByCsSeasonCompetition(season),
                "Tabela mora imati 16 unosa, po jedan po klubu");

        assertTrue(playerRepository.count() >= TEAMS * PLAYERS_PER_TEAM,
                "Svaki klub trazi 15 igraca, a u bazi ih ima samo " + playerRepository.count());

        assertTrue(countryRepository.findByIsoCodeIgnoreCase("SRB").isPresent(),
                "Zemlja na koju liga pokazuje mora da postojati u bazi");
    }

    /**
     * Drugi poziv na zdravom svetu ne sme nista da doda. Seeder se poziva na svaki boot,
     * pa bez ovoga svaki restart raste baza za 16 klubova i 240 igraca.
     */
    @Test
    @DisplayName("Drugi poziv na zdravom svetu ne dodaje nista")
    void secondRunCreatesNothing() {
        initializer.ensureCSDataOnStartup();

        long competitions = competitionRepository.count();
        long teams = teamRepository.count();
        long players = playerRepository.count();
        long entries = entryRepository.count();

        assertTrue(competitions > 0 && teams > 0 && players > 0 && entries > 0,
                "Prvi poziv nije napravio svet: competitions=" + competitions
                        + " teams=" + teams + " players=" + players + " entries=" + entries);

        initializer.ensureCSDataOnStartup();

        assertEquals(competitions, competitionRepository.count(), "Stvorene su nove lige");
        assertEquals(teams, teamRepository.count(), "Stvoreni su novi klubovi");
        assertEquals(players, playerRepository.count(), "Stvoreni su novi igraci");
        assertEquals(entries, entryRepository.count(), "Stvoreni su novi unosi tabele");
    }

    /**
     * Delimican svet: liga postoji, igrača nema. Ovo je bio tacan propust starog guarda —
     * nije ni zemlja ni liga nedostajalo, pa je "ima ligu" znacilo "sve je tu", a modul je
     * ostajao bez igrača. StartGame je padao, a niko nije prijavio zasto.
     *
     * <p>Ovo je jedini test koji dokazuje granicu popravke: da se igrači stvaraju
     * <b>po klubu</b>, a ne "za sve ili ništa". Ako bi se popravka odvijala globalno, ovaj
     * test bi brojao 240 igraca i prolazio, dok bi stvarni svet imao 16 klubova sa po 30
     * igrača. Zato se broji <b>po klubu</b>, ne ukupno.
     */
    @Test
    @DisplayName("Liga bez igrača se dopuni po klubu, bez dupliranja postojećih")
    void leagueWithoutPlayersGetsHealedPerTeam() {
        initializer.ensureCSDataOnStartup();

        List<CTeam> teams = teamRepository.findAllByTypeOrderByIdAsc(CSCompetitionTeamType.CLUB);
        assertEquals(TEAMS, teams.size(), "Liga treba 16 klubova");

        for (CTeam team : teams) {
            assertEquals(PLAYERS_PER_TEAM, playerRepository.countByCTeam(team),
                    "Posle popravke svaki klub mora imati tacno 15 igraca: " + team.getName()
                            + " ima " + playerRepository.countByCTeam(team));
        }
    }

    /**
     * Delimican svet na nivou tabele: liga postoji i ima klubove, ali jedan klub nema unos u
     * tekucoj sezoni. Proverava da li se popravka odvija po klubu, a ne "za sve ili ništa".
     */
    @Test
    @DisplayName("Jedan klub bez unosa u tabeli dobije upis — popravka je po klubu")
    void teamWithoutTableEntryGetsHealed() {
        initializer.ensureCSDataOnStartup();

        CSCompetition league = leagues().getFirst();
        CSSeasonCompetition season = seasonRepository
                .findByCsCompetitionAndSeasonYear(league, Year.now().getValue())
                .orElseThrow(() -> new AssertionError("Nema sezone za tekucnu godinu"));

        List<CSCompetitionEntry> entries = entryRepository.findByCsSeasonCompetitionCsCompetition(league);
        assertEquals(TEAMS, entries.size(), "Preduslov: tabela mora biti potpuna pre testa");

        CSCompetitionEntry victim = entries.getFirst();
        Long victimTeamId = victim.getCTeam().getId();
        entryRepository.deleteById(victim.getId());
        assertEquals(TEAMS - 1, entryRepository.countByCsSeasonCompetition(season),
                "Preduslov: upis mora biti obrisan");

        initializer.ensureCSDataOnStartup();

        assertEquals(TEAMS, entryRepository.countByCsSeasonCompetition(season),
                "Izbrisani upis tabele nije dopunjen");
        assertTrue(entryRepository.findByCsSeasonCompetitionAndCTeam(season, teamRepository.findById(victimTeamId).orElseThrow()).isPresent(),
                "Upis mora da se vrati baš za klub kome je nedostajao");
    }

    private List<CSCompetition> leagues() {
        return competitionRepository.findByCsCountry_IsoCodeAndType("SRB", CSCompetitionType.LEAGUE);
    }

    private List<CSCompetition> competitions() {
        return competitionRepository.findAll();
    }
}