package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.newLogic.dto.CountrySummaryDTO;
import org.example.footballmanager.newLogic.dto.LeagueTableDTO;
import org.example.footballmanager.newLogic.dto.MatchDTO;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.repository.*;
import org.example.footballmanager.newLogic.service.ScheduleInsightService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.example.footballmanager.newLogic.util.LeagueTableOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.dto.PlayerDTO;
import org.example.footballmanager.newLogic.model.MatchPlayerStats;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.Team;
import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.service.NationalTeamService;
import org.example.footballmanager.newLogic.service.PresenceRegistry;
import org.example.footballmanager.newLogic.service.NationalTeamElectionService;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.util.CupFixtureSeeder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    private final PresenceRegistry presenceRegistry;
    private final org.example.footballmanager.newLogic.util.InternationalClubCups internationalClubCups;
    private final PlayerRepository playerRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final MatchRepository matchRepository;
    private final MatchFixtureRepository matchFixtureRepository;
    private final SeasonRepository seasonRepository;
    private final ScheduleInsightService scheduleInsightService;
    private final SeasonService seasonService;
    private final org.example.footballmanager.newLogic.service.RankingPointsReader rankingPoints;
    private final org.example.footballmanager.newLogic.repository.ClubSeasonRankingPointsRepository
            clubSeasonRankingPointsRepository;
    private final org.example.footballmanager.newLogic.service.RankingTieBreakService tieBreaks;
    private final org.example.commonmanager.repository.UserRepository humanUserRepository;
    private final org.example.footballmanager.newLogic.service.PlusFeatureService plusFeatures;
    private final org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository matchPlayerStatsRepository;

    public CountryController(CountryRepository countryRepository,
            org.example.commonmanager.repository.UserRepository humanUserRepository,
            CompetitionRepository competitionRepository, CompetitionEntryRepository competitionEntryRepository, TeamRepository teamRepository, PlayerRepository playerRepository, SeasonCompetitionRepository seasonCompetitionRepository, MatchRepository matchRepository, MatchFixtureRepository matchFixtureRepository, SeasonRepository seasonRepository, ScheduleInsightService scheduleInsightService, SeasonService seasonService, NationalTeamService nationalTeamService,
            NationalTeamElectionService electionService,
            PresenceRegistry presenceRegistry,
            org.example.footballmanager.newLogic.util.InternationalClubCups internationalClubCups,
            org.example.footballmanager.newLogic.service.PlusFeatureService plusFeatures,
            org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository matchPlayerStatsRepository,
            org.example.footballmanager.newLogic.service.RankingPointsReader rankingPoints,
            org.example.footballmanager.newLogic.repository.ClubSeasonRankingPointsRepository
                    clubSeasonRankingPointsRepository,
            org.example.footballmanager.newLogic.service.RankingTieBreakService tieBreaks) {
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
        this.presenceRegistry = presenceRegistry;
        this.internationalClubCups = internationalClubCups;
        this.plusFeatures = plusFeatures;
        this.rankingPoints = rankingPoints;
        this.clubSeasonRankingPointsRepository = clubSeasonRankingPointsRepository;
        this.matchPlayerStatsRepository = matchPlayerStatsRepository;
        this.tieBreaks = tieBreaks;
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
        // **The season, read from the clock (P1-CUPS-5).**
        //
        // The cup summary below used to read `out.get("currentSeason")` — a key this method never put
        // into the map — so the ternary always short-circuited to 1. From season 3 on, the World page
        // counted a season nobody had finished. Reading the clock is the same thing
        // `getLeagueTable` does and the reason it exists: a season number is a fact, not a default.
        int activeSeason = seasonService.getActiveSeasonYear();
        out.put("currentSeason", activeSeason);
        // A world that is short of the catalogue is broken, not interesting, so it is surfaced rather
        // than rendered as a smaller world.
        out.put("complete", all.size() == CountryCatalog.all().size());
        // <b>Two numbers, both labelled for what they are.</b> This used to be one number called
        // "users" and rendered as "Human players", and it was `countByRoleIsNotNull()` — registered
        // accounts, every one of which has been counted since the day they registered. The owner's note
        // on this was "do not label the number 'online' until this exists", so the registered count
        // keeps its own name and the online count is a separate, honestly-derived figure.
        out.put("registeredPlayers", presenceRegistry.registeredCount());
        out.put("onlinePlayers", presenceRegistry.onlineCount());
        // Stated on the page, not buried: "online" is meaningless without the window that defines it.
        out.put("onlineWindowMinutes", PresenceRegistry.ONLINE_WINDOW.toMinutes());

        // The three international club cups, with the clubs that have actually qualified for each. The
        // World page used to render all seven international competitions as a disabled row saying "Not
        // created yet", which is a claim about the database being empty — and three of them were not.
        //
        // Entry is decided by the *finished* season, so on a world part-way through season one there is
        // nothing to qualify from yet and the counts are honestly zero rather than invented. The
        // subtraction is guarded at 1: there is no season 0, so a world in season one qualifies nobody
        // rather than reading a season that was never played.
        // Season 1 has no finished domestic tables. Keep all international fields empty until season 2;
        // displaying a partial field here would make the World page claim that a competition is running.
        out.put("clubCups", internationalClubCups.summarise(activeSeason > 1 ? activeSeason - 1 : 0));
        return out;
    }

    @GetMapping("/catalog")
    public List<Map<String, Object>> getCountryCatalog() {
        Set<String> seeded = countryRepository.findAll().stream()
                .map(Country::getIsoCode)
                .filter(code -> code != null && !code.isBlank())
                .map(code -> code.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        // Was `teamRepository.findAll()` mapped to its country: every club in the world, materialised, on
        // a public endpoint the registration page calls before anyone has logged in, to answer a yes/no
        // per country. Touching a Country also fetches its two eager national sides. A projection returns
        // the same answer as a handful of strings and loads no entity.
        Set<String> seededWithClubs = teamRepository.findDistinctIsoCodesOfCountriesWithClubs().stream()
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
     * Every country's national rating, best first, with its position.
     *
     * <p>Ranked on the national Elo the matches actually produced — `Country.reputation` for the senior
     * side and `Country.youthRating` for U-21 — and nowhere else. The owner asked for "ranking points and
     * a position on the ranking list" on a country's page (2026-10-07), which cannot be answered from a
     * single country's row: a position is a statement about every other country too.
     *
     * <p>Computed here rather than in the browser so the World page and a country page cannot disagree
     * about who is 12th, and so "position" is one definition in the codebase.
     *
     * <p><b>Equal ratings share a position.</b> Two countries that have played the same football and
     * earned the same number are the same distance from the top, and a table that numbers them 7 and 8
     * is claiming a difference it cannot support. The count of countries strictly above is what defines
     * the position.
     */
    /**
     * The clubs of one country, ranked (owner, 2026-10-07, P1-CTRY-1).
     *
     * <p>*"nedostaje mi na stranici Country novi tab gde je ranking lista klubova iz te zemlje"* — a tab
     * listing the clubs of that country.
     *
     * <p>Ordered by the same <b>ranking points</b> the national ranking uses, so there is one system and
     * not two: the club Elo that used to be the rating is head-to-head and is not a ranking
     * ({@code RatingEngine.clubK} no longer reads either rating). A country with clubs ranked by its
     * national Elo and its clubs ranked by the old club Elo would be two orderings on one screen.
     *
     * <p><b>Division tier is carried, because it is what the points are scaled by.</b> Two clubs on the
     * same number in different divisions are not equal, and a table that does not say which division a
     * row is in cannot be read.
     *
     * <p>The whole country's clubs are returned in one read of the ledger, not one read per club.
     */
    @GetMapping("/{isoCode}/clubs/ranking")
    public Map<String, Object> clubRanking(@PathVariable String isoCode,
                                          @RequestParam(defaultValue = "100") int limit) {
        Country country = requireCountry(isoCode);
        int season = seasonService.getActiveSeasonYear();

        Map<Long, Double> totals = new LinkedHashMap<>();
        List<ClubSeasonRankingPoints> rows =
                clubSeasonRankingPointsRepository.findAllByCompetitionCountryId(country.getId());
        Map<Long, Map<Integer, Double>> byTeam = new LinkedHashMap<>();
        for (ClubSeasonRankingPoints row : rows) {
            byTeam.computeIfAbsent(row.getTeam().getId(), key -> new LinkedHashMap<>())
                    .put(row.getSeasonYear(), row.total());
        }
        for (Map.Entry<Long, Map<Integer, Double>> entry : byTeam.entrySet()) {
            totals.put(entry.getKey(), org.example.footballmanager.newLogic.service.RankingPointsEngine.windowedTotal(season, entry.getValue()));
        }

        int safeLimit = limit < 1 ? 100 : Math.min(limit, 500);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Team club : teamRepository.findClubTeamsForCountry(country.getId())) {
            if (club.getId() == null) {
                continue;
            }
            Double total = totals.get(club.getId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("teamId", club.getId());
            row.put("name", club.getName());
            row.put("tier", club.getCompetition() == null || club.getCompetition().getTier() == null
                    ? 1 : club.getCompetition().getTier());
            row.put("division", club.getCompetition() == null ? null : club.getCompetition().getName());
            row.put("points", total == null
                    ? org.example.footballmanager.newLogic.service.RankingPointsEngine.START_POINTS
                    : round2(total));
            row.put("rated", total != null);
            out.add(row);
        }
        // Points, then the country's own coin. Never the name: alphabetical order is not a sporting
        // result, and it is the rule a manager can see straight away and has no reason to believe.
        long coin = tieBreaks.clubSeed(season, country.getId());
        out.sort(Comparator.comparingDouble((Map<String, Object> row) -> (Double) row.get("points"))
                .reversed()
                .thenComparingLong(row -> tieBreaks.coin(coin, clubIdOf(row))));

        // A distinct position for every row, whatever the points say. Two clubs level on points get two
        // positions, decided by the coin — the owner's rule, and the reason this loop no longer shares a
        // position between equals.
        int position = 0;
        int shown = 0;
        for (Map<String, Object> row : out) {
            row.put("position", ++position);
            if (shown++ < safeLimit) {
                continue;
            }
        }
        // The list itself is capped, but the position each row carries is the country's real position.
        List<Map<String, Object>> page = out.size() > safeLimit ? out.subList(0, safeLimit) : out;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("country", country.getIsoCode());
        result.put("seasonYear", season);
        result.put("totalClubs", out.size());
        result.put("clubs", page);
        return result;
    }

    @GetMapping("/ranking")
    public List<Map<String, Object>> ranking(@RequestParam(defaultValue = "senior") String level) {
        boolean youth = "u21".equalsIgnoreCase(String.valueOf(level));
        org.example.footballmanager.newLogic.model.NationalTeamLevel nationalLevel = youth
                ? org.example.footballmanager.newLogic.model.NationalTeamLevel.U21
                : org.example.footballmanager.newLogic.model.NationalTeamLevel.SENIOR;
        int season = seasonService.getActiveSeasonYear();

        List<Country> world = countryRepository.findAll().stream()
                .filter(country -> country.getSeniorNationalTeam() != null
                        || country.getU21NationalTeam() != null)
                .toList();

        // One read per level, not per country. This used to ask for every played national match once per
        // country, purely to decide whether that country had any results: 48 full scans of the match
        // table to answer a yes/no question about 48 countries.
        Map<Long, Double> totals = rankingPoints.totalsForAllCountries(nationalLevel, season);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Country country : world) {
            Double total = totals.get(country.getId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("isoCode", country.getIsoCode());
            row.put("name", country.getName());
            // Carried for the tie-break coin only, so the ladder does not have to look the country up
            // again by its ISO code to decide who is level with whom.
            row.put("countryId", country.getId());
            row.put("level", youth ? "u21" : "senior");
            // Ordered by ACHIEVEMENT points, not by the head-to-head Elo this replaced. The owner
            // collapsed the two into one system: a club or country earns points for what it achieved and
            // for how it compared with the forecast, never for beating a bigger name.
            row.put("points", total == null
                    ? org.example.footballmanager.newLogic.service.RankingPointsEngine.START_POINTS
                    : round2(total));
            row.put("rated", total != null);
            rows.add(row);
        }

        // Points, then the world's coin for this season. Never the name — see the club ladder.
        long coin = tieBreaks.countrySeed(season);
        rows.sort(Comparator.comparingDouble((Map<String, Object> row) -> (Double) row.get("points"))
                .reversed()
                .thenComparingLong(row -> tieBreaks.coin(coin, countryIdOf(row))));

        // Computed here rather than stored: a position is a statement about every other country too, so
        // storing it lets two screens disagree. Every row gets its own, so two countries level on points
        // no longer share a rank.
        int position = 0;
        for (Map<String, Object> row : rows) {
            row.put("position", ++position);
        }
        return rows;
    }

    /** The club behind a ladder row, for the coin. Null id sorts on the mixed seed alone. */
    private static Long clubIdOf(Map<String, Object> row) {
        Object id = row.get("teamId");
        return id instanceof Number n ? n.longValue() : null;
    }

    /** The country behind a ranking row, for the coin. */
    private static Long countryIdOf(Map<String, Object> row) {
        Object id = row.get("countryId");
        return id instanceof Number n ? n.longValue() : null;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** The national Elo for one level. Senior reputation and youth rating are different columns. */
    private double ratingOf(Country country, boolean youth) {
        Number value = youth ? country.getYouthRating() : country.getReputation();
        return value == null ? 0d : value.doubleValue();
    }

    /**
     * Whether this country has played anything at this level, so a seed rating is not shown as a result.
     *
     * <p>A country that has not played holds the starting rating, and a ranking table that shows it
     * without saying so reads as "they are exactly average", which is a claim nobody has earned.
     */
    private boolean hasResults(Country country, boolean youth) {
        Team side = youth ? country.getU21NationalTeam() : country.getSeniorNationalTeam();
        if (side == null || side.getId() == null) {
            return false;
        }
        return matchRepository.findPlayedNationalScoredInOrder().stream()
                .anyMatch(match -> side.getId().equals(match.homeTeamId()) || side.getId().equals(match.awayTeamId()));
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
        // **Scoped to NATIONAL (P1-CUPS-5).**
        //
        // This was `c.getCountry() == null || country.getId().equals(c.getCountry().getId())`, and the
        // fifteen international club cups have `country == null` — so they passed the country filter.
        // `findFirst()` over an unordered table then handed one of them back, and a country page
        // rendered the Champions Cup as "this country's cup".
        //
        // `scope` is the column that exists for exactly this: NATIONAL is a cup between clubs of one
        // country, INTERNATIONAL is a cup whose entrants come from several. `CompetitionType.CUP` on its
        // own does not say which, and there are sixteen CUP rows in the world.
        Competition cup = competitionRepository.findAll().stream()
                .filter(c -> c.getType() == CompetitionType.CUP)
                .filter(c -> c.getScope() == CompetitionScope.NATIONAL)
                .filter(c -> country.getId().equals(c.getCountry() == null ? null : c.getCountry().getId()))
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
            // The Match row this fixture became, for the ties that have been played. Without it the page
            // has only the fixture id, and every post-match endpoint - lineups, stats, goals, report -
            // needs the MATCH id, so a played tie would open with every one of those panels empty.
            tie.put("matchId", fixture.getPlayedMatch() == null ? null : fixture.getPlayedMatch().getId());
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
                // 404, not 400. "No such tie" is a failed lookup, and this line threw
                // IllegalArgumentException, which the exception handler quite reasonably maps to a client
                // error - so asking for a tie that does not exist looked like a malformed request.
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No such cup tie: " + fixtureId));

        // **The country in the path has to mean something.** `requireCountry(isoCode)` used to be called
        // and its result thrown away, so the country was checked for existence and then ignored: the tie was
        // loaded by id alone and any manager could read any country's cup tie by guessing an id. The check
        // ran, and did nothing, which is worse than not having it because the code reads as though it were
        // scoped.
        //
        // A tie belongs to the country of its competition. A tie whose competition has no country cannot be
        // shown under any country's path either - there is nothing to prove it is being asked for properly.
        String pathIso = country.getIsoCode() == null ? null : country.getIsoCode().toUpperCase(Locale.ROOT);
        Competition cup = fixture.getCompetition();
        String tieIso = cup == null || cup.getCountry() == null || cup.getCountry().getIsoCode() == null
                ? null : cup.getCountry().getIsoCode().toUpperCase(Locale.ROOT);
        if (tieIso == null || !tieIso.equals(pathIso)) {
            // Not found rather than forbidden: telling a caller that the tie exists but is not theirs is
            // the same information as the score they were asking for.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No such cup tie in " + country.getName() + ": " + fixtureId);
        }

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

        // One order for the whole game - see LeagueTableOrder for the three that disagreed.
        List<CompetitionEntry> sortedEntries = LeagueTableOrder.sort(entries);

        // Who manages each club, in ONE query for the whole table.
        //
        // A league table is up to 310 rows, so resolving a manager per row would be 310 queries on a
        // page opened constantly — the N+1 this codebase has already measured and removed three times
        // (P1-4). Only human-run clubs are asked about: the rest are bots and there is no account to
        // find, which also keeps the query to the handful of rows that can answer.
        Map<Long, User> managersByTeamId = managersByTeamIdFor(sortedEntries);

        // Mapiraj na DTO sa position iz sortiranja
        List<LeagueTableDTO> table = new ArrayList<>();
        for (int i = 0; i < sortedEntries.size(); i++) {
            CompetitionEntry e = sortedEntries.get(i);
            User manager = managersByTeamId.get(e.getTeam().getId());
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
                    e.getTeam().isHumanControlled(),
                    e.getTeam().getEloRating(),
                    e.getTeam().getEloDelta(),
                    manager == null ? null : manager.getId(),
                    manager == null ? null : displayNameOrLogin(manager),
                    manager != null && manager.getDisplayName() != null && !manager.getDisplayName().isBlank()
            ));
        }

        return ResponseEntity.ok(table);
    }

    /**
     * The manager of every human-run club in these entries, keyed by team id.
     *
     * <p>One query, not one per row. Deliberately skips non-human clubs: a bot club has no account, and
     * asking about all 310 rows to learn about two of them is the shape of the N+1 P1-4 removed
     * elsewhere.
     */
    private Map<Long, User> managersByTeamIdFor(List<CompetitionEntry> entries) {
        List<Long> humanTeamIds = entries.stream()
                .map(CompetitionEntry::getTeam)
                .filter(team -> team != null && team.getId() != null && team.isHumanControlled())
                .map(Team::getId)
                .distinct()
                .toList();
        if (humanTeamIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, User> byTeamId = new java.util.HashMap<>();
        for (User user : humanUserRepository.findAllByFootballTeamIdIn(humanTeamIds)) {
            if (user.getFootballTeam() == null || user.getFootballTeam().getId() == null) {
                continue;
            }
            // Lowest id wins if two accounts ever point at one club, so the answer is stable rather
            // than dependent on row order. Same rule as ClubOwnershipLinker.managerOf.
            byTeamId.merge(user.getFootballTeam().getId(), user,
                    (a, b) -> a.getId() <= b.getId() ? a : b);
        }
        return byTeamId;
    }

    /**
     * The name to show for a manager: what he calls himself, or his login when he has not chosen one.
     *
     * <p>Fifth independent copy of this fallback. {@link org.example.commonmanager.model.User} documents
     * two of the others, and every one was written separately — which is why every self-registered
     * manager shows an email address beside his posts.
     */
    private static String displayNameOrLogin(User user) {
        return user.getDisplayName() != null && !user.getDisplayName().isBlank()
                ? user.getDisplayName().trim()
                : user.getUsername();
    }

    @GetMapping("/leagues/{leagueId}/matches")
    public List<MatchDTO> getLeagueMatches(@PathVariable Long leagueId,
                                           @RequestParam(value = "seasonYear", required = false) Integer seasonYear,
                                           @AuthenticationPrincipal User user) {
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

        // The viewer, so his own result can stay hidden here too. This called the no-viewer form, which
        // means the DTO could never decide anybody was involved - so a league table of results showed
        // the manager his own scoreline before he had asked for it.
        Long viewerTeamId = plusFeatures.viewerTeamId(user);
        return matches.stream()
                .map(m -> MatchDTO.from(m, viewerTeamId))
                .collect(Collectors.toList());
    }

    @GetMapping("/leagues/{leagueId}/schedule")
    public List<Map<String, Object>> getLeagueSchedule(@PathVariable Long leagueId,
                                                        @RequestParam(value = "seasonYear", required = false) Integer seasonYear) {
        Competition league = competitionRepository.findById(leagueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "League not found"));
        int activeSeasonYear = seasonYear != null ? seasonYear : seasonService.getActiveSeasonYear();

        // **A GET does not build the world.**
        //
        // This called ensureEntriesForSeasonCompetition and ensureDoubleRoundRobinSchedule, so opening the
        // schedule page created the entries and every fixture of a double round robin — thousands of rows —
        // on a request that is supposed to be safe. Three consequences: any authenticated manager could
        // write to the world just by opening a page; two managers opening it at once raced each other into
        // generating the same fixtures; and anything that caches a GET — a proxy, a CDN, the browser — could
        // freeze the fixture list at the moment it first ran.
        //
        // Both are already done where they belong: `PyramidBuilder` calls them when a pyramid is built,
        // `SimulatedWorldSeeder` reaches it, and `AdminController` exposes that as the seeding action.
        // This endpoint is left a pure read, so a league with no schedule is honestly empty rather than
        // quietly generated by whoever looked at it first.
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

    /**
     * A club's players, for whoever is asking.
     *
     * <p><b>This used to return raw {@code Player} entities for any {@code teamId} with no ownership or
     * country check</b> — so any logged-in manager could read any rival's entire squad, and a raw entity
     * carries {@code skills}, {@code talent}, {@code earnings}, the full injury record and
     * {@code personality}. {@code talent} is the number this codebase goes to real lengths to withhold: it
     * is the whole reason {@code PlusFeatureService} exists.
     *
     * <p>It now does exactly what {@code TeamController.getPlayers} does — the same {@link PlayerDTO} and
     * the same {@code PlusFeatureService} entitlement — so there is one rule for "what may this viewer see
     * about this player" instead of one per controller. A rival's talent comes back null, exactly as it does
     * from the team endpoint this now mirrors.
     *
     * <p>Nothing calls this endpoint; it existed beside the team one and answered a strictly larger
     * question. It is kept because the route is public API surface and narrowing it costs nothing.
     */
    @GetMapping("/teams/{teamId}/players")
    public ResponseEntity<List<PlayerDTO>> getPlayers(
            @PathVariable Long teamId,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.example.commonmanager.model.User viewer) {
        List<Player> teamPlayers = playerRepository.findByTeamId(teamId);
        Map<Long, List<MatchPlayerStats>> statsByPlayerId = teamPlayers.isEmpty()
                ? Map.of()
                : matchPlayerStatsRepository.findByPlayerIdIn(teamPlayers.stream().map(Player::getId).toList())
                        .stream()
                        .filter(stats -> stats.getPlayer() != null)
                        .collect(Collectors.groupingBy(stats -> stats.getPlayer().getId()));

        Long viewerTeamId = plusFeatures.viewerTeamId(viewer);
        List<PlayerDTO> players = teamPlayers.stream()
                .map(player -> {
                    List<MatchPlayerStats> stats = statsByPlayerId.get(player.getId());
                    List<MatchPlayerStats> safeStats = stats == null ? List.of() : stats;
                    double averageRating10 = safeStats.stream()
                            .mapToInt(MatchPlayerStats::getRating)
                            .average()
                            .orElse(0.0) / 10.0;
                    Double rounded = safeStats.isEmpty()
                            ? null
                            : Math.round(averageRating10 * 10.0) / 10.0;
                    return PlayerDTO.from(player, safeStats.size(), rounded,
                            plusFeatures.talentOrNull(player, viewer, viewerTeamId));
                })
                .toList();
        return ResponseEntity.ok(players);
    }
    // P1-CUPS-3: country-side qualifying race
    @GetMapping("/{isoCode}/qualifying")
    public Map<String, Object> qualifying(
            @PathVariable String isoCode) {
        Country country = requireCountry(isoCode);
        int season = currentSeason();
        List<Competition> divisions = competitionRepository.findByCountryId(country.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("country", country.getName());
        out.put("isoCode", country.getIsoCode());
        out.put("season", season);

        Map<Integer, List<List<CompetitionEntry>>> tablesByTier = new LinkedHashMap<>();
        for (Competition division : divisions) {
            if (division.getType() != CompetitionType.LEAGUE) continue;
            SeasonCompetition sc = seasonCompetitionRepository.findByCompetitionAndSeasonYear(division, season)
                    .orElse(null);
            if (sc == null) continue;
            List<CompetitionEntry> table = LeagueTableOrder.sort(
                    competitionEntryRepository.findBySeasonCompetition(sc));
            if (table.isEmpty()) continue;
            tablesByTier.computeIfAbsent(division.getTier(), ignored -> new ArrayList<>()).add(table);
        }

        List<Map<String, Object>> tiers = new ArrayList<>();
        for (Map.Entry<Integer, List<List<CompetitionEntry>>> tier : tablesByTier.entrySet()) {
            int tierNumber = tier.getKey();
            Map<String, Object> tierData = new LinkedHashMap<>();
            tierData.put("tier", tierNumber);
            List<Map<String, Object>> cups = new ArrayList<>();
            if (tierNumber == 1) {
                List<CompetitionEntry> table = tier.getValue().get(0);
                cups.add(cupRace("Champions Cup", entriesAt(table, 0, 1), 1));
                cups.add(cupRace("Masters Cup", entriesAt(table, 1, 2), 2));
                cups.add(cupRace("Challenge Cup", entriesAt(table, 3, 1), 1));
            } else {
                List<CompetitionEntry> champions = poolAt(tier.getValue(), 0);
                List<CompetitionEntry> masters = new ArrayList<>();
                masters.addAll(poolAt(tier.getValue(), 1));
                masters.addAll(poolAt(tier.getValue(), 2));
                List<CompetitionEntry> challenge = poolAt(tier.getValue(), 3);
                cups.add(cupRace("Champions Cup", champions, 1));
                cups.add(cupRace("Masters Cup", masters, 2));
                cups.add(cupRace("Challenge Cup", challenge, 1));
            }
            tierData.put("cups", cups);
            tiers.add(tierData);
        }
        out.put("tiers", tiers);
        return out;
    }

    private static CompetitionEntry entryAt(List<CompetitionEntry> table, int index) {
        return index < table.size() ? table.get(index) : null;
    }

    private static List<CompetitionEntry> entriesAt(List<CompetitionEntry> table, int from, int count) {
        if (from >= table.size()) return List.of();
        return new ArrayList<>(table.subList(from, Math.min(table.size(), from + count)));
    }

    private static List<CompetitionEntry> poolAt(List<List<CompetitionEntry>> tables, int position) {
        List<CompetitionEntry> pool = new ArrayList<>();
        for (List<CompetitionEntry> table : tables) {
            CompetitionEntry entry = entryAt(table, position);
            if (entry != null) pool.add(entry);
        }
        return LeagueTableOrder.sort(pool);
    }

    private static Map<String, Object> cupRace(String cup, List<CompetitionEntry> candidates,
                                                int places) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            CompetitionEntry entry = candidates.get(index);
            rows.add(qualifyingRow(entry, index + 1, index < places));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cup", cup);
        result.put("places", places);
        result.put("standings", rows);
        result.put("selected", rows.stream().filter(row -> Boolean.TRUE.equals(row.get("qualifies"))).count());
        return result;
    }

    private static Map<String, Object> qualifyingRow(CompetitionEntry entry, int poolPosition,
                                                      boolean qualifies) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("teamName", entry.getTeam() == null ? null : entry.getTeam().getName());
        row.put("position", poolPosition);
        row.put("leaguePosition", entry.getPosition());
        row.put("points", entry.getPoints());
        row.put("goalsFor", entry.getGoalsScored());
        row.put("goalsAgainst", entry.getGoalsConceded());
        row.put("goalDifference", entry.getGoalsScored() - entry.getGoalsConceded());
        row.put("qualifies", qualifies);
        return row;
    }

}
