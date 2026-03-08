package org.example.footballmanager.zox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.*;
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.model.event.RedCardEvent;
import org.example.footballmanager.model.event.SubstitutionEvent;
import org.example.footballmanager.model.event.YellowCardEvent;
import org.example.footballmanager.repository.*;
import org.example.footballmanager.util.match.MatchAnalyticsService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ZOX Match Analytics Service
 * Generates detailed match previews, player ratings, and statistics
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZoxMatchAnalyticsService {

    private final MatchRepository matchRepository;
    private final MatchPlayerStatsRepository matchPlayerStatsRepository;
    private final PlayerRepository playerRepository;
    private final LineupRepository lineupRepository;
    private final MatchAnalyticsService matchAnalyticsService;
    private final MatchEventRepository matchEventRepository;

    /**
     * Generiše detaljni match preview sa svim analytics
     */
    public ZoxMatchPreviewDTO generateMatchPreview(Long matchId) {
        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));

        ZoxMatchPreviewDTO preview = ZoxMatchPreviewDTO.builder()
                .matchId(matchId)
                .homeTeamId(match.getHomeTeam().getId())
                .awayTeamId(match.getAwayTeam().getId())
                .homeTeamName(match.getHomeTeam().getName())
                .awayTeamName(match.getAwayTeam().getName())
                .homeFormation(getTeamFormation(match.getHomeTeam()))
                .awayFormation(getTeamFormation(match.getAwayTeam()))
                .build();

        // Lineup sa ratings
        preview.setHomeLineup(getPlayerRatingsForTeam(matchId, match.getHomeTeam()));
        preview.setAwayLineup(getPlayerRatingsForTeam(matchId, match.getAwayTeam()));

        // Prediction
        ZoxMatchPredictionDTO prediction = calculateMatchPrediction(match);
        preview.setHomeWinProbability(prediction.getHomeWinProbability());
        preview.setDrawProbability(prediction.getDrawProbability());
        preview.setAwayWinProbability(prediction.getAwayWinProbability());
        preview.setExpectedHomeGoals(prediction.getExpectedHomeGoals());
        preview.setExpectedAwayGoals(prediction.getExpectedAwayGoals());

        // Ratings
        preview.setHomeTeamRating(calculateTeamRating(match.getHomeTeam()));
        preview.setAwayTeamRating(calculateTeamRating(match.getAwayTeam()));

        return preview;
    }

    /**
     * Kalkuliše player ratings za sve igrače na terenu
     */
    public List<ZoxPlayerRatingDTO> getPlayerRatingsForTeam(Long matchId, Team team) {
        List<Lineup> lineups = lineupRepository.findAll().stream()
                .filter(l -> l.getMatch().getId().equals(matchId) && l.getTeam().equals(team))
                .collect(Collectors.toList());

        // Get starting players from lineups
        List<ZoxPlayerRatingDTO> ratings = new ArrayList<>();
        for (Lineup lineup : lineups) {
            if (lineup.getStartingPlayers() != null) {
                for (Player player : lineup.getStartingPlayers()) {
                    ratings.add(calculatePlayerRating(player, lineup.getMatch()));
                }
            }
        }
        return ratings;
    }

    /**
     * Kalkuliše individual player rating (0-10)
     */
    private ZoxPlayerRatingDTO calculatePlayerRating(Player player, Match match) {
        ZoxPlayerRatingDTO rating = ZoxPlayerRatingDTO.builder()
                .playerId(player.getId())
                .name(player.getName())
                .position(player.getPosition() != null ? player.getPosition().toString() : "Unknown")
                .squadNumber(player.getSquadNumber())
                .build();

        // Get match stats if available
        Optional<MatchPlayerStats> statsOpt = matchPlayerStatsRepository.findAll().stream()
                .filter(s -> s.getPlayer().equals(player) && s.getMatch().equals(match))
                .findFirst();

        if (statsOpt.isPresent()) {
            MatchPlayerStats stats = statsOpt.get();
            
            rating.setPasses(0); // Not available in model
            rating.setSuccessfulPasses(0);
            rating.setTackles(0);
            rating.setShotsOnTarget(0);
            rating.setYellowCards(stats.getYellowCards());
            rating.setRedCards(stats.getRedCards());

            // Calculate ratings based on position and stats
            rating.setOverallRating(calculateOverallRating(player, stats));
            rating.setAttackRating(calculateAttackRating(player, stats));
            rating.setDefenseRating(calculateDefenseRating(player, stats));
            
            // Expected goals/assists
            rating.setExpectedGoals((double) stats.getGoals());
            rating.setExpectedAssists((double) stats.getAssists());
        } else {
            // No stats - use base player attributes
            rating.setOverallRating(getPlayerBaseRating(player));
            rating.setAttackRating(getPlayerAttributeRating(player, "attack"));
            rating.setDefenseRating(getPlayerAttributeRating(player, "defense"));
            rating.setExpectedGoals(0.0);
            rating.setExpectedAssists(0.0);
        }

        rating.setStatus("active");
        return rating;
    }

    /**
     * Match prediction - probabiliteti ishoda
     */
    public ZoxMatchPredictionDTO calculateMatchPrediction(Match match) {
        Team home = match.getHomeTeam();
        Team away = match.getAwayTeam();

        int homeRating = calculateTeamRating(home);
        int awayRating = calculateTeamRating(away);

        // Simple Elo-based prediction
        double ratingDiff = homeRating - awayRating;
        double homeWinProb = 1.0 / (1.0 + Math.pow(10, -ratingDiff / 400.0));
        double awayWinProb = 1.0 / (1.0 + Math.pow(10, ratingDiff / 400.0));
        double drawProb = 1.0 - homeWinProb - awayWinProb;
        drawProb = Math.max(0.1, Math.min(0.5, drawProb)); // Constrain draw probability

        // Expected goals
        double expectedHomeGoals = 1.5 + (homeRating / 100.0) * 0.5;
        double expectedAwayGoals = 1.5 + (awayRating / 100.0) * 0.5;

        return ZoxMatchPredictionDTO.builder()
                .homeWinProbability(homeWinProb)
                .drawProbability(drawProb)
                .awayWinProbability(awayWinProb)
                .expectedHomeGoals(Math.round(expectedHomeGoals * 100) / 100.0)
                .expectedAwayGoals(Math.round(expectedAwayGoals * 100) / 100.0)
                .mostLikelyResult(getMostLikelyResult(homeWinProb, drawProb, awayWinProb))
                .build();
    }

    /**
     * Kalkuliši team strength rating (0-100)
     */
    public Integer calculateTeamRating(Team team) {
        List<Player> players = playerRepository.findAll().stream()
                .filter(p -> p.getTeam() != null && p.getTeam().equals(team))
                .collect(Collectors.toList());

        if (players.isEmpty()) return 50;

        double avgRating = players.stream()
                .mapToDouble(this::getPlayerBaseRating)
                .average()
                .orElse(50.0);

        return (int) avgRating;
    }

    /**
     * Get team formation string
     */
    private String getTeamFormation(Team team) {
        // TODO: Fetch actual formation from tactics/lineup
        return "4-3-3";
    }

    /**
     * Calculate overall player rating based on position and stats
     */
    private Double calculateOverallRating(Player player, MatchPlayerStats stats) {
        String position = player.getPosition().name();
        double baseRating = getPlayerBaseRating(player);
        double statsModifier = 0.0;

        // Modifier based on performance stats
        if (stats.getGoals() > 0) {
            statsModifier += stats.getGoals() * 2;
        }
        if (stats.getAssists() > 0) {
            statsModifier += stats.getAssists() * 1.5;
        }
        if (stats.getRating() > 0) {
            statsModifier += (stats.getRating() - 5) * 0.5; // Center around 5
        }

        double rating = baseRating + statsModifier;
        return Math.min(10.0, Math.max(1.0, rating));
    }

    private Double calculateAttackRating(Player player, MatchPlayerStats stats) {
        double base = getPlayerAttributeRating(player, "attack");
        if (stats.getGoals() > 0) {
            base += stats.getGoals() * 1.5;
        }
        return Math.min(10.0, base);
    }

    private Double calculateDefenseRating(Player player, MatchPlayerStats stats) {
        double base = getPlayerAttributeRating(player, "defense");
        if (stats.getYellowCards() > 0 || stats.getRedCards() > 0) {
            base -= (stats.getYellowCards() * 0.5 + stats.getRedCards() * 2.0);
        }
        return Math.min(10.0, Math.max(1.0, base));
    }

    private Double getPlayerBaseRating(Player player) {
        // Use player skills average
        if (player.getSkills() != null) {
            Skills skills = player.getSkills();
            double avg = (skills.getStamina() +
                    skills.getGoalkeeper() +
                    skills.getDefender() +
                    skills.getPace() +
                    skills.getTechnique() +
                    skills.getPlaymaker() +
                    skills.getPassing() +
                    skills.getStriker()) / 8.0 / 20.0; // Skills are 0-20, convert to 0-10
            return Math.min(10.0, avg);
        }
        return 6.5;
    }

    private Double getPlayerAttributeRating(Player player, String attribute) {
        if (player.getSkills() == null) return 6.5;
        
        Skills skills = player.getSkills();
        return switch (attribute) {
            case "attack" -> (double) skills.getStriker() / 20.0;
            case "defense" -> (double) skills.getDefender() / 20.0;
            case "passing" -> (double) skills.getPassing() / 20.0;
            default -> 6.5;
        };
    }

    private String getMostLikelyResult(double homeWin, double draw, double awayWin) {
        double max = Math.max(homeWin, Math.max(draw, awayWin));
        if (max == homeWin) return "HOME_WIN";
        if (max == draw) return "DRAW";
        return "AWAY_WIN";
    }

    /**
     * Generate formation visualization with player positions
     */
    public ZoxFormationDTO generateFormation(Long matchId, Team team) {
        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found"));

        List<Lineup> lineups = lineupRepository.findAll().stream()
                .filter(l -> l.getMatch().getId().equals(matchId) && l.getTeam().equals(team))
                .collect(Collectors.toList());

        if (lineups.isEmpty()) {
            return ZoxFormationDTO.builder()
                    .formation("4-3-3")
                    .positions(new ZoxFormationDTO.ZoxPlayerPositionDTO[0])
                    .build();
        }

        Lineup lineup = lineups.get(0);
        List<Player> startingPlayers = lineup.getStartingPlayers() != null ? 
                lineup.getStartingPlayers() : new ArrayList<>();

        ZoxFormationDTO.ZoxPlayerPositionDTO[] positions = new ZoxFormationDTO.ZoxPlayerPositionDTO[startingPlayers.size()];
        for (int i = 0; i < startingPlayers.size(); i++) {
            Player player = startingPlayers.get(i);
            ZoxFormationDTO.ZoxPlayerPositionDTO pos = calculatePlayerPosition(player, i, startingPlayers.size());
            positions[i] = pos;
        }

        return ZoxFormationDTO.builder()
                .formation(lineup.getFormation() != null ? lineup.getFormation() : "4-3-3")
                .positions(positions)
                .build();
    }

    /**
     * Calculate player field position based on their role
     */
    private ZoxFormationDTO.ZoxPlayerPositionDTO calculatePlayerPosition(Player player, int index, int totalPlayers) {
        String position = player.getPosition() != null ? player.getPosition().toString() : "CM";
        
        // Default positions by type (in percentage of field 0-100)
        Double x = 50.0;
        Double y = 50.0;

        switch (position.toUpperCase()) {
            case "GK", "GOALKEEPER" -> { x = 5.0; y = 50.0; }
            case "LB", "RB", "RWB", "LWB" -> { // Wing backs
                x = 20.0;
                y = index % 2 == 0 ? 20.0 : 80.0;
            }
            case "CB", "DC" -> { // Center backs
                x = 15.0;
                y = index % 2 == 0 ? 30.0 : 70.0;
            }
            case "DM", "CDM" -> { // Defensive midfielders
                x = 35.0;
                y = 50.0;
            }
            case "CM", "MC" -> { // Central midfielders
                x = 50.0;
                y = 50.0;
            }
            case "AM", "CAM" -> { // Attacking midfielders
                x = 65.0;
                y = 50.0;
            }
            case "LM", "RM", "LW", "RW" -> { // Wing players
                x = 60.0;
                y = index % 2 == 0 ? 15.0 : 85.0;
            }
            case "ST", "CF", "FW" -> { // Strikers
                x = 85.0;
                y = 50.0;
            }
        }

        return ZoxFormationDTO.ZoxPlayerPositionDTO.builder()
                .playerId(player.getId())
                .playerName(player.getName())
                .position(position)
                .x(x)
                .y(y)
                .rating((int) Math.round(getPlayerBaseRating(player)))
                .number(player.getSquadNumber())
                .build();
    }

    /**
     * Generate event stream for match
     */
    public ZoxEventStreamDTO generateEventStream(Long matchId) {
        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found"));

        List<ZoxEventStreamDTO.ZoxMatchEventDTO> events = new ArrayList<>();
        
        // Collect all events from match
        for (GoalEvent goal : match.getGoals()) {
            events.add(ZoxEventStreamDTO.ZoxMatchEventDTO.builder()
                    .minute(goal.getMinute())
                    .type("GOAL")
                    .teamName(goal.getTeam().getName())
                    .playerName(goal.getScorer().getName())
                    .playerAssistName(goal.getAssistant() != null ? goal.getAssistant().getName() : null)
                    .description(goal.getScorer().getName() + " scores!")
                    .eventIcon("⚽")
                    .build());
        }

        for (YellowCardEvent yellowCard : match.getYellowCards()) {
            events.add(ZoxEventStreamDTO.ZoxMatchEventDTO.builder()
                    .minute(yellowCard.getMinute())
                    .type("YELLOW_CARD")
                    .teamName(yellowCard.getTeam().getName())
                    .playerName(yellowCard.getPlayer().getName())
                    .description(yellowCard.getPlayer().getName() + " receives yellow card")
                    .eventIcon("🟨")
                    .build());
        }

        for (RedCardEvent redCard : match.getRedCards()) {
            events.add(ZoxEventStreamDTO.ZoxMatchEventDTO.builder()
                    .minute(redCard.getMinute())
                    .type("RED_CARD")
                    .teamName(redCard.getTeam().getName())
                    .playerName(redCard.getPlayer().getName())
                    .description(redCard.getPlayer().getName() + " receives red card")
                    .eventIcon("🟥")
                    .build());
        }

        for (SubstitutionEvent substitution : match.getSubstitutions()) {
            events.add(ZoxEventStreamDTO.ZoxMatchEventDTO.builder()
                    .minute(substitution.getMinute())
                    .type("SUBSTITUTION")
                    .teamName(substitution.getTeam().getName())
                    .playerName(substitution.getPlayerOut().getName())
                    .playerAssistName(substitution.getPlayerIn().getName())
                    .description(substitution.getPlayerOut().getName() + " → " + substitution.getPlayerIn().getName())
                    .eventIcon("🔄")
                    .build());
        }

        // Sort by minute
        events.sort((e1, e2) -> Integer.compare(e1.getMinute(), e2.getMinute()));

        int currentMinute = match.getFinished() ? 90 : 45; // Simplified

        return ZoxEventStreamDTO.builder()
                .matchId(matchId)
                .minute(currentMinute)
                .timeStatus(match.getFinished() ? "FT" : "Live")
                .homeGoals(match.getHomeGoals())
                .awayGoals(match.getAwayGoals())
                .events(events)
                .build();
    }
}
