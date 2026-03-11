package org.example.footballmanager.zox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.footballmanager.dto.*;
import org.example.footballmanager.model.Lineup;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchTickState;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.event.*;
import org.example.footballmanager.repository.MatchEventRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.MatchTickStateRepository;
import org.example.footballmanager.util.events.MatchEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ZoxReplayService {

    private static final int DEFAULT_TICKS_PER_MINUTE = 27;
    private static final int TICK_DURATION_MS = 370;
    private static final int CHUNK_DURATION_MS = 30_000;

    private final MatchRepository matchRepository;
    private final MatchTickStateRepository tickStateRepository;
    private final MatchEventRepository matchEventRepository;
    private final ObjectMapper objectMapper;
    private final MatchEventMapper matchEventMapper;

    public ZoxPlaybackMetadataDTO getPlaybackMetadata(Long matchId) {
        Match match = loadMatch(matchId);
        List<MatchTickState> tickStates = loadTickStates(match);
        List<ZoxReplayEventDTO> replayEvents = loadReplayEvents(match);
        int ticksPerMinute = resolveTicksPerMinute(replayEvents);

        int totalTicks = tickStates.isEmpty() ? 0 : tickStates.get(tickStates.size() - 1).getTick();
        long totalDurationMs = toTimestampMs(totalTicks);

        return ZoxPlaybackMetadataDTO.builder()
                .matchId(match.getId())
                .homeTeamId(match.getHomeTeam() != null ? match.getHomeTeam().getId() : null)
                .awayTeamId(match.getAwayTeam() != null ? match.getAwayTeam().getId() : null)
                .homeTeamName(match.getHomeTeam() != null ? match.getHomeTeam().getName() : "Home")
                .awayTeamName(match.getAwayTeam() != null ? match.getAwayTeam().getName() : "Away")
                .homeFormation(resolveFormation(match.getHomeLineup(), match.getHomeFormation()))
                .awayFormation(resolveFormation(match.getAwayLineup(), match.getAwayFormation()))
                .homeGoals(match.getHomeGoals())
                .awayGoals(match.getAwayGoals())
                .timeStatus(match.isFinished() || match.isPlayed() ? "FT" : (match.isStarted() ? "LIVE" : "Replay"))
                .ticksPerMinute(ticksPerMinute)
                .tickDurationMs(TICK_DURATION_MS)
                .chunkDurationMs(CHUNK_DURATION_MS)
                .chunkCount(calculateChunkCount(totalDurationMs))
                .totalTicks(totalTicks)
                .totalDurationMs(totalDurationMs)
                .playersData(buildPlayerMetadata(match))
                .goalsData(buildGoalMarkers(match, replayEvents, ticksPerMinute))
                .eventData(replayEvents)
                .keyMoments(replayEvents.stream().filter(ZoxReplayEventDTO::isKeyEvent).toList())
                .build();
    }

    public ZoxPlaybackChunkDTO getPlaybackChunk(Long matchId, int chunkIndex) {
        Match match = loadMatch(matchId);
        List<ZoxReplayFrameDTO> allFrames = loadFrames(match);
        long totalDurationMs = allFrames.isEmpty() ? 0L : allFrames.get(allFrames.size() - 1).getTimestampMs();
        int chunkCount = calculateChunkCount(totalDurationMs);

        if (chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new IllegalArgumentException("Chunk " + chunkIndex + " is outside available range 0.." + (chunkCount - 1));
        }

        long startTimeMs = (long) chunkIndex * CHUNK_DURATION_MS;
        long endTimeMs = Math.min(totalDurationMs, startTimeMs + CHUNK_DURATION_MS);
        boolean lastChunk = chunkIndex == chunkCount - 1;

        List<ZoxReplayFrameDTO> frames = sliceFramesForChunk(allFrames, startTimeMs, endTimeMs, lastChunk);
        List<ZoxReplayEventDTO> eventData = loadReplayEvents(match).stream()
                .filter(event -> isInsideChunk(event.getTimestampMs(), startTimeMs, endTimeMs, lastChunk))
                .toList();

        return ZoxPlaybackChunkDTO.builder()
                .matchId(matchId)
                .chunkIndex(chunkIndex)
                .startTimeMs(startTimeMs)
                .endTimeMs(endTimeMs)
                .lastChunk(lastChunk)
                .frames(frames)
                .playerPositions(buildPlayerPositions(frames))
                .ballData(buildBallData(frames))
                .eventData(eventData)
                .build();
    }

    private Match loadMatch(Long matchId) {
        Match match = matchRepository.findWithTeamsAndLineupsById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));
        initializeLineup(match.getHomeLineup());
        initializeLineup(match.getAwayLineup());
        return match;
    }

    private void initializeLineup(Lineup lineup) {
        if (lineup == null) {
            return;
        }
        lineup.getFormation();
        lineup.getOrderedStartingPlayers().size();
        lineup.getOrderedSubstitutePlayers().size();
    }

    private List<MatchTickState> loadTickStates(Match match) {
        return tickStateRepository.findByMatchOrderByTickAsc(match);
    }

    private List<ZoxReplayFrameDTO> loadFrames(Match match) {
        List<ZoxReplayFrameDTO> frames = new ArrayList<>();
        for (MatchTickState state : loadTickStates(match)) {
            frames.add(toFrame(state));
        }
        frames.sort(Comparator.comparingLong(ZoxReplayFrameDTO::getTimestampMs));
        return frames;
    }

    private ZoxReplayFrameDTO toFrame(MatchTickState state) {
        try {
            List<PlayerPositionDTO> players = objectMapper.readValue(state.getPlayerPositionsJson(), new TypeReference<List<PlayerPositionDTO>>() {});
            BallPositionDTO ball = objectMapper.readValue(state.getBallPositionJson(), BallPositionDTO.class);
            return ZoxReplayFrameDTO.builder()
                    .timestampMs(toTimestampMs(state.getTick()))
                    .tick(state.getTick())
                    .minute(state.getMinute())
                    .players(players)
                    .ball(ball)
                    .carrierPlayerId(state.getCurrentCarrierId())
                    .ballInTransit(state.isBallInTransit())
                    .pendingReceiverId(state.getPendingReceiverId())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse replay frame for tick " + state.getTick(), e);
        }
    }

    private List<ZoxReplayFrameDTO> sliceFramesForChunk(List<ZoxReplayFrameDTO> allFrames, long startTimeMs, long endTimeMs, boolean lastChunk) {
        if (allFrames.isEmpty()) {
            return List.of();
        }

        int firstInChunk = -1;
        int lastInChunk = -1;
        for (int i = 0; i < allFrames.size(); i++) {
            long timestampMs = allFrames.get(i).getTimestampMs();
            if (isInsideChunk(timestampMs, startTimeMs, endTimeMs, lastChunk)) {
                if (firstInChunk < 0) {
                    firstInChunk = i;
                }
                lastInChunk = i;
            }
        }

        if (firstInChunk < 0) {
            int insertionPoint = 0;
            while (insertionPoint < allFrames.size() && allFrames.get(insertionPoint).getTimestampMs() < startTimeMs) {
                insertionPoint++;
            }
            int from = Math.max(0, insertionPoint - 1);
            int to = Math.min(allFrames.size() - 1, insertionPoint);
            return new ArrayList<>(allFrames.subList(from, to + 1));
        }

        int from = Math.max(0, firstInChunk - 1);
        int to = Math.min(allFrames.size() - 1, lastInChunk + 1);
        return new ArrayList<>(allFrames.subList(from, to + 1));
    }

    private boolean isInsideChunk(long timestampMs, long startTimeMs, long endTimeMs, boolean lastChunk) {
        return timestampMs >= startTimeMs && (lastChunk ? timestampMs <= endTimeMs : timestampMs < endTimeMs);
    }

    private List<ZoxReplayEventDTO> loadReplayEvents(Match match) {
        return matchEventRepository.findByMatch(match).stream()
                .sorted(Comparator
                        .comparingInt(this::resolveEventTick)
                        .thenComparingInt(MatchEvent::getMinute)
                        .thenComparing(event -> Optional.ofNullable(event.getId()).orElse(0L)))
                .map(event -> toReplayEvent(event, match))
                .toList();
    }

    private ZoxReplayEventDTO toReplayEvent(MatchEvent event, Match match) {
        MatchEventDTO dto = matchEventMapper.toDto(event);
        int tick = resolveEventTick(event);
        String teamName = dto != null ? dto.getTeamName() : null;

        ZoxReplayEventDTO.ZoxReplayEventDTOBuilder builder = ZoxReplayEventDTO.builder()
                .eventId(event.getId())
                .timestampMs(toTimestampMs(tick))
                .tick(tick)
                .minute(event.getMinute())
                .clockLabel(dto != null ? dto.getClockLabel() : event.getMinute() + "'")
                .type(dto != null ? dto.getType() : event.getClass().getSimpleName())
                .description(dto != null && dto.getDescription() != null ? dto.getDescription() : event.getDescription())
                .displayCategory(dto != null ? dto.getDisplayCategory() : "commentary")
                .importance(dto != null ? dto.getImportance() : "low")
                .keyEvent(dto != null && dto.isKeyEvent())
                .xG(dto != null ? dto.getXG() : null)
                .teamName(teamName)
                .teamSide(resolveTeamSide(teamName, match))
                .playerId(resolvePrimaryPlayerId(event))
                .playerName(dto != null ? dto.getPlayerName() : null)
                .secondaryPlayerId(resolveSecondaryPlayerId(event))
                .secondaryPlayerName(dto != null ? dto.getSecondaryPlayerName() : null)
                .targetPlayerName(dto != null ? dto.getTargetPlayerName() : null)
                .outcome(dto != null ? dto.getOutcome() : null)
                .scoreAfterEvent(resolveScoreAfterEvent(event));

        if (dto instanceof GoalEventDTO goalDto) {
            builder.scorerName(goalDto.getScorerName())
                    .assistantName(goalDto.getAssistantName())
                    .scoreAfterGoal(goalDto.getScoreAfterGoal())
                    .scoreAfterEvent(goalDto.getScoreAfterGoal())
                    .scored(goalDto.isScored());
        }
        if (dto instanceof PenaltyEventDTO penaltyDto) {
            builder.takerName(penaltyDto.getTakerName())
                    .scoreAfterGoal(penaltyDto.getScoreAfterGoal())
                    .scored(penaltyDto.isScored());
            if (penaltyDto.getScoreAfterGoal() != null) {
                builder.scoreAfterEvent(penaltyDto.getScoreAfterGoal());
            }
        }
        if (dto instanceof SubstitutionEventDTO substitutionDto) {
            builder.playerOutName(substitutionDto.getPlayerOutName())
                    .playerInName(substitutionDto.getPlayerInName());
        }
        if (dto instanceof VARReviewEventDTO varDto) {
            builder.decision(varDto.getDecision())
                    .reviewTarget(varDto.getReviewTarget())
                    .overturnReason(varDto.getOverturnReason());
        }
        if (dto instanceof GoalKickEventDTO goalKickDto) {
            builder.goalkeeperName(goalKickDto.getGoalkeeperName());
        }
        if (dto instanceof FreeKickEventDTO freeKickDto) {
            builder.takerName(freeKickDto.getTakerName());
        }
        if (dto instanceof ThrowInEventDTO throwInDto) {
            builder.takerName(throwInDto.getTakerName());
        }
        if (dto instanceof MatchEndedDTO matchEndedDto) {
            builder.homeTeamName(matchEndedDto.getHomeTeamName())
                    .awayTeamName(matchEndedDto.getAwayTeamName())
                    .homeGoals(matchEndedDto.getHomeGoals())
                    .awayGoals(matchEndedDto.getAwayGoals());
        }
        if (dto instanceof ChanceEventDTO chanceDto) {
            builder.dangerous(chanceDto.isDangerous());
        }

        return builder.build();
    }

    private List<ZoxReplayPlayerDTO> buildPlayerMetadata(Match match) {
        List<ZoxReplayPlayerDTO> players = new ArrayList<>();
        appendPlayers(players, match.getHomeLineup(), "HOME", true);
        appendPlayers(players, match.getHomeLineup(), "HOME", false);
        appendPlayers(players, match.getAwayLineup(), "AWAY", true);
        appendPlayers(players, match.getAwayLineup(), "AWAY", false);
        return players;
    }

    private void appendPlayers(List<ZoxReplayPlayerDTO> target, Lineup lineup, String teamSide, boolean starters) {
        if (lineup == null) {
            return;
        }

        List<Player> players = starters ? lineup.getStartingPlayers() : lineup.getSubstitutes();
        if (players == null) {
            return;
        }

        for (Player player : players) {
            if (player == null || player.getId() == null) {
                continue;
            }
            boolean alreadyPresent = target.stream().anyMatch(existing -> Objects.equals(existing.getPlayerId(), player.getId()));
            if (alreadyPresent) {
                continue;
            }
            target.add(ZoxReplayPlayerDTO.builder()
                    .playerId(player.getId())
                    .name(player.getName())
                    .shortName(resolveShortName(player.getName()))
                    .squadNumber(player.getSquadNumber())
                    .position(player.getPosition() != null ? player.getPosition().name() : null)
                    .teamSide(teamSide)
                    .starter(starters)
                    .build());
        }
    }

    private List<ZoxReplayGoalDTO> buildGoalMarkers(Match match, List<ZoxReplayEventDTO> replayEvents, int ticksPerMinute) {
        List<ZoxReplayGoalDTO> goals = new ArrayList<>();
        int homeScore = 0;
        int awayScore = 0;

        for (ZoxReplayEventDTO event : replayEvents) {
            if (!"goal".equals(event.getType())) {
                continue;
            }
            if ("HOME".equals(event.getTeamSide())) {
                homeScore++;
            } else if ("AWAY".equals(event.getTeamSide())) {
                awayScore++;
            }
            goals.add(ZoxReplayGoalDTO.builder()
                    .timestampMs(event.getTimestampMs())
                    .tick(event.getTick())
                    .minute(event.getMinute())
                    .teamSide(event.getTeamSide())
                    .teamName(event.getTeamName())
                    .playerId(event.getPlayerId())
                    .playerName(event.getPlayerName())
                    .scoreAfterGoal(event.getScoreAfterEvent() != null ? event.getScoreAfterEvent() : homeScore + "-" + awayScore)
                    .homeScore(homeScore)
                    .awayScore(awayScore)
                    .build());
        }

        if (!goals.isEmpty()) {
            return goals;
        }

        if (match.getHomeGoals() > 0 || match.getAwayGoals() > 0) {
            goals.add(ZoxReplayGoalDTO.builder()
                    .timestampMs(toTimestampMs(ticksPerMinute * 90))
                    .tick(ticksPerMinute * 90)
                    .minute(90)
                    .scoreAfterGoal(match.getHomeGoals() + "-" + match.getAwayGoals())
                    .homeScore(match.getHomeGoals())
                    .awayScore(match.getAwayGoals())
                    .build());
        }
        return goals;
    }

    private Map<Long, List<ZoxReplayPositionPointDTO>> buildPlayerPositions(List<ZoxReplayFrameDTO> frames) {
        Map<Long, List<ZoxReplayPositionPointDTO>> positions = new LinkedHashMap<>();
        for (ZoxReplayFrameDTO frame : frames) {
            for (PlayerPositionDTO player : frame.getPlayers()) {
                positions.computeIfAbsent((long) player.getId(), ignored -> new ArrayList<>())
                        .add(ZoxReplayPositionPointDTO.builder()
                                .timestampMs(frame.getTimestampMs())
                                .x(player.getX())
                                .y(player.getY())
                                .visible(true)
                                .build());
            }
        }
        return positions;
    }

    private List<ZoxReplayBallPointDTO> buildBallData(List<ZoxReplayFrameDTO> frames) {
        return frames.stream()
                .map(frame -> ZoxReplayBallPointDTO.builder()
                        .timestampMs(frame.getTimestampMs())
                        .x(frame.getBall() != null ? frame.getBall().getX() : 50.0)
                        .y(frame.getBall() != null ? frame.getBall().getY() : 50.0)
                        .carrierPlayerId(frame.getCarrierPlayerId())
                        .ballInTransit(frame.isBallInTransit())
                        .pendingReceiverId(frame.getPendingReceiverId())
                        .build())
                .toList();
    }

    private int resolveEventTick(MatchEvent event) {
        if (event.getTick() > 0) {
            return event.getTick();
        }
        if (event instanceof VARReviewEvent varReviewEvent) {
            int reviewedTick = resolveReviewedEventTick(varReviewEvent);
            if (reviewedTick > 0) {
                return reviewedTick;
            }
        }
        return Math.max(0, event.getMinute() * DEFAULT_TICKS_PER_MINUTE);
    }

    private int resolveReviewedEventTick(VARReviewEvent event) {
        if (event.getReviewedGoalEvent() != null && event.getReviewedGoalEvent().getTick() > 0) {
            return event.getReviewedGoalEvent().getTick();
        }
        if (event.getReviewedPenaltyEvent() != null && event.getReviewedPenaltyEvent().getTick() > 0) {
            return event.getReviewedPenaltyEvent().getTick();
        }
        if (event.getReviewedOffsideEvent() != null && event.getReviewedOffsideEvent().getTick() > 0) {
            return event.getReviewedOffsideEvent().getTick();
        }
        return 0;
    }

    private int resolveTicksPerMinute(List<ZoxReplayEventDTO> replayEvents) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (ZoxReplayEventDTO event : replayEvents) {
            int candidate = estimateSupportedTickRate(event);
            if (candidate <= 0) {
                continue;
            }
            counts.merge(candidate, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(DEFAULT_TICKS_PER_MINUTE);
    }

    private int estimateSupportedTickRate(ZoxReplayEventDTO event) {
        if (event == null || event.getTick() <= 0 || event.getMinute() <= 0) {
            return 0;
        }
        double rawRate = event.getTick() / (double) event.getMinute();
        return Math.abs(rawRate - 12.0) <= Math.abs(rawRate - 27.0) ? 12 : 27;
    }

    private String resolveFormation(Lineup lineup, String fallbackFormation) {
        if (lineup != null && lineup.getFormation() != null && !lineup.getFormation().isBlank()) {
            return lineup.getFormation();
        }
        return (fallbackFormation == null || fallbackFormation.isBlank()) ? "4-4-2" : fallbackFormation;
    }

    private String resolveShortName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "Player";
        }
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0];
        }
        String first = parts[0];
        String last = parts[parts.length - 1];
        return first.charAt(0) + ". " + last;
    }

    private String resolveTeamSide(String teamName, Match match) {
        if (teamName == null) {
            return null;
        }
        if (match.getHomeTeam() != null && teamName.equals(match.getHomeTeam().getName())) {
            return "HOME";
        }
        if (match.getAwayTeam() != null && teamName.equals(match.getAwayTeam().getName())) {
            return "AWAY";
        }
        return null;
    }

    private Long resolvePrimaryPlayerId(MatchEvent event) {
        return switch (event) {
            case GoalEvent goalEvent -> idOf(goalEvent.getScorer());
            case YellowCardEvent yellowCardEvent -> idOf(yellowCardEvent.getPlayer());
            case RedCardEvent redCardEvent -> idOf(redCardEvent.getPlayer());
            case InjuryEvent injuryEvent -> idOf(injuryEvent.getPlayer());
            case PenaltyEvent penaltyEvent -> idOf(penaltyEvent.getTaker());
            case SubstitutionEvent substitutionEvent -> idOf(substitutionEvent.getPlayerOut());
            case OffsideEvent offsideEvent -> idOf(offsideEvent.getPlayer());
            case CornerEvent cornerEvent -> idOf(cornerEvent.getPlayer());
            case ThrowInEvent throwInEvent -> idOf(throwInEvent.getTaker());
            case GoalKickEvent goalKickEvent -> idOf(goalKickEvent.getGoalkeeper());
            case FreeKickEvent freeKickEvent -> idOf(freeKickEvent.getTaker());
            case ShotOnTargetEvent shotOnTargetEvent -> idOf(shotOnTargetEvent.getShooter());
            case ShotOffTargetEvent shotOffTargetEvent -> idOf(shotOffTargetEvent.getShooter());
            case ChanceEvent chanceEvent -> idOf(chanceEvent.getPlayer());
            case PassEvent passEvent -> idOf(passEvent.getPasser());
            case InterceptionEvent interceptionEvent -> idOf(interceptionEvent.getInterceptor());
            case DribbleEvent dribbleEvent -> idOf(dribbleEvent.getDribbler());
            case DuelEvent duelEvent -> idOf(duelEvent.getPlayer1());
            case VARReviewEvent varReviewEvent -> resolveVarPrimaryPlayerId(varReviewEvent);
            default -> null;
        };
    }

    private Long resolveSecondaryPlayerId(MatchEvent event) {
        return switch (event) {
            case GoalEvent goalEvent -> idOf(goalEvent.getAssistant());
            case SubstitutionEvent substitutionEvent -> idOf(substitutionEvent.getPlayerIn());
            case PassEvent passEvent -> idOf(passEvent.getReceiver());
            case InterceptionEvent interceptionEvent -> idOf(interceptionEvent.getOriginalPasser());
            case DuelEvent duelEvent -> idOf(duelEvent.getPlayer2());
            default -> null;
        };
    }

    private Long resolveVarPrimaryPlayerId(VARReviewEvent event) {
        if (event.getReviewedGoalEvent() != null) {
            return idOf(event.getReviewedGoalEvent().getScorer());
        }
        if (event.getReviewedPenaltyEvent() != null) {
            return idOf(event.getReviewedPenaltyEvent().getTaker());
        }
        if (event.getReviewedOffsideEvent() != null) {
            return idOf(event.getReviewedOffsideEvent().getPlayer());
        }
        return null;
    }

    private String resolveScoreAfterEvent(MatchEvent event) {
        if (event instanceof GoalEvent goalEvent) {
            return goalEvent.getScoreAfterGoal();
        }
        return null;
    }

    private Long idOf(Player player) {
        return player != null ? player.getId() : null;
    }

    private int calculateChunkCount(long totalDurationMs) {
        return totalDurationMs <= 0 ? 1 : (int) (totalDurationMs / CHUNK_DURATION_MS) + 1;
    }

    private long toTimestampMs(int tick) {
        return (long) tick * TICK_DURATION_MS;
    }
}