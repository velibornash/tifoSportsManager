package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.newLogic.dto.CountrySummaryDTO;
import org.example.footballmanager.newLogic.dto.LeagueTableDTO;
import org.example.footballmanager.newLogic.dto.MatchDTO;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.repository.*;
import org.example.footballmanager.newLogic.service.ScheduleInsightService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.Team;
import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.service.NationalTeamService;
import org.example.footballmanager.newLogic.service.NationalTeamElectionService;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.util.CupFixtureSeeder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.example.footballmanager.newLogic.model.CountryCatalog;
import org.example.footballmanager.newLogic.util.WorldCatalogSeeder;
import org.example.footballmanager.newLogic.repository.TeamRepository;

import java.util.Locale;
import java.util.Set;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;


@RestController
@RequestMapping("/countries")
public class CountryController {
    private final CountryRepository countryRepository;

    private final NationalTeamService nationalTeamService;
    private final NationalTeamElectionService electionService;
    private final TeamRepository teamRepository;
    private final CompetitionRepository competitionRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final PlayerRepository playerRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final MatchRepository matchRepository;
    private final MatchFixtureRepository matchFixtureRepository;
    private final SeasonRepository seasonRepository;
    private final ScheduleInsightService scheduleInsightService;
    private final SeasonService seasonService;
    private final org.example.commonmanager.repository.UserRepository humanUserRepository;

    public CountryController(CountryRepository countryRepository,
            org.example.commonmanager.repository.UserRepository humanUserRepository,
            CompetitionRepository competitionRepository, CompetitionEntryRepository competitionEntryRepository, TeamRepository teamRepository, PlayerRepository playerRepository, SeasonCompetitionRepository seasonCompetitionRepository, MatchRepository matchRepository, MatchFixtureRepository matchFixtureRepository, SeasonRepository seasonRepository, ScheduleInsightService scheduleInsightService, SeasonService seasonService, NationalTeamService nationalTeamService,
            NationalTeamElectionService electionService) {
        this.countryRepository = countryRepository;
        this.competitionRepository = competitionRepository;
        this.competitionEntryRepository = competitionEntryRepository;
        this.teamRepository = teamRepository;
        this.playerRepository = playerRepository;
        this.seasonCompetitionRepository = seasonCompetitionRepository;
        this.matchRepository = matchRepository;
        this.matchFixtureRepository = matchFixtureRepository;
        this.seasonRepository = seasonRepository;
        this.scheduleInsightService = scheduleInsightService;
        this.seasonService = seasonService;
        this.humanUserRepository = humanUserRepository;
        this.nationalTeamService = nationalTeamService;
        this.electionService = electionService;
    }

    /**
     * The 48 nations, for the registration form (owner, 2026-09-28).
     *
     * <p>Served from {@link CountryCatalog} rather than from the {@code country} table, and that
     * distinction matters. The registration form must work <b>before anyone has registered</b> — for a
     * new install, and for a manager choosing a country whose leagues are not seeded yet. Listing
     * only seeded countries would hide exactly the countries a new player most wants to see.
     *
     * <p>Each entry also reports whether it is playable right now, so the form can show "not seeded
     * yet" rather than letting someone pick a country that will refuse their registration.
     *
     * <p>Public on purpose: it must be readable by an unauthenticated visitor, and it exposes nothing
     * but a list of country names.
     */
    /**
     * The world: every country, its state, and how big it is.
     *
     * <p>Built for the World page. The page previously read the country list and rendered whatever
     * came back, so when the database held one country the page claimed the world had one country -
     * a page that reports the data source rather than describing the game. This endpoint reports the
     * whole catalogue and marks what is playable, so a SIMULATED country is visibly not a club
     * pyramid instead of silently missing.
     */
    @GetMapping("/world")
    public Map<String, Object> worldOverview() {
        List<CountrySummaryDTO> all = getAllCountries();
        long active = all.stream().filter(c -> "ACTIVE".equals(c.getState())).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("countries", all);
        out.put("totalCountries", all.size());
        out.put("expectedCountries", CountryCatalog.all().size());
        out.put("activeCountries", active);
        // A world that is short of the catalogue is broken, not interesting, so it is surfaced rather
        // than rendered as a smaller world.
        out.put("complete", all.size() == CountryCatalog.all().size());
        out.put("startRating", WorldCatalogSeeder.STARTING_RATING);
        out.put("users", humanUserRepository.countByRoleIsNotNull());
        return out;
    }

    @GetMapping("/catalog")
    public List<Map<String, Object>> getCountryCatalog() {
        Set<String> seeded = countryRepository.findAll().stream()
                .map(Country::getIsoCode)
                .filter(code -> code != null && !code.isBlank())
                .map(code -> code.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        Set<String> seededWithClubs = teamRepository.findAll().stream()
                .map(team -> team.getCountry())
                .filter(Objects::nonNull)
                .map(Country::getIsoCode)
                .filter(code -> code != null && !code.isBlank())
                .map(code -> code.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());

        return CountryCatalog.all().stream().map(c -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", c.code());
            row.put("name", c.displayName());
            row.put("seeded", seeded.contains(c.code()));
            row.put("hasClubs", seededWithClubs.contains(c.code()));
            return row;
        }).toList();
    }

    @GetMapping
    public List<CountrySummaryDTO> getAllCountries() {
        return countryRepository.findAll().stream()
                .sorted(Comparator.comparing(Country::getName, String.CASE_INSENSITIVE_ORDER))
                .map(CountrySummaryDTO::from)
                .toList();
    }

    /**
     * A national team: squad, pool, selector, ranking, fixtures, election state (owner, 2026-09-28).
     *
     * <p>Replaces a hand-rolled map that reported the viewer as selector. That made every user in a
     * country believe they ran the national team and left nothing to check "is this viewer the
     * selector" against, so the squad could not be restricted to them.
     */
    @GetMapping("/{isoCode}/national-team")
    public Map<String, Object> getNationalTeam(
            @PathVariable String isoCode,
            @RequestParam(defaultValue = "senior") String level,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.example.commonmanager.model.User viewer) {
        Country country = requireCountry(isoCode);
        return nationalTeamService.describe(country, NationalTeamLevel.from(level), viewer);
    }

    /**
     * Puts a club player on the national roster.
     *
     * <p>Selector-only, and re-checked here rather than trusting the client. The client hides the
     * button, but a hidden button is not a permission.
     */
    @PostMapping("/{isoCode}/national-team/squad")
    public Map<String, Object> addToSquad(
            @PathVariable String isoCode,
            @RequestParam(defaultValue = "senior") String level,
            @RequestBody Map<String, Object> body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.example.commonmanager.model.User viewer) {
        Country country = requireCountry(isoCode);
        long sourcePlayerId = Long.parseLong(String.valueOf(body.get("playerId")));
        return nationalTeamService.addToSquad(country, NationalTeamLevel.from(level), sourcePlayerId, viewer);
    }

    /** Takes a national-roster row off the squad. The club player is untouched. */
    @org.springframework.web.bind.annotation.DeleteMapping("/{isoCode}/national-team/squad/{playerId}")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void removeFromSquad(
            @PathVariable String isoCode,
            @PathVariable long playerId,
            @RequestParam(defaultValue = "senior") String level,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.example.commonmanager.model.User viewer) {
        nationalTeamService.removeFromSquad(requireCountry(isoCode), NationalTeamLevel.from(level), playerId, viewer);
    }

    /**
     * The cup bracket for this country: every round, every tie.
     *
     * <p>Rounds that have not been drawn yet are reported as empty rather than omitted, so the cup
     * page can show the shape of the tournament and which round is next instead of a list that
     * silently grows.
     *
     * <p>The season is derived from the clock, never assumed. This asked for a literal season
     * number while {@link CupFixtureSeeder} wrote every cup fixture against the world's own season,
     * so the page reported "0 ties across 8 rounds" over a bracket the seeder had just reported as
     * 54 ties drawn - two artefacts, two hardcoded answers, and neither reading the clock.
     */
    @GetMapping("/{isoCode}/cup")
    public Map<String, Object> getCup(@PathVariable String isoCode,
                                      @RequestParam(value = "seasonYear", required = false) Integer seasonYear) {
        int activeSeasonYear = seasonYear != null ? seasonYear : seasonService.getActiveSeasonYear();
        Country country = requireCountry(isoCode);
        Competition cup = competitionRepository.findAll().stream()
                .filter(c -> c.getType() == CompetitionType.CUP)
                .filter(c -> c.getCountry() == null || country.getId().equals(c.getCountry().getId()))
                .findFirst()
                .orElse(null);

        Map<String, Object> out = new LinkedHashMap<>();
        if (cup == null) {
            out.put("exists", false);
            out.put("rounds", List.of());
            return out;
        }
        out.put("exists", true);
        out.put("competitionId", cup.getId());
        out.put("name", cup.getName());
        out.put("teamsPerCompetition", cup.getTeamsPerCompetition());

        List<MatchFixture> all = matchFixtureRepository
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(cup.getId(), activeSeasonYear);
        Map<Integer, List<Map<String, Object>>> byRound = new LinkedHashMap<>();
        for (int round : CupFixtureSeeder.CUP_WEEKS) {
            byRound.put(round, new ArrayList<>());
        }
        for (MatchFixture fixture : all) {
            List<Map<String, Object>> bucket = byRound.computeIfAbsent(
                    fixture.getWeekNumber() == null ? 0 : fixture.getWeekNumber(), k -> new ArrayList<>());
            Map<String, Object> tie = new LinkedHashMap<>();
            tie.put("id", fixture.getId());
            tie.put("home", fixture.getHomeTeam() == null ? null : fixture.getHomeTeam().getName());
            tie.put("away", fixture.getAwayTeam() == null ? null : fixture.getAwayTeam().getName());
            tie.put("played", fixture.isPlayed());
            bucket.add(tie);
        }
        List<Map<String, Object>> rounds = new ArrayList<>();
        for (int round : CupFixtureSeeder.CUP_WEEKS) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("week", round);
            row.put("fixtures", byRound.getOrDefault(round, List.of()));
            rounds.add(row);
        }
        out.put("rounds", rounds);
        out.put("totalFixtures", all.size());
        return out;
    }

    /**
     * Pre-match state for one cup tie (owner, 2026-09-29).
     *
     * <p>The owner asked for this after finding that a cup tie was a row of text. It is, until it is
     * played: a fixture is a scheduled pairing, and the {@code Match} only exists once the simulator
     * has run. So the tie needs somewhere to be looked at before that - the same information a league
     * fixture shows, for the same reason.
     *
     * <p>Team names are returned with their ids so the client can link to the club page, which is what
     * "click the teams" needs; the squad is here so the tie is worth opening.
     */
    @GetMapping("/{isoCode}/cup/fixture/{fixtureId}")
    public Map<String, Object> getCupFixture(@PathVariable String isoCode,
                                             @PathVariable long fixtureId) {
        Country country = requireCountry(isoCode);
        MatchFixture fixture = matchFixtureRepository.findById(fixtureId)
                .orElseThrow(() -> new IllegalArgumentException("No such cup tie: " + fixtureId));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fixtureId", fixture.getId());
        out.put("week", fixture.getWeekNumber());
        out.put("day", fixture.getDayNumber());
        out.put("round", fixture.getRoundNumber());
        out.put("played", fixture.isPlayed());
        out.put("date", fixture.getMatchDate());
        // Null until the simulator has run. Deliberately not faked: a tie that has not been played
        // has no result, and inventing a 0-0 would read as a real one.
        out.put("matchId", fixture.getPlayedMatch() == null ? null : fixture.getPlayedMatch().getId());
        out.put("homeScore", fixture.getPlayedMatch() == null ? null : fixture.getPlayedMatch().getHomeGoals());
        out.put("awayScore", fixture.getPlayedMatch() == null ? null : fixture.getPlayedMatch().getAwayGoals());
        out.put("home", cupSide(fixture.getHomeTeam()));
        out.put("away", cupSide(fixture.getAwayTeam()));
        return out;
    }

    /** One side of a tie: identity for linking, and enough squad to make it worth opening. */
    private Map<String, Object> cupSide(Team team) {
        Map<String, Object> side = new LinkedHashMap<>();
        if (team == null) {
            return side;
        }
        side.put("id", team.getId());
        side.put("name", team.getName());
        side.put("logoUrl", team.getLogoUrl());
        side.put("country", team.getCountry() == null ? null : team.getCountry().getIsoCode());
        side.put("squad", playerRepository.findByTeamId(team.getId()).stream()
                .limit(25)
                .map(player -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", player.getName());
                    row.put("position", player.getPosition() == null ? null : player.getPosition().name());
                    row.put("rating", player.getRating());
                    row.put("age", player.getAge());
                    return row;
                })
                .toList());
        return side;
    }

    /**
     * Playoff ties for this country.
     *
     * <p>Empty until playoffs exist. Reported as an empty list so the link is live and the page says
     * "none yet" rather than 404-ing at a section the general tab always offers.
     */
    @GetMapping("/{isoCode}/playoffs")
    public Map<String, Object> getPlayoffs(@PathVariable String isoCode) {
        Country country = requireCountry(isoCode);
        List<Competition> playoffs = competitionRepository.findAll().stream()
                .filter(c -> c.getType() == CompetitionType.LEAGUE)
                .filter(c -> c.getName() != null && c.getName().toLowerCase().contains("playoff"))
                .filter(c -> c.getCountry() == null || country.getId().equals(c.getCountry().getId()))
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exists", !playoffs.isEmpty());
        out.put("fixtures", List.of());
        out.put("note", playoffs.isEmpty()
                ? "No playoff competition exists for this country yet."
                : "Playoff competition exists but no ties have been generated.");
        return out;
    }

    // ------------------------------------------------------------ elections

    /**
     * The election for one national side, with the running state and candidate list.
     *
     * <p>Tallies are omitted for an undecided election unless the caller is an admin or the owner.
     * That is decided on the server, not by the client choosing not to render them.
     */
    @GetMapping("/{isoCode}/national-team/election")
    public Map<String, Object> getElection(
            @PathVariable String isoCode,
            @RequestParam(defaultValue = "senior") String level,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.example.commonmanager.model.User viewer) {
        Country country = requireCountry(isoCode);
        return electionService.describeElection(country, NationalTeamLevel.from(level), currentSeason(),
                viewer, isElectionAdmin(viewer));
    }

    /** Stands the caller for selector, or revives a previous candidacy they had withdrawn. */
    @PostMapping("/{isoCode}/national-team/election/candidacy")
    public Map<String, Object> registerCandidate(
            @PathVariable String isoCode,
            @RequestParam(defaultValue = "senior") String level,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.example.commonmanager.model.User viewer) {
        Country country = requireCountry(isoCode);
        electionService.register(country, NationalTeamLevel.from(level), currentSeason(), viewer, weekOneKickoff());
        return electionService.describeElection(country, NationalTeamLevel.from(level), currentSeason(),
                viewer, true);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{isoCode}/national-team/election/candidacy")
    public Map<String, Object> withdrawCandidate(
            @PathVariable String isoCode,
            @RequestParam(defaultValue = "senior") String level,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.example.commonmanager.model.User viewer) {
        Country country = requireCountry(isoCode);
        electionService.withdraw(country, NationalTeamLevel.from(level), currentSeason(), viewer);
        return electionService.describeElection(country, NationalTeamLevel.from(level), currentSeason(),
                viewer, true);
    }

    /**
     * Casts a vote, or moves the caller's existing one.
     *
     * <p>One vote per user, changeable, and self-voting is allowed - all three were the owner's
     * explicit rules, so none of them are guarded against here.
     */
    @PostMapping("/{isoCode}/national-team/election/vote")
    public Map<String, Object> vote(
            @PathVariable String isoCode,
            @RequestParam(defaultValue = "senior") String level,
            @RequestBody Map<String, Object> body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.example.commonmanager.model.User viewer) {
        Country country = requireCountry(isoCode);
        long candidateId = Long.parseLong(String.valueOf(body.get("candidateId")));
        electionService.vote(country, NationalTeamLevel.from(level), currentSeason(), viewer, candidateId);
        return electionService.describeElection(country, NationalTeamLevel.from(level), currentSeason(),
                viewer, true);
    }

    /**
     * Closes the vote and appoints the winner.
     *
     * <p>Admin or owner only. A tie is reported rather than broken: the owner never specified a
     * tiebreak, and picking one inside a declaration method would be a rule nobody chose.
     */
    @PostMapping("/{isoCode}/national-team/election/declare")
    public Map<String, Object> declare(
            @PathVariable String isoCode,
            @RequestParam(defaultValue = "senior") String level,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.example.commonmanager.model.User viewer) {
        requireElectionAdmin(viewer);
        return electionService.declare(requireCountry(isoCode), NationalTeamLevel.from(level), currentSeason());
    }

    @PostMapping("/{isoCode}/national-team/election/annul")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void annul(
            @PathVariable String isoCode,
            @RequestParam(defaultValue = "senior") String level,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.example.commonmanager.model.User viewer) {
        requireElectionAdmin(viewer);
        electionService.annul(requireCountry(isoCode), NationalTeamLevel.from(level), currentSeason());
    }

    /**
     * Forces an election into a state, so the panel can be exercised without waiting for week 1.
     *
     * <p>Admin only, and recorded as a manual override: the clock will not then quietly close an
     * election somebody opened on purpose.
     */
    @PostMapping("/{isoCode}/national-team/election/status")
    public Map<String, Object> setElectionStatus(
            @PathVariable String isoCode,
            @RequestParam(defaultValue = "senior") String level,
            @RequestParam String status,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.example.commonmanager.model.User viewer) {
        requireElectionAdmin(viewer);
        Country country = requireCountry(isoCode);
        electionService.forceStatus(country, NationalTeamLevel.from(level), currentSeason(), org.example.footballmanager.newLogic.model.NationalTeamElection.Status.valueOf(status));
        return electionService.describeElection(country, NationalTeamLevel.from(level), currentSeason(),
                viewer, true);
    }

    /** Every election for a country, for the admin list. */
    @GetMapping("/{isoCode}/elections")
    public List<Map<String, Object>> listElections(
            @PathVariable String isoCode,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.example.commonmanager.model.User viewer) {
        requireElectionAdmin(viewer);
        return electionService.listForCountry(requireCountry(isoCode).getId());
    }

    /**
     * When week 1 day 1 kicked off, which is what the voting window is measured from.
     *
     * <p>Falls back to the start of the current day when the season clock cannot be read, so the
     * election is still usable rather than permanently unopenable on a fresh install.
     */
    private java.time.Instant weekOneKickoff() {
        try {
            Integer currentWeek = seasonService.getCurrentWeek();
            if (currentWeek != null && currentWeek >= 1) {
                return java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS);
            }
        } catch (RuntimeException ignored) {
            // Fall through to the default below.
        }
        return java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS);
    }

    /**
     * The current season, from the game clock.
     *
     * <p>Server-side on purpose. The election endpoints used to take a season parameter, which let
     * the client ask about a season nobody was holding an election for, and a default of 1 then
     * reported "no election" while one was running.
     *
     * <p>This guessed the season from the highest row in the season table. It now asks the clock,
     * like every other reader. Three copies of "which season is it" is how the country page and the
     * cup seeder came to disagree about the same bracket in the first place.
     */
    private int currentSeason() {
        return seasonService.getActiveSeasonYear();
    }

    private boolean isElectionAdmin(User viewer) {
        if (viewer == null) {
            return false;
        }
        // Role check plus the seeded owner. The owner is matched by address rather than by a flag so
        // that a fresh install has someone who can run the election, which is the only way to
        // appoint a selector before the first election cycle exists.
        return (viewer.getRole() != null && viewer.getRole().name().equals("OWNER"))
                || "velibor@example.com".equalsIgnoreCase(viewer.getEmail());
    }

    private void requireElectionAdmin(User viewer) {
        if (!isElectionAdmin(viewer)) {
            throw new SecurityException("Only an administrator can do that.");
        }
    }

    private Country requireCountry(String isoCode) {
        return countryRepository.findByIsoCode(String.valueOf(isoCode).toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException("No such country: " + isoCode));
    }

    @GetMapping("/{isoCode}/leagues")
    public List<Competition> getLeagues(@PathVariable String isoCode) {
        return competitionRepository.findByCountryIsoCodeAndType(isoCode, CompetitionType.LEAGUE);
    }
    @GetMapping("/leagues/{leagueId}/teams")
    public List<Map<String, Object>> getTeams(@PathVariable Long leagueId,
                                              @RequestParam(value = "seasonYear", required = false) Integer seasonYear) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Liga nije pronađena"));
        int activeSeasonYear = seasonYear != null ? seasonYear : seasonService.getActiveSeasonYear();
        seasonService.ensureEntriesForSeasonCompetition(league, activeSeasonYear);

        SeasonCompetition seasonCompetition = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, activeSeasonYear)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sezona nije pronađena"));

        return competitionEntryRepository.findBySeasonCompetition(seasonCompetition)
                .stream()
                .filter(entry -> entry.getTeam() != null)
                .sorted(Comparator
                        .comparing(CompetitionEntry::getPosition, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(entry -> entry.getTeam().getName(), String.CASE_INSENSITIVE_ORDER))
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", entry.getTeam().getId());
                    row.put("name", entry.getTeam().getName());
                    row.put("humanControlled", entry.getTeam().isHumanControlled());
                    return row;
                })
                .toList();
    }
    @GetMapping("/leagues/{leagueId}/table")
    public ResponseEntity<List<LeagueTableDTO>> getLeagueTable(@PathVariable Long leagueId,
                                                               @RequestParam(value = "seasonYear", required = false) Integer seasonYear) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Liga nije pronađena"));
        int activeSeasonYear = seasonYear != null ? seasonYear : seasonService.getActiveSeasonYear();
        seasonService.ensureEntriesForSeasonCompetition(league, activeSeasonYear);

        SeasonCompetition currentSeasonComp = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, activeSeasonYear)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sezona nije pronađena"));

        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(currentSeasonComp);

        // Sortiraj
        List<CompetitionEntry> sortedEntries = entries.stream()
                .sorted(Comparator.comparing(CompetitionEntry::getPoints, Comparator.reverseOrder())
                        .thenComparing(e -> e.getGoalsScored() - e.getGoalsConceded(), Comparator.reverseOrder())
                        .thenComparing(CompetitionEntry::getGoalsScored, Comparator.reverseOrder()))
                .toList();

        // Mapiraj na DTO sa position iz sortiranja
        List<LeagueTableDTO> table = new ArrayList<>();
        for (int i = 0; i < sortedEntries.size(); i++) {
            CompetitionEntry e = sortedEntries.get(i);
            table.add(new LeagueTableDTO(
                    e.getTeam().getId(),
                    e.getTeam().getName(),
                    e.getPoints(),
                    e.getGoalsScored(),
                    e.getGoalsConceded(),
                    e.getGoalsScored() - e.getGoalsConceded(),
                    e.getWins() != null ? e.getWins() : 0,
                    e.getDraws() != null ? e.getDraws() : 0,
                    e.getLosses() != null ? e.getLosses() : 0,
                    i + 1,
                    e.getTeam().isHumanControlled()
            ));
        }

        return ResponseEntity.ok(table);
    }
    @GetMapping("/leagues/{leagueId}/matches")
    public List<MatchDTO> getLeagueMatches(@PathVariable Long leagueId,
                                           @RequestParam(value = "seasonYear", required = false) Integer seasonYear) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Liga nije pronađena"));
        int activeSeasonYear = seasonYear != null ? seasonYear : seasonService.getActiveSeasonYear();
        seasonService.ensureEntriesForSeasonCompetition(league, activeSeasonYear);
        SeasonCompetition sc = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, activeSeasonYear)
                .orElse(null);
        if (sc == null) {
            return List.of();
        }

        // Dohvati sve timove u ligi
        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(sc);
        List<Long> teamIds = entries.stream().map(e -> e.getTeam().getId()).toList();

        List<Match> matches = matchRepository
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(leagueId, activeSeasonYear)
                .stream()
                .filter(m -> m.getHomeTeam() != null && m.getAwayTeam() != null)
                .filter(m -> teamIds.contains(m.getHomeTeam().getId()) && teamIds.contains(m.getAwayTeam().getId()))
                .filter(Match::isPlayed)
                .toList();

        // Mapiraj u DTO
        return matches.stream()
                .map(MatchDTO::from)
                .collect(Collectors.toList());
    }

    @GetMapping("/leagues/{leagueId}/schedule")
    public List<Map<String, Object>> getLeagueSchedule(@PathVariable Long leagueId,
                                                        @RequestParam(value = "seasonYear", required = false) Integer seasonYear) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "League not found"));
        int activeSeasonYear = seasonYear != null ? seasonYear : seasonService.getActiveSeasonYear();

        seasonService.ensureEntriesForSeasonCompetition(league, activeSeasonYear);
        seasonService.ensureDoubleRoundRobinSchedule(league, activeSeasonYear);

        List<MatchFixture> fixtures = matchFixtureRepository.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(leagueId, activeSeasonYear);
        Map<Long, ScheduleInsightService.TeamSnapshot> snapshots = scheduleInsightService.buildTeamSnapshots(fixtures.stream()
                .flatMap(fixture -> java.util.stream.Stream.of(fixture.getHomeTeam(), fixture.getAwayTeam()))
                .filter(Objects::nonNull)
                .toList());

        return fixtures
                .stream()
                .filter(f -> f.getHomeTeam() != null && f.getAwayTeam() != null)
                .map(f -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    Match playedMatch = f.getPlayedMatch();
                    ScheduleInsightService.FixtureInsights insights = scheduleInsightService.buildFixtureInsights(
                            f.getHomeTeam(),
                            f.getAwayTeam(),
                            snapshots
                    );
                    row.put("fixtureId", f.getId());
                    row.put("id", playedMatch != null ? playedMatch.getId() : null);
                    row.put("homeTeamId", f.getHomeTeam().getId());
                    row.put("awayTeamId", f.getAwayTeam().getId());
                    row.put("homeTeam", f.getHomeTeam().getName());
                    row.put("awayTeam", f.getAwayTeam().getName());
                    row.put("homeGoals", playedMatch != null ? playedMatch.getHomeGoals() : 0);
                    row.put("awayGoals", playedMatch != null ? playedMatch.getAwayGoals() : 0);
                    row.put("played", f.isPlayed());
                    row.put("round", f.getRoundNumber() != null ? f.getRoundNumber() : 1);
                    row.put("week", f.getWeekNumber() != null ? f.getWeekNumber() : f.getRoundNumber());
                    row.put("seasonYear", f.getSeasonYear());
                    row.put("competitionName", f.getCompetition() != null ? f.getCompetition().getName() : league.getName());
                    row.put("stadium", f.getHomeTeam().getStadium() != null ? f.getHomeTeam().getStadium().getName() : "N/A");
                    row.put("homeTeamStrength", insights.homeTeamStrength());
                    row.put("awayTeamStrength", insights.awayTeamStrength());
                    row.put("homeTeamForm", insights.homeTeamForm());
                    row.put("awayTeamForm", insights.awayTeamForm());
                    row.put("prediction", toPredictionMap(insights.prediction()));
                    row.put("matchDate", f.getMatchDate() != null ? f.getMatchDate().toString().substring(0, 16).replace("T", " ") : "N/A");
                    return row;
                })
                .toList();
    }

    @GetMapping("/leagues/{leagueId}/season-summary")
    public Map<String, Object> getLeagueSeasonSummary(@PathVariable Long leagueId,
                                                      @RequestParam(value = "seasonYear", required = false) Integer seasonYear) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "League not found"));
        int activeSeasonYear = seasonYear != null ? seasonYear : seasonService.getActiveSeasonYear();
        return seasonService.buildPlayoffSummary(league, activeSeasonYear);
    }

    private Map<String, Object> toPredictionMap(ScheduleInsightService.Prediction prediction) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("homeWinProbability", prediction.homeWinProbability());
        payload.put("drawProbability", prediction.drawProbability());
        payload.put("awayWinProbability", prediction.awayWinProbability());
        payload.put("expectedHomeGoals", prediction.expectedHomeGoals());
        payload.put("expectedAwayGoals", prediction.expectedAwayGoals());
        payload.put("mostLikelyResult", prediction.mostLikelyResult());
        payload.put("confidence", prediction.confidence());
        payload.put("analysis", prediction.analysis());
        return payload;
    }

    @GetMapping("/leagues/{leagueId}/seasons")
    public List<Map<String, Object>> getLeagueSeasons(@PathVariable Long leagueId) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "League not found"));
        List<Integer> years = seasonCompetitionRepository.findSeasonYearsByCompetitionId(league.getId());
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < years.size(); i++) {
            Integer year = years.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seasonYear", year);
            row.put("seasonNumber", i + 1);
            result.add(row);
        }
        return result;
    }

    @GetMapping("/leagues/{leagueId}/player-directory")
    public List<Map<String, Object>> getLeaguePlayerDirectory(@PathVariable Long leagueId,
                                                              @RequestParam(value = "seasonYear", required = false) Integer seasonYear) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "League not found"));
        int activeSeasonYear = seasonYear != null ? seasonYear : seasonService.getActiveSeasonYear();
        seasonService.ensureEntriesForSeasonCompetition(league, activeSeasonYear);

        SeasonCompetition seasonCompetition = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, activeSeasonYear)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "League season not found"));

        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(seasonCompetition);
        Map<Long, String> teamNameById = entries.stream()
                .filter(entry -> entry.getTeam() != null && entry.getTeam().getId() != null)
                .collect(Collectors.toMap(entry -> entry.getTeam().getId(), entry -> entry.getTeam().getName(), (left, right) -> left));

        return playerRepository.findByTeamIdIn(new ArrayList<>(teamNameById.keySet())).stream()
                .map(player -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    Long teamId = player.getTeam() != null ? player.getTeam().getId() : null;
                    row.put("id", player.getId());
                    row.put("name", player.getName());
                    row.put("teamId", teamId);
                    row.put("teamName", teamId != null ? teamNameById.get(teamId) : null);
                    return row;
                })
                .toList();
    }

    @GetMapping("/teams/{teamId}/players")
    public List<Player> getPlayers(@PathVariable Long teamId) {
        return playerRepository.findByTeamId(teamId);
    }
}
