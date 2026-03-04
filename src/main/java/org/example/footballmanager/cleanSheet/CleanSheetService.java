package org.example.footballmanager.cleanSheet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.cleanSheet.engine.CSLeagueManager;
import org.example.footballmanager.cleanSheet.engine.CSMatchSimulator;
import org.example.footballmanager.cleanSheet.model.*;
import org.example.footballmanager.cleanSheet.state.CleanSheetGameState;
import org.example.footballmanager.model.*;
import org.example.footballmanager.repository.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CleanSheetService {

    private final CompetitionRepository competitionRepository;
    private final SeasonCompetitionRepository seasonCompetitionRepository;
    private final CompetitionEntryRepository competitionEntryRepository;
    private final PlayerRepository playerRepository;

    private final Map<Long, CleanSheetGameState> activeGames = new ConcurrentHashMap<>();
    private final CSMatchSimulator matchSimulator = new CSMatchSimulator();
    private final CSLeagueManager leagueManager = new CSLeagueManager();

    /**
     * Pokrece novu igru — cita iz baze JEDNOM, mapira u CS objekte,
     * generise raspored, i cuva u memoriji.
     */
    public CleanSheetGameState startNewGame(Long userId, Team userTeamEntity) {
        log.info("Starting new Clean Sheet game for user {} with team {}", userId, userTeamEntity.getName());

        // 1. Nadji ligu u kojoj je korisnikov tim
        Competition league = userTeamEntity.getCompetition();
        if (league == null) {
            league = competitionRepository.findById(1L)
                    .orElseThrow(() -> new RuntimeException("League not found"));
        }

        int seasonYear = Calendar.getInstance().get(Calendar.YEAR);
        Competition finalLeague = league;
        SeasonCompetition sc = seasonCompetitionRepository
                .findByCompetitionAndSeasonYear(league, seasonYear)
                .orElseGet(() -> seasonCompetitionRepository
                        .findByCompetitionAndSeasonYear(finalLeague, seasonYear - 1)
                        .orElseThrow(() -> new RuntimeException("SeasonCompetition not found")));

        // 2. Ucitaj sve timove u ligi
        List<CompetitionEntry> entries = competitionEntryRepository.findBySeasonCompetition(sc);
        List<Team> teamsInLeague = entries.stream()
                .map(CompetitionEntry::getTeam)
                .filter(Objects::nonNull)
                .toList();

        // 3. Mapiraj timove
        List<CSTeam> csTeams = teamsInLeague.stream()
                .map(CSMapper::toCSTeam)
                .toList();

        CSTeam userTeam = csTeams.stream()
                .filter(t -> t.getId().equals(userTeamEntity.getId()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("User team not found in league"));

        // 4. Mapiraj igrace svih timova
        Map<Long, List<CSPlayer>> allRosters = new HashMap<>();
        for (Team team : teamsInLeague) {
            List<Player> players = playerRepository.findByTeam(team);
            allRosters.put(team.getId(), CSMapper.toCSPlayers(players));
        }

        List<CSPlayer> userRoster = allRosters.getOrDefault(userTeam.getId(), new ArrayList<>());

        // 5. Kreiraj GameState
        CleanSheetGameState state = new CleanSheetGameState();
        state.setUserId(userId);
        state.setSeasonYear(sc.getSeasonYear());
        state.setCurrentRound(1);
        state.setUserTeam(userTeam);
        state.setRoster(userRoster);
        state.setAllTeams(new ArrayList<>(csTeams));
        state.setAllTeamRosters(allRosters);
        state.setTactics(CSTactics.builder().build());

        // 6. Inicijalizuj tabelu (sve na nuli — nova sezona)
        state.setLeagueTable(leagueManager.initializeTable(csTeams));

        // 7. Generisi raspored
        state.setSchedule(leagueManager.generateSchedule(csTeams));

        // 8. Welcome poruka
        state.addInboxMessage("welcome",
                "Dobrodosao u Clean Sheet! Upravljas timom " + userTeam.getName() +
                ". Sezona " + sc.getSeasonYear() + "/" + (sc.getSeasonYear() + 1) +
                ". Liga ima " + csTeams.size() + " timova, " + state.getTotalRounds() + " kola. Srecno!");

        // 9. Sacuvaj
        activeGames.put(userId, state);
        log.info("Clean Sheet game started: {} teams, {} rounds, {} players for user team",
                csTeams.size(), state.getTotalRounds(), userRoster.size());

        return state;
    }

    /**
     * Odigraj sledece kolo — simulira korisnikov mec (puna simulacija)
     * i sve ostale meceve u kolu (brza simulacija).
     */
    public Map<String, Object> advanceRound(Long userId) {
        CleanSheetGameState state = getStateOrThrow(userId);

        if (state.isSeasonOver()) {
            throw new RuntimeException("Sezona je zavrsena!");
        }

        int round = state.getCurrentRound();

        // Nadji korisnikov mec u ovom kolu
        CSFixture userFixture = state.getSchedule().stream()
                .filter(f -> f.getRound() == round)
                .filter(f -> f.getHomeTeamId().equals(state.getUserTeam().getId())
                        || f.getAwayTeamId().equals(state.getUserTeam().getId()))
                .findFirst()
                .orElse(null);

        CSMatchResult userResult = null;
        if (userFixture != null && !userFixture.isPlayed()) {
            CSTeam home = findTeam(state, userFixture.getHomeTeamId());
            CSTeam away = findTeam(state, userFixture.getAwayTeamId());

            List<CSPlayer> homePlayers = state.getAllTeamRosters()
                    .getOrDefault(home.getId(), List.of());
            List<CSPlayer> awayPlayers = state.getAllTeamRosters()
                    .getOrDefault(away.getId(), List.of());

            // Korisnikova taktika se koristi za njegov tim
            CSTactics homeTactics = home.getId().equals(state.getUserTeam().getId())
                    ? state.getTactics() : CSTactics.builder().build();
            CSTactics awayTactics = away.getId().equals(state.getUserTeam().getId())
                    ? state.getTactics() : CSTactics.builder().build();

            userResult = matchSimulator.simulate(home, homePlayers, away, awayPlayers,
                    homeTactics, awayTactics, round);

            userFixture.setPlayed(true);
            userFixture.setResult(userResult);

            state.getMatchHistory().add(userResult);
            state.addInboxMessage("match", "Kolo " + round + ": " + userResult.getSummary());
        }

        // Simuliraj ostale meceve
        List<CSMatchResult> allResults = leagueManager.simulateRound(state, round, userResult);

        // Oporavi fatigue izmedju kola
        recoverFatigueBetweenRounds(state);

        state.setCurrentRound(round + 1);

        // Ako je sezona gotova
        if (state.isSeasonOver()) {
            CSTableEntry champion = state.getLeagueTable().get(0);
            state.addInboxMessage("info",
                    "Sezona je zavrsena! Prvak: " + champion.getTeamName() +
                    " sa " + champion.getPoints() + " bodova.");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("userMatch", userResult);
        response.put("allResults", allResults);
        response.put("round", round);
        response.put("table", state.getLeagueTable());
        response.put("seasonOver", state.isSeasonOver());
        // Return updated roster so frontend can refresh goals/assists
        response.put("roster", state.getRoster());
        return response;
    }

    public CleanSheetGameState getState(Long userId) {
        return activeGames.get(userId);
    }

    public List<CSTableEntry> getTable(Long userId) {
        return getStateOrThrow(userId).getLeagueTable();
    }

    public List<CSPlayer> getPlayers(Long userId) {
        return getStateOrThrow(userId).getRoster();
    }

    public List<CSFixture> getSchedule(Long userId) {
        return getStateOrThrow(userId).getSchedule();
    }

    public List<CSInboxMessage> getInbox(Long userId) {
        return getStateOrThrow(userId).getInbox();
    }

    public CSTactics changeTactics(Long userId, String formation, String style) {
        CleanSheetGameState state = getStateOrThrow(userId);
        CSTactics tactics = state.getTactics();

        if (formation != null && !formation.isBlank()) {
            tactics.setFormation(formation);
        }
        if (style != null && !style.isBlank()) {
            try {
                tactics.setStyle(CSPlayStyle.valueOf(style.toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown play style: {}", style);
            }
        }
        state.addInboxMessage("info",
                "Taktika promenjena: " + tactics.getFormation() + " / " + tactics.getStyle());
        return tactics;
    }

    public boolean hasActiveGame(Long userId) {
        return activeGames.containsKey(userId);
    }

    private CleanSheetGameState getStateOrThrow(Long userId) {
        CleanSheetGameState state = activeGames.get(userId);
        if (state == null) {
            throw new RuntimeException("No active Clean Sheet game for user " + userId);
        }
        return state;
    }

    private CSTeam findTeam(CleanSheetGameState state, Long teamId) {
        return state.getAllTeams().stream()
                .filter(t -> t.getId().equals(teamId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Team not found: " + teamId));
    }

    public Map<String, Object> getTeamInfo(Long userId, Long teamId) {
        CleanSheetGameState state = getStateOrThrow(userId);
        CSTeam team = state.getAllTeams().stream()
                .filter(t -> t.getId().equals(teamId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Team not found: " + teamId));
        List<CSPlayer> roster = state.getAllTeamRosters()
                .getOrDefault(teamId, List.of());
        Map<String, Object> result = new HashMap<>();
        result.put("team", team);
        result.put("roster", roster);
        return result;
    }

    public List<Map<String, Object>> getTopScorers(Long userId) {
        CleanSheetGameState state = getStateOrThrow(userId);
        return state.getAllTeamRosters().values().stream()
                .flatMap(List::stream)
                .filter(p -> p.getGoals() > 0)
                .sorted(Comparator.comparingInt(CSPlayer::getGoals).reversed())
                .limit(20)
                .map(p -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", p.getName());
                    m.put("goals", p.getGoals());
                    m.put("position", p.getPosition());
                    m.put("playerId", p.getId());
                    // find team name
                    m.put("teamName", findTeamNameForPlayer(state, p.getId()));
                    return m;
                })
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getTopAssists(Long userId) {
        CleanSheetGameState state = getStateOrThrow(userId);
        return state.getAllTeamRosters().values().stream()
                .flatMap(List::stream)
                .filter(p -> p.getAssists() > 0)
                .sorted(Comparator.comparingInt(CSPlayer::getAssists).reversed())
                .limit(20)
                .map(p -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", p.getName());
                    m.put("assists", p.getAssists());
                    m.put("position", p.getPosition());
                    m.put("playerId", p.getId());
                    m.put("teamName", findTeamNameForPlayer(state, p.getId()));
                    return m;
                })
                .collect(Collectors.toList());
    }

    private String findTeamNameForPlayer(CleanSheetGameState state, Long playerId) {
        for (var entry : state.getAllTeamRosters().entrySet()) {
            for (CSPlayer p : entry.getValue()) {
                if (p.getId().equals(playerId)) {
                    return state.getAllTeams().stream()
                            .filter(t -> t.getId().equals(entry.getKey()))
                            .map(CSTeam::getName)
                            .findFirst().orElse("?");
                }
            }
        }
        return "?";
    }

    private void recoverFatigueBetweenRounds(CleanSheetGameState state) {
        for (List<CSPlayer> roster : state.getAllTeamRosters().values()) {
            for (CSPlayer p : roster) {
                double recovery = 1.0 + new Random().nextDouble() * 1.5;
                p.setFatigue(Math.max(0, p.getFatigue() - recovery));
            }
        }
    }
}
