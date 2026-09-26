package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.repository.*;
import org.example.footballmanager.newLogic.model.JuniorStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeasonService {

    public static final int BASE_SEASON_YEAR = 2025;
    // Re-exported from SeasonCalendar, which is the single definition of the season shape. These
    // used to be 18 / 19 / 20, which described a twenty-week season with a round per week. The
    // playoff and friendly generators below hang off them, so repointing them here moves that
    // machinery onto the owner's twelve-week calendar without rewriting it.
    public static final int WEEKS_PER_SEASON = SeasonCalendar.WEEKS_PER_SEASON;
    public static final int LEAGUE_END = SeasonCalendar.LEAGUE_END_WEEK;
    public static final int LEAGUE_ROUNDS = SeasonCalendar.LEAGUE_ROUNDS;
    public static final int PLAYOFF_WEEK = SeasonCalendar.PLAYOFF_WEEK;
    public static final int FRIENDLY_WEEK = SeasonCalendar.BREAK_WEEK;

    private final GameClockRepository gameClockRepository;
    private final SeasonRepository seasonRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final CompetitionRepository competitionRepository;
    private final MatchFixtureRepository matchFixtureRepository;
    private final TeamRepository teamRepository;
    private final WeeklyFinanceService weeklyFinances;
    private final PlayerContractService contracts;
    private final ContractBackfillService contractBackfill;
    private final PlayerRepository playerRepository;
    private final JuniorRepository juniorRepository;
    private final YouthAcademyService youthAcademyService;
    private final TransferService transferService;
    private final FriendlyRequestService friendlyRequests;
    private final LoanService loans;
    private final Random random = new Random();

    @Transactional
    public GameClock getOrCreateClock() {
        GameClock clock = gameClockRepository.findById(1L).orElseGet(() -> {
            GameClock c = new GameClock();
            c.setId(1L);
            c.setCurrentSeason(1);
            c.setCurrentWeek(1);
            c.setCurrentDate(LocalDateTime.now());
            return c;
        });
        if (clock.getCurrentSeason() != null && clock.getCurrentSeason() > 1000) {
            // Legacy format stored calendar year (e.g. 2025). Convert to Season index (Season 1 starts at BASE_SEASON_YEAR).
            int normalized = clock.getCurrentSeason() - BASE_SEASON_YEAR + 1;
            clock.setCurrentSeason(Math.max(1, normalized));
        }
        if (clock.getCurrentSeason() == null || clock.getCurrentSeason() < 1) {
            clock.setCurrentSeason(1);
        }
        if (clock.getCurrentWeek() == null || clock.getCurrentWeek() < 1) {
            clock.setCurrentWeek(1);
        }
        if (clock.getCurrentDate() == null) {
            clock.setCurrentDate(LocalDateTime.now());
        }
        return gameClockRepository.save(clock);
    }

    public int getActiveSeasonYear() {
        GameClock clock = getOrCreateClock();
        return BASE_SEASON_YEAR + (clock.getCurrentSeason() - 1);
    }

    public int getCurrentWeek() {
        return getOrCreateClock().getCurrentWeek();
    }

    public int countRemainingFixturesForWeek(Long leagueId, int seasonYear, int currentWeek) {
        return matchFixtureRepository.findByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalseOrderByMatchDateAsc(
                leagueId, seasonYear, currentWeek
        ).size();
    }

    @Transactional
    public Season ensureActiveSeasonEntity() {
        int year = getActiveSeasonYear();
        return seasonRepository.findBySeasonYear(year).orElseGet(() -> {
            Season season = new Season();
            season.setSeasonYear(year);
            season.setDescription("Season " + (year - BASE_SEASON_YEAR + 1));
            return seasonRepository.save(season);
        });
    }

    @Transactional
    public SeasonCompetition ensureSeasonCompetition(Competition competition, int seasonYear) {
        return seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(competition, seasonYear)
                .orElseGet(() -> {
                    SeasonCompetition sc = new SeasonCompetition();
                    sc.setCompetition(competition);
                    sc.setSeasonYear(seasonYear);
                    sc.setFinished(false);
                    return seasonCompetitionRepository.save(sc);
                });
    }

    @Transactional
    public void ensureEntriesForSeasonCompetition(Competition competition, int seasonYear) {
        SeasonCompetition sc = ensureSeasonCompetition(competition, seasonYear);
        List<CompetitionEntry> existing = competitionEntryRepository.findBySeasonCompetition(sc);
        List<Team> currentLeagueTeams = teamRepository.findByCompetitionId(competition.getId());

        if (!existing.isEmpty()) {
            Set<Long> existingTeamIds = existing.stream()
                    .map(CompetitionEntry::getTeam)
                    .filter(Objects::nonNull)
                    .map(Team::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Set<Long> currentTeamIds = currentLeagueTeams.stream()
                    .map(Team::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            if (existingTeamIds.size() == currentTeamIds.size() && existingTeamIds.equals(currentTeamIds)) {
                return;
            }

            // Delete one by one to avoid batch update issues
            for (CompetitionEntry entry : existing) {
                if (entry.getId() != null) {
                    competitionEntryRepository.deleteById(entry.getId());
                }
            }
            existing = List.of();
            log.warn("Rebuilding season entries for league {} season {} because membership drift was detected. Existing={}, current={}",
                    competition.getName(), seasonYear, existingTeamIds.size(), currentTeamIds.size());
        }

        List<CompetitionEntry> entriesToCreate = new ArrayList<>(currentLeagueTeams.size());
        for (Team t : currentLeagueTeams) {
            CompetitionEntry entry = new CompetitionEntry();
            entry.setSeasonCompetition(sc);
            entry.setTeam(t);
            entry.setPoints(0);
            entry.setGoalsScored(0);
            entry.setGoalsConceded(0);
            entry.setWins(0);
            entry.setDraws(0);
            entry.setLosses(0);
            entriesToCreate.add(entry);
        }
        if (!entriesToCreate.isEmpty()) {
            competitionEntryRepository.saveAll(entriesToCreate);
        }
    }

    @Transactional
    public CompetitionEntry findOrCreateEntry(SeasonCompetition sc, Team team) {
        return competitionEntryRepository
                .findBySeasonCompetitionAndTeam(sc, team)
                .orElseGet(() -> {
                    CompetitionEntry entry = new CompetitionEntry();
                    entry.setSeasonCompetition(sc);
                    entry.setTeam(team);
                    entry.setPoints(0);
                    entry.setGoalsScored(0);
                    entry.setGoalsConceded(0);
                    entry.setWins(0);
                    entry.setDraws(0);
                    entry.setLosses(0);
                    return competitionEntryRepository.save(entry);
                });
    }

    @Transactional
    public void ensureDoubleRoundRobinSchedule(Competition competition, int seasonYear) {
        if (competition == null || competition.getId() == null) return;
        List<MatchFixture> existing = matchFixtureRepository.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                competition.getId(), seasonYear
        );
        boolean hasRounds = existing.stream().anyMatch(m -> m.getRoundNumber() != null && m.getRoundNumber() >= 1);
        if (hasRounds) return;

        SeasonCompetition sc = ensureSeasonCompetition(competition, seasonYear);
        List<Team> teams = competitionEntryRepository.findBySeasonCompetition(sc).stream()
                .map(CompetitionEntry::getTeam)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Team::getId))
                .collect(Collectors.toList());

        if (teams.size() < 2) return;

        List<Team> list = new ArrayList<>(teams);
        if (list.size() % 2 != 0) list.add(null);
        int n = list.size();
        int rounds = n - 1;
        int matchesPerRound = n / 2;

        GameClock clock = getOrCreateClock();
        LocalDateTime startDate = clock.getCurrentDate();

        List<MatchFixture> fixtures = new ArrayList<>();
        for (int round = 0; round < rounds; round++) {
            for (int i = 0; i < matchesPerRound; i++) {
                Team home = list.get(i);
                Team away = list.get(n - 1 - i);
                if (home == null || away == null) continue;
                MatchFixture fixture = new MatchFixture();
                fixture.setHomeTeam(home);
                fixture.setAwayTeam(away);
                fixture.setCompetition(competition);
                fixture.setSeasonYear(seasonYear);
                fixture.setRoundNumber(round + 1);
                // The week comes from the season calendar, not from the round number. Previously
                // each round was its own week, which made an 18-round season 18 weeks long and
                // put the mid-season window in the wrong place entirely. Two rounds share a week
                // now, except in weeks 5 and 6 where a friendly takes the second slot.
                int week = SeasonCalendar.weekOfRound(round + 1);
                fixture.setWeekNumber(week > 0 ? week : round + 1);
                // Only a relative offset is kept: the season is twelve weeks and has no months in
                // it, so this is used for ordering only and never shown to a manager.
                fixture.setMatchDate(startDate.plusWeeks(fixture.getWeekNumber() - 1L));
                fixture.setPlayed(false);
                fixtures.add(fixture);
            }
            Team last = list.remove(n - 1);
            list.add(1, last);
        }
        int firstHalf = fixtures.size();
        for (int i = 0; i < firstHalf; i++) {
            MatchFixture base = fixtures.get(i);
            MatchFixture reverse = new MatchFixture();
            reverse.setHomeTeam(base.getAwayTeam());
            reverse.setAwayTeam(base.getHomeTeam());
            reverse.setCompetition(competition);
            reverse.setSeasonYear(seasonYear);
            reverse.setRoundNumber(base.getRoundNumber() + rounds);
            reverse.setWeekNumber(base.getWeekNumber());
            reverse.setMatchDate(base.getMatchDate());
            reverse.setPlayed(false);
            fixtures.add(reverse);
        }
        matchFixtureRepository.saveAll(fixtures);
        log.info("Generated double round-robin schedule for league {} season {} with {} fixtures",
                competition.getName(), seasonYear, fixtures.size());
    }

    @Transactional
    public void ensurePlayoffWeekFixtures(Competition superLiga, int seasonYear) {
        List<MatchFixture> existing = matchFixtureRepository.findByCompetitionIdAndSeasonYearAndRoundNumberOrderByMatchDateAsc(
                superLiga.getId(), seasonYear, PLAYOFF_WEEK
        );
        if (!existing.isEmpty()) return;

        SeasonCompetition topSc = seasonCompetitionRepository.findByCompetitionAndSeasonYear(superLiga, seasonYear).orElse(null);
        if (topSc == null) return;
        List<CompetitionEntry> top = sortTable(competitionEntryRepository.findBySeasonCompetition(topSc));
        if (top.size() < 8) return;

        List<Competition> tier2Leagues = competitionRepository
                .findByCountryIsoCodeAndTypeAndTierOrderByDivisionLevelAscIdAsc("SRB", CompetitionType.LEAGUE, 2);
        if (tier2Leagues.size() < 2) return;

        // The two second-placed clubs, each with the table it finished on, so they can be ranked
        // against each other rather than by which league they happened to be in.
        List<CompetitionEntry> lowerRunners = new ArrayList<>();
        for (Competition lowerLeague : tier2Leagues.subList(0, 2)) {
            SeasonCompetition lowerSc = seasonCompetitionRepository.findByCompetitionAndSeasonYear(lowerLeague, seasonYear).orElse(null);
            if (lowerSc == null) continue;
            List<CompetitionEntry> lowerTable = sortTable(competitionEntryRepository.findBySeasonCompetition(lowerSc));
            if (lowerTable.size() > 1) lowerRunners.add(lowerTable.get(1));
        }
        if (lowerRunners.size() < 2) return;

        // The owner is explicit: the seventh plays the weaker of the two, the eighth the stronger.
        // Pairing by league order instead gave the seventh whichever happened to be listed first,
        // so on a normal table - where the second of league B is the better side - the draw was
        // the wrong way round and the stronger club got the easier tie.
        List<CompetitionEntry> ranked = new ArrayList<>(lowerRunners);
        ranked.sort(Comparator.comparingInt(
                (CompetitionEntry e) -> e.getPoints() == null ? 0 : e.getPoints()).reversed()
                .thenComparing(Comparator.comparingInt(
                        (CompetitionEntry e) -> (e.getGoalsScored() == null ? 0 : e.getGoalsScored())
                                - (e.getGoalsConceded() == null ? 0 : e.getGoalsConceded()))
                        .reversed()));
        CompetitionEntry weaker = ranked.get(1);
        CompetitionEntry stronger = ranked.get(0);

        GameClock clock = getOrCreateClock();
        MatchFixture m1 = new MatchFixture();
        m1.setHomeTeam(top.get(6).getTeam());
        m1.setAwayTeam(weaker.getTeam());
        m1.setCompetition(superLiga);
        m1.setSeasonYear(seasonYear);
        m1.setRoundNumber(PLAYOFF_WEEK);
        m1.setWeekNumber(PLAYOFF_WEEK);
        m1.setMatchDate(clock.getCurrentDate().plusWeeks(PLAYOFF_WEEK - clock.getCurrentWeek()));
        m1.setPlayed(false);

        MatchFixture m2 = new MatchFixture();
        m2.setHomeTeam(top.get(7).getTeam());
        m2.setAwayTeam(stronger.getTeam());
        m2.setCompetition(superLiga);
        m2.setSeasonYear(seasonYear);
        m2.setRoundNumber(PLAYOFF_WEEK);
        m2.setWeekNumber(PLAYOFF_WEEK);
        m2.setMatchDate(clock.getCurrentDate().plusWeeks(PLAYOFF_WEEK - clock.getCurrentWeek()));
        m2.setPlayed(false);
        matchFixtureRepository.saveAll(List.of(m1, m2));
    }

    // Friendlies are no longer generated here. A club asks for one and the other club may
    // refuse; see FriendlyRequestService. Nothing is scheduled until both sides agree.

    @Transactional
    public void advanceWeekAndHandleSeasonTransition(Competition superLiga) {
        advanceWeekAndHandleSeasonTransition(superLiga, null);
    }

    /**
     * Advances the week, leaving the manager's own club out of the AI friendly negotiations.
     *
     * @param humanTeamId the club the player manages, or null when nobody is managing one
     */
    @Transactional
    public void advanceWeekAndHandleSeasonTransition(Competition superLiga, Long humanTeamId) {
        GameClock clock = getOrCreateClock();
        int week = clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek();
        if (week < FRIENDLY_WEEK) {
            clock.setCurrentWeek(week + 1);
            clock.setCurrentDate(clock.getCurrentDate().plusWeeks(1));
            gameClockRepository.save(clock);
            decrementInjuriesByWeek();
            recoverFatigueForWeek();
            expirePlayerContracts();
            settleWeeklyFinancesForAllClubs();
            int seasonNumber = clock.getCurrentSeason() == null ? 1 : clock.getCurrentSeason();
            int newWeek = clock.getCurrentWeek();
            // A friendly request nobody answered simply lapses once its week has gone. Without
            // this, a club that asked in week 5 still had a live request in week 11.
            // Loans that have run their course. A loan is temporary by definition, so somebody has
            // to close them out or a squad accumulates players who left weeks ago.
            int loansClosed = loans.closeFinishedLoans();
            if (loansClosed > 0) {
                log.info("Week {}: {} loans finished", newWeek, loansClosed);
            }
            int lapsed = friendlyRequests.expireStaleRequests();
            if (lapsed > 0) {
                log.info("Week {}: {} friendly requests lapsed unanswered", newWeek, lapsed);
            }
            // The rest of the league negotiates its own friendlies for the coming week. The
            // manager's club is left out, so whether to take a friendly - and so whether to trade
            // a training session for ninety minutes - stays their decision.
            int arranged = friendlyRequests.runAiFriendlyWeek(seasonNumber, newWeek, humanTeamId);
            if (arranged > 0) {
                log.info("Week {}: {} AI friendlies arranged", newWeek, arranged);
            }
            if (newWeek == 2) {
                youthAcademyService.generateSeasonIntakeForWeek2(seasonNumber, newWeek);
            } else {
                youthAcademyService.progressActiveJuniorsWeekly(seasonNumber, newWeek);
            }
            transferService.simulateWeeklyMarketActivity();
            return;
        }
        performPromotionRelegationAndNewSeason(superLiga);
    }

    /**
     * Settles the week for <b>every</b> club, not just the one the player manages.
     *
     * <p>This is the difference between a game and a spreadsheet. Before it, gate income, broadcast
     * money and prize money existed as formulas nothing called and {@code Player.earnings} was
     * seeded and read by nothing — so the wage bill was free and the transfer budget had nothing to
     * be limited by. An AI club with no economy is an AI club that can never be bought, sold, or
     * promoted out of trouble, which quietly breaks promotion and relegation.
     *
     * <p>Each club settles in its own transaction (REQUIRES_NEW inside the finance service), and a
     * failure for one club is logged and stepped over rather than rolling back the other 309.
     */
    /**
     * Expires contracts that have run out, turning those players into free agents (Sprint 3.1).
     *
     * <p>This is what makes the market move. Without it a player can never stop having a club, which
     * is why the free-agent route was impossible by construction.
     */
    @Transactional
    public int expirePlayerContracts() {
        GameClock clock = getOrCreateClock();
        Integer season = clock.getCurrentSeason();
        if (season == null) return 0;

        // A new season needs contracts before anything else, or every player would expire at once.
        int backfilled = contractBackfill.backfill(season);

        List<Player> released = contracts.expireContracts(season);
        if (!released.isEmpty()) {
            log.info("Season {}: {} contracts expired, {} players became free agents "
                    + "(backfilled {})", season, released.size(), released.size(), backfilled);
        }
        return released.size();
    }

    @Transactional
    public int settleWeeklyFinancesForAllClubs() {
        GameClock clock = getOrCreateClock();
        Integer season = clock.getCurrentSeason();
        Integer week = clock.getCurrentWeek();
        List<Team> clubs = teamRepository.findAll();
        int settled = 0;
        for (Team club : clubs) {
            try {
                WeeklyFinanceService.WeekResult r =
                        weeklyFinances.applyWeeklyFinances(club, season, week);
                if (!r.notApplied()) settled++;
            } catch (RuntimeException e) {
                log.warn("Finance settlement failed for club {} ({}): {}", club.getId(),
                        club.getName(), e.getMessage());
            }
        }
        log.info("Settled weekly finances for {} of {} clubs (season {} week {})",
                settled, clubs.size(), season, week);
        return settled;
    }

    @Transactional
    protected void decrementInjuriesByWeek() {
        List<Player> players = playerRepository.findByInjuryDaysRemainingGreaterThan(0);
        boolean changed = false;
        for (Player player : players) {
            int current = Math.max(0, player.getInjuryDaysRemaining());
            if (current <= 0) continue;
            int next = Math.max(0, current - 7);
            player.setInjuryDaysRemaining(next);
            if (next == 0) {
                player.setInjurySeasonNumber(null);
                player.setInjuryWeekNumber(null);
            }
            changed = true;
        }
        if (changed) {
            playerRepository.saveAll(players);
        }
    }

    /**
     * Passive weekly recovery (Sprint 1.6).
     *
     * <p>Fatigue used to have no sink at all: the only thing that reduced it was the medical
     * button, which is free and unbounded. So fatigue was a monotonic ratchet, and because it feeds
     * both injury risk and {@code Team.getAvailablePlayers()} it would have pinned a squad at
     * maximum condition permanently.
     *
     * <p>Recovery scales with age and with how much condition the week actually cost, so a starter
     * who played every week ends a season meaningfully tired, a fringe player recovers fully, and
     * an older player recovers less. The medical page had been claiming "weekly passive healing
     * still applies" while nothing of the kind existed.
     */
    @Transactional
    protected void recoverFatigueForWeek() {
        List<Player> squad = playerRepository.findAll();
        boolean changed = false;
        for (Player player : squad) {
            if (player == null || player.getSkills() == null) continue;
            int current = player.getSkills().getFatigue();
            if (current <= 0) continue;

            // Base weekly recovery, reduced by age: a 34-year-old does not bounce back like a 22-year-old.
            int baseRecovery = 22;
            int age = player.getAge();
            double ageFactor = age <= 24 ? 1.0 : age <= 29 ? 0.85 : age <= 33 ? 0.65 : 0.5;
            int recovered = (int) Math.round(baseRecovery * ageFactor);

            player.getSkills().setFatigue(Math.max(0, current - recovered));
            changed = true;
        }
        if (changed) {
            playerRepository.saveAll(squad);
        }
    }

    @Transactional
    public void performPromotionRelegationAndNewSeason(Competition superLiga) {
        int endingSeasonYear = getActiveSeasonYear();
        applyPromotionRelegation(superLiga, endingSeasonYear);
        agePlayersAndJuniorsOneYear();

        GameClock clock = getOrCreateClock();
        clock.setCurrentSeason(clock.getCurrentSeason() + 1);
        clock.setCurrentWeek(1);
        clock.setCurrentDate(clock.getCurrentDate().plusWeeks(1));
        gameClockRepository.save(clock);

        int nextSeasonYear = getActiveSeasonYear();
        ensureActiveSeasonEntity();

        for (Competition league : findSerbianLeagues()) {
            ensureEntriesForSeasonCompetition(league, nextSeasonYear);
            ensureDoubleRoundRobinSchedule(league, nextSeasonYear);
            resetCompetitionEntriesForSeason(league, nextSeasonYear);
        }

        log.info("Season rollover complete. New season year={}, week=1", nextSeasonYear);
    }

    @Transactional
    protected void agePlayersAndJuniorsOneYear() {
        playerRepository.incrementAgeForAllPlayers();
        juniorRepository.incrementAgeByStatus(JuniorStatus.ACTIVE);
    }

    @Transactional
    public void resetCompetitionEntriesForSeason(Competition league, int seasonYear) {
        SeasonCompetition sc = ensureSeasonCompetition(league, seasonYear);
        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(sc);
        for (CompetitionEntry e : entries) {
            e.setPoints(0);
            e.setGoalsScored(0);
            e.setGoalsConceded(0);
            e.setWins(0);
            e.setDraws(0);
            e.setLosses(0);
        }
        if (!entries.isEmpty()) {
            competitionEntryRepository.saveAll(entries);
        }
    }

    @Transactional
    public void applyPromotionRelegation(Competition superLiga, int seasonYear) {
        List<Competition> serbianLeagues = findSerbianLeagues();
        if (serbianLeagues.isEmpty()) {
            return;
        }

        Map<Integer, List<Competition>> leaguesByTier = serbianLeagues.stream()
                .filter(competition -> competition.getTier() != null)
                .collect(Collectors.groupingBy(Competition::getTier, TreeMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), list -> list.stream()
                                .sorted(Comparator.comparing((Competition c) -> c.getDivisionLevel() == null ? Integer.MAX_VALUE : c.getDivisionLevel())
                                        .thenComparing(Competition::getId))
                                .toList())));

        Map<Long, Competition> targetCompetitionByTeamId = new HashMap<>();
        Map<Long, Team> teamsById = new HashMap<>();
        for (Competition league : serbianLeagues) {
            SeasonCompetition seasonCompetition = seasonCompetitionRepository.findByCompetitionAndSeasonYear(league, seasonYear).orElse(null);
            if (seasonCompetition == null) {
                continue;
            }
            for (CompetitionEntry entry : competitionEntryRepository.findBySeasonCompetition(seasonCompetition)) {
                Team team = entry.getTeam();
                if (team == null || team.getId() == null) {
                    continue;
                }
                targetCompetitionByTeamId.putIfAbsent(team.getId(), league);
                teamsById.putIfAbsent(team.getId(), team);
            }
        }

        List<Integer> tiers = new ArrayList<>(leaguesByTier.keySet());
        Collections.sort(tiers);
        for (Integer tier : tiers) {
            if (tier == null) {
                continue;
            }
            List<Competition> parentLeagues = leaguesByTier.getOrDefault(tier, List.of());
            List<Competition> childLeagues = leaguesByTier.getOrDefault(tier + 1, List.of());
            if (parentLeagues.isEmpty() || childLeagues.isEmpty()) {
                continue;
            }

            for (int parentIndex = 0; parentIndex < parentLeagues.size(); parentIndex++) {
                Competition parentLeague = parentLeagues.get(parentIndex);
                List<Competition> assignedChildLeagues = assignChildLeagues(parentIndex, parentLeagues.size(), childLeagues);
                applyPromotionRelegationForLeague(parentLeague, assignedChildLeagues, seasonYear, targetCompetitionByTeamId, teamsById);
            }
        }

        List<Team> updatedTeams = targetCompetitionByTeamId.entrySet().stream()
                .map(entry -> {
                    Team team = teamsById.get(entry.getKey());
                    Competition targetCompetition = entry.getValue();
                    if (team == null || targetCompetition == null) {
                        return null;
                    }
                    team.setCompetition(targetCompetition);
                    return team;
                })
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (!updatedTeams.isEmpty()) {
            teamRepository.saveAll(updatedTeams);
        }

        for (Competition league : serbianLeagues) {
            long leagueCount = teamRepository.countByCompetition(league);
            log.info("Promotion/relegation applied for season {}. League {} now has {} teams.", seasonYear, league.getName(), leagueCount);
        }
    }

    private void applyPromotionRelegationForLeague(Competition parentLeague,
                                                   List<Competition> childLeagues,
                                                   int seasonYear,
                                                   Map<Long, Competition> targetCompetitionByTeamId,
                                                   Map<Long, Team> teamsById) {
        if (parentLeague == null || childLeagues == null || childLeagues.isEmpty()) {
            return;
        }

        SeasonCompetition parentSeasonCompetition = seasonCompetitionRepository.findByCompetitionAndSeasonYear(parentLeague, seasonYear).orElse(null);
        if (parentSeasonCompetition == null) {
            return;
        }

        List<CompetitionEntry> parentTable = sortTable(competitionEntryRepository.findBySeasonCompetition(parentSeasonCompetition));
        int expectedTeams = parentLeague.getTeamsPerCompetition() != null ? parentLeague.getTeamsPerCompetition() : parentTable.size();
        int movementSlots = childLeagues.size();
        int safeCount = expectedTeams - (movementSlots * 2);
        if (parentTable.size() < expectedTeams || safeCount < 1) {
            return;
        }

        List<Team> playoffTop = new ArrayList<>();
        List<Team> relegatedDirect = new ArrayList<>();
        for (int i = safeCount; i < safeCount + movementSlots; i++) {
            Team team = parentTable.get(i).getTeam();
            if (team != null) {
                playoffTop.add(team);
                teamsById.putIfAbsent(team.getId(), team);
            }
        }
        for (int i = safeCount + movementSlots; i < safeCount + movementSlots * 2; i++) {
            Team team = parentTable.get(i).getTeam();
            if (team != null) {
                relegatedDirect.add(team);
                teamsById.putIfAbsent(team.getId(), team);
            }
        }
        if (playoffTop.size() < movementSlots || relegatedDirect.size() < movementSlots) {
            return;
        }

        List<Team> promotedDirect = new ArrayList<>();
        List<Team> playoffLower = new ArrayList<>();
        for (Competition childLeague : childLeagues) {
            SeasonCompetition childSeasonCompetition = seasonCompetitionRepository.findByCompetitionAndSeasonYear(childLeague, seasonYear).orElse(null);
            if (childSeasonCompetition == null) {
                return;
            }
            List<CompetitionEntry> childTable = sortTable(competitionEntryRepository.findBySeasonCompetition(childSeasonCompetition));
            if (childTable.size() < 2) {
                return;
            }

            Team champion = childTable.get(0).getTeam();
            Team runnerUp = childTable.get(1).getTeam();
            if (champion == null || runnerUp == null) {
                return;
            }
            promotedDirect.add(champion);
            playoffLower.add(runnerUp);
            teamsById.putIfAbsent(champion.getId(), champion);
            teamsById.putIfAbsent(runnerUp.getId(), runnerUp);
        }

        for (int i = 0; i < movementSlots; i++) {
            Team directPromoted = promotedDirect.get(i);
            Team directRelegated = relegatedDirect.get(i);
            Team topPlayoffTeam = playoffTop.get(i);
            Team lowerPlayoffTeam = playoffLower.get(i);
            Competition childLeague = childLeagues.get(i);

            targetCompetitionByTeamId.put(directPromoted.getId(), parentLeague);
            targetCompetitionByTeamId.put(directRelegated.getId(), childLeague);

            Team playoffWinner = resolvePlayoffWinner(parentLeague, seasonYear, topPlayoffTeam, lowerPlayoffTeam);
            if (Objects.equals(playoffWinner.getId(), lowerPlayoffTeam.getId())) {
                targetCompetitionByTeamId.put(lowerPlayoffTeam.getId(), parentLeague);
                targetCompetitionByTeamId.put(topPlayoffTeam.getId(), childLeague);
            } else {
                targetCompetitionByTeamId.put(topPlayoffTeam.getId(), parentLeague);
                targetCompetitionByTeamId.put(lowerPlayoffTeam.getId(), childLeague);
            }
        }
    }

    private List<Competition> assignChildLeagues(int parentIndex, int parentCount, List<Competition> childLeagues) {
        if (childLeagues.isEmpty() || parentCount <= 0 || parentIndex < 0 || parentIndex >= parentCount) {
            return List.of();
        }

        int baseSize = childLeagues.size() / parentCount;
        int remainder = childLeagues.size() % parentCount;
        int start = parentIndex * baseSize + Math.min(parentIndex, remainder);
        int length = baseSize + (parentIndex < remainder ? 1 : 0);
        int end = Math.min(childLeagues.size(), start + length);
        if (start >= end) {
            return List.of();
        }
        return childLeagues.subList(start, end);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> buildPlayoffSummary(Competition superLiga, int seasonYear) {
        SeasonCompetition topSc = seasonCompetitionRepository.findByCompetitionAndSeasonYear(superLiga, seasonYear).orElse(null);
        if (topSc == null) {
            return Map.of(
                    "seasonYear", seasonYear,
                    "directPromotions", List.of(),
                    "directRelegations", List.of(),
                    "playoffResults", List.of()
            );
        }

        List<CompetitionEntry> top = sortTable(competitionEntryRepository.findBySeasonCompetition(topSc));
        List<Competition> tier2Leagues = findTier2Leagues();
        if (top.size() < 10 || tier2Leagues.size() < 2) {
            return Map.of(
                    "seasonYear", seasonYear,
                    "directPromotions", List.of(),
                    "directRelegations", List.of(),
                    "playoffResults", List.of()
            );
        }

        List<Map<String, Object>> directRelegations = new ArrayList<>();
        directRelegations.add(Map.of(
                "team", top.get(8).getTeam().getName(),
                "toLeague", tier2Leagues.get(0).getName()
        ));
        directRelegations.add(Map.of(
                "team", top.get(9).getTeam().getName(),
                "toLeague", tier2Leagues.get(1).getName()
        ));

        List<Map<String, Object>> directPromotions = new ArrayList<>();
        for (Competition lowerLeague : tier2Leagues.subList(0, 2)) {
            SeasonCompetition lowerSc = seasonCompetitionRepository.findByCompetitionAndSeasonYear(lowerLeague, seasonYear).orElse(null);
            if (lowerSc == null) {
                continue;
            }
            List<CompetitionEntry> lowerTable = sortTable(competitionEntryRepository.findBySeasonCompetition(lowerSc));
            if (lowerTable.isEmpty()) {
                continue;
            }
            directPromotions.add(Map.of(
                    "team", lowerTable.getFirst().getTeam().getName(),
                    "fromLeague", lowerLeague.getName()
            ));
        }

        SeasonCompetition nextTopSc = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(superLiga, seasonYear + 1)
                .orElse(null);
        Set<Long> nextSeasonSuperLigaTeamIds = nextTopSc == null
                ? Set.of()
                : competitionEntryRepository.findBySeasonCompetition(nextTopSc).stream()
                .map(CompetitionEntry::getTeam)
                .filter(Objects::nonNull)
                .map(Team::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<Map<String, Object>> playoffResults = matchFixtureRepository
                .findByCompetitionIdAndSeasonYearAndRoundNumberOrderByMatchDateAsc(superLiga.getId(), seasonYear, PLAYOFF_WEEK)
                .stream()
                .filter(fixture -> fixture.getHomeTeam() != null && fixture.getAwayTeam() != null)
                .map(fixture -> toPlayoffResultSummary(fixture, nextSeasonSuperLigaTeamIds))
                .toList();

        return Map.of(
                "seasonYear", seasonYear,
                "directPromotions", directPromotions,
                "directRelegations", directRelegations,
                "playoffResults", playoffResults
        );
    }

    private Team resolvePlayoffWinner(Team topTeam, Team lowerTeam) {
        double topRep = topTeam.getReputation() != null ? topTeam.getReputation() : 50.0;
        double lowerRep = lowerTeam.getReputation() != null ? lowerTeam.getReputation() : 45.0;
        double topChance = Math.max(0.35, Math.min(0.72, (topRep + 8.0) / (topRep + lowerRep + 8.0)));
        return random.nextDouble() < topChance ? topTeam : lowerTeam;
    }

    private Team resolvePlayoffWinner(Competition superLiga, int seasonYear, Team topTeam, Team lowerTeam) {
        MatchFixture playedFixture = matchFixtureRepository
                .findByCompetitionIdAndSeasonYearAndRoundNumberOrderByMatchDateAsc(superLiga.getId(), seasonYear, PLAYOFF_WEEK)
                .stream()
                .filter(fixture -> fixture.getHomeTeam() != null && fixture.getAwayTeam() != null)
                .filter(fixture -> Objects.equals(fixture.getHomeTeam().getId(), topTeam.getId()))
                .filter(fixture -> Objects.equals(fixture.getAwayTeam().getId(), lowerTeam.getId()))
                .findFirst()
                .orElse(null);

        if (playedFixture != null && playedFixture.getPlayedMatch() != null) {
            Match playedMatch = playedFixture.getPlayedMatch();
            if (playedMatch.getHomeGoals() > playedMatch.getAwayGoals()) {
                return topTeam;
            }
            if (playedMatch.getAwayGoals() > playedMatch.getHomeGoals()) {
                return lowerTeam;
            }
        }
        return resolvePlayoffWinner(topTeam, lowerTeam);
    }

    private List<Competition> findTier2Leagues() {
        return findSerbianLeagues().stream()
                .filter(c -> c.getCountry() != null && "SRB".equalsIgnoreCase(c.getCountry().getIsoCode()))
                .filter(c -> Objects.equals(c.getTier(), 2))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Competition> getSerbianLeaguesInOrder() {
        return findSerbianLeagues();
    }

    private List<Competition> findSerbianLeagues() {
        return competitionRepository.findByCountryIsoCodeAndTypeOrderByTierAscDivisionLevelAscIdAsc("SRB", CompetitionType.LEAGUE);
    }

    private Map<String, Object> toPlayoffResultSummary(MatchFixture fixture, Set<Long> nextSeasonSuperLigaTeamIds) {
        Match playedMatch = fixture.getPlayedMatch();
        Team winner = determineArchivedPlayoffWinner(fixture, nextSeasonSuperLigaTeamIds);

        return Map.of(
                "homeTeam", fixture.getHomeTeam().getName(),
                "awayTeam", fixture.getAwayTeam().getName(),
                "homeGoals", playedMatch != null ? playedMatch.getHomeGoals() : 0,
                "awayGoals", playedMatch != null ? playedMatch.getAwayGoals() : 0,
                "winner", winner != null ? winner.getName() : "TBD"
        );
    }

    private Team determineArchivedPlayoffWinner(MatchFixture fixture, Set<Long> nextSeasonSuperLigaTeamIds) {
        Match playedMatch = fixture.getPlayedMatch();
        if (playedMatch != null) {
            if (playedMatch.getHomeGoals() > playedMatch.getAwayGoals()) {
                return fixture.getHomeTeam();
            }
            if (playedMatch.getAwayGoals() > playedMatch.getHomeGoals()) {
                return fixture.getAwayTeam();
            }
        }

        Long homeId = fixture.getHomeTeam() != null ? fixture.getHomeTeam().getId() : null;
        Long awayId = fixture.getAwayTeam() != null ? fixture.getAwayTeam().getId() : null;
        boolean homeStayedOrPromoted = homeId != null && nextSeasonSuperLigaTeamIds.contains(homeId);
        boolean awayStayedOrPromoted = awayId != null && nextSeasonSuperLigaTeamIds.contains(awayId);

        if (homeStayedOrPromoted ^ awayStayedOrPromoted) {
            return homeStayedOrPromoted ? fixture.getHomeTeam() : fixture.getAwayTeam();
        }

        if (fixture.getHomeTeam() == null || fixture.getAwayTeam() == null) {
            return null;
        }

        return resolvePlayoffWinner(fixture.getHomeTeam(), fixture.getAwayTeam());
    }

    private List<CompetitionEntry> sortTable(List<CompetitionEntry> entries) {
        return entries.stream()
                .sorted(Comparator
                        .comparingInt((CompetitionEntry e) -> safe(e.getPoints())).reversed()
                        .thenComparing(Comparator.comparingInt((CompetitionEntry e) -> safe(e.getGoalsScored()) - safe(e.getGoalsConceded())).reversed())
                        .thenComparing(Comparator.comparingInt((CompetitionEntry e) -> safe(e.getGoalsScored())).reversed())
                        .thenComparing(e -> e.getTeam() != null ? e.getTeam().getId() : Long.MAX_VALUE))
                .toList();
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }
}
