package org.tifo.footballmanager.newLogic.service;

import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.CountryCatalog;
import org.tifo.footballmanager.newLogic.model.CountryState;
import org.tifo.footballmanager.newLogic.repository.CountryRepository;
import org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.footballmanager.newLogic.util.NationalTeamSeeder;
import org.tifo.footballmanager.newLogic.util.WorldCatalogSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks the world is whole, and puts it back if it is not (owner, 2026-09-29).
 *
 * <p>This exists because a reset interrupted part-way left a world that looked plausible and was not:
 * 310 clubs and 2,790 fixtures from the old bootstrap, nine countries still carrying legacy ISO codes
 * ({@code DEU}, {@code GBR}, {@code HRV}), and <b>zero</b> national teams — because the catalogue and
 * national-team seeding had not been reached yet. Nothing reported that. The world was simply a
 * different game, and there was no obvious way back.
 *
 * <p><b>Everything here is idempotent</b>, so it is safe to run on every boot and safe to run twice by
 * hand. The principle is that the world converges rather than depending on a run completing: a
 * process killed at any point is repaired by the next start, instead of leaving a half-built world
 * that nobody notices.
 */
@Service
public class WorldIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(WorldIntegrityService.class);

    private final CountryRepository countries;
    private final TeamRepository teams;
    private final org.tifo.footballmanager.newLogic.repository.PlayerRepository players;
    private final MatchFixtureRepository fixtures;
    private final WorldCatalogSeeder catalogue;
    private final NationalTeamSeeder nationalTeams;

    public WorldIntegrityService(CountryRepository countries, TeamRepository teams,
                                 org.tifo.footballmanager.newLogic.repository.PlayerRepository players,
                                 MatchFixtureRepository fixtures,
                                 WorldCatalogSeeder catalogue, NationalTeamSeeder nationalTeams) {
        this.countries = countries;
        this.teams = teams;
        this.players = players;
        this.fixtures = fixtures;
        this.catalogue = catalogue;
        this.nationalTeams = nationalTeams;
    }

    /**
     * What the world looks like right now, and whether that is what it should be.
     *
     * <p>Reports rather than fixes, so it can be asked the question without changing anything.
     *
     * <p><b>What it used to leave out, and why that was the whole defect.</b> The answer was computed
     * from four things: the countries, the senior sides, the squads on them, and the legacy rows.
     * <b>Not one of those is a club, a player or a fixture</b> — and those three are what a world
     * is made of. So the readout said <em>"World is whole"</em> for a world with <b>zero clubs and zero
     * matches</b>: press <em>Repair world</em> on a fresh database and it seeds the forty-eight
     * countries and their ninety-six sides, and the panel then congratulates a world with nothing in
     * it. The club count was in the payload all along and <b>the panel never read it</b>.
     *
     * <p>So the answer now counts the substance of the world and says <b>which button</b> builds what
     * is missing. That is what makes the admin buttons discoverable: a readout that names its own
     * repair is the difference between a first run and a first run followed by an evening of
     * guessing which of seven buttons was meant.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> report() {
        List<Country> all = countries.findAll();
        int expectedCountries = CountryCatalog.all().size();
        int seniorSides = 0;
        int seniorSidesWithSquad = 0;
        List<String> sidesWithoutSquad = new ArrayList<>();
        for (Country country : all) {
            if (country.getSeniorNationalTeam() == null) {
                continue;
            }
            seniorSides++;
            if (!players.findByTeamId(country.getSeniorNationalTeam().getId()).isEmpty()) {
                seniorSidesWithSquad++;
            } else {
                // Named, because "the world needs repair" without saying which country is the same
                // sentence as the one this replaces, only quieter.
                sidesWithoutSquad.add(country.getIsoCode() == null ? country.getName() : country.getIsoCode());
            }
        }

        // The country that is actually played. Every other country may be a name and a table.
        //
        // **There can be more than one.** "Activate a country" marks a country ACTIVE and does not
        // unmark the one that was, so a world can hold two. The counts are therefore the sum over the
        // played countries and not the first one found — the old code read a single country and
        // would have reported on whichever the database happened to return first.
        List<Country> active = all.stream()
                .filter(c -> c.getState() == CountryState.ACTIVE)
                .toList();
        long activeClubs = 0;
        long activePlayers = 0;
        long activeFixtures = 0;
        for (Country country : active) {
            activeClubs += teams.countClubTeamsForCountry(country.getId());
            activePlayers += players.countClubPlayersForCountry(country.getId());
            activeFixtures += fixtures.countForCountry(country.getId());
        }
        String activeNames = active.isEmpty()
                ? null
                : String.join(", ", active.stream().map(Country::getName).toList());

        List<String> legacy = legacyRows();

        // The counts go into one value, and the verdict is made there — see WorldState. This service
        // gathers; it does not judge.
        WorldState state = new WorldState(
                all.size(),
                expectedCountries,
                teams.findByType(org.tifo.footballmanager.newLogic.model.CompetitionTeamType.NATIONAL_TEAM).size(),
                seniorSides,
                seniorSidesWithSquad,
                teams.countClubTeams(),
                activeNames,
                activeClubs,
                activePlayers,
                activeFixtures,
                sidesWithoutSquad,
                legacy);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("countries", state.countries());
        out.put("expectedCountries", state.expectedCountries());
        out.put("nationalSides", state.nationalSides());
        out.put("seniorSides", state.seniorSides());
        out.put("seniorSidesWithSquad", state.seniorSidesWithSquad());
        out.put("clubs", state.clubs());
        out.put("legacyRows", state.legacyRows());
        out.put("activeCountries", state.activeCountries());
        out.put("activeClubs", state.activeClubs());
        out.put("activePlayers", state.activePlayers());
        out.put("activeFixtures", state.activeFixtures());
        out.put("sidesWithoutSquad", state.sidesWithoutSquad());
        out.put("healthy", state.healthy());
        out.put("nextStep", state.nextStep());
        return out;
    }

    /**
     * The country rows from before the catalogue, by ISO code.
     *
     * <p>Read once per report and handed on, rather than asked twice: the answer used to be computed
     * twice — once for the payload and once inside the verdict — and two reads of the same list are
     * two chances to disagree.
     */
    private List<String> legacyRows() {
        return countries.findAll().stream()
                .map(Country::getIsoCode)
                .filter(code -> code != null && WorldCatalogSeeder.LEGACY_ISO_CODES.contains(code))
                .toList();
    }

    /**
     * Brings the world to the state it should be in.
     *
     * <p>Runs the same steps boot does, in the same order, so a repair and a normal start converge on
     * the same result. Safe to call repeatedly and safe to call on a healthy world.
     */
    @Transactional
    public Map<String, Object> repair() {
        Map<String, Object> before = report();
        log.info("World integrity check before repair: {}", before);
        catalogue.seedAll();
        nationalTeams.seedIfMissing(countries.findAll());
        Map<String, Object> after = report();
        log.info("World integrity check after repair: {}", after);
        return after;
    }
}
