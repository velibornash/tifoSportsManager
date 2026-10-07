package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.newLogic.dto.MatchLineupPlayerDTO;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchPlayerStats;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/match-stats")
public class MatchPlayerStatsController {

    private final MatchPlayerStatsRepository statsRepository;
    private final MatchRepository matchRepository;

    public MatchPlayerStatsController(MatchPlayerStatsRepository statsRepository, MatchRepository matchRepository) {
        this.statsRepository = statsRepository;
        this.matchRepository = matchRepository;
    }

    @GetMapping("/{matchId}")
    public List<MatchPlayerStats> getStatsByMatch(@PathVariable Long matchId) {
        return statsRepository.findByMatchId(matchId);
    }

    @GetMapping("/player/{playerId}")
    public ResponseEntity<Map<String, Object>> getStatsByPlayer(@PathVariable Long playerId) {
        List<MatchPlayerStats> stats = statsRepository.findByPlayerId(playerId);
        double averageRating100 = stats.stream()
                .mapToInt(MatchPlayerStats::getRating)
                .average()
                .orElse(0.0);
        double averageRating10 = averageRating100 / 10.0;

        Map<String, Object> payload = new HashMap<>();
        payload.put("playerId", playerId);
        payload.put("matchesPlayed", stats.size());
        payload.put("averageRating100", Math.round(averageRating100 * 100.0) / 100.0);
        payload.put("averageRating10", stats.isEmpty() ? null : Math.round(averageRating10 * 10.0) / 10.0);
        return ResponseEntity.ok(payload);
    }

    /**
     * Every match a player has a stat line for, most recent first — the profile's Matches tab.
     *
     * <p>This tab was a placeholder reading "player-by-player match log can be wired later". The data was
     * always there: {@link MatchPlayerStats} carries minutes, rating, goals, assists and both cards per
     * match. Nothing joined it to the match, so there was nothing to render.
     *
     * <p>The match id is sent as a **Match id, never a fixture id** — the two are separate tables with
     * overlapping numbers, and the player page holds a Match because a stat line can only exist for a
     * match that was played. The frontend passes {@code fixture: false} for the same reason.
     */
    @GetMapping("/player/{playerId}/matches")
    public ResponseEntity<List<Map<String, Object>>> getPlayerMatches(@PathVariable Long playerId) {
        List<Map<String, Object>> out = statsRepository.findByPlayerIdNewestFirst(playerId).stream()
                .map(stats -> {
                    Match match = stats.getMatch();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("matchId", match.getId());
                    row.put("seasonYear", match.getSeasonYear());
                    row.put("weekNumber", match.getWeekNumber());
                    row.put("dayNumber", match.getDayNumber());
                    row.put("competitionName", match.getCompetition() == null ? null : match.getCompetition().getName());
                    row.put("homeTeamName", match.getHomeTeam() == null ? null : match.getHomeTeam().getName());
                    row.put("awayTeamName", match.getAwayTeam() == null ? null : match.getAwayTeam().getName());
                    row.put("homeGoals", match.getHomeGoals());
                    row.put("awayGoals", match.getAwayGoals());
                    // Was this player's team at home? The profile needs it to show the result from this
                    // player's side — a 3-4 loss reads as a win when you were the away side at 4-3.
                    row.put("wasHome", match.getHomeTeam() != null && stats.getPlayer() != null
                            && match.getHomeTeam().getId().equals(stats.getPlayer().getTeam() == null
                                    ? null : stats.getPlayer().getTeam().getId()));
                    row.put("minutesPlayed", stats.getMinutesPlayed());
                    // 1-10 like every other rating in the app, not the stored 0-100.
                    row.put("rating", Math.round(ratingOutOfTen(stats.getRating()) * 10.0) / 10.0);
                    row.put("goals", stats.getGoals());
                    row.put("assists", stats.getAssists());
                    row.put("yellowCards", stats.getYellowCards());
                    row.put("redCards", stats.getRedCards());
                    return row;
                })
                .toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping("/lineups/{matchId}")
    public ResponseEntity<Map<String, Object>> getLineupsByMatch(@PathVariable Long matchId) {
        // **A 404, not a 500.** The preview page fetches lineups for an *unplayed fixture* too, and a
        // bare RuntimeException came out of the global handler as an unhandled error with a full stack
        // trace in the log - the owner reported it as `Unhandled exception during GET
        // /match-stats/lineups/1: Match not found` while simply browsing a league fixture. There is no
        // Match row yet, which is not an error condition; it is an unplayed match.
        //
        // Also the null case the owner could reach by hand: a match whose lineups are stored as JSON and
        // has no per-player stats rows.
        Match match = matchRepository.findById(matchId).orElse(null);
        if (match == null) {
            return ResponseEntity.status(404).build();
        }

        String homeTeam = match.getHomeTeam().getName();
        String awayTeam = match.getAwayTeam().getName();

        List<MatchPlayerStats> stats = statsRepository.findByMatchId(matchId);

        List<MatchLineupPlayerDTO> home = stats.stream()
                .filter(s -> s.getPlayer() != null
                        && s.getPlayer().getTeam() != null
                        && homeTeam.equals(s.getPlayer().getTeam().getName()))
                .map(this::toLineupDto)
                .toList();

        List<MatchLineupPlayerDTO> away = stats.stream()
                .filter(s -> s.getPlayer() != null
                        && s.getPlayer().getTeam() != null
                        && awayTeam.equals(s.getPlayer().getTeam().getName()))
                .map(this::toLineupDto)
                .toList();

        Map<String, Object> payload = new HashMap<>();
        payload.put("homeTeam", homeTeam);
        payload.put("awayTeam", awayTeam);
        payload.put("homeTeamId", match.getHomeTeam() != null ? match.getHomeTeam().getId() : null);
        payload.put("awayTeamId", match.getAwayTeam() != null ? match.getAwayTeam().getId() : null);
        payload.put("homeLineup", home);
        payload.put("awayLineup", away);
        return ResponseEntity.ok(payload);
    }

    /**
     * The match rating on the 1-10 scale the whole app shows.
     *
     * <p>{@code MatchPlayerStats.rating} is stored 0-100; every rating the user ever sees is 1-10. The
     * lineup already normalised it here, and the player match log sent the raw 0-100 value instead — so
     * the hero said "MATCH RATING 10.0" and the row underneath it said "100" on the same page.
     *
     * <p>Extracted so there is one conversion. A second copy of a scale rule is how the two drifts apart
     * in the first place.
     */
    private double ratingOutOfTen(int storedRating) {
        double grade = storedRating;
        if (grade > 10.0) {
            grade = grade / 10.0;
        }
        return Math.max(1.0, Math.min(10.0, grade));
    }

    private MatchLineupPlayerDTO toLineupDto(MatchPlayerStats stats) {
        double grade = ratingOutOfTen(stats.getRating());

        return new MatchLineupPlayerDTO(
                stats.getPlayer().getId(),
                stats.getPlayer().getName(),
                stats.getPlayer().getPosition().name(),
                stats.getPlayer().getTeam().getName(),
                Math.round(grade * 10.0) / 10.0,
                stats.getGoals(),
                stats.getAssists(),
                stats.getYellowCards(),
                stats.getRedCards(),
                stats.getMinutesPlayed()
        );
    }
}
