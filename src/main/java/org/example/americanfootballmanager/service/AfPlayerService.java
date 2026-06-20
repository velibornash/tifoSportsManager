package org.example.americanfootballmanager.service;

import org.example.americanfootballmanager.dto.*;
import org.example.americanfootballmanager.model.*;
import org.example.americanfootballmanager.repository.*;
import org.example.commonmanager.model.CommonCompetition;
import org.example.commonmanager.repository.CommonCompetitionRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AfPlayerService {

    private final AfPlayerRepository playerRepository;
    private final AfPlayerSeasonStatsRepository seasonStatsRepository;
    private final CommonCompetitionRepository competitionRepository;

    public AfPlayerService(AfPlayerRepository playerRepository,
                            AfPlayerSeasonStatsRepository seasonStatsRepository,
                            CommonCompetitionRepository competitionRepository) {
        this.playerRepository = playerRepository;
        this.seasonStatsRepository = seasonStatsRepository;
        this.competitionRepository = competitionRepository;
    }

    public List<AfPlayerDTO> getTeamPlayers(Long teamId) {
        return playerRepository.findByTeamIdOrderByPositionAndNumber(teamId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public Optional<AfPlayerDTO> getPlayerById(Long id) {
        return playerRepository.findById(id).map(this::toDTO);
    }

    public List<AfPlayerDTO> getAllPlayersByLeague(Long competitionId) {
        return playerRepository.findByCompetitionId(competitionId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public List<AfPlayerDTO> getTopPassingYards(Long leagueId, int limit) {
        return getAllPlayersByLeague(leagueId).stream()
                .filter(p -> p.getStats() != null && p.getStats().getGames() > 0)
                .sorted((a, b) -> Integer.compare(
                        b.getStats().getPassingYards() != null ? b.getStats().getPassingYards() : 0,
                        a.getStats().getPassingYards() != null ? a.getStats().getPassingYards() : 0))
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<AfPlayerDTO> getTopRushingYards(Long leagueId, int limit) {
        return getAllPlayersByLeague(leagueId).stream()
                .filter(p -> p.getStats() != null && p.getStats().getGames() > 0)
                .sorted((a, b) -> Integer.compare(
                        b.getStats().getRushingYards() != null ? b.getStats().getRushingYards() : 0,
                        a.getStats().getRushingYards() != null ? a.getStats().getRushingYards() : 0))
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<AfPlayerDTO> getTopReceivingYards(Long leagueId, int limit) {
        return getAllPlayersByLeague(leagueId).stream()
                .filter(p -> p.getStats() != null && p.getStats().getGames() > 0)
                .sorted((a, b) -> Integer.compare(
                        b.getStats().getReceivingYards() != null ? b.getStats().getReceivingYards() : 0,
                        a.getStats().getReceivingYards() != null ? a.getStats().getReceivingYards() : 0))
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<AfPlayerDTO> getTopTackles(Long leagueId, int limit) {
        return getAllPlayersByLeague(leagueId).stream()
                .filter(p -> p.getStats() != null && p.getStats().getGames() > 0)
                .sorted((a, b) -> Integer.compare(
                        b.getStats().getTackles() != null ? b.getStats().getTackles() : 0,
                        a.getStats().getTackles() != null ? a.getStats().getTackles() : 0))
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<AfPlayerDTO> getTopInterceptions(Long leagueId, int limit) {
        return getAllPlayersByLeague(leagueId).stream()
                .filter(p -> p.getStats() != null && p.getStats().getGames() > 0)
                .sorted((a, b) -> Integer.compare(
                        b.getStats().getInterceptions() != null ? b.getStats().getInterceptions() : 0,
                        a.getStats().getInterceptions() != null ? a.getStats().getInterceptions() : 0))
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<AfPlayerDTO> getTopSacks(Long leagueId, int limit) {
        return getAllPlayersByLeague(leagueId).stream()
                .filter(p -> p.getStats() != null && p.getStats().getGames() > 0)
                .sorted((a, b) -> Integer.compare(
                        b.getStats().getSacks() != null ? b.getStats().getSacks() : 0,
                        a.getStats().getSacks() != null ? a.getStats().getSacks() : 0))
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<AfPlayerSeasonStatsDTO> getPlayerSeasonStats(Long playerId) {
        return seasonStatsRepository.findByPlayerIdOrderBySeasonYearAsc(playerId)
                .stream()
                .map(this::toSeasonStatsDTO)
                .collect(Collectors.toList());
    }

    public AfPlayerDTO toDTO(AfPlayer player) {
        Map<String, Integer> skills = new LinkedHashMap<>();
        skills.put("stamina", player.getSkillStamina());
        skills.put("strength", player.getSkillStrength());
        skills.put("pace", player.getSkillPace());
        skills.put("playmaking", player.getSkillPlaymaking());
        skills.put("passing", player.getSkillPassing());
        skills.put("running", player.getSkillRunning());
        skills.put("tackling", player.getSkillTackling());
        skills.put("shooting", player.getSkillShooting());

        AfTeam team = player.getTeam();

        return AfPlayerDTO.builder()
                .id(player.getId())
                .name(player.getName())
                .position(player.getPosition().name())
                .jerseyNumber(player.getJerseyNumber())
                .teamId(team != null ? team.getId() : null)
                .teamName(team != null ? team.getName() : null)
                .teamShortName(team != null ? team.getShortName() : null)
                .teamColor(team != null ? team.getColor() : null)
                .injured(player.getInjured())
                .fatigue(player.getFatigue())
                .skills(skills)
                .stats(toStatsDTO(player.getStats()))
                .overall(player.getOverall())
                .build();
    }

    public AfPlayerSeasonStatsDTO toSeasonStatsDTO(AfPlayerSeasonStats s) {
        String compName = null;
        if (s.getCompetitionId() != null) {
            compName = competitionRepository.findById(s.getCompetitionId())
                    .map(CommonCompetition::getName)
                    .orElse(null);
        }
        return AfPlayerSeasonStatsDTO.builder()
                .id(s.getId())
                .seasonYear(s.getSeasonYear())
                .competitionId(s.getCompetitionId())
                .teamId(s.getTeamId())
                .teamName(s.getTeamName())
                .competitionName(compName)
                .gamesPlayed(s.getGamesPlayed())
                .touchdowns(s.getTouchdowns())
                .fieldGoalsMade(s.getFieldGoalsMade())
                .fieldGoalsAttempted(s.getFieldGoalsAttempted())
                .fgPct(s.getFieldGoalsAttempted() > 0 ? (double) s.getFieldGoalsMade() / s.getFieldGoalsAttempted() * 100 : 0)
                .tackles(s.getTackles())
                .interceptions(s.getInterceptions())
                .sacks(s.getSacks())
                .passingYards(s.getPassingYards())
                .rushingYards(s.getRushingYards())
                .receivingYards(s.getReceivingYards())
                .passingTouchdowns(s.getPassingTouchdowns())
                .rushingTouchdowns(s.getRushingTouchdowns())
                .receivingTouchdowns(s.getReceivingTouchdowns())
                .twoPointConversions(s.getTwoPointConversions())
                .fumbles(s.getFumbles())
                .build();
    }

    public AfPlayerStatsDTO toStatsDTO(AfPlayerStats stats) {
        if (stats == null) return null;
        return AfPlayerStatsDTO.builder()
                .games(stats.getGamesPlayed())
                .touchdowns(stats.getTouchdowns())
                .fieldGoalsMade(stats.getFieldGoalsMade())
                .fieldGoalsAttempted(stats.getFieldGoalsAttempted())
                .fgPct(stats.getFieldGoalsAttempted() > 0 ? (double) stats.getFieldGoalsMade() / stats.getFieldGoalsAttempted() * 100 : 0)
                .tackles(stats.getTackles())
                .interceptions(stats.getInterceptions())
                .sacks(stats.getSacks())
                .passingYards(stats.getPassingYards())
                .rushingYards(stats.getRushingYards())
                .receivingYards(stats.getReceivingYards())
                .passingTouchdowns(stats.getPassingTouchdowns())
                .rushingTouchdowns(stats.getRushingTouchdowns())
                .receivingTouchdowns(stats.getReceivingTouchdowns())
                .twoPointConversions(stats.getTwoPointConversions())
                .fumbles(stats.getFumbles())
                .build();
    }
}
