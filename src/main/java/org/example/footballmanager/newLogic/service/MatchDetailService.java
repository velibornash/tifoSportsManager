package org.example.footballmanager.newLogic.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.dto.MatchEventFlatDTO;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchDetailService {

    private final MatchRepository matchRepository;
    private final ObjectMapper objectMapper;

    public List<MatchEventFlatDTO> getMatchEventsFlat(Long matchId) {
        log.info("Request for match details, ID: {}", matchId);

        Match match = matchRepository.findById(matchId).orElse(null);
        if (match == null) {
            log.warn("Match ID {} does not exist in database.", matchId);
            throw new RuntimeException("Match not found: " + matchId);
        }

        String eventJson = match.getEventJson();
        if (eventJson == null || eventJson.isBlank()) {
            log.warn("Match ID {} has no event_json data.", matchId);
            return List.of();
        }

        List<MatchEventFlatDTO> dtos = new ArrayList<>();
        try {
            List<Map<String, Object>> events = objectMapper.readValue(eventJson, new TypeReference<>() {});

            String homeTeam = match.getHomeTeam() != null ? match.getHomeTeam().getName() : "Home";
            String awayTeam = match.getAwayTeam() != null ? match.getAwayTeam().getName() : "Away";
            int homeGoals = match.getHomeGoals();
            int awayGoals = match.getAwayGoals();
            String matchDate = match.getMatchDate() != null ? match.getMatchDate().toString() : null;

            for (Map<String, Object> event : events) {
                MatchEventFlatDTO dto = mapEventToDTO(event, matchId, matchDate, homeTeam, awayTeam, homeGoals, awayGoals);
                if (dto != null) {
                    dtos.add(dto);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse event_json for matchId={}", matchId, e);
        }

        log.info("Successfully mapped {} events for matchId={}", dtos.size(), matchId);
        return dtos;
    }

    private MatchEventFlatDTO mapEventToDTO(Map<String, Object> event, Long matchId, String matchDate,
                                             String homeTeam, String awayTeam, int homeGoals, int awayGoals) {
        MatchEventFlatDTO dto = new MatchEventFlatDTO();
        dto.setMatchId(matchId);
        dto.setMatchDate(matchDate);
        dto.setHomeTeam(homeTeam);
        dto.setAwayTeam(awayTeam);
        dto.setHomeGoals(homeGoals);
        dto.setAwayGoals(awayGoals);

        Integer minute = getInt(event, "minute");
        dto.setMatchMinute(minute);

        String teamSide = getString(event, "teamSide");
        String eventTeam = "HOME".equals(teamSide) ? homeTeam : "AWAY".equals(teamSide) ? awayTeam : null;
        dto.setEventTeam(eventTeam);

        // Determine event type and populate fields
        if (event.containsKey("scorerName")) {
            // GOAL event
            dto.setEventType("GoalEvent");
            dto.setScorer(getString(event, "scorerName"));
            dto.setAssistant(getString(event, "assistantName"));
            dto.setScoreTeam(eventTeam);
            Integer homeAfter = getInt(event, "homeScoreAfter");
            Integer awayAfter = getInt(event, "awayScoreAfter");
            if (homeAfter != null && awayAfter != null) {
                dto.setScoreAfterGoal(homeAfter + "-" + awayAfter);
            }
            dto.setGoalScored(true);
            Double xg = getDouble(event, "xG");
            if (xg != null) dto.setXG(xg);
        } else if (event.containsKey("cardType")) {
            // CARD event
            String cardType = getString(event, "cardType");
            if ("YELLOW".equals(cardType)) {
                dto.setEventType("YellowCardEvent");
                dto.setYellowCardPlayer(getString(event, "playerName"));
                dto.setYellowCardTeam(eventTeam);
            } else if ("RED".equals(cardType)) {
                dto.setEventType("RedCardEvent");
                dto.setRedCardPlayer(getString(event, "playerName"));
                dto.setRedCardTeam(eventTeam);
            }
        } else if (event.containsKey("duelType")) {
            // DUEL event - skip for now, too noisy
            return null;
        } else if (event.containsKey("passerName")) {
            // PASS event - skip for now, too noisy
            return null;
        } else if (event.containsKey("isGoal") || event.containsKey("xg")) {
            // SHOT event
            Boolean isGoal = getBoolean(event, "isGoal");
            Boolean onTarget = getBoolean(event, "onTarget");
            if (Boolean.TRUE.equals(isGoal)) {
                return null; // Goals are handled by GOAL events
            }
            if (Boolean.TRUE.equals(onTarget)) {
                dto.setEventType("ShotOnTargetEvent");
                dto.setShotOnTargetPlayer(getString(event, "shooterName"));
                dto.setShotOnTargetTeam(eventTeam);
            } else {
                dto.setEventType("ShotOffTargetEvent");
                dto.setShotOffTargetPlayer(getString(event, "shooterName"));
                dto.setShotOffTargetTeam(eventTeam);
            }
            Double xg = getDouble(event, "xG");
            if (xg != null) dto.setXG(xg);
        } else if (event.containsKey("setPieceType")) {
            // SET_PIECE event
            String setPieceType = getString(event, "setPieceType");
            if ("CORNER".equals(setPieceType)) {
                dto.setEventType("CornerEvent");
                dto.setCornerTeam(eventTeam);
                dto.setCornerTaker(getString(event, "takerName"));
            } else if ("FREE_KICK".equals(setPieceType)) {
                dto.setEventType("FreeKickEvent");
                dto.setFreeKickTeam(eventTeam);
                dto.setFreeKickTaker(getString(event, "takerName"));
            } else if ("THROW_IN".equals(setPieceType)) {
                return null; // Skip throw-ins
            } else if ("GOAL_KICK".equals(setPieceType)) {
                return null; // Skip goal kicks
            }
        } else if (event.containsKey("penaltyFoul")) {
            // PENALTY/FOUL event
            dto.setEventType("PenaltyEvent");
            dto.setPenaltyTeam(eventTeam);
            dto.setPenaltyTaker(getString(event, "takerName"));
            Boolean penaltyFoul = getBoolean(event, "penaltyFoul");
            dto.setPenaltyScored(penaltyFoul);
        } else if (event.containsKey("playerOutName") && event.containsKey("playerInName")) {
            // SUBSTITUTION event
            dto.setEventType("SubstitutionEvent");
            dto.setSubstitutionTeam(eventTeam);
            dto.setPlayerOutName(getString(event, "playerOutName"));
            dto.setPlayerInName(getString(event, "playerInName"));
        } else if (event.containsKey("playerName") && !event.containsKey("playerOutName")) {
            // INJURY event (has playerId, playerName but no playerOut)
            dto.setEventType("InjuryEvent");
            dto.setInjuryTeam(eventTeam);
            dto.setInjuryPlayer(getString(event, "playerName"));
        } else if (event.containsKey("homeTeamName") && event.containsKey("awayTeamName")) {
            // MATCH_START event
            dto.setEventType("MatchStart");
        } else if (event.containsKey("homeGoals") && event.containsKey("awayGoals") && minute != null && minute >= 90) {
            // MATCH_END event
            dto.setEventType("MatchEnd");
        } else {
            // Unknown event type
            return null;
        }

        return dto;
    }

    private String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private Integer getInt(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return null;
    }

    private Double getDouble(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.doubleValue();
        return null;
    }

    private Boolean getBoolean(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Boolean b) return b;
        return null;
    }
}
