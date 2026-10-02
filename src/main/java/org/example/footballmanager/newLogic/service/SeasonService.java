package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.util.LeagueTableOrder;
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

    // A season is a NUMBER, counted from 1, and `season_year` on every table carries that number.
    //
    // This used to be a calendar year: BASE_SEASON_YEAR = 2025, and callers asked for
    // BASE_SEASON_YEAR + (season - 1). The offset is gone, because a manager's season is twelve
    // weeks and roughly four run in a year (SeasonCalendar), so a calendar year cannot name one.
    // It was also the cause of the cup page reporting "0 ties across 8 rounds" over a bracket the
    // seeder had just reported as drawn - two artefacts that disagreed, each with a hardcoded
    // season index, and the fix that had gone in before moved the seeder onto the year rather than
    // removing the offset. One number, written once, is the only thing that cannot drift.
    //
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
    private final SquadTrainingService squadTraining;
    private final SquadEnvironmentService squadEnvironment;
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
        // A row created before the day/hour columns existed has them null, and an entity field
        // default only applies to rows Hibernate inserts - it does not backfill. Reading that null
        // made /api/game-clock report hour=null, so the day is seeded here instead of being patched
        // in the database by hand.
        if (clock.getCurrentDay() == null) {
            clock.setCurrentDay(GameDay.FIRST);
        }
        if (clock.getCurrentHour() == null) {
            // Before the first kickoff of the day, not at kickoff: the day starts at 09:00 so
            // "Watch match" is correctly inactive until the hour reaches the fixture.
            clock.setCurrentHour(9);
        }
        if (clock.getCurrentSeason() != null && clock.getCurrentSeason() > 1000) {
            // A calendar year means this clock predates the season-number scheme. Every such world
            // was still on its first season, so it maps to 1 - not to an arithmetic guess that would
            // need the year it came from, which is the constant this change exists to delete.
            clock.setCurrentSeason(1);
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

    /**
     * The season the world is in, as a NUMBER counted from 1.
     *
     * <p>Named for the {@code season_year} column it is compared against on fixtures, elections,
     * finance and every competition table. It is a season number, not a calendar year, and the
     * difference is the whole point: the season is twelve weeks, so four of them run in a year and a
     * year cannot identify one.
     */
    public int getActiveSeasonYear() {
        GameClock clock = getOrCreateClock();
        return clock.getCurrentSeason() == null ? 1 : clock.getCurrentSeason();
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
            season.setDescription("Season " + year);
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

    /**
     * Make sure every club in the competition has a row in this season's table, and no club that
     * left still has one.
     *
     * <p>It used to <b>delete every entry and rebuild from zero</b> whenever membership was not an
     * exact set match. That made it a data-loss path on the read side, because the league table
     * endpoint calls this before it reads: one team joining a division, or one leaving it, reset
     * every other club's points, wins, draws, losses and goals to zero, mid-season, for a manager
     * who had done nothing but open the page.
     *
     * <p>Now the difference is applied as a difference. A club that is new gets a row at zero. A club
     * that is no longer in the competition loses its row, because its results do not belong to this
     * table. Every other row is left exactly as it is, with its record intact.
     */
    @Transactional
    public void ensureEntriesForSeasonCompetition(Competition competition, int seasonYear) {
        SeasonCompetition sc = ensureSeasonCompetition(competition, seasonYear);
        List<CompetitionEntry> existing = competitionEntryRepository.findBySeasonCompetition(sc);
        List<Team> currentLeagueTeams = teamRepository.findByCompetitionId(competition.getId());

        Set<Long> existingTeamIds = existing.stream()
                .map(CompetitionEntry::getTeam)
                .filter(Objects::nonNull)
                .map(Team::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> currentTeamIds = currentLeagueTeams.stream()
                .map(Team::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (existingTeamIds.equals(currentTeamIds)) {
            return;
        }

        // A club that has left the competition, and one whose row was left behind by a half-created
        // entry, cannot be distinguished here and are treated the same: the row goes, because a
        // result scored for a club that is not in this table is not this table's result.
        List<CompetitionEntry> stale = existing.stream()
                .filter(entry -> entry.getTeam() == null
                        || entry.getTeam().getId() == null
                        || !currentTeamIds.contains(entry.getTeam().getId()))
                .toList();
        for (CompetitionEntry entry : stale) {
            if (entry.getId() != null) {
                competitionEntryRepository.deleteById(entry.getId());
            }
        }

        List<CompetitionEntry> entriesToCreate = new ArrayList<>();
        for (Team t : currentLeagueTeams) {
            if (t.getId() == null || existingTeamIds.contains(t.getId())) {
                // Already has a row, and that row carries the season so far. Recreating it here is
                // what used to throw the season away.
                continue;
            }
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
        log.info("Season entries for {} season {} adjusted: {} removed, {} added. Records of {} "
                        + "existing club(s) left untouched.",
                competition.getName(), seasonYear, stale.size(), entriesToCreate.size(),
                existingTeamIds.size() - stale.size());
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
                // The calendar decides both the week AND the day. Previously this computed its own
                // week and the return-leg loop then copied it, so week 1 collected round 1, round 2
                // and both of their return legs - four rounds in a week, a club playing four times.
                // That was the real cause of the 775/2015 day split, not the day labelling.
                LeagueSlotSchedule.Placement placement = LeagueSlotSchedule.forRound(round + 1);
                if (placement == null) {
                    // More rounds than the calendar schedules. Skipped rather than guessed, because a
                    // guessed week is how this went wrong in the first place.
                    continue;
                }
                fixture.setWeekNumber(placement.week());
                // Only a relative offset is kept: the season is twelve weeks and has no months in
                // it, so this is used for ordering only and never shown to a manager.
                fixture.setDayNumber(placement.day());
                fixture.setMatchDate(kickoffFor(startDate, placement));
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
            // The return leg asks the calendar where IT goes. Copying the first leg's week is what
            // stacked four rounds into one week; a return leg belongs in its own round slot, which
            // for a double round-robin is the second half of the season.
            reverse.setRoundNumber(base.getRoundNumber() + rounds);
            LeagueSlotSchedule.Placement returnPlacement =
                    LeagueSlotSchedule.forRound(base.getRoundNumber() + rounds);
            if (returnPlacement == null) {
                continue;
            }
            reverse.setWeekNumber(returnPlacement.week());
            reverse.setDayNumber(returnPlacement.day());
            reverse.setMatchDate(startDate.plusWeeks(returnPlacement.week() - 1L));
            reverse.setPlayed(false);
            fixtures.add(reverse);
        }
        matchFixtureRepository.saveAll(fixtures);
        log.info("Generated double round-robin schedule for league {} season {} with {} fixtures",
                competition.getName(), seasonYear, fixtures.size());
    }

    /**
     * When a fixture is kicked off: the week offset, and the hour the schedule template says.
     *
     * <p>The hour used to be inherited from {@code clock.getCurrentDate()}, which is wall-clock time
     * advanced by the offset - so <b>every fixture in the world carried the minute the world happened to
     * be created</b>. The owner saw 17:16 on a league fixture and asked where it came from, and the
     * answer was "when I seeded it". The template says day 3 is 19:00 and day 7 is 16:00, and that is
     * what a fixture now carries.
     *
     * <p>The week offset is still kept because the column is what the schedule is ordered by. It is
     * still not a calendar - there is no date in this game, only a season, a week, a day and an hour.
     */
    private LocalDateTime kickoffFor(LocalDateTime startDate, LeagueSlotSchedule.Placement placement) {
        LocalDateTime weekStart = startDate.plusWeeks(placement.week() - 1L);
        Integer kickoffHour = org.example.footballmanager.newLogic.model.GameDay
                .of(placement.day())
                .kickoffHour();
        // A day with no fixture has no kickoff hour and the schedule says so. Falling back to the start
        // date's own time would put the wall-clock stamp straight back.
        if (kickoffHour == null) {
            kickoffHour = 12;
        }
        return weekStart.withHour(kickoffHour).withMinute(0).withSecond(0).withNano(0);
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
        // Every lower league, not the first two: the mover iterates them all, so the summary
        // reporting two would omit a promotion the game actually makes. subList(0, 2) also threw on a
        // country with fewer than two lower leagues.
        for (Competition lowerLeague : tier2Leagues) {
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
        // The same comparator the table is drawn with. This stopped at goal difference, so two
        // runners-up level on points and goal difference were ordered arbitrarily here while the
        // table ordered them by goals scored - which is the same stronger-club-gets-the-easier-tie
        // bug the comment above describes, reintroduced by a comparator one key short.
        List<CompetitionEntry> ranked = new ArrayList<>(LeagueTableOrder.sort(lowerRunners));
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
            // Everyone trains. The owner's rule is that AI clubs do nothing except that their
            // players still improve - a league where three hundred clubs never train is a league
            // where a manager's own training means nothing. This is the floor under a club's chosen
            // programme, not a replacement for it, and it goes through the same percentage maths.
            int defaultTrained = squadTraining.trainEveryClub(seasonNumber, newWeek);
            if (defaultTrained > 0) {
                log.info("Week {} season {}: default training applied to {} players",
                        newWeek, seasonNumber, defaultTrained);
            }
            // The social side of the squad moves after the work, not before: familiarity follows the
            // minutes just played, and cohesion follows how many faces are still new to the system.
            // Doing it first would have credited a player with the week he is about to play.
            int squadsAdvanced = squadEnvironment.advanceWeek(seasonNumber, newWeek);
            if (squadsAdvanced > 0) {
                log.info("Week {} season {}: squad environment advanced for {} clubs",
                        newWeek, seasonNumber, squadsAdvanced);
            }

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
        performPromotionRelegationAndNewSeason();
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
    /**
     * The week-scoped maintenance that used to be inline in advance-week (owner, 2026-09-28).
     *
     * <p>Extracted so {@code WeekRolloverJob} can call it through the service instead of the four
     * pieces being copied into the job. These are protected because only the clock was supposed to
     * call them; the job is now the caller, so there is one public entry point and no second copy
     * of the logic.
     */
    public void applyWeekMaintenance() {
        decrementInjuriesByWeek();
        recoverFatigueForWeek();
        expirePlayerContracts();
    }

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
        // **Only the tired.** This used to be findAll() with a `fatigue <= 0 → continue` inside the
        // loop, so every week it materialised every player in the world — 370,000 rows once the
        // simulated countries are seeded — to recover the tired few, and then saveAll'd the whole list
        // back, changed or not. `findByLastPlayedAtIsNotNull` is the same fix in the other job and the
        // reasoning belongs to both, so it is written down here too rather than left to be rediscovered.
        //
        // saveAll is kept, deliberately: on a list of only the tired players it is cheap, and an
        // explicit write is easier to trust than a reader having to know that the players are managed.
        List<Player> squad = playerRepository.findBySkillsFatigueGreaterThan(0);
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
    public void performPromotionRelegationAndNewSeason() {
        int endingSeasonYear = getActiveSeasonYear();
        applyPromotionRelegation(endingSeasonYear);
        agePlayersAndJuniorsOneYear();

        GameClock clock = getOrCreateClock();
        clock.setCurrentSeason(clock.getCurrentSeason() + 1);
        clock.setCurrentWeek(1);
        clock.setCurrentDate(clock.getCurrentDate().plusWeeks(1));
        gameClockRepository.save(clock);

        int nextSeasonYear = getActiveSeasonYear();
        ensureActiveSeasonEntity();

        int divisionsOpened = openNewSeasonForEveryCountry(nextSeasonYear);

        log.info("Season rollover complete. New season year={}, week=1, {} division(s) opened across {} country/countries.",
                nextSeasonYear, divisionsOpened, allLeagueCompetitionsByCountry().size());
    }

    /**
     * Opens the new season's table rows and fixture list for <b>every country's</b> divisions.
     *
     * <p>This was {@code findSerbianLeagues()}. A country the owner activated got a full 31-division
     * pyramid, played season one, and then had no season two at all: no table rows, no fixtures, nothing
     * for the day-3 and day-7 matchday jobs to select from. Everything the activation built correctly was
     * quietly dropped on the floor one season later, and the country went silent.
     *
     * <p>Takes the season number rather than reading the clock, so it is callable — and testable — without
     * moving the world a season forward.
     *
     * @return how many divisions were opened
     */
    @Transactional
    public int openNewSeasonForEveryCountry(int seasonYear) {
        int opened = 0;
        for (List<Competition> countryLeagues : allLeagueCompetitionsByCountry().values()) {
            for (Competition league : countryLeagues) {
                ensureEntriesForSeasonCompetition(league, seasonYear);
                ensureDoubleRoundRobinSchedule(league, seasonYear);
                resetCompetitionEntriesForSeason(league, seasonYear);
                opened++;
            }
        }
        return opened;
    }

    @Transactional
    /**
     * The year turn: everybody a year older, and anybody out of the academy graduated.
     *
     * <p>Order matters. Juniors are aged <b>first</b> and the graduation window is enforced
     * <b>afterwards</b>, so a junior who was nineteen when the season ended reaches twenty and is
     * promoted in the same pass. Checking the window before the ageing would let a twenty-year-old
     * sit in the academy for a whole extra season.
     */
    protected void agePlayersAndJuniorsOneYear() {
        playerRepository.incrementAgeForAllPlayers();
        juniorRepository.incrementAgeByStatus(JuniorStatus.ACTIVE);

        int season = getActiveSeasonYear();
        int graduated = youthAcademyService.promoteJuniorsPastWindow(season, season);
        if (graduated > 0) {
            log.info("Season {}: {} junior(s) graduated on reaching the age of {}",
                    season, graduated, YouthAcademyService.GRADUATION_MAX_AGE);
        }
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
    /**
     * Runs the promotion ladder for <b>every country that has one</b>.
     *
     * <p>The old signature took a {@code superLiga} and never used it. That unused parameter is what hid
     * the bug underneath: the country was hard-coded to Serbia one method call away, so a caller could
     * hand in Croatia's top flight and get Serbia's ladder, and there was no type or argument that made
     * that visible. It is gone, and the method says which countries it is working on.
     */
    public void applyPromotionRelegation(int seasonYear) {
        int countriesLaddered = 0;
        for (List<Competition> leagues : allLeagueCompetitionsByCountry().values()) {
            if (applyPromotionRelegationForCountry(leagues, seasonYear) > 0) {
                countriesLaddered++;
            }
        }
        if (countriesLaddered > 0) {
            log.info("Season {}: promotion and relegation ran for {} country ladder(s).",
                    seasonYear, countriesLaddered);
        }
    }

    /** One country's ladder. Returns how many clubs changed division. */
    private int applyPromotionRelegationForCountry(List<Competition> countryLeagues, int seasonYear) {
        List<Competition> serbianLeagues = countryLeagues;
        if (serbianLeagues.isEmpty()) {
            return 0;
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

        // Per-division logging for thirty-one divisions times every country is a wall of text that
        // buries the one line that matters, so the count goes up and the detail goes down.
        int moved = updatedTeams.size();
        if (moved > 0) {
            log.info("Season {}: {} club(s) changed division across {} division(s).",
                    seasonYear, moved, serbianLeagues.size());
        }
        return moved;
    }

    /**
     * Where a league's promotion and relegation boundary falls, and who sits on it.
     *
     * <p><b>This exists because the boundary was written down twice and the two copies disagreed.</b>
     * {@code applyPromotionRelegationForLeague} computed it — {@code safeCount = expectedTeams - 2 *
     * movementSlots}, so a sixteen-club league over two lower leagues relegates the 15th and 16th. The
     * summary that <i>tells the manager</i> which clubs those are hardcoded {@code top.get(8)} and
     * {@code top.get(9)}: the 9th and 10th. So the game relegated one pair of clubs and reported another.
     *
     * <p>One definition, called by both. A second copy of a boundary rule is how the answer and the
     * arithmetic drift apart, and here the drift was invisible because both look plausible.
     *
     * @param movementSlots how many clubs move down per lower league — normally the number of child leagues
     */
    record PromotionRelegationBoundary(int expectedTeams, int safeCount,
                                  List<Team> playoffTop, List<Team> relegatedDirect) {
        boolean isUsable() {
            return safeCount >= 1;
        }
    }

    /**
     * Computes the boundary from the league's own size and its number of lower leagues.
     *
     * @return empty-safe: a league too small to have a boundary yields {@code safeCount <= 0}
     */
    private PromotionRelegationBoundary boundaryFor(List<CompetitionEntry> sortedParentTable,
                                                    Integer teamsPerCompetition,
                                                    int movementSlots) {
        if (movementSlots < 1) {
            return new PromotionRelegationBoundary(sortedParentTable.size(), 0, List.of(), List.of());
        }
        int expectedTeams = teamsPerCompetition != null && teamsPerCompetition > 0
                ? teamsPerCompetition
                : sortedParentTable.size();
        int safeCount = expectedTeams - (movementSlots * 2);

        List<Team> playoffTop = new ArrayList<>();
        List<Team> relegatedDirect = new ArrayList<>();
        // Bounds-checked, so a table shorter than the rule expects reports a boundary of nobody rather
        // than throwing an IndexOutOfBounds at a manager.
        for (int i = safeCount; i < safeCount + movementSlots; i++) {
            CompetitionEntry entry = entryAt(sortedParentTable, i);
            if (entry != null && entry.getTeam() != null) {
                playoffTop.add(entry.getTeam());
            }
        }
        for (int i = safeCount + movementSlots; i < safeCount + movementSlots * 2; i++) {
            CompetitionEntry entry = entryAt(sortedParentTable, i);
            if (entry != null && entry.getTeam() != null) {
                relegatedDirect.add(entry.getTeam());
            }
        }
        return new PromotionRelegationBoundary(expectedTeams, safeCount, playoffTop, relegatedDirect);
    }

    private CompetitionEntry entryAt(List<CompetitionEntry> table, int index) {
        return index >= 0 && index < table.size() ? table.get(index) : null;
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
        int movementSlots = childLeagues.size();

        // The one definition of the boundary. buildPlayoffSummary reads the same call, so the clubs the
        // manager is told are being relegated are the clubs that are being relegated.
        PromotionRelegationBoundary boundary = boundaryFor(
                parentTable, parentLeague.getTeamsPerCompetition(), movementSlots);
        int safeCount = boundary.safeCount();
        // The original guard, unchanged: a table shorter than the league claims to have is not something to
        // move clubs out of. An earlier version of this edit wrote a comparison that was always false and
        // silently dropped it.
        if (parentTable.size() < boundary.expectedTeams() || !boundary.isUsable()) {
            return;
        }

        List<Team> playoffTop = new ArrayList<>(boundary.playoffTop());
        List<Team> relegatedDirect = new ArrayList<>(boundary.relegatedDirect());
        for (Team team : playoffTop) {
            teamsById.putIfAbsent(team.getId(), team);
        }
        for (Team team : relegatedDirect) {
            teamsById.putIfAbsent(team.getId(), team);
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

        // **The same boundary the mover uses.** This hardcoded `top.get(8)` and `top.get(9)` — the 9th and
        // 10th — while applyPromotionRelegationForLeague relegated the bottom of the table, which for a
        // sixteen-club league over two lower leagues is the 15th and 16th. So the summary told the manager
        // one pair of clubs were being relegated while a different pair was. Both look plausible, which is
        // why nobody noticed.
        PromotionRelegationBoundary boundary = boundaryFor(
                top, superLiga.getTeamsPerCompetition(), tier2Leagues.size());

        List<Map<String, Object>> directRelegations = new ArrayList<>();
        List<Team> relegated = boundary.relegatedDirect();
        for (int i = 0; i < relegated.size() && i < tier2Leagues.size(); i++) {
            directRelegations.add(Map.of(
                    "team", relegated.get(i).getName(),
                    "toLeague", tier2Leagues.get(i).getName()
            ));
        }

        List<Map<String, Object>> directPromotions = new ArrayList<>();
        // Every lower league, not the first two: the mover iterates them all, so the summary
        // reporting two would omit a promotion the game actually makes. subList(0, 2) also threw on a
        // country with fewer than two lower leagues.
        for (Competition lowerLeague : tier2Leagues) {
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

    /**
     * Serbia's divisions, in ladder order.
     *
     * <p>Kept under its own name because several callers genuinely mean Serbia — the playoff resolution
     * below only knows how to find a tier-2 league in the same country as the top flight, and the
     * playoff tie is resolved against "the top flight" as a single competition. Those are Serbia-shaped
     * assumptions and are called out rather than spread.
     */
    private List<Competition> findSerbianLeagues() {
        return competitionRepository.findByCountryIsoCodeAndTypeOrderByTierAscDivisionLevelAscIdAsc("SRB", CompetitionType.LEAGUE);
    }

    /**
     * Every league division in the world, grouped by country, each country's own ladder in order.
     *
     * <p>This is what the season rollover and the promotion ladder now work from. Both used to call
     * {@link #findSerbianLeagues()}, which meant that a country the owner activated built a full
     * 31-division pyramid, played one season, and then stopped: the rollover created the new season's
     * table rows and fixture lists for Serbia's divisions only, so Croatia's thirty-one divisions had
     * no season two and went silent.
     *
     * <p><b>Asked of the database rather than filtered in Java.</b> This reads every competition in the
     * world — the leagues plus the cups, some 1,500 of them — to keep the leagues, and it did so once
     * per season rollover for each of the two paths that call it.
     */
    private Map<String, List<Competition>> allLeagueCompetitionsByCountry() {
        Map<String, List<Competition>> byCountry = new TreeMap<>();
        for (Competition league : competitionRepository.findByType(CompetitionType.LEAGUE)) {
            if (league.getCountry() == null) {
                continue;
            }
            byCountry.computeIfAbsent(league.getCountry().getIsoCode(), key -> new ArrayList<>()).add(league);
        }
        byCountry.values().forEach(leagues -> leagues.sort(
                Comparator.comparing((Competition c) -> c.getTier() == null ? Integer.MAX_VALUE : c.getTier())
                        .thenComparing(c -> c.getDivisionLevel() == null ? Integer.MAX_VALUE : c.getDivisionLevel())
                        .thenComparing(Competition::getId)));
        return byCountry;
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
        return LeagueTableOrder.sort(entries);
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }
}
