package org.example.footballmanager.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.*;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.model.JuniorStatus;
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
    public static final int LEAGUE_ROUNDS = 18;
    public static final int PLAYOFF_WEEK = 19;
    public static final int FRIENDLY_WEEK = 20;

    private final GameClockRepository gameClockRepository;
    private final SeasonRepository seasonRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final CompetitionRepository competitionRepository;
    private final MatchFixtureRepository matchFixtureRepository;
    private final TeamRepository teamRepository;
    private final PlayerRepository playerRepository;
    private final JuniorRepository juniorRepository;
    private final YouthAcademyService youthAcademyService;
    private final TransferService transferService;
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

            competitionEntryRepository.deleteAll(existing);
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
                fixture.setWeekNumber(round + 1);
                fixture.setMatchDate(startDate.plusWeeks(round));
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
            reverse.setWeekNumber(base.getWeekNumber() + rounds);
            reverse.setMatchDate(base.getMatchDate().plusWeeks(rounds));
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

        List<Team> lowerRunners = new ArrayList<>();
        for (Competition lowerLeague : tier2Leagues.subList(0, 2)) {
            SeasonCompetition lowerSc = seasonCompetitionRepository.findByCompetitionAndSeasonYear(lowerLeague, seasonYear).orElse(null);
            if (lowerSc == null) continue;
            List<CompetitionEntry> lowerTable = sortTable(competitionEntryRepository.findBySeasonCompetition(lowerSc));
            if (lowerTable.size() > 1) lowerRunners.add(lowerTable.get(1).getTeam());
        }
        if (lowerRunners.size() < 2) return;

        GameClock clock = getOrCreateClock();
        MatchFixture m1 = new MatchFixture();
        m1.setHomeTeam(top.get(6).getTeam());
        m1.setAwayTeam(lowerRunners.get(0));
        m1.setCompetition(superLiga);
        m1.setSeasonYear(seasonYear);
        m1.setRoundNumber(PLAYOFF_WEEK);
        m1.setWeekNumber(PLAYOFF_WEEK);
        m1.setMatchDate(clock.getCurrentDate().plusWeeks(PLAYOFF_WEEK - clock.getCurrentWeek()));
        m1.setPlayed(false);

        MatchFixture m2 = new MatchFixture();
        m2.setHomeTeam(top.get(7).getTeam());
        m2.setAwayTeam(lowerRunners.get(1));
        m2.setCompetition(superLiga);
        m2.setSeasonYear(seasonYear);
        m2.setRoundNumber(PLAYOFF_WEEK);
        m2.setWeekNumber(PLAYOFF_WEEK);
        m2.setMatchDate(clock.getCurrentDate().plusWeeks(PLAYOFF_WEEK - clock.getCurrentWeek()));
        m2.setPlayed(false);
        matchFixtureRepository.saveAll(List.of(m1, m2));
    }

    @Transactional
    public void ensureFriendlyWeekFixtures(Competition superLiga, int seasonYear) {
        List<MatchFixture> existing = matchFixtureRepository.findByCompetitionIdAndSeasonYearAndRoundNumberOrderByMatchDateAsc(
                superLiga.getId(), seasonYear, FRIENDLY_WEEK
        );
        if (!existing.isEmpty()) return;

        SeasonCompetition sc = seasonCompetitionRepository.findByCompetitionAndSeasonYear(superLiga, seasonYear).orElse(null);
        if (sc == null) return;
        List<Team> teams = competitionEntryRepository.findBySeasonCompetition(sc).stream()
                .map(CompetitionEntry::getTeam)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Collections.shuffle(teams);
        GameClock clock = getOrCreateClock();
        List<MatchFixture> fixtures = new ArrayList<>();
        for (int i = 0; i + 1 < teams.size(); i += 2) {
            MatchFixture m = new MatchFixture();
            m.setHomeTeam(teams.get(i));
            m.setAwayTeam(teams.get(i + 1));
            m.setCompetition(superLiga);
            m.setSeasonYear(seasonYear);
            m.setRoundNumber(FRIENDLY_WEEK);
            m.setWeekNumber(FRIENDLY_WEEK);
            m.setMatchDate(clock.getCurrentDate().plusWeeks(FRIENDLY_WEEK - clock.getCurrentWeek()));
            m.setPlayed(false);
            fixtures.add(m);
        }
        matchFixtureRepository.saveAll(fixtures);
    }

    @Transactional
    public void advanceWeekAndHandleSeasonTransition(Competition superLiga) {
        GameClock clock = getOrCreateClock();
        int week = clock.getCurrentWeek() == null ? 1 : clock.getCurrentWeek();
        if (week < FRIENDLY_WEEK) {
            clock.setCurrentWeek(week + 1);
            clock.setCurrentDate(clock.getCurrentDate().plusWeeks(1));
            gameClockRepository.save(clock);
            decrementInjuriesByWeek();
            int seasonNumber = clock.getCurrentSeason() == null ? 1 : clock.getCurrentSeason();
            int newWeek = clock.getCurrentWeek();
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
        SeasonCompetition topSc = seasonCompetitionRepository.findByCompetitionAndSeasonYear(superLiga, seasonYear).orElse(null);
        if (topSc == null) return;
        List<CompetitionEntry> top = sortTable(competitionEntryRepository.findBySeasonCompetition(topSc));
        if (top.size() < 10) return;

        List<Competition> tier2Leagues = competitionRepository
                .findByCountryIsoCodeAndTypeAndTierOrderByDivisionLevelAscIdAsc("SRB", CompetitionType.LEAGUE, 2);
        if (tier2Leagues.size() < 2) return;

        List<Team> stayingTop = List.of(
                top.get(0).getTeam(),
                top.get(1).getTeam(),
                top.get(2).getTeam(),
                top.get(3).getTeam(),
                top.get(4).getTeam(),
                top.get(5).getTeam()
        );
        List<Team> relegated = List.of(top.get(8).getTeam(), top.get(9).getTeam());
        List<Team> playoffTop = List.of(top.get(6).getTeam(), top.get(7).getTeam());

        List<Team> promotedDirect = new ArrayList<>();
        List<Team> playoffLower = new ArrayList<>();
        for (Competition lowerLeague : tier2Leagues.subList(0, 2)) {
            SeasonCompetition lowerSc = seasonCompetitionRepository.findByCompetitionAndSeasonYear(lowerLeague, seasonYear).orElse(null);
            if (lowerSc == null) continue;
            List<CompetitionEntry> lowerTable = sortTable(competitionEntryRepository.findBySeasonCompetition(lowerSc));
            if (!lowerTable.isEmpty()) promotedDirect.add(lowerTable.get(0).getTeam());
            if (lowerTable.size() > 1) playoffLower.add(lowerTable.get(1).getTeam());
        }
        if (promotedDirect.size() < 2 || playoffLower.size() < 2) return;

        Team playoffWinnerA = resolvePlayoffWinner(superLiga, seasonYear, playoffTop.get(0), playoffLower.get(0));
        Team playoffWinnerB = resolvePlayoffWinner(superLiga, seasonYear, playoffTop.get(1), playoffLower.get(1));

        for (int i = 0; i < relegated.size() && i < tier2Leagues.size(); i++) {
            relegated.get(i).setCompetition(tier2Leagues.get(i));
        }

        for (Team team : stayingTop) {
            team.setCompetition(superLiga);
        }
        for (Team team : promotedDirect) {
            team.setCompetition(superLiga);
        }

        if (Objects.equals(playoffWinnerA.getId(), playoffLower.get(0).getId())) {
            playoffTop.get(0).setCompetition(tier2Leagues.get(0));
            playoffLower.get(0).setCompetition(superLiga);
        } else {
            playoffTop.get(0).setCompetition(superLiga);
            playoffLower.get(0).setCompetition(tier2Leagues.get(0));
        }
        if (Objects.equals(playoffWinnerB.getId(), playoffLower.get(1).getId())) {
            playoffTop.get(1).setCompetition(tier2Leagues.get(1));
            playoffLower.get(1).setCompetition(superLiga);
        } else {
            playoffTop.get(1).setCompetition(superLiga);
            playoffLower.get(1).setCompetition(tier2Leagues.get(1));
        }

        List<Team> updatedTeams = new ArrayList<>();
        updatedTeams.addAll(stayingTop);
        updatedTeams.addAll(relegated);
        updatedTeams.addAll(promotedDirect);
        updatedTeams.addAll(playoffTop);
        updatedTeams.addAll(playoffLower);
        teamRepository.saveAll(updatedTeams);

        long superLigaCount = teamRepository.countByCompetition(superLiga);
        log.info("Promotion/relegation applied for season {}. Superliga now has {} teams.", seasonYear, superLigaCount);
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
