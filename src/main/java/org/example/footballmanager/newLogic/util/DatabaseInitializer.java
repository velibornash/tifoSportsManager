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

    /**
     * The second human manager's club and login (owner request 2026-09-27).
     *
     * <p>Kept next to {@link #OWNER_EMAIL} rather than buried in a service, because these two are the
     * only two accounts in the game and they have to be findable from one screen. {@code REGULAR} is
     * deliberate: this account manages a club and has every club tool, but it does not reach
     * {@code /admin/**}, which is reserved for OWNER/ADMIN/DEV.
     */
    private static final String SECOND_EMAIL = "kecko@example.com";
    private static final String SECOND_PASSWORD = "Kecko123!";
    private static final String SREMAC_TEAM_NAME = "Sremac Berkasovo";
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

    @EventListener(ApplicationReadyEvent.class)
    public void sanitizeLegacySchemaOnStartup() {
        resetService.sanitizeLegacyLineupOrderSchema();
        resetService.migrateTickStateMinuteColumn();
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
    @EventListener(ApplicationReadyEvent.class)
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
            createSecondUserIfNotExists();
            return;
        }

        log.warn("Core football data missing on startup. Bootstrapping baseline Serbian pyramid.");
        try {
            initSerbianFootballStructure();
            Team ownerTeam = createOwnerUserIfNotExists();
            createSecondUserIfNotExists();
            seedInitialJuniorsForOwnerIfMissing(ownerTeam);
            assignSquadNumbersIfMissing();
        } catch (Exception e) {
            // The stack trace matters. This catch used to log only e.getMessage() and call it a
            // concurrent reset, which is how a lazy-proxy failure hid in plain sight: the message
            // said "no Session" and the summary said something that had nothing to do with it.
            // A seeding failure that is only visible as a missing club is a failure nobody debugs.
            log.error("Startup initialization failed — the database may be partially seeded", e);
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

        assignSquadNumbersIfMissing();
        log.info("Inicijalizacija završena.");
        progressListener.accept("Database rebuild completed.");
    }

    @Transactional
    public void seedOwnerAfterReset() {
        Team ownerTeam = createOwnerUserIfNotExists();
        createSecondUserIfNotExists();
        seedInitialJuniorsForOwnerIfMissing(ownerTeam);
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
            owner.setRole(UserRole.OWNER);
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
    private Team createSecondUserIfNotExists() {
        Team sremac = teamFactory.findOrCreate(SREMAC_TEAM_NAME);
        applyClubIdentity(sremac);

        Optional<User> existing = userRepository.findByUsernameOrEmail(SECOND_EMAIL);
        if (existing.isEmpty()) {
            User user = new User();
            user.setEmail(SECOND_EMAIL);
            user.setUsername(SECOND_EMAIL);
            user.setPassword(encoder.encode(SECOND_PASSWORD));
            // REGULAR, not ADMIN: he manages a club and reaches nothing under /admin/**.
            user.setRole(UserRole.REGULAR);
            user.setCTeam(csTeamNamed(SREMAC_TEAM_NAME));
            user.setTifoCTeam(csTeamNamed(SREMAC_TEAM_NAME));
            userRepository.save(user);
            log.info("Kreiran korisnik '{}' sa timom {}", SECOND_EMAIL, SREMAC_TEAM_NAME);
        } else {
            User user = existing.get();
            user.setCTeam(csTeamNamed(SREMAC_TEAM_NAME));
            user.setTifoCTeam(csTeamNamed(SREMAC_TEAM_NAME));
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
                    return csTeamRepository.save(cs);
                });
    }

    void applyOwnerIdentity(User owner, Team ownerTeam) {
        owner.setEmail(OWNER_EMAIL);
        owner.setUsername(OWNER_EMAIL);
        org.example.footballtextmanager.model.CTeam csTeam = csTeamRepository.findByName("OFK Omladinac")
                .orElseGet(() -> {
                    org.example.footballtextmanager.model.CTeam ct = new org.example.footballtextmanager.model.CTeam();
                    ct.setName("OFK Omladinac");
                    return csTeamRepository.save(ct);
                });
        owner.setCTeam(csTeam);
        owner.setTifoCTeam(csTeam);
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

    private void restoreTacticsProfiles(List<TacticsProfileSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }

        int restored = 0;
        for (TacticsProfileSnapshot snapshot : snapshots) {
            Team team = teamRepository.findByName(snapshot.teamName).orElse(null);
            if (team == null || team.getId() == null) {
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
    public void initSerbianFootballStructure() {
        // 1. Država – Srbija + još nekoliko (modularno)
        createCountryIfNotExists("Srbija", "SRB", 65, 70);
        createCountryIfNotExists("Bosna i Hercegovina", "BIH", 55, 60);
        createCountryIfNotExists("Crna Gora", "MNE", 50, 55);
        createCountryIfNotExists("Hrvatska", "HRV", 70, 75);
        createCountryIfNotExists("Slovenija", "SVN", 60, 65);
        createCountryIfNotExists("Severna Makedonija", "MKD", 45, 50);
        createCountryIfNotExists("Nemačka", "DEU", 95, 95);
        createCountryIfNotExists("Engleska", "GBR", 95, 90);
        createCountryIfNotExists("Brazil", "BRA", 90, 85);

        Country serbia = countryRepository.findByIsoCode("SRB").orElseThrow();

        // 2. Kreiraj tekuću sezonu (2025)
        Season currentSeason = createSeasonIfNotExists(2025, "2025/2026 Season");

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
        competitionRepository.findAll().stream()
                .filter(c -> c.getCountry().getIsoCode().equals("SRB") && c.getTier() > 1)
                .forEach(league -> populateLeagueWithTeams(league, 10, false, currentSeason,
                        MUNICIPAL_SID_LEAGUE.equals(league.getName())
                                ? MUNICIPAL_SID_CLUBS : null));

        // 5. Dodaj PromotionRule za lige
        addPromotionRulesForLeagues(currentSeason);

        // 6. Dodaj Kup Srbije (nacionalni kup)
        createCupCompetitionIfNotExists(serbia, "Kup Srbije", 64, currentSeason);

        // 7. Generate double round-robin fixtures for all Serbian leagues (season year = 2025)
        int seasonYear = 2025;
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

    private void populateLeagueWithTeams(Competition league, int teamCount, boolean includeOmladinac,
                                         Season season) {
        populateLeagueWithTeams(league, teamCount, includeOmladinac, season, null);
    }

    /**
     * Fills a division, preferring real club names where the league has them.
     *
     * @param preferredNames clubs this league really contains, or null to use generated names
     *                       throughout. Only as many as are still missing are taken, so a league that
     *                       is already half full is topped up with generated clubs rather than
     *                       double-seeding the real ones.
     */
    private void populateLeagueWithTeams(Competition league, int teamCount, boolean includeOmladinac,
                                         Season season, List<String> preferredNames) {
        SeasonCompetition sc = seasonCompetitionRepository.findByCompetitionAndSeasonYear(league, season.getSeasonYear())
                .orElseThrow(() -> new RuntimeException("Sezona za ligu nije pronađena"));

        long currentTeams = competitionEntryRepository.countBySeasonCompetition(sc);
        int toCreate = teamCount - (int) currentTeams;

        if (toCreate <= 0) {
            log.info("Liga {} već ima {} timova → preskačem", league.getName(), currentTeams);
            return;
        }

        Set<Long> usedTeamIdsInLeague = competitionEntryRepository.findBySeasonCompetition(sc)
                .stream()
                .map(entry -> entry.getTeam().getId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        int attempts = 0;
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
                    toCreate--;
                }
            }
        }
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
            // The fixture view already resolves a stadium whose name contains "livadice" to
            // /images/livadice.png, so naming the ground is all that is needed to put the real
            // picture on the match screen.
            stadium.setLocation("Berkasovo");
        } else if ("OFK Omladinac".equals(name)) {
            team.setLogoUrl(OMLADINAC_LOGO);
            team.setHumanControlled(true);
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
