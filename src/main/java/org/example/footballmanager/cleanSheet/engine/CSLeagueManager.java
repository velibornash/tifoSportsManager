package org.example.footballmanager.cleanSheet.engine;

import org.example.footballmanager.cleanSheet.model.*;
import org.example.footballmanager.cleanSheet.state.CleanSheetGameState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Upravljanje ligom — raspored, tabela, simulacija ostalih meceva.
 * Cista Java, bez JPA.
 */
public class CSLeagueManager {

    private final CSMatchSimulator simulator = new CSMatchSimulator();
    private final Random random = new Random();

    /**
     * Generise round-robin raspored (svako sa svakim, kuca i gost).
     * Za N timova: (N-1)*2 kola, N/2 meceva po kolu.
     */
    public List<CSFixture> generateSchedule(List<CSTeam> teams) {
        List<CSFixture> fixtures = new ArrayList<>();
        int n = teams.size();
        if (n < 2) return fixtures;

        // Standard round-robin algorithm
        List<CSTeam> list = new ArrayList<>(teams);

        // Ako je neparan broj, dodaj "bye" (null)
        boolean hasBye = n % 2 != 0;
        if (hasBye) {
            list.add(null);
            n = list.size();
        }

        int totalRounds = (n - 1) * 2; // kuca + gost
        int matchesPerRound = n / 2;

        // Prva polovina — klasican round-robin
        for (int round = 0; round < n - 1; round++) {
            for (int match = 0; match < matchesPerRound; match++) {
                int homeIdx = match;
                int awayIdx = n - 1 - match;

                CSTeam home = list.get(homeIdx);
                CSTeam away = list.get(awayIdx);

                if (home == null || away == null) continue; // bye

                fixtures.add(CSFixture.builder()
                        .round(round + 1)
                        .homeTeamId(home.getId())
                        .homeTeamName(home.getName())
                        .awayTeamId(away.getId())
                        .awayTeamName(away.getName())
                        .played(false)
                        .build());
            }
            // Rotiraj sve osim prvog
            CSTeam last = list.remove(n - 1);
            list.add(1, last);
        }

        // Druga polovina — obrnuti domacin/gost
        int firstHalfSize = fixtures.size();
        for (int i = 0; i < firstHalfSize; i++) {
            CSFixture f = fixtures.get(i);
            fixtures.add(CSFixture.builder()
                    .round(f.getRound() + (n - 1))
                    .homeTeamId(f.getAwayTeamId())
                    .homeTeamName(f.getAwayTeamName())
                    .awayTeamId(f.getHomeTeamId())
                    .awayTeamName(f.getHomeTeamName())
                    .played(false)
                    .build());
        }

        return fixtures;
    }

    /**
     * Generise random rivalstva izmedju timova (1-3 rivala po timu).
     * Ovo se koristi da oznaci derbi meceve.
     */
    public Map<Long, Set<Long>> generateDerbyRivalries(List<CSTeam> teams) {
        Map<Long, Set<Long>> rivalMap = new HashMap<>();
        List<CSTeam> others = new ArrayList<>(teams);

        for (CSTeam team : teams) {
            Set<Long> rivals = new HashSet<>();
	            List<CSTeam> available = new ArrayList<>(others.stream()
	                    .filter(t -> !t.getId().equals(team.getId()))
	                    .toList());
            Collections.shuffle(available, random);
            int count = 1 + random.nextInt(3);
            for (int i = 0; i < Math.min(count, available.size()); i++) {
                rivals.add(available.get(i).getId());
                rivalMap.computeIfAbsent(available.get(i).getId(), key -> new HashSet<>()).add(team.getId());
            }
            rivalMap.put(team.getId(), rivals);
        }
        return rivalMap;
    }

    /**
     * Postavlja derby flag na sve fixture-e na osnovu mape rivala.
     */
    public void applyDerbyFlags(List<CSFixture> fixtures, Map<Long, Set<Long>> derbyRivalries) {
        for (CSFixture fixture : fixtures) {
            Set<Long> homeRivals = derbyRivalries.getOrDefault(fixture.getHomeTeamId(), Set.of());
            fixture.setDerby(homeRivals.contains(fixture.getAwayTeamId()));
        }
    }

    /**
     * Inicijalizuje tabelu za sve timove (sve na nuli).
     */
    public List<CSTableEntry> initializeTable(List<CSTeam> teams) {
        List<CSTableEntry> table = new ArrayList<>();
        for (CSTeam team : teams) {
            table.add(CSTableEntry.builder()
                    .teamId(team.getId())
                    .teamName(team.getName())
                    .points(0).wins(0).draws(0).losses(0)
                    .goalsScored(0).goalsConceded(0).played(0)
                    .build());
        }
        return table;
    }

    /**
     * Azurira tabelu na osnovu rezultata jednog meca.
     */
    public void updateTable(List<CSTableEntry> table, CSMatchResult result) {
        CSTableEntry homeEntry = table.stream()
                .filter(e -> e.getTeamId().equals(result.getHomeTeamId()))
                .findFirst().orElse(null);
        CSTableEntry awayEntry = table.stream()
                .filter(e -> e.getTeamId().equals(result.getAwayTeamId()))
                .findFirst().orElse(null);

        if (homeEntry == null || awayEntry == null) return;

        int hg = result.getHomeGoals();
        int ag = result.getAwayGoals();

        homeEntry.setPlayed(homeEntry.getPlayed() + 1);
        awayEntry.setPlayed(awayEntry.getPlayed() + 1);

        homeEntry.setGoalsScored(homeEntry.getGoalsScored() + hg);
        homeEntry.setGoalsConceded(homeEntry.getGoalsConceded() + ag);
        awayEntry.setGoalsScored(awayEntry.getGoalsScored() + ag);
        awayEntry.setGoalsConceded(awayEntry.getGoalsConceded() + hg);

        if (hg > ag) {
            homeEntry.setPoints(homeEntry.getPoints() + 3);
            homeEntry.setWins(homeEntry.getWins() + 1);
            awayEntry.setLosses(awayEntry.getLosses() + 1);
        } else if (hg == ag) {
            homeEntry.setPoints(homeEntry.getPoints() + 1);
            awayEntry.setPoints(awayEntry.getPoints() + 1);
            homeEntry.setDraws(homeEntry.getDraws() + 1);
            awayEntry.setDraws(awayEntry.getDraws() + 1);
        } else {
            awayEntry.setPoints(awayEntry.getPoints() + 3);
            awayEntry.setWins(awayEntry.getWins() + 1);
            homeEntry.setLosses(homeEntry.getLosses() + 1);
        }

        table.sort(Comparator
                .comparingInt(CSTableEntry::getPoints).reversed()
                .thenComparing(Comparator.comparingInt(CSTableEntry::getGoalDifference).reversed())
                .thenComparing(Comparator.comparingInt(CSTableEntry::getGoalsScored).reversed()));
    }

    /**
     * Simulira sve meceve u kolu osim korisnikovog.
     * Korisnikov mec se obradjuje odvojeno (sa punom simulacijom).
     */
    public List<CSMatchResult> simulateRound(CleanSheetGameState state, int round,
                                             CSMatchResult userMatchResult) {
        List<CSMatchResult> roundResults = new ArrayList<>();

        if (userMatchResult != null) {
            roundResults.add(userMatchResult);
            updateTable(state.getLeagueTable(), userMatchResult);
        }

        List<CSFixture> roundFixtures = state.getSchedule().stream()
                .filter(f -> f.getRound() == round && !f.isPlayed())
                .toList();

        for (CSFixture fixture : roundFixtures) {
            if (userMatchResult != null
                    && fixture.getHomeTeamId().equals(userMatchResult.getHomeTeamId())
                    && fixture.getAwayTeamId().equals(userMatchResult.getAwayTeamId())) {
                fixture.setPlayed(true);
                fixture.setResult(userMatchResult);
                continue;
            }

            CSTeam home = state.getAllTeams().stream()
                    .filter(t -> t.getId().equals(fixture.getHomeTeamId()))
                    .findFirst().orElse(null);
            CSTeam away = state.getAllTeams().stream()
                    .filter(t -> t.getId().equals(fixture.getAwayTeamId()))
                    .findFirst().orElse(null);

            if (home == null || away == null) continue;

            List<CSPlayer> homePlayers = state.getAllTeamRosters().getOrDefault(home.getId(), List.of());
            List<CSPlayer> awayPlayers = state.getAllTeamRosters().getOrDefault(away.getId(), List.of());

            List<Long> userStarterIds = state.getTactics() != null ? state.getTactics().getStarterIds() : List.of();
            List<Long> userBenchIds = state.getTactics() != null ? state.getTactics().getBenchIds() : List.of();
            List<CSPlayer> homeStarters = simulator.pickStartingEleven(
                    homePlayers,
                    home.getId().equals(state.getUserTeam().getId()) ? userStarterIds : List.of()
            );
            List<CSPlayer> homeBench = simulator.pickBenchPlayers(
                    homePlayers,
                    homeStarters,
                    home.getId().equals(state.getUserTeam().getId()) ? userBenchIds : List.of()
            );
            List<CSPlayer> awayStarters = simulator.pickStartingEleven(
                    awayPlayers,
                    away.getId().equals(state.getUserTeam().getId()) ? userStarterIds : List.of()
            );
            List<CSPlayer> awayBench = simulator.pickBenchPlayers(
                    awayPlayers,
                    awayStarters,
                    away.getId().equals(state.getUserTeam().getId()) ? userBenchIds : List.of()
            );

            CSMatchResult result = simulator.simulateQuick(home, homeStarters, homeBench, away, awayStarters, awayBench, round);

            fixture.setPlayed(true);
            fixture.setResult(result);

            updateTable(state.getLeagueTable(), result);
            roundResults.add(result);
        }

        return roundResults;
    }
}
