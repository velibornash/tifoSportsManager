package org.example.basketballmanager.service;

import org.example.basketballmanager.dto.BbPlayerDTO;
import org.example.basketballmanager.dto.BbPlayerSeasonStatsDTO;
import org.example.basketballmanager.dto.BbPlayerStatsDTO;
import org.example.basketballmanager.model.BbPlayer;
import org.example.basketballmanager.model.BbPlayerSeasonStats;
import org.example.basketballmanager.model.BbPlayerStats;
import org.example.basketballmanager.model.BbTeam;
import org.example.basketballmanager.repository.BbPlayerRepository;
import org.example.basketballmanager.repository.BbPlayerSeasonStatsRepository;
import org.example.commonmanager.model.CommonCompetition;
import org.example.commonmanager.repository.CommonCompetitionRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class BbPlayerService {

    private final BbPlayerRepository playerRepository;
    private final BbPlayerSeasonStatsRepository seasonStatsRepository;
    private final CommonCompetitionRepository competitionRepository;

    public BbPlayerService(BbPlayerRepository playerRepository,
                           BbPlayerSeasonStatsRepository seasonStatsRepository,
                           CommonCompetitionRepository competitionRepository) {
        this.playerRepository = playerRepository;
        this.seasonStatsRepository = seasonStatsRepository;
        this.competitionRepository = competitionRepository;
    }

    public List<BbPlayerDTO> getTeamPlayers(Long teamId) {
        return playerRepository.findByTeamIdOrderByPositionAndNumber(teamId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public Optional<BbPlayerDTO> getPlayerById(Long id) {
        return playerRepository.findById(id).map(this::toDTO);
    }

    public List<BbPlayerDTO> getAllPlayersByLeague(Long competitionId) {
        return playerRepository.findByCompetitionId(competitionId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public List<BbPlayerDTO> getTopScorers(Long leagueId, int limit) {
        return getAllPlayersByLeague(leagueId).stream()
                .filter(p -> p.getStats() != null && p.getStats().getGames() > 0)
                .sorted((a, b) -> Double.compare(
                        b.getStats().getPpg() != null ? b.getStats().getPpg() : 0,
                        a.getStats().getPpg() != null ? a.getStats().getPpg() : 0))
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<BbPlayerDTO> getTopRebounders(Long leagueId, int limit) {
        return getAllPlayersByLeague(leagueId).stream()
                .filter(p -> p.getStats() != null && p.getStats().getGames() > 0)
                .sorted((a, b) -> Double.compare(
                        b.getStats().getRpg() != null ? b.getStats().getRpg() : 0,
                        a.getStats().getRpg() != null ? a.getStats().getRpg() : 0))
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<BbPlayerDTO> getTopAssists(Long leagueId, int limit) {
        return getAllPlayersByLeague(leagueId).stream()
                .filter(p -> p.getStats() != null && p.getStats().getGames() > 0)
                .sorted((a, b) -> Double.compare(
                        b.getStats().getApg() != null ? b.getStats().getApg() : 0,
                        a.getStats().getApg() != null ? a.getStats().getApg() : 0))
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<BbPlayerSeasonStatsDTO> getPlayerSeasonStats(Long playerId) {
        return seasonStatsRepository.findByPlayerIdOrderBySeasonYearAsc(playerId)
                .stream()
                .map(this::toSeasonStatsDTO)
                .collect(Collectors.toList());
    }

    public BbPlayerDTO toDTO(BbPlayer player) {
        Map<String, Integer> skills = new LinkedHashMap<>();
        skills.put("pace", player.getSkillPace());
        skills.put("steals", player.getSkillSteals());
        skills.put("blocks", player.getSkillBlocks());
        skills.put("freeThrows", player.getSkillFreeThrows());
        skills.put("twoPtShot", player.getSkillTwoPtShot());
        skills.put("threePtShot", player.getSkillThreePtShot());
        skills.put("rebounding", player.getSkillRebounding());
        skills.put("playmaking", player.getSkillPlaymaking());

        BbTeam team = player.getTeam();

        return BbPlayerDTO.builder()
                .id(player.getId())
                .name(player.getName())
                .position(player.getPosition().name())
                .height(player.getHeight())
                .weight(player.getWeight())
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

    public BbPlayerSeasonStatsDTO toSeasonStatsDTO(BbPlayerSeasonStats s) {
        String compName = null;
        if (s.getCompetitionId() != null) {
            compName = competitionRepository.findById(s.getCompetitionId())
                    .map(CommonCompetition::getName)
                    .orElse(null);
        }
        return BbPlayerSeasonStatsDTO.builder()
                .id(s.getId())
                .seasonYear(s.getSeasonYear())
                .competitionId(s.getCompetitionId())
                .teamId(s.getTeamId())
                .teamName(s.getTeamName())
                .competitionName(compName)
                .gamesPlayed(s.getGamesPlayed())
                .pointsScored(s.getPointsScored())
                .ppg(s.getPpg())
                .reboundsTotal(s.getReboundsTotal())
                .rpg(s.getRpg())
                .assistsTotal(s.getAssistsTotal())
                .apg(s.getApg())
                .stealsTotal(s.getStealsTotal())
                .spg(s.getSpg())
                .blocksTotal(s.getBlocksTotal())
                .bpg(s.getBpg())
                .turnoversTotal(s.getTurnoversTotal())
                .topg(s.getTopg())
                .twoPtMade(s.getTwoPtMade())
                .twoPtAttempted(s.getTwoPtAttempted())
                .twoPtPct(s.getTwoPtPct())
                .threePtMade(s.getThreePtMade())
                .threePtAttempted(s.getThreePtAttempted())
                .threePtPct(s.getThreePtPct())
                .ftMade(s.getFtMade())
                .ftAttempted(s.getFtAttempted())
                .ftPct(s.getFtPct())
                .build();
    }

    public BbPlayerStatsDTO toStatsDTO(BbPlayerStats stats) {
        if (stats == null) return null;
        return BbPlayerStatsDTO.builder()
                .games(stats.getGamesPlayed())
                .points(stats.getPointsScored())
                .rebounds(stats.getReboundsTotal())
                .assists(stats.getAssistsTotal())
                .steals(stats.getStealsTotal())
                .blocks(stats.getBlocksTotal())
                .turnovers(stats.getTurnoversTotal())
                .ppg(stats.getPpg())
                .rpg(stats.getRpg())
                .apg(stats.getApg())
                .spg(stats.getSpg())
                .bpg(stats.getBpg())
                .topg(stats.getTopg())
                .twoPtPct(stats.getTwoPtPct())
                .threePtPct(stats.getThreePtPct())
                .ftPct(stats.getFtPct())
                .twoPtMade(stats.getTwoPtMade())
                .twoPtAttempted(stats.getTwoPtAttempted())
                .threePtMade(stats.getThreePtMade())
                .threePtAttempted(stats.getThreePtAttempted())
                .ftMade(stats.getFtMade())
                .ftAttempted(stats.getFtAttempted())
                .build();
    }
}
