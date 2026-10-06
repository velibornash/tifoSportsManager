package org.example.footballmanager.newLogic.util;

import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.*;
import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.newLogic.model.tactics.TeamTacticsProfile;
import org.example.footballmanager.newLogic.repository.*;
import org.example.footballmanager.newLogic.service.ResetService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.example.footballmanager.newLogic.service.TacticsProfileBackupEntry;
import org.example.footballmanager.newLogic.service.TacticsProfileBackupService;
import org.example.footballmanager.newLogic.service.YouthAcademyService;
import org.example.footballmanager.newLogic.service.YouthAcademyService;
import org.example.footballmanager.newLogic.util.players.PlayerFactory;
import org.example.footballmanager.newLogic.util.players.SquadNumberAssigner;
import org.example.footballmanager.newLogic.util.teams.EconomyProfileService;
import org.example.footballmanager.newLogic.util.teams.TeamFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;


@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseInitializer {

    private static final String OWNER_EMAIL = "velibor@example.com";
    private static final String OWNER_DISPLAY_NAME = "Velja";

    /**
     * The second human manager's club and login (owner request 2026-09-27).
     *
     * <p>Kept next to {@link #OWNER_EMAIL} rather than buried in a service, because these two are the
     * only two accounts in the game and they have to be findable from one screen. {@code REGULAR} is
     * deliberate: this account manages a club and has every club tool, but it does not reach
     * {@code /admin/**}, which is reserved for OWNER/ADMIN/DEV.
     */
    private static final String SECOND_EMAIL = "kecko@example.com";

    /**
     * The country both seeded managers play in.
     *
     * <p>Named rather than left to be derived from the club (owner, 2026-09-28), because a derived
     * country cannot be chosen and a null one drops an account onto the legacy path — so a seeded
     * manager and a newly registered one would behave differently for no visible reason.
     */
    private static final String SEEDED_COUNTRY_CODE = "SRB";
    private static final String SECOND_DISPLAY_NAME = "Kecko";
    private static final String SECOND_PASSWORD = "Kecko123!";
    private static final String SREMAC_TEAM_NAME = "Sremac Berkasovo";
    private static final String SREMAC_STADIUM_IMAGE = "/images/livadice.png";
    private static final String SREMAC_LOGO = "/images/sremac_logo.jpg";
    private static final String OMLADINAC_LOGO = "/images/omladinac.png";
    private static final String SREMAC_STADIUM = "Stadion Livadice";

    /**
     * The Šid municipal league, and the nine real clubs that play in it.
     *
     * <p>Real names rather than generated ones because the point of this league is that it is a
     * recognisable place — a manager who is sent here is sent somewhere, not somewhere random. Two
     * of the nine already carry their town inside the club name ({@code OFK Bingula},
     * {@code OFK Bačinci}) so appending it again would read as a stutter; the other seven follow the
     * same "Club Place" shape as Sremac Berkasovo.
     *
     * <p>Note the ninth club is a plain {@code Omladinac} from Batrovci, which is a different club
     * from velibor's {@code OFK Omladinac} in the Superliga. They are distinct strings, so nothing
     * collides — but the two names are close enough to be worth knowing about.
     */
    private static final String MUNICIPAL_SID_LEAGUE = "Opštinska liga Šid";
    private static final List<String> MUNICIPAL_SID_CLUBS = List.of(
            SREMAC_TEAM_NAME,      // Sremac, Berkasovo   - the human's club
            "Sinđelić Gibarac",
            "Graničar Jamena",
            "Jednota Šid",
            "Omladinac Batrovci",
            "Borac Ilinci",
            "Jedinstvo Morović",
            "OFK Bačinci",         // town already in the name
            "OFK Bingula");        // town already in the name

    private final CountryRepository countryRepository;
    private final CompetitionRepository competitionRepository;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final TransferActivitySeeder transferActivitySeeder;
    private final NationalTeamSeeder nationalTeamSeeder;
    private final CupFixtureSeeder cupFixtureSeeder;
    private final LeagueFixtureDayBackfill leagueFixtureDayBackfill;
    private final SeasonNumberBackfill seasonNumberBackfill;
    private final PlayerRatingBackfill playerRatingBackfill;
    private final BotLeagueStandardBackfill botLeagueStandardBackfill;
    private final InternationalClubCups internationalClubCups;
    private final SimulatedWorldSeeder simulatedWorldSeeder;
    private final org.example.footballmanager.newLogic.service.NationalRatingService nationalRatingService;
    private final WorldCatalogSeeder worldCatalogSeeder;
    private final org.example.footballmanager.newLogic.service.WorldIntegrityService worldIntegrity;
    private final org.springframework.transaction.PlatformTransactionManager seedingTransactionManager;

    private org.springframework.transaction.support.TransactionTemplate requiresNew;

    /**
     * The transaction used for the seeding steps that must be able to fail without poisoning the boot.
     *
     * <p>Built here rather than injected: Spring Boot auto-configures a TransactionTemplate with
     * REQUIRED propagation, which joins the caller's transaction - exactly what this exists to avoid,
     * so injecting one would have made it silently a no-op.
     */
    @jakarta.annotation.PostConstruct
    void configureIsolatedSeedingTransaction() {
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(seedingTransactionManager);
        template.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNew = template;
    }
    private final InternationalFixtureSeeder internationalFixtureSeeder;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final org.example.footballmanager.newLogic.service.NationalTeamService nationalTeamService;
    private final org.example.footballmanager.newLogic.service.NationalTeamElectionService electionService;
    private final PlayerRepository playerRepository;
    private final SeasonRepository seasonRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final PromotionRuleRepository promotionRuleRepository;
    private final TeamTacticsProfileRepository teamTacticsProfileRepository;
    private final PlayerFactory playerFactory;
    private final TeamFactory teamFactory;
    private final EconomyProfileService economyProfile;
    private final PasswordEncoder encoder;  // Spring Security BCrypt encoder
    private final Random random = new Random();
    private final ResetService resetService;
    private final SeasonService seasonService;
    private final YouthAcademyService youthAcademyService;
    private final SquadNumberAssigner squadNumberAssigner;
    private final TacticsProfileBackupService tacticsProfileBackupService;
    private final org.example.footballtextmanager.repository.CSTeamRepository csTeamRepository;
    private final org.example.footballtextmanager.repository.CSCountryRepository csCountryRepository;
    private final StadiumRepository stadiumRepository;
    private final org.example.footballmanager.newLogic.repository.JuniorRepository juniorRepository;

    /**
     * Widens the {@code competition.type} check constraint to every value the enum now carries.
     *
     * <p>The table shipped with a CHECK of {@code type IN ('LEAGUE','CUP')}, so adding INTERNATIONAL
     * and TOURNAMENT to the enum was not enough - PostgreSQL rejected the insert and the application
     * context failed to start. A check constraint is part of the schema, not something
     * {@code ddl-auto=update} touches, so it is widened here and it is done idempotently: a fresh
     * install and an existing one both end up with the same set of values.
     */
    private void widenCompetitionTypeConstraint() {
        try {
            jdbcTemplate.execute("ALTER TABLE competition DROP CONSTRAINT IF EXISTS competition_type_check");
            jdbcTemplate.execute("ALTER TABLE competition ADD CONSTRAINT competition_type_check "
                    + "CHECK (type IN ('LEAGUE','CUP','INTERNATIONAL','TOURNAMENT'))");
        } catch (RuntimeException e) {
            log.warn("Could not widen the competition type constraint: {}", e.getMessage());
        }
    }

    /**
     * The current season, from the game clock.
     *
     * <p>It used to be the highest season row in the table, which is a guess about the world rather
     * than a reading of it. The clock is the one place the season is decided.
     */
    public int currentSeasonYear() {
        return seasonService.getActiveSeasonYear();
    }

    /**
     * When week 1 day 1 kicked off, which is where the voting window is measured from.
     *
     * <p>Anchored to the start of the current day rather than the instant the process booted, so the
     * window does not move every time the app restarts.
     */
    private java.time.Instant weekOneKickoff() {
        return java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sanitizeLegacySchemaOnStartup() {
        resetService.sanitizeLegacyLineupOrderSchema();
        resetService.migrateTickStateMinuteColumn();
        // Before anything can create a season row. Rewriting calendar years into season numbers
        // happens further down, and a world that still holds 2025 will have a seeder ask for season 1,
        // find nothing, and create a second row beside the one it should have reused. That is how one
        // league came to have three season competitions - and findByCompetitionAndSeasonYear throws on
        // that rather than degrading, so the league table and the club schedule both went down.
        resetService.enforceOneSeasonCompetitionPerSeason();
    }

    /**
     * Bootstraps the pyramid, and is <b>transactional on purpose</b>.
     *
     * <p>{@code Team.stadium} and {@code User.CTeam} are lazy proxies. An event listener runs
     * outside any session, so touching either throws "could not initialize proxy - no Session" and
     * aborts the whole seeding run half way through — leaving a database that looks seeded and is
     * missing a club, an account and a badge.
     *
     * <p>The annotation has to be here and not on the private helpers. Spring's {@code @Transactional}
     * works through a proxy, which cannot see a private method, and even a public one would be
     * bypassed by the self-invocation from here. Putting the boundary on the listener is the only
     * placement that actually opens a session for the work underneath it.
     */
    /**
     * Countries have to exist before the pyramid reads them, on the bootstrap path as well as the
     * normal one.
     *
     * <p>This used to be satisfied by the hard-coded list of nine inside the pyramid bootstrap. That
     * list is gone, so without this a fresh install builds a Serbian pyramid against a world with no
     * countries in it.
     */
    private void seedWorldBeforePyramid() {
        worldCatalogSeeder.seedAll();
        nationalTeamSeeder.seedIfMissing(countryRepository.findAll());
    }

    /**
     * Recomputes every country's rating from the internationals on the database.
     *
     * <p>A replay rather than an increment, so it is idempotent, repairs a bad write, and gives the
     * owner a moved column on the world he already has instead of after a reset. Cheap enough to run on
     * both boot paths: the table is 24 rows today.
     */
    private void applyNationalRatings() {
        try {
            nationalRatingService.recomputeDurably();
        } catch (RuntimeException e) {
            log.warn("Could not recompute national Elo ratings: {}", e.getMessage());
        }
    }

    /**
     * Puts every bot club's squad on its division's standard.
     *
     * <p>One method called from both boot paths, because the standards are a <i>convergence</i> step,
     * not a creation step: a world that already exists is the one that most needs it, and the version
     * that only ran while creating the world left the manager's own world flat. Idempotent, so calling
     * it on every boot is safe.
     */
    private void applyBotLeagueStandards() {
        try {
            botLeagueStandardBackfill.backfill();
        } catch (RuntimeException e) {
            log.warn("Could not apply the bot league tier standards: {}", e.getMessage());
        }
    }

    /**
     * Builds the baseline world — ONCE, and only when something explicitly asks for it.
     *
     * <p><b>Deliberately not an {@code @EventListener} any more</b> (owner, 2026-10-01). Starting the
     * application is now exactly that: it starts the application. No country catalogue, no pyramid, no
     * national sides, no backfills, no repair — nothing that writes to the database.
     *
     * <p>This used to run on every boot, which was the reported problem and not a small one: the
     * world's seeding is tens of thousands of writes and it ran before the application was usable, so
     * a cold start on a small server looked like a hang, and there was no way to start the app and
     * look at a world before it was changed underneath you. Worse, on the owner's Oracle instance it
     * had failed silently for a whole season — the catch below swallows seeding failures so a boot
     * with a partial world still comes up, which is right for resilience and catastrophic for
     * "why are there no leagues", because the failure was never visible from the outside.
     *
     * <p>Reachable from the admin tools, which is where a rebuild belongs: <b>Initialise</b> builds the
     * Serbian structure, <b>Repair world</b> tops up anything missing, <b>Re-seed national teams</b>
     * fills empty squads, and <b>Seed other nations</b> builds the simulated half of the world.
     */
    @Transactional
    public void ensureBaselineDataOnStartup() {
        if (countryRepository.count() > 0
                && competitionRepository.count() > 0
                && teamRepository.count() > 0
                && userRepository.findByUsernameOrEmail(OWNER_EMAIL).isPresent()) {
            // The baseline is already there, so the league content cannot be re-created. The second
            // manager still has to be, though: this runs on every boot and is idempotent, which is
            // what makes it safe to call from the "everything already exists" path as well as the
            // bootstrap one.
            ensureSidLeague();
            createSecondUserIfNotExists();
            refreshClubIdentities();
            backfillJuniorArrivalAges();
            int positioned = youthAcademyService.assignMissingPositions();
            if (positioned > 0) {
                log.info("Assigned an intake position to {} pre-existing academy juniors (Sprint 5.3).",
                        positioned);
            }
            applyManagerIdentities();
            backfillClubCountries();
            backfillStadiumCeilings();
            // The three international club cups. The World page listed them as "Not created yet" for
            // as long as the page existed, which was a claim about the database being empty and was
            // true of the competitions — there were no records, only markup.
            // The 46 simulated countries. A country that is in the world but not activated still needs
            // clubs, ratings and a standing position, or the international field is thirty-two clubs
            // instead of forty-eight. Seeded as a static world — no players, no fixtures, no matches —
            // and before the cups, because the cup qualification reads these tables.
            //
            // Its own transaction: 46 countries is the biggest single write on this boot, and letting a
            // failure in it roll back the club identities, ratings and elections above it would undo
            // work that has nothing to do with it.
            try {
                requiresNew.executeWithoutResult(status ->
                        simulatedWorldSeeder.seedAllSimulated(currentSeasonYear()));
            } catch (RuntimeException e) {
                log.warn("Could not seed the simulated countries: {}", e.getMessage());
            }
            try {
                internationalClubCups.ensureCompetitionsDurably();
            } catch (RuntimeException e) {
                log.warn("Could not create the international club cups: {}", e.getMessage());
            }
            // Every country reads 1500 and always has. RatingEngine existed since 2026-09-28 with no
            // caller, so the World page's rating column was real data that could only ever be 1500.
            // Replayed from the match history, so the 24 internationals already on the database count.
            applyNationalRatings();
            // The 300-odd bot clubs already in this world were seeded by the old generator, which
            // drew every skill uniformly and ignored the division — so tier 1 and tier 5 measured
            // within 0.05 of each other. The backfill used to sit only on the create-the-world path,
            // which is exactly the path a world someone has been playing never takes.
            applyBotLeagueStandards();
            // The national-team columns have been null for every country since the country page
            // existed, so the panel rendered a hand-written name and a button to a placeholder. Runs on
            // every boot and is a no-op once the teams exist.
            // Every country in the catalogue has to exist before anything reads all countries, and
            // the national sides have to exist before the selectors and elections reference them. This
            // ran after both, so 42 new countries existed with no national side and no bot squad -
            // which is exactly why the internationals had nothing to draw against.
            // In their own transaction, so a failure rolls back the catalogue alone and leaves the boot
            // transaction usable. Sharing it is what made the boot unrecoverable: the catalogue threw
            // while dropping HRV, the catch swallowed it, later steps then reported success inside a
            // transaction already marked rollback-only, and the commit threw UnexpectedRollbackException
            // - taking 42 countries and 84 national sides back down with it.
            try {
                requiresNew.executeWithoutResult(status -> worldCatalogSeeder.seedAll());
            } catch (RuntimeException e) {
                log.warn("Could not seed the world catalogue: {}", e.getMessage());
            }
            try {
                requiresNew.executeWithoutResult(
                        status -> nationalTeamSeeder.seedIfMissing(countryRepository.findAll()));
            } catch (RuntimeException e) {
                log.warn("Could not seed national teams: {}", e.getMessage());
            }
            try {
                cupFixtureSeeder.seedIfMissing();
            } catch (RuntimeException e) {
                log.warn("Could not draw the cup: {}", e.getMessage());
            }
            // The repair runs after every other seeding step, and it is the safety net. Each step
            // above can be interrupted, and when one is the world is left plausible and wrong - a
            // reset killed part-way through left nine legacy countries and no national teams, and
            // nothing said so. This converges the world whether or not the earlier steps ran, so a
            // process killed at any point repairs itself on the next start.
            try {
                Map<String, Object> integrity = worldIntegrity.repair();
                if (Boolean.FALSE.equals(integrity.get("healthy"))) {
                    log.warn("World is still not whole after repair: {}", integrity);
                } else {
                    log.info("World integrity OK: {}", integrity);
                }
            } catch (RuntimeException e) {
                log.warn("World repair failed: {}", e.getMessage());
            }

            // The backfills live here, not in the "baseline already exists" branch above, and that
            // placement was a bug of its own: a fresh Reset + Initialize goes through the pyramid and
            // the world build below, so a backfill sitting above never ran for a new world. Proof: a
            // freshly initialised world came up with 4,650 club players whose rating had never been
            // computed, while the 2,400 national-squad players - who are written by the seeder that
            // creates them - were fine.
            //
            // This is the same place the integrity repair runs, and for the same reason: it is after
            // every seeding step, so it converges the world whichever door it came in by.
            // League fixtures seeded before MatchFixture.dayNumber existed have a week but no day, so
            // the day-3 and day-7 matchday jobs select nothing and the season plays no league football.
            try {
                leagueFixtureDayBackfill.backfill();
            } catch (RuntimeException e) {
                log.warn("Could not stamp the day onto league fixtures: {}", e.getMessage());
            }
            applyBotLeagueStandards();
            // Player.rating had three writers meaning three different things, so the stored column
            // does not match the skills it is supposed to be derived from.
            try {
                playerRatingBackfill.backfill();
            } catch (RuntimeException e) {
                log.warn("Could not recompute player ratings: {}", e.getMessage());
            }
            // Rows written while a season was a calendar year. Every reader now asks the clock, so
            // until these are rewritten a played world looks like a world with no football in it.
            try {
                seasonNumberBackfill.backfill();
            } catch (RuntimeException e) {
                log.warn("Could not rewrite calendar years into season numbers: {}", e.getMessage());
            }

            widenCompetitionTypeConstraint();
            try {
                internationalFixtureSeeder.seedIfMissing(seasonService.getActiveSeasonYear());
            } catch (RuntimeException e) {
                log.warn("Could not draw the internationals: {}", e.getMessage());
            }
            // The country's manager stands in as selector until the elections run. Recorded as an
            // appointment rather than derived from the viewer, so "only the selector sees the squad"
            // is a checkable fact instead of everyone appearing to run the team.
            try {
                nationalTeamService.appointBaselineSelectors(
                        countryRepository.findByIsoCode("SRB").orElse(null));
            } catch (RuntimeException e) {
                log.warn("Could not appoint the baseline selector: {}", e.getMessage());
            }
            // Elections were never created automatically, so the national-team panel could only ever
            // report "no election running" and nobody could stand in one. ensureElection is
            // idempotent, so this is a no-op once an election exists for the season.
            try {
                for (Country country : countryRepository.findAll()) {
                    for (org.example.footballmanager.newLogic.model.NationalTeamLevel lvl
                            : org.example.footballmanager.newLogic.model.NationalTeamLevel.values()) {
                        electionService.ensureElection(country, lvl, currentSeasonYear(), weekOneKickoff());
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Could not open the national-team elections: {}", e.getMessage());
            }
            return;
        }

        log.warn("Core football data missing on startup. Bootstrapping baseline Serbian pyramid.");
        try {
            seedWorldBeforePyramid();
            seedWorldBeforePyramid();
        initSerbianFootballStructure();
            ensureSidLeague();
            backfillClubCountries();
            backfillStadiumCeilings();
            Team ownerTeam = createOwnerUserIfNotExists();
            createSecondUserIfNotExists();
            seedInitialJuniorsForOwnerIfMissing(ownerTeam);
        seedStandInTransferActivity();
            assignSquadNumbersIfMissing();
        } catch (Exception e) {
            // The stack trace matters. This catch used to log only e.getMessage() and call it a
            // concurrent reset, which is how a lazy-proxy failure hid in plain sight: the message
            // said "no Session" and the summary said something that had nothing to do with it.
            // A seeding failure that is only visible as a missing club is a failure nobody debugs.
            log.error("Startup initialization failed — the database may be partially seeded", e);
            // NOT rethrown, deliberately: this method is also the boot listener, and throwing here
            // takes down an application that has a partly-built but otherwise usable world. Boot
            // stays resilient.
            //
            // The cost of staying resilient is that whoever asked for a rebuild cannot tell it failed,
            // which is the bug that was reported. That is fixed at the caller instead —
            // AdminDatabaseAsyncService verifies the world after this returns and fails the job if
            // nothing was built. Resilience here, honesty there.
        }
    }


    public void init() {
        resetAndInitializeDatabase();
    }

    public void resetOnly() {
        log.info("Počinje soft reset baze podataka (čuvaju se korisnici, taktike, struktura)...");
        resetService.resetFootballDataOnly();
        log.info("Soft reset baze završen.");
    }

    /** Clear all football data but preserve user account + tactics profiles. Does NOT rebuild structure. */
    public void clearDatabaseOnly() {
        clearDatabaseOnly(message -> {});
    }

    public void clearDatabaseOnly(Consumer<String> progressListener) {
        log.info("Clearing football data (preserving owner account + tactics profiles)...");
        progressListener.accept("Snapshotting tactics profiles...");
        List<TacticsProfileSnapshot> tacticsSnapshots = snapshotTacticsProfiles();
        progressListener.accept("Resetting database...");
        resetService.resetDatabase();
        // Re-seed Omladinac team + players + re-link common User's CTeam reference
        progressListener.accept("Re-seeding Omladinac team...");
        seedOwnerAfterReset();
        progressListener.accept("Restoring tactics profiles...");
        restoreTacticsProfiles(tacticsSnapshots);
        log.info("Database cleared. Use Initialize to rebuild the football structure.");
        progressListener.accept("Clear completed.");
    }

    public void resetAndInitializeDatabase() {
        resetAndInitializeDatabase(message -> {});
    }

    public void resetAndInitializeDatabase(Consumer<String> progressListener) {
        log.info("Počinje automatska inicijalizacija baze podataka...");
        progressListener.accept("Snapshotting tactics profiles...");
        List<TacticsProfileSnapshot> tacticsSnapshots = snapshotTacticsProfiles();
        progressListener.accept("Resetting football data...");
        resetService.resetDatabase();
        // 1. Always run structure init after a full reset.
        log.info("Pokrecem punu inicijalizaciju strukture...");
        progressListener.accept("Rebuilding Serbian pyramid...");
        initSerbianFootballStructure();

        // 2. Kreiraj Owner korisnika ako ne postoji (sada baza ima strukturu, timovi postoje)
        progressListener.accept("Restoring owner account...");
        Team ownerTeam = createOwnerUserIfNotExists();
        // Same reason as on a cold boot: a rebuild from the admin tools has to leave the database in
        // the state a normal boot would, and that includes the second manager. The owner's own
        // account is restored here, so leaving his out would be the same omission one level down.
        createSecondUserIfNotExists();
        progressListener.accept("Restoring tactics profiles...");
        restoreTacticsProfiles(tacticsSnapshots);
        progressListener.accept("Assigning squad numbers and juniors...");
        seedInitialJuniorsForOwnerIfMissing(ownerTeam);
        seedStandInTransferActivity();

        assignSquadNumbersIfMissing();
        log.info("Inicijalizacija završena.");
        progressListener.accept("Database rebuild completed.");
    }

    @Transactional

    /**
     * Puts some stand-in activity on the transfer market (owner, 2026-09-28).
     *
     * <p>Deliberately scaffolding, and recorded as such in the backlog. The real feature is the weekly
     * AI transfer loop; {@code NegotiationService} is written and unwired, so the transfer screens
     * have nothing to render and cannot be judged at all. Random clubs, random players, prices scaled
     * off real player values, plus a couple of genuine offers on the manager's own players.
     *
     * <p>Best-effort and swallowed on purpose. A demo seeder failing must not stop a database
     * initialise, and this runs after the owner and the juniors have already been created — losing
     * those to a decorative step would be a bad trade.
     */
    private void seedStandInTransferActivity() {
        try {
            java.util.List<Team> humanTeams = teamRepository.findClubTeamsForOperations().stream()
                    .filter(t -> t.getId() != null)
                    .filter(Team::isHumanControlled)
                    .toList();
            transferActivitySeeder.seedIfMarketIsEmpty(humanTeams);
        } catch (RuntimeException e) {
            log.warn("Could not seed stand-in transfer activity: {}", e.getMessage());
        }
    }

    public void seedOwnerAfterReset() {
        Team ownerTeam = createOwnerUserIfNotExists();
        createSecondUserIfNotExists();
        seedInitialJuniorsForOwnerIfMissing(ownerTeam);
        seedStandInTransferActivity();
        assignSquadNumbersIfMissing();
    }

    private void assignSquadNumbersIfMissing() {
        teamRepository.findAll().forEach(squadNumberAssigner::assignMissingNumbers);
    }

    private Team createOwnerUserIfNotExists() {
        Team ownerTeam;
        Optional<User> existingOwner = userRepository.findByUsernameOrEmail(OWNER_EMAIL);
        if (existingOwner.isEmpty()) {
            User owner = new User();

            // Pronađi Omladinac (pretpostavljam da postoji nakon inicijalizacije)
            Team omladinac = teamRepository.findByName("OFK Omladinac")
                    .orElseGet(() -> {
                        log.warn("Omladinac nije pronađen – kreira se placeholder tim");
                        Team temp = teamFactory.findOrCreate("OFK Omladinac");
                        teamRepository.save(temp);
                        return temp;
                    });

            omladinac.setHumanControlled(true);
            teamRepository.save(omladinac);
            applyClubIdentity(omladinac);
            applyOwnerIdentity(owner, omladinac);
            owner.setPassword(encoder.encode("A12345!"));
            owner.setDisplayName(OWNER_DISPLAY_NAME);
            // A paid subscription, held separately from the role. OWNER would already see everything
            // through the role bypass, so this field is what the profile reports as PLUS -- and it is
            // the difference between "may see talent" and "paid for talent".
            owner.setPlusSubscription(true);
            owner.setRole(UserRole.OWNER);
            owner.setCountryCode(SEEDED_COUNTRY_CODE);
            userRepository.save(owner);
            ownerTeam = omladinac;

            log.info("Kreiran Owner korisnik 'velibor' sa timom OFK Omladinac (ID: {})", omladinac.getId());
        } else {
            log.info("Owner korisnik 'velibor' već postoji – preskačem kreiranje.");
            User owner = existingOwner.orElseThrow();
            ownerTeam = teamRepository.findByName("OFK Omladinac")
                    .orElseGet(() -> teamFactory.findOrCreate("OFK Omladinac"));
            ownerTeam.setHumanControlled(true);
            teamRepository.save(ownerTeam);
            applyClubIdentity(ownerTeam);
            applyOwnerIdentity(owner, ownerTeam);
            if (owner.getRole() == null) {
                owner.setRole(UserRole.OWNER);
            }
            userRepository.save(owner);
        }
        return ownerTeam;
    }

    /**
     * Creates the second human manager and points him at Sremac Berkasovo.
     *
     * <p>Structurally a twin of {@link #createOwnerUserIfNotExists()}, which is the point: the game
     * has no other way to own a club, and until {@code RegistrationService} is actually wired to a
     * controller this is the only path that produces one.
     *
     * <p><b>The user is linked to the club by name, not by id.</b> {@code User} has no foreign key
     * to the football {@code Team}; it holds a {@code CTeam} and {@code /auth/me} resolves the
     * football club by matching that name. So the CTeam and the Team must be created with
     * <b>identical</b> names or the account logs in and manages nothing. That is the single easiest
     * way to get this silently wrong, which is why both names come from the same constant.
     *
     * <p>Idempotent: a boot on an existing database finds the account and re-asserts the link rather
     * than making a second one.
     */
    /**
     * Re-asserts the badge, ground and human flag on the two human clubs.
     *
     * <p>Exists because {@code createSecondUserIfNotExists} is the only thing that ran on an
     * already-seeded database, and the badges are set by the owner path instead. So a database
     * seeded <b>before</b> the badge became a column kept playing with a blank crest: the identity
     * work only ever happened on a fresh install, which is the same "it only works if you reset"
     * shape that has bitten this file more than once.
     *
     * <p>Idempotent and cheap — two lookups and a field write each.
     */
    /**
     * Makes sure the Šid municipal league exists and is full, on a database that already has data.
     *
     * <p>This is the same "only works if you reset" problem as the country backfill, one level up. A
     * database seeded before the Šid league existed takes the early-return branch on every boot, so
     * the second manager's club was created <b>in no league at all</b> — the club existed, the
     * account worked, and the league view had nothing to show. Everything here is find-or-create, so
     * running it on an already-correct database changes nothing.
     */
    @Transactional
    public void ensureSidLeague() {
        Optional<Competition> existing =
                competitionRepository.findByNameAndCountryIsoCode(MUNICIPAL_SID_LEAGUE, "SRB");
        if (existing.isPresent() && entriesFor(existing.get()) >= 10) {
            return;
        }
        Country serbia = countryRepository.findByIsoCode("SRB").orElse(null);
        Season season = seasonRepository.findAll().stream().findFirst().orElse(null);
        if (serbia == null || season == null) {
            log.warn("Opštinska liga Šid cannot be created yet: no Serbia or no season");
            return;
        }
        Competition league = existing.orElseGet(() ->
                createLeagueIfNotExists(serbia, 5, MUNICIPAL_SID_LEAGUE, 16, 10, season));
        int filled = populateLeagueWithTeams(league, 10, false, season, MUNICIPAL_SID_CLUBS);
        log.info("Opštinska liga Šid ensured: {} club(s) in the division", filled);
    }

    private int entriesFor(Competition league) {
        return seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, seasonRepository.findAll().stream()
                        .findFirst().map(Season::getSeasonYear).orElse(0))
                .map(sc -> (int) competitionEntryRepository.countBySeasonCompetition(sc))
                .orElse(0);
    }

    /**
     * Gives every club in the text-manager a country if it has none.
     *
     * <p>A backfill, not a fix-on-create, because the clubs that exist today were created before
     * the country was set and would keep their empty country forever otherwise — the manager sees
     * "Country data is not available for this manager yet" and there is no way for them to fix it.
     * That is the same "only works if you reset the database" shape this file keeps producing.
     *
     * <p>All of them are Serbian: the pyramid this seeds is the Serbian one, and a club with no
     * country is a club whose manager cannot see their own country, so defaulting is the correct
     * answer rather than leaving the field null.
     *
     * @return how many clubs were given a country
     */
    @Transactional
    public int backfillClubCountries() {
        var serbia = serbiaForTextManager();
        List<org.example.footballtextmanager.model.CTeam> orphans =
                csTeamRepository.findAll().stream()
                        .filter(ct -> ct != null && ct.getCsCountry() == null)
                        .toList();
        orphans.forEach(ct -> ct.setCsCountry(serbia));
        if (!orphans.isEmpty()) {
            csTeamRepository.saveAll(orphans);
            log.info("Gave {} club(s) a country — they had none, so their manager had no country page",
                    orphans.size());
        }
        return orphans.size();
    }

    /**
     * Gives every ground an expansion ceiling, if it has none.
     *
     * <p>The tier seeding sets this for new clubs, but it deliberately skips any club whose budget
     * has already been set, so every existing ground in the database would keep no ceiling and could
     * be expanded forever. 1.6x its current size, which is roughly the ratio the seeder uses.
     */
    @Transactional
    public int backfillStadiumCeilings() {
        List<Stadium> grounds = stadiumRepository.findAll();
        List<Stadium> changed = grounds.stream()
                .filter(st -> st != null && st.getExpandableTo() == null
                        && st.getCapacity() != null && st.getCapacity() > 0)
                .toList();
        for (Stadium st : changed) {
            st.setExpandableTo((int) Math.round(st.getCapacity() * 1.6));
        }
        if (!changed.isEmpty()) {
            stadiumRepository.saveAll(changed);
            log.info("Gave {} ground(s) an expansion ceiling — they could otherwise grow forever",
                    changed.size());
        }
        return changed.size();
    }

    @Transactional
    private void refreshClubIdentities() {
        for (String name : List.of("OFK Omladinac", SREMAC_TEAM_NAME)) {
            teamRepository.findByName(name)
                    .ifPresent(this::applyClubIdentity);
        }
    }

    /**
     * Keeps the two seeded managers' display names and subscriptions authoritative (owner, 2026-09-27).
     *
     * <p><b>This is a repair, not a seed.</b> The creation blocks above only run when the account does
     * not exist, so an existing save would keep a null display name forever and would never pick up a
     * change of subscription. A paid account that the profile still shows as unsubscribed is worse
     * than one that was never set up, because it looks like a bug in the checkout.
     *
     * <p>Written as an unconditional set rather than a null check, because "velibor has PLUS" is a
     * <i>state</i> that can change after the row was created, not a default to fill in once.
     */
    private void applyManagerIdentities() {
        // Both seeded managers play in Serbia (owner, 2026-09-28). Stated explicitly on every boot
        // rather than left null and derived from the club, because the whole country-agnostic system
        // reads this column - a null here would silently fall back to the legacy club-derived path and
        // make the two accounts behave differently from a newly registered one.
        userRepository.findByUsernameOrEmail(OWNER_EMAIL).ifPresent(owner -> {
            owner.setDisplayName(OWNER_DISPLAY_NAME);
            owner.setPlusSubscription(true);
            owner.setCountryCode(SEEDED_COUNTRY_CODE);
            userRepository.save(owner);
        });
        userRepository.findByUsernameOrEmail(SECOND_EMAIL).ifPresent(user -> {
            user.setDisplayName(SECOND_DISPLAY_NAME);
            user.setPlusSubscription(false);
            user.setCountryCode(SEEDED_COUNTRY_CODE);
            userRepository.save(user);
        });
    }

    /**
     * Gives every pre-existing junior an arrival age (Sprint 5.2).
     *
     * <p>A junior's talent report narrows against how long the club has been watching him, and that
     * needs an arrival age. The column did not exist when the academies in existing saves were
     * created, so every legacy row would report the maximum possible uncertainty <b>forever</b> — a
     * permanently vague report is not honesty, it is a missing field.
     *
     * <p>Backfilled to {@link org.example.footballmanager.newLogic.service.YouthAcademyService#GRADUATION_MIN_AGE}
     * deliberately, and the reason is worth stating. The true arrival age is not recoverable: a
     * nineteen-year-old might have arrived at fifteen or last season. Of the two possible fallbacks,
     * one over-claims and one under-claims:
     *
     * <ul>
     *   <li>leaving it null keeps the report frozen at ±4 — safe, and useless forever;</li>
     *   <li>assuming a <i>long</i> observation window (arrived at 15, graduates at 20) narrows at the
     *       slowest rate the 15-20 window allows, so it never tells a manager he knows more than he
     *       could. A late arrival gains a slightly tighter band a season early; that is the cheaper
     *       of the two mistakes.</li>
     * </ul>
     */
    private void backfillJuniorArrivalAges() {
        List<Junior> legacy = juniorRepository.findByArrivalAgeIsNull();
        if (legacy.isEmpty()) return;
        for (Junior junior : legacy) {
            junior.setArrivalAge(YouthAcademyService.GRADUATION_MIN_AGE);
        }
        juniorRepository.saveAll(legacy);
        log.info("Backfilled arrival age for {} pre-existing academy juniors (Sprint 5.2 talent reports).",
                legacy.size());
    }

    private Team createSecondUserIfNotExists() {
        Team sremac = teamFactory.findOrCreate(SREMAC_TEAM_NAME);
        applyClubIdentity(sremac);

        Optional<User> existing = userRepository.findByUsernameOrEmail(SECOND_EMAIL);
        if (existing.isEmpty()) {
            User user = new User();
            user.setEmail(SECOND_EMAIL);
            user.setUsername(SECOND_EMAIL);
            user.setDisplayName(SECOND_DISPLAY_NAME);
            // No subscription: kecko is the account that must see the paywall, so the talent reports
            // come back empty for him. That is the only way to test that the gate holds.
            user.setPlusSubscription(false);
            user.setPassword(encoder.encode(SECOND_PASSWORD));
            // REGULAR, not ADMIN: he manages a club and reaches nothing under /admin/**.
            user.setRole(UserRole.REGULAR);
            user.setCTeam(csTeamNamed(SREMAC_TEAM_NAME));
            user.setTifoCTeam(csTeamNamed(SREMAC_TEAM_NAME));
            // The club by id as well as by name (P2-20 Phase 1). Without it this account is one of
            // the rows "Repair club links" exists for, and a fresh install ships with a seeded
            // manager whose profile has no club.
            user.setFootballTeam(sremac);
            user.setCountryCode(SEEDED_COUNTRY_CODE);
            userRepository.save(user);
            log.info("Kreiran korisnik '{}' sa timom {}", SECOND_EMAIL, SREMAC_TEAM_NAME);
        } else {
            User user = existing.get();
            user.setCTeam(csTeamNamed(SREMAC_TEAM_NAME));
            user.setTifoCTeam(csTeamNamed(SREMAC_TEAM_NAME));
            // Same reason as the create branch above, and this is the branch that runs on an existing
            // database: the owner's row came back with a null football_team_id for exactly this
            // reason — a method that rewrites the account without mentioning the key.
            user.setFootballTeam(sremac);
            user.setCountryCode(SEEDED_COUNTRY_CODE);
            if (user.getRole() == null) {
                user.setRole(UserRole.REGULAR);
            }
            userRepository.save(user);
        }
        return sremac;
    }

    /**
     * The text-mode club record for a name, created on demand.
     *
     * <p>Found rather than passed in so the two sides cannot disagree: the CTeam name is derived from
     * the football Team name, which is the only thing the name-based lookup in {@code /auth/me}
     * actually compares.
     */
    private org.example.footballtextmanager.model.CTeam csTeamNamed(String teamName) {
        return csTeamRepository.findByName(teamName)
                .orElseGet(() -> {
                    org.example.footballtextmanager.model.CTeam cs = new org.example.footballtextmanager.model.CTeam();
                    cs.setName(teamName);
                    // The country is not optional. A manager without one has no country page, no
                    // flag in the menu, and no competitions to look at — the club exists and the
                    // world around it does not. Set here rather than by a separate backfill so a
                    // club is never briefly in that state.
                    cs.setCsCountry(serbiaForTextManager());
                    return csTeamRepository.save(cs);
                });
    }

    /**
     * The Serbian record in the text-manager's own country table.
     *
     * <p>There are <b>two</b> country tables in this project — {@code newLogic.model.Country} and
     * {@code footballtextmanager.model.CSCountry} — and the user-facing country page reads the
     * second one while the league endpoints read the first. A club belongs to both, and the two are
     * not kept in step by anything except this kind of explicit call.
     *
     * <p>Found or created, because the text-manager seeder early-returns on an existing database and
     * so may never have created Serbia at all on an install that was already seeded.
     */
    private org.example.footballtextmanager.model.CSCountry serbiaForTextManager() {
        return csCountryRepository.findByIsoCodeIgnoreCase("SRB")
                .orElseGet(() -> {
                    org.example.footballtextmanager.model.CSCountry c =
                            new org.example.footballtextmanager.model.CSCountry();
                    c.setName("Serbia");
                    c.setIsoCode("SRB");
                    c.setFlagImagePath("/images/serbiaflag.png");
                    c.setCurrencyCode("RSD");
                    c.setReputation(55);
                    c.setYouthRating(65);
                    return csCountryRepository.save(c);
                });
    }

    void applyOwnerIdentity(User owner, Team ownerTeam) {
        owner.setEmail(OWNER_EMAIL);
        owner.setUsername(OWNER_EMAIL);
        org.example.footballtextmanager.model.CTeam csTeam = csTeamRepository.findByName("OFK Omladinac")
                .orElseGet(() -> {
                    org.example.footballtextmanager.model.CTeam ct = new org.example.footballtextmanager.model.CTeam();
                    ct.setName("OFK Omladinac");
                    ct.setCsCountry(serbiaForTextManager());
                    return csTeamRepository.save(ct);
                });
        owner.setCTeam(csTeam);
        owner.setTifoCTeam(csTeam);
        // The newLogic club by id, which is what every reader now uses (P2-20 Phase 1).
        //
        // <p>Set here because this method runs on a path that <b>wrote the owner back</b>: it is called
        // from both branches of the owner setup, including the "already exists" one. Without this the
        // column depended on somebody having run the repair by hand after the column was added —
        // which is exactly what happened: the owner's row came back with a null
        // {@code football_team_id} on the first Phase 2 start, while the seeded second manager's was
        // fine because {@code createSecondUserIfNotExists} set it differently.
        owner.setFootballTeam(ownerTeam);
    }

    private List<TacticsProfileSnapshot> snapshotTacticsProfiles() {
        Map<String, TacticsProfileSnapshot> merged = new LinkedHashMap<>();

        teamTacticsProfileRepository.findAll().stream()
                .map(profile -> {
                    Team team = profile.getTeam();
                    if (team == null || team.getName() == null || team.getName().isBlank()) {
                        return null;
                    }
                    TacticsProfileSnapshot snapshot = new TacticsProfileSnapshot();
                    snapshot.teamName = team.getName();
                    snapshot.formation = profile.getFormation();
                    snapshot.style = profile.getStyle();
                    snapshot.rulesJson = profile.getRulesJson();
                    snapshot.setPiecesJson = profile.getSetPiecesJson();
                    snapshot.version = profile.getVersion();
                    return snapshot;
                })
                .filter(Objects::nonNull)
                .forEach(snapshot -> merged.put(snapshot.teamName, snapshot));

        tacticsProfileBackupService.loadAll().stream()
                .map(this::toTacticsSnapshot)
                .filter(Objects::nonNull)
                .forEach(snapshot -> {
                    TacticsProfileSnapshot existing = merged.get(snapshot.teamName);
                    boolean shouldReplace = existing == null
                            || (snapshot.rulesJson != null && snapshot.rulesJson.length() > 10
                                && (existing.rulesJson == null || existing.rulesJson.length() <= 10))
                            || (snapshot.version != null && existing.version != null && snapshot.version > existing.version);
                    if (shouldReplace) {
                        merged.put(snapshot.teamName, snapshot);
                    }
                });

        return new ArrayList<>(merged.values());
    }

    /**
     * Restores tactical editor profiles, from the database snapshot <b>and</b> from the backup file.
     *
     * <p><b>The file was the missing half.</b> The snapshot is taken from
     * {@code team_tactics_profile}, so on a world where that table is already empty — which is what a
     * previous reset leaves behind — the snapshot is empty too and the restore had nothing to give.
     * Meanwhile {@code TacticsProfileBackupService} has written {@code var/tactics-editor-profiles.json}
     * on <em>every</em> editor save since it existed, and {@code loadAll()} had <b>zero callers</b>. The
     * owner's five profiles were in that file and nowhere else, so one Reset destroyed them permanently
     * and nothing said so: the log line read "Restored 0 tactics editor profiles after reset."
     *
     * <p>So both sources are read now, and the database wins where both have the club — it is the
     * authoritative copy and the file is the durable one.
     */
    private void restoreTacticsProfiles(List<TacticsProfileSnapshot> snapshots) {
        restoreTacticsProfiles(snapshots, tacticsProfileBackupService);
    }

    /**
     * The restore, with the backup service supplied.
     *
     * <p>Package-private rather than private so {@code TacticsProfileRestoreTest} can point the backup at
     * a temporary file. The production path passes the injected service and is unchanged; a test that
     * exercised this against {@code var/tactics-editor-profiles.json} would be reading and rewriting the
     * repository's own state to prove something about a method.
     */
    void restoreTacticsProfiles(List<TacticsProfileSnapshot> snapshots,
                                TacticsProfileBackupService backupService) {
        List<TacticsProfileSnapshot> wanted = new ArrayList<>(
                snapshots == null ? List.of() : snapshots);
        java.util.Set<String> alreadyNamed = new java.util.HashSet<>();
        wanted.forEach(snapshot -> alreadyNamed.add(snapshot.teamName));
        for (TacticsProfileBackupEntry entry : backupService.loadAll()) {
            if (entry != null && entry.getTeamName() != null && !alreadyNamed.contains(entry.getTeamName())) {
                TacticsProfileSnapshot fromFile = toTacticsSnapshot(entry);
                if (fromFile != null) {
                    wanted.add(fromFile);
                    alreadyNamed.add(fromFile.teamName);
                }
            }
        }

        if (wanted.isEmpty()) {
            return;
        }

        int restored = 0;
        List<String> unmatched = new ArrayList<>();
        for (TacticsProfileSnapshot snapshot : wanted) {
            Team team = teamRepository.findByName(snapshot.teamName).orElse(null);
            if (team == null || team.getId() == null) {
                // Named rather than skipped. The old `continue` made a profile that could not be placed
                // indistinguishable from there being none, which is how four of the owner's five
                // profiles could vanish without a word — the world holds five different Beograd clubs
                // and no "FK Beograd", so the name simply does not resolve.
                unmatched.add(snapshot.teamName);
                continue;
            }
            TeamTacticsProfile profile = teamTacticsProfileRepository.findByTeamId(team.getId()).orElseGet(TeamTacticsProfile::new);
            profile.setTeam(team);
            profile.setFormation(snapshot.formation);
            profile.setStyle(snapshot.style);
            profile.setRulesJson(snapshot.rulesJson);
            profile.setSetPiecesJson(snapshot.setPiecesJson);
            profile.setVersion(snapshot.version != null ? snapshot.version : 1L);
            profile.setUpdatedAt(java.time.LocalDateTime.now());
            teamTacticsProfileRepository.save(profile);
            restored++;
        }
        log.info("Restored {} tactics editor profiles after reset.", restored);
        if (!unmatched.isEmpty()) {
            log.warn("{} tactical profile(s) could not be placed because no club has that exact name: "
                            + "{}. The world holds several clubs with similar names, and which one was "
                            + "meant is a decision for the owner — the profile is still in "
                            + "var/tactics-editor-profiles.json.",
                    unmatched.size(), unmatched);
        }
    }

    private TacticsProfileSnapshot toTacticsSnapshot(TacticsProfileBackupEntry entry) {
        if (entry == null || entry.getTeamName() == null || entry.getTeamName().isBlank()) {
            return null;
        }
        TacticsProfileSnapshot snapshot = new TacticsProfileSnapshot();
        snapshot.teamName = entry.getTeamName();
        snapshot.formation = entry.getFormation();
        snapshot.style = entry.getStyle();
        snapshot.rulesJson = entry.getRulesJson();
        snapshot.setPiecesJson = entry.getSetPiecesJson();
        snapshot.version = entry.getVersion();
        return snapshot;
    }

    private void seedInitialJuniorsForOwnerIfMissing(Team ownerTeam) {
        if (ownerTeam == null || ownerTeam.getId() == null) return;
        int seasonNumber = seasonService.getOrCreateClock().getCurrentSeason();
        youthAcademyService.seedInitialJuniorsForTeam(ownerTeam.getId(), seasonNumber);
    }

    @Transactional
    /**
     * The Serbian structure on its own: the catalogue, the pyramid, the schedule, the squads and the
     * owner.
     *
     * <p>What "Initialise" now means. It used to mean "build the entire world", which put the country
     * catalogue, forty-six simulated nations, the national sides and the cup draw behind one button
     * whose label said none of that.
     */
    public void buildSerbianStructure() {
        seedWorldBeforePyramid();
        initSerbianFootballStructure();
        ensureSidLeague();
        backfillClubCountries();
        backfillStadiumCeilings();
        Team ownerTeam = createOwnerUserIfNotExists();
        createSecondUserIfNotExists();
        // The manager stands in as selector until the elections run.
        //
        // This was in the boot path, and the boot path no longer seeds anything. So after
        // Initialise DB nobody was appointed, and the national-team page opened for the owner with
        // "you are not the selector" on a side that had no selector at all. The owner reported it as
        // the page not opening, which is close enough — the page opened and was useless.
        //
        // It belongs here, next to the owner it appoints.
        try {
            nationalTeamService.appointBaselineSelectors(
                    countryRepository.findByIsoCode("SRB").orElse(null));
        } catch (RuntimeException e) {
            log.warn("Could not appoint the baseline selector: {}", e.getMessage());
        }
        seedInitialJuniorsForOwnerIfMissing(ownerTeam);
        seedStandInTransferActivity();
        assignSquadNumbersIfMissing();
    }

    public void initSerbianFootballStructure() {
        // No country is created here any more (owner, 2026-09-29).
        //
        // This used to create nine, with the old ISO codes and Serbian names - HRV, DEU and GBR among
        // them. Those codes are not in the catalogue, so a reset put nine legacy countries into the
        // world and the catalogue then added its own 48 on top: 51 countries, 102 national sides and
        // two rows each for Croatia, England and Germany. The catalogue is the only place countries
        // come from, and seedWorldBeforePyramid() runs it before this method.

        // Self-heals rather than throwing.
        //
        // This was `orElseThrow()`, and a single missing country row took the whole bootstrap with
        // it: every league below is created from `serbia`, so the exception escaped before the first
        // one existed. The catch in the caller logged it and returned normally, so the admin job
        // reported "completed successfully" on a database with no leagues in it at all — which is
        // exactly the report that sent us looking here.
        //
        // The catalogue is the only source of countries, so re-running it is the correct repair
        // rather than a guess, and it is idempotent.
        Country serbia = countryRepository.findByIsoCode("SRB").orElseGet(() -> {
            log.warn("Serbia is missing from the world; re-seeding the country catalogue before "
                    + "building the pyramid.");
            seedWorldBeforePyramid();
            return countryRepository.findByIsoCode("SRB").orElseThrow(
                    () -> new IllegalStateException(
                            "The country catalogue did not produce Serbia, so no league can be built. "
                                    + "This is a seeding failure, not a missing club."));
        });

        // 2. The current season. A season is a number counted from 1 - twelve weeks, four to a year.
        Season currentSeason = createSeasonIfNotExists(1, "Season 1");

        // 3. Kreiraj lige ako ne postoje
        Competition tier1 = createLeagueIfNotExists(serbia, 1, "Superliga Srbije", 1, 10, currentSeason);
        createLeagueIfNotExists(serbia, 2, "Prva liga Srbije Grupa A", 1, 10, currentSeason);
        createLeagueIfNotExists(serbia, 2, "Prva liga Srbije Grupa B", 2, 10, currentSeason);

        // Tier 3 – 4 lige
        for (int i = 1; i <= 4; i++) {
            createLeagueIfNotExists(serbia, 3, "Srpska liga Grupa " + (char)('A' + i - 1), i, 10, currentSeason);
        }

        // Tier 4 – 8 liga
        for (int i = 1; i <= 8; i++) {
            createLeagueIfNotExists(serbia, 4, "Okružna liga Grupa " + i, i, 10, currentSeason);
        }

        // Tier 5 – 16 liga. The sixteenth one is a real place with real clubs in it rather than a
        // sixteenth placeholder: Opštinska liga Šid is where the second human manager starts, and it
        // is the bottom of the pyramid, so it is the only league a new player ever actually sees.
        for (int i = 1; i <= 16; i++) {
            String name = (i == 16) ? MUNICIPAL_SID_LEAGUE : "Opštinska liga Grupa " + i;
            createLeagueIfNotExists(serbia, 5, name, i, 10, currentSeason);
        }

        // 4. Popuni timove u Tier 1 (Superliga) – obavezno Omladinac + 9 random/stvarnih
        populateLeagueWithTeams(tier1, 10, true, currentSeason);

        // Ostale lige popuni random timovima. The Šid league gets its real clubs first and one random
        // club to make ten, which is what the rest of the pyramid does for the remainder.
        //
        // **The null check is load-bearing.** This used to be `c.getCountry().getIsoCode()`, which threw
        // on any competition with no country - and the four national-team competitions
        // (`NationalTeamCompetitions`) are exactly that: an international tournament has no country
        // behind it. So once the NT competitions existed, Initialize DB died here with an NPE, halfway
        // through building a pyramid, and the admin panel reported "Database job 'initialize' failed".
        // Twelve lines below, the same file already asks `c.getCountry() != null` for the same reason.
        competitionRepository.findAll().stream()
                .filter(c -> c.getCountry() != null && "SRB".equals(c.getCountry().getIsoCode()))
                .filter(c -> c.getTier() != null && c.getTier() > 1)
                .forEach(league -> populateLeagueWithTeams(league, 10, false, currentSeason,
                        MUNICIPAL_SID_LEAGUE.equals(league.getName())
                                ? MUNICIPAL_SID_CLUBS : null));

        // 5. Dodaj PromotionRule za lige
        addPromotionRulesForLeagues(currentSeason);

        // 6. Dodaj Kup Srbije (nacionalni kup)
        createCupCompetitionIfNotExists(serbia, "Kup Srbije", 64, currentSeason);

        // 7. Generate double round-robin fixtures for all Serbian leagues, on the current season
        int seasonYear = currentSeasonYear();
        competitionRepository.findAll().stream()
                .filter(c -> c.getCountry() != null && "SRB".equals(c.getCountry().getIsoCode()))
                .filter(c -> c.getType() == CompetitionType.LEAGUE)
                .forEach(league -> seasonService.ensureDoubleRoundRobinSchedule(league, seasonYear));
    }

    private Country createCountryIfNotExists(String name, String isoCode, int reputation, int youthRating) {
        return countryRepository.findByIsoCode(isoCode)
                .map(existing -> {
                    if ((existing.getFlagImagePath() == null || existing.getFlagImagePath().isBlank())
                            && "SRB".equalsIgnoreCase(isoCode)) {
                        existing.setFlagImagePath(resolveCountryFlagImagePath(isoCode));
                        return countryRepository.save(existing);
                    }
                    return existing;
                })
                .orElseGet(() -> {
                    Country c = new Country();
                    c.setName(name);
                    c.setIsoCode(isoCode);
                    c.setFlagImagePath(resolveCountryFlagImagePath(isoCode));
                    c.setCurrencyCode(isoCode.equals("SRB") ? "RSD" : "EUR");
                    c.setReputation(reputation);
                    c.setYouthRating(youthRating);
                    return countryRepository.save(c);
                });
    }

    private String resolveCountryFlagImagePath(String isoCode) {
        return "SRB".equalsIgnoreCase(String.valueOf(isoCode)) ? "/images/serbiaflag.png" : null;
    }

    private Season createSeasonIfNotExists(int year, String description) {
        return seasonRepository.findBySeasonYear(year)
                .orElseGet(() -> {
                    Season s = new Season();
                    s.setSeasonYear(year);
                    s.setDescription(description);
                    return seasonRepository.save(s);
                });
    }

    private Competition createLeagueIfNotExists(Country country, int tier, String name, int divisionLevel, int teamsCount, Season season) {
        Optional<Competition> existing = competitionRepository.findByNameAndCountryIsoCode(name, country.getIsoCode());
        if (existing.isPresent()) {
            return existing.get();
        }

        Competition comp = new Competition();
        comp.setName(name);
        comp.setType(CompetitionType.LEAGUE);
        comp.setScope(CompetitionScope.NATIONAL);
        comp.setTeamType(CompetitionTeamType.CLUB);
        comp.setCountry(country);
        comp.setTier(tier);
        comp.setDivisionLevel(divisionLevel);
        comp.setTeamsPerCompetition(teamsCount);
        comp.setReputationWeight(tier * 20);
        competitionRepository.save(comp);

        createSeasonCompetitionIfNotExists(comp, season);
        return comp;
    }

    private SeasonCompetition createSeasonCompetitionIfNotExists(Competition competition, Season season) {
        SeasonCompetition sc = new SeasonCompetition();
        sc.setSeasonYear(season.getSeasonYear());
        sc.setCompetition(competition);
        sc.setFinished(false);
        return seasonCompetitionRepository.save(sc);
    }

    /**
     * Team ids already placed in ANY league during this seeding run.
     *
     * <p>This is deliberately global, not per-league. It used to be rebuilt for every league, which
     * combined with name-based team creation to put one club in two leagues: League A took a random
     * name, League B drew the same name, {@code findOrCreate} returned the <em>same</em> Team, and
     * {@code addTeamToLeague} then reassigned its competition. A club has exactly one division, and
     * two clubs are allowed to share a name, so identity here has to be the id.
     */
    private final Set<Long> teamIdsAssignedToALeague = new HashSet<>();

    private int populateLeagueWithTeams(Competition league, int teamCount, boolean includeOmladinac,
                                        Season season) {
        return populateLeagueWithTeams(league, teamCount, includeOmladinac, season, null);
    }

    /**
     * Fills a division, preferring real club names where the league has them.
     *
     * @param preferredNames clubs this league really contains, or null to use generated names
     *                       throughout. Only as many as are still missing are taken, so a league that
     *                       is already half full is topped up with generated clubs rather than
     *                       double-seeding the real ones.
     */
    private int populateLeagueWithTeams(Competition league, int teamCount, boolean includeOmladinac,
                                        Season season, List<String> preferredNames) {
        SeasonCompetition sc = seasonCompetitionRepository.findByCompetitionAndSeasonYear(league, season.getSeasonYear())
                .orElseThrow(() -> new RuntimeException("Sezona za ligu nije pronađena"));

        long currentTeams = competitionEntryRepository.countBySeasonCompetition(sc);
        int toCreate = teamCount - (int) currentTeams;

        if (toCreate <= 0) {
            log.info("Liga {} već ima {} timova → preskačem", league.getName(), currentTeams);
            return (int) currentTeams;
        }

        Set<Long> usedTeamIdsInLeague = competitionEntryRepository.findBySeasonCompetition(sc)
                .stream()
                .map(entry -> entry.getTeam().getId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        int attempts = 0;
        int added = 0;
        final int maxAttempts = 2000;

        // 1. Dodaj Omladinac ako treba i ako ga nema
        if (includeOmladinac) {
            Team omladinac = teamFactory.findOrCreate("OFK Omladinac");
            if (omladinac.getId() != null
                    && !usedTeamIdsInLeague.contains(omladinac.getId())
                    && !teamIdsAssignedToALeague.contains(omladinac.getId())) {
                addTeamToLeague(omladinac, sc);
                usedTeamIdsInLeague.add(omladinac.getId());
                teamIdsAssignedToALeague.add(omladinac.getId());
                economyProfile.apply(omladinac, league);
                toCreate--;
                log.info("Dodat Omladinac u ligu: {}", league.getName());
            }
        }

        // 2. Real clubs first. This is the difference between the Šid league being Šid and being
        //    sixteen generic names that happen to sit in a division.
        if (preferredNames != null) {
            for (String realName : preferredNames) {
                if (toCreate <= 0) break;
                Team real = teamFactory.findOrCreate(realName);
                if (real.getId() == null
                        || usedTeamIdsInLeague.contains(real.getId())
                        || teamIdsAssignedToALeague.contains(real.getId())) {
                    continue;
                }
                if (competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, real).isEmpty()) {
                    addTeamToLeague(real, sc);
                    usedTeamIdsInLeague.add(real.getId());
                    teamIdsAssignedToALeague.add(real.getId());
                    economyProfile.apply(real, league);
                    toCreate--;
                    log.info("Dodat stvarni tim {} u ligu {}", realName, league.getName());
                }
            }
        }

        // 3. Generated names fill whatever is left, which is one club in the Šid league.
        while (toCreate > 0 && attempts < maxAttempts) {
            String candidateName = getRandomTeamName();
            Team team = teamFactory.findOrCreate(candidateName);
            if (team.getId() == null
                    || usedTeamIdsInLeague.contains(team.getId())
                    // The real fix for the two-leagues bug: a name collision in this league must
                    // skip, not re-add, the club another league already owns.
                    || teamIdsAssignedToALeague.contains(team.getId())) {
                attempts++;
                continue;
            }
            if (competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, team).isEmpty()) {
                addTeamToLeague(team, sc);
                usedTeamIdsInLeague.add(team.getId());
                teamIdsAssignedToALeague.add(team.getId());
                economyProfile.apply(team, league);
                added++;
                toCreate--;
                log.info("Dodat tim {} u ligu {}", candidateName, league.getName());
            }
            attempts++;
        }

        if (toCreate > 0) {
            log.warn("Nisam uspeo da popunim ligu {} sa {} timova – ostalo je {} da se doda, pokušavam fallback imena",
                    league.getName(), teamCount, toCreate);
            // fallback deterministic names to guarantee 10 teams
            int fallbackIdx = 1;
            while (toCreate > 0) {
                String fallbackName = "FK Fallback " + league.getName() + " " + fallbackIdx++;
                Team team = teamFactory.findOrCreate(fallbackName);
                if (team.getId() != null && !usedTeamIdsInLeague.contains(team.getId())
                        && competitionEntryRepository.findBySeasonCompetitionAndTeam(sc, team).isEmpty()) {
                    addTeamToLeague(team, sc);
                    usedTeamIdsInLeague.add(team.getId());
                    added++;
                    toCreate--;
                }
            }
        }
        return added;
    }

    private void addTeamToLeague(Team team, SeasonCompetition sc) {
        // Defensive: a club belongs to one division. If it is somehow already in another, leave it
        // there rather than silently moving it - moving it is what used to corrupt the league table.
        Competition current = team.getCompetition();
        if (current != null && !current.getId().equals(sc.getCompetition().getId())) {
            log.warn("Preskakujem {}: vec je u ligi {}, ne {}", team.getName(), current.getName(),
                    sc.getCompetition().getName());
            return;
        }
        team.setCompetition(sc.getCompetition());
        if (team.getCountry() == null) {
            team.setCountry(sc.getCompetition().getCountry());
        }
        teamRepository.save(team);

        CompetitionEntry entry = new CompetitionEntry();
        entry.setSeasonCompetition(sc);
        entry.setTeam(team);
        entry.setPoints(0);
        entry.setGoalsScored(0);
        entry.setGoalsConceded(0);
        entry.setPosition(0);
        entry.setWins(0);
        entry.setDraws(0);
        entry.setLosses(0);
        competitionEntryRepository.save(entry);

        // Kreiraj igrače ako ih nema
        if (playerRepository.countByTeam(team) == 0) {
            if (Objects.equals(team.getName(), "OFK Omladinac")) {
                playerFactory.createOmladinacPlayers(team);
                applyOmladinacTalentProfile(team);
            } else if (Objects.equals(team.getName(), SREMAC_TEAM_NAME)) {
                playerFactory.createSremacPlayers(team);
            } else {
                playerFactory.createRandomTeamPlayers(team.getName(), team);
            }
        }
        squadNumberAssigner.assignMissingNumbers(team);
    }

    /**
     * Gives the two human clubs their identity: badge, ground, and a manager.
     *
     * <p>Reads {@code Team.stadium}, which is a lazy proxy, so it has to run inside the session the
     * caller opened. See {@link #ensureBaselineDataOnStartup()} — the transaction belongs on the
     * listener, not here.
     *
     * <p>Split out of {@code addTeamToLeague} on purpose. Adding a club to a division and giving it
     * a face are different jobs, and only one of them should run when a league is re-populated — the
     * badge and the ground are facts about the club, not about the season it happens to be in.
     */
    private void applyClubIdentity(Team team) {
        String name = team.getName();
        if (name == null) {
            return;
        }
        if (SREMAC_TEAM_NAME.equals(name)) {
            team.setLogoUrl(SREMAC_LOGO);
            team.setHumanControlled(true);
            Stadium stadium = team.getStadium();
            if (stadium == null) {
                stadium = new Stadium();
                stadium.setTeam(team);
                team.setStadium(stadium);
            }
            stadium.setName(SREMAC_STADIUM);
            // The picture used to be inferred from the name: the fixture view matched "livadice" by
            // substring in a function nothing called, so the artwork was unreachable. The field holds
            // it directly now, and a club that has not uploaded anything falls back to the Dunjareal
            // ground rather than a grey box.
            if (stadium.getImage() == null || stadium.getImage().isBlank()) {
                stadium.setImage(SREMAC_STADIUM_IMAGE);
            }
            stadium.setLocation("Berkasovo");
        } else if ("OFK Omladinac".equals(name)) {
            team.setLogoUrl(OMLADINAC_LOGO);
            team.setHumanControlled(true);
            // Test data, at the owner's request (2026-09-27): Omladinac runs a junior school so the
            // academy, the talent bands and the graduation window can all be exercised immediately.
            //
            // <b>Sremac is deliberately left alone.</b> Its column is new and therefore null, which is
            // "no school", so kecko gets to switch one on and see the week-1 window for himself.
            // Nothing here ever switches a school *off*, so a school a manager really did open
            // survives a restart.
            if (team.getJuniorSchoolActive() == null) {
                team.setJuniorSchoolActive(true);
                team.setJuniorSchoolSinceSeason(seasonRepository.findAll().stream()
                        .map(s -> s.getSeasonYear())
                        .min(Integer::compareTo)
                        .orElse(1));
            }
        }
        teamRepository.save(team);
    }

    private void applyOmladinacTalentProfile(Team team) {
        List<Player> players = playerRepository.findByTeam(team);
        if (players.isEmpty()) return;

        Map<String, Double> fixedTalent = new HashMap<>();
        fixedTalent.put(normalizeName("Ljupče Ožegović"), 10.0);
        fixedTalent.put(normalizeName("Borislav Negovanović"), 9.0);
        fixedTalent.put(normalizeName("Žika Veljković"), 8.0);
        fixedTalent.put(normalizeName("Šumenko Dabić"), 7.0);

        players.forEach(player -> {
            String key = normalizeName(player.getName());
            Double talent = fixedTalent.get(key);
            if (talent == null) {
                talent = 5.0 + random.nextDouble() * 3.0; // 5.0 - 8.0
            }
            player.setTalent(Math.max(1.0, Math.min(10.0, talent)));
            if ("zvezdan vukomanovic".equals(key)) {
                player.setSquadNumber(1);
            }
        });
        playerRepository.saveAll(players);
    }

    private String normalizeName(String name) {
        if (name == null) return "";
        return name.toLowerCase(Locale.ROOT)
                .replace("č", "c")
                .replace("ć", "c")
                .replace("š", "s")
                .replace("ž", "z")
                .replace("đ", "dj");
    }

    private String getRandomTeamName() {
        String[] prefixes = {"FK", "OFK", "RFK", "SK", "TSK", "NK", "ŽFK", "GFK"};
        String[] cities = {
                "Beograd", "Novi Sad", "Niš", "Kragujevac", "Subotica", "Zrenjanin", "Čačak", "Kraljevo",
                "Smederevo", "Leskovac", "Užice", "Valjevo", "Vranje", "Šabac", "Zaječar", "Pančevo",
                "Požarevac", "Surdulica", "Loznica", "Bor", "Prokuplje", "Gornji Milanovac", "Jagodina",
                "Vrbas", "Sombor", "Kikinda", "Pirot", "Kruševac", "Sremska Mitrovica", "Inđija",
                "Ruma", "Aranđelovac", "Paraćin", "Kovin", "Apatin", "Prijepolje", "Pirot", "Negotin",
                "Ćuprija", "Svilajnac", "Bajina Bašta", "Nova Pazova", "Zemun", "Bačka Palanka", "Bečej",
                "Temerin", "Vrnjačka Banja", "Trstenik", "Kladovo", "Ivanjica"
        };
        String[] clubWords = {
                "Radnički", "Metalac", "Sloga", "Mladost", "Napredak", "Jedinstvo", "Budućnost", "Proleter",
                "Borac", "Sloboda", "Rudar", "Železničar", "Dinamo", "Hajduk", "Sinđelić", "Timok",
                "Morava", "Tamiš", "Kolubara", "Javor", "Balkan", "Pobeda", "Bratstvo", "Partizan",
                "Teleoptik", "Grafičar", "Omladinac", "Car Konstantin", "Mlava", "Zlatibor", "Borski", "Podunavac"
        };
        String[] localityExtras = {
                "United", "City", "Sport", "1901", "1912", "1913", "1919", "1923", "1928", "1931", "1945", "1950"
        };

        String prefix = prefixes[random.nextInt(prefixes.length)];
        String city = cities[random.nextInt(cities.length)];
        String clubWord = clubWords[random.nextInt(clubWords.length)];
        String extra = localityExtras[random.nextInt(localityExtras.length)];

        return switch (random.nextInt(5)) {
            case 0 -> prefix + " " + city;
            case 1 -> prefix + " " + clubWord + " " + city;
            case 2 -> prefix + " " + city + " " + extra;
            case 3 -> prefix + " " + clubWord + " " + extra;
            default -> prefix + " " + clubWord + " " + city + " " + extra;
        };
    }

    private void addPromotionRulesForLeagues(Season season) {
        Competition tier1 = competitionRepository.findByNameAndCountryIsoCode("Superliga Srbije", "SRB").orElseThrow();
        addPromotionRule(tier1, RuleType.RELEGATION, 9, 10, null, 2, false);
        addPromotionRule(tier1, RuleType.PLAYOFF, 7, 8, null, 2, true);
        // Dodaj ostale po potrebi
    }

    private void addPromotionRule(Competition competition, RuleType type, int positionFrom, int positionTo,
                                  Competition target, int spots, boolean isPlayoff) {
        PromotionRule rule = new PromotionRule();
        rule.setCompetition(competition);
        rule.setRuleType(type);
        rule.setPositionFrom(positionFrom);
        rule.setPositionTo(positionTo);
        rule.setTargetCompetition(target);
        rule.setIsPlayoff(isPlayoff);
        promotionRuleRepository.save(rule);
    }

    private static class TacticsProfileSnapshot {
        private String teamName;
        private String formation;
        private String style;
        private String rulesJson;
        private String setPiecesJson;
        private Long version;
    }

    private Competition createCupCompetitionIfNotExists(Country country, String name, int teamsCount, Season season) {
        Optional<Competition> existing = competitionRepository.findByNameAndCountryIsoCode(name, country.getIsoCode());
        if (existing.isPresent()) {
            return existing.get();
        }

        Competition cup = new Competition();
        cup.setName(name);
        cup.setType(CompetitionType.CUP);
        cup.setScope(CompetitionScope.NATIONAL);
        cup.setTeamType(CompetitionTeamType.CLUB);
        cup.setCountry(country);
        cup.setTeamsPerCompetition(teamsCount);
        cup.setHasSeeding(true);
        cup.setSeededTeamsCount(teamsCount / 2);
        competitionRepository.save(cup);

        createSeasonCompetitionIfNotExists(cup, season);
        return cup;
    }
}
