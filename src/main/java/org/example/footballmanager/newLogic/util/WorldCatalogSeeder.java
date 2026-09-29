package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryCatalog;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.ScoutAssignmentRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.NationalTeamAppointmentRepository;
import org.example.footballmanager.newLogic.repository.NationalTeamElectionRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes every country in the catalogue exist, with its state set (owner, 2026-09-29).
 *
 * <p>The owner chose represented-but-unplayed countries over seeding 48 club pyramids up front. So all
 * 48 countries exist, each with a senior side and a U-21, which is enough for the national-team half
 * of the game to work across the whole map. Only Serbia is ACTIVE - a real club pyramid and a
 * simulated season. The rest are SIMULATED and cost nothing until they are activated.
 *
 * <p>This is the fix for the internationals drawing nothing: a country with no clubs used to produce
 * an empty national side, so there was nothing to draw against. With bot squads on all 48, there is.
 *
 * <p>Reputation and youth rating are set from a deterministic spread rather than left null, because a
 * country with no reputation is not a country a scout report can say anything about.
 */
@Component
public class WorldCatalogSeeder {

    private static final Logger log = LoggerFactory.getLogger(WorldCatalogSeeder.class);

    /** The only country played to begin with. */
    private static final String ACTIVE_COUNTRY = "SRB";

    /**
     * Legacy country rows from an earlier seed, removed on sight (owner, 2026-09-29).
     *
     * <p>They predate the catalogue and used different ISO codes and Serbian names, so the catalogue
     * did not recognise them: Croatia existed twice ({@code HRV} and {@code CRO}) and England twice
     * ({@code GBR} and {@code ENG}), which is how 48 countries became 51 and 96 national sides became
     * 102. Germany was one row but named "Nemačka", and because the seeder matches on ISO code it
     * found the row and left the name alone.
     *
     * <p>{@code DEU} is deleted too and simply re-created by the catalogue under its English name on
     * the next pass - the code is canonical, the row was not. The national teams on these rows go with
     * them; keeping them would leave four sides belonging to a country that no longer exists.
     */
    /** Every country starts here; results move it. */
    public static final int STARTING_RATING = 1500;

    public static final List<String> LEGACY_ISO_CODES = List.of("HRV", "GBR", "DEU");

    private final CountryRepository countries;
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final NationalTeamElectionRepository elections;
    private final NationalTeamAppointmentRepository appointments;
    private final CompetitionRepository competitions;
    private final ScoutAssignmentRepository scouting;

    public WorldCatalogSeeder(CountryRepository countries, TeamRepository teams,
                                PlayerRepository players,
                               NationalTeamElectionRepository elections,
                               NationalTeamAppointmentRepository appointments,
                               CompetitionRepository competitions,
                               ScoutAssignmentRepository scouting) {
        this.countries = countries;
        this.teams = teams;
        this.players = players;
        this.elections = elections;
        this.appointments = appointments;
        this.competitions = competitions;
        this.scouting = scouting;
    }

    @Transactional
    public int seedAll() {
        // First, not last. The legacy rows are superseded by the catalogue, so deleting them and then
        // seeding the catalogue recreates DEU under its English name in the same pass. Deleting after
        // the loop left the country missing until the next boot, which is what happened.
        dropLegacyRows();
        List<Country> ensured = new ArrayList<>();
        int created = 0;
        int renamed = 0;
        for (CountryCatalog entry : CountryCatalog.all()) {
            var existing = countries.findByIsoCode(entry.code());
            if (existing.isPresent()) {
                Country country = existing.get();
                // The catalogue owns the name. A row created by an older seed kept its old name -
                // Germany came back as Nemacka - because the seeder matched on ISO code and never
                // looked at the name. Corrected here so the country page and the registration
                // picker cannot disagree with each other.
                if (!entry.displayName().equals(country.getName())) {
                    log.info("Renaming {} from '{}' to the catalogue name '{}'.",
                            entry.code(), country.getName(), entry.displayName());
                    country.setName(entry.displayName());
                    countries.save(country);
                    renamed++;
                }
                ensured.add(country);
                continue;
            }
            Country country = new Country();
            country.setName(entry.displayName());
            country.setIsoCode(entry.code());
            // A spread, derived from the code so it is stable across installs. Not real-world ratings -
            // a claim about which country is stronger than which is not something to invent here.
            // Every country starts level at 1500 and earns its rating from results (owner,
            // 2026-09-29). The manager world is not a replica of the real one, so seeding a
            // strength table here would be a claim about real countries that this game is not
            // making - and it was doing damage, because a hash-derived 40-60 band made country
            // strength nearly meaningless to scouting and youth recruitment.
            country.setReputation(STARTING_RATING);
            country.setYouthRating(STARTING_RATING);
            country.setState(entry.code().equals(ACTIVE_COUNTRY)
                    ? CountryState.ACTIVE : CountryState.SIMULATED);
            ensured.add(countries.save(country));
            created++;
        }
        // Serbia is the one country played, whatever the catalogue says.
        countries.findByIsoCode(ACTIVE_COUNTRY).ifPresent(serbia -> {
            if (serbia.getState() != CountryState.ACTIVE) {
                serbia.setState(CountryState.ACTIVE);
                countries.save(serbia);
            }
        });
        if (created > 0) {
            log.info("World catalogue: created {} country row(s), renamed {}; {} of {} now exist, {} active.",
                    created, renamed, ensured.size(), CountryCatalog.all().size(), 1);
        }
        return ensured.size();
    }

    /**
     * Deletes the legacy rows, after the catalogue has run.
     *
     * <p>Runs BEFORE the catalogue loop, so a legacy row whose code is also a catalogue code is
     * deleted and immediately re-created under its English name in the same pass.
     *
     * <p><b>Children first.</b> The national teams hold a foreign key to the country and the players
     * hold one to the team, so deleting the country first fails on the constraint rather than
     * cascading. Players, then sides, then the country. Stated in that order on purpose: the reverse is
     * the version that works locally and breaks on a stricter database.
     */
    @Transactional
    public void dropLegacyRows() {
        for (String code : LEGACY_ISO_CODES) {
            countries.findByIsoCode(code).ifPresent(country -> {
                log.info("Removing legacy country {} ({}), superseded by the catalogue.",
                        code, country.getName());
                // Clear the references FIRST and flush, then delete the teams. Deleting the teams
                // first is the version that looks right and is not: the country still points at them,
                // so the delete fails on the foreign key - which is what happened on the first run.
                Team senior = country.getSeniorNationalTeam();
                Team youth = country.getU21NationalTeam();
                country.setSeniorNationalTeam(null);
                country.setU21NationalTeam(null);
                countries.saveAndFlush(country);

                for (Team side : List.of(senior, youth)) {
                    if (side != null && side.getId() != null) {
                        players.deleteAll(players.findByTeamId(side.getId()));
                    }
                }
                players.flush();
                for (Team side : List.of(senior, youth)) {
                    if (side != null && side.getId() != null) {
                        teams.delete(side);
                    }
                }
                teams.flush();
                // Five tables hold a foreign key to country, and Hibernate does not cascade a plain
                // delete across them. Anything still pointing at the row has to go first, or the
                // delete is refused and the legacy country simply stays - which is what happened on
                // the first two attempts.
                elections.deleteAll(elections.findByCountryId(country.getId()));
                appointments.deleteAll(appointments.findByCountryId(country.getId()));
                competitions.deleteAll(competitions.findByCountryId(country.getId()));
                scouting.deleteAll(scouting.findByCountryId(country.getId()));
                countries.delete(country);
            });
        }
    }
}
