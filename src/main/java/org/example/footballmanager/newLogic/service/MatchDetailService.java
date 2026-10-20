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
import java.util.Locale;
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
            if (dtos.isEmpty()) {
                MatchEventFlatDTO start = new MatchEventFlatDTO();
                start.setMatchId(matchId);
                start.setMatchDate(matchDate);
                start.setHomeTeam(homeTeam);
                start.setAwayTeam(awayTeam);
                start.setHomeGoals(homeGoals);
                start.setAwayGoals(awayGoals);
                start.setMatchMinute(0);
                start.setEventType("MatchStart");
                dtos.add(start);
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

        String teamSide = getString(event, "teamSide");
        String eventTeam = "HOME".equals(teamSide) ? homeTeam : "AWAY".equals(teamSide) ? awayTeam : null;
        dto.setEventTeam(eventTeam);
        dto.setMatchMinute(getInt(event, "minute"));

        String type = normalizeEventType(event);
        if (type == null) return null;

        return switch (type) {
            case "GOAL" -> mapGoal(dto, event, eventTeam);
            case "YELLOW_CARD", "CARD" -> mapCard(dto, event, eventTeam, "YELLOW");
            case "RED_CARD" -> mapCard(dto, event, eventTeam, "RED");
            case "PENALTY", "PENALTY_AWARDED", "PENALTY_GOAL" -> mapPenalty(dto, event, eventTeam, type);
            case "CORNER" -> mapCorner(dto, event, eventTeam);
            case "FREE_KICK" -> mapFreeKick(dto, event, eventTeam);
            case "SHOT_ON_TARGET", "SHOT_SAVED", "SHOT_BLOCKED", "SHOT_POST" ->
                    mapShot(dto, event, eventTeam, true);
            case "SHOT_OFF_TARGET", "SHOT_MISSED" -> mapShot(dto, event, eventTeam, false);
            case "SUB", "SUBSTITUTION" -> mapSubstitution(dto, event, eventTeam);
            case "INJURY" -> mapInjury(dto, event, eventTeam);
            case "MATCH_START" -> mapMatchBoundary(dto, "MatchStart");
            case "MATCH_END" -> mapMatchBoundary(dto, "MatchEnd");
            default -> null;
        };
    }

    private MatchEventFlatDTO mapGoal(MatchEventFlatDTO dto, Map<String, Object> event,
                                      String eventTeam) {
        dto.setEventType("GoalEvent");
        dto.setScorer(firstNonBlank(getString(event, "scorerName"), getString(event, "playerName")));
        dto.setAssistant(firstNonBlank(getString(event, "assistantName"),
                getString(event, "assistName"), getString(event, "assistant")));
        dto.setScoreTeam(eventTeam);
        Integer homeAfter = getInt(event, "homeScoreAfter");
        Integer awayAfter = getInt(event, "awayScoreAfter");
        if (homeAfter != null && awayAfter != null) {
            dto.setScoreAfterGoal(homeAfter + "-" + awayAfter);
        }
        Boolean isGoal = getBoolean(event, "isGoal");
        dto.setGoalScored(isGoal == null || isGoal);
        dto.setXG(firstDouble(event, "xG", "xg"));
        return dto;
    }

    private MatchEventFlatDTO mapCard(MatchEventFlatDTO dto, Map<String, Object> event,
                                      String eventTeam, String defaultType) {
        String cardType = firstNonBlank(getString(event, "cardType"), defaultType);
        if (!"YELLOW".equalsIgnoreCase(cardType) && !"RED".equalsIgnoreCase(cardType)) return null;
        String player = firstNonBlank(getString(event, "playerName"), getString(event, "takerName"));
        if ("RED".equalsIgnoreCase(cardType)) {
            dto.setEventType("RedCardEvent");
            dto.setRedCardPlayer(player);
            dto.setRedCardTeam(eventTeam);
        } else {
            dto.setEventType("YellowCardEvent");
            dto.setYellowCardPlayer(player);
            dto.setYellowCardTeam(eventTeam);
        }
        return dto;
    }

    private MatchEventFlatDTO mapPenalty(MatchEventFlatDTO dto, Map<String, Object> event,
                                         String eventTeam, String type) {
        dto.setEventType("PenaltyEvent");
        dto.setPenaltyTeam(eventTeam);
        dto.setPenaltyTaker(firstNonBlank(getString(event, "takerName"),
                getString(event, "playerName")));
        Boolean penaltyScored = getBoolean(event, "penaltyScored");
        if (penaltyScored == null && "PENALTY_GOAL".equals(type)) penaltyScored = true;
        dto.setPenaltyScored(penaltyScored);
        return dto;
    }

    private MatchEventFlatDTO mapCorner(MatchEventFlatDTO dto, Map<String, Object> event,
                                        String eventTeam) {
        dto.setEventType("CornerEvent");
        dto.setCornerTeam(eventTeam);
        dto.setCornerTaker(firstNonBlank(getString(event, "takerName"),
                getString(event, "playerName")));
        return dto;
    }

    private MatchEventFlatDTO mapFreeKick(MatchEventFlatDTO dto, Map<String, Object> event,
                                          String eventTeam) {
        dto.setEventType("FreeKickEvent");
        dto.setFreeKickTeam(eventTeam);
        dto.setFreeKickTaker(firstNonBlank(getString(event, "takerName"),
                getString(event, "playerName")));
        return dto;
    }

    private MatchEventFlatDTO mapShot(MatchEventFlatDTO dto, Map<String, Object> event,
                                      String eventTeam, boolean onTarget) {
        if (Boolean.TRUE.equals(getBoolean(event, "isGoal"))) return null;
        String player = firstNonBlank(getString(event, "shooterName"),
                getString(event, "playerName"));
        if (onTarget) {
            dto.setEventType("ShotOnTargetEvent");
            dto.setShotOnTargetPlayer(player);
            dto.setShotOnTargetTeam(eventTeam);
        } else {
            dto.setEventType("ShotOffTargetEvent");
            dto.setShotOffTargetPlayer(player);
            dto.setShotOffTargetTeam(eventTeam);
        }
        dto.setXG(firstDouble(event, "xG", "xg"));
        return dto;
    }

    private MatchEventFlatDTO mapSubstitution(MatchEventFlatDTO dto, Map<String, Object> event,
                                              String eventTeam) {
        dto.setEventType("SubstitutionEvent");
        dto.setSubstitutionTeam(eventTeam);
        dto.setPlayerOutName(firstNonBlank(getString(event, "playerOutName"),
                getString(event, "outPlayerName")));
        dto.setPlayerInName(firstNonBlank(getString(event, "playerInName"),
                getString(event, "inPlayerName")));
        return dto;
    }

    private MatchEventFlatDTO mapInjury(MatchEventFlatDTO dto, Map<String, Object> event,
                                        String eventTeam) {
        dto.setEventType("InjuryEvent");
        dto.setInjuryTeam(eventTeam);
        dto.setInjuryPlayer(firstNonBlank(getString(event, "playerName"),
                getString(event, "injuredPlayerName")));
        return dto;
    }

    private MatchEventFlatDTO mapMatchBoundary(MatchEventFlatDTO dto, String eventType) {
        dto.setEventType(eventType);
        return dto;
    }

    private String normalizeEventType(Map<String, Object> event) {
        String type = getString(event, "type");
        if (type == null || type.isBlank()) {
            if (event.containsKey("scorerName")) return "GOAL";
            if (event.containsKey("cardType")) return "CARD";
            if (event.containsKey("playerOutName") && event.containsKey("playerInName")) return "SUB";
            if (event.containsKey("isGoal") || event.containsKey("onTarget")) {
                if (Boolean.TRUE.equals(getBoolean(event, "isGoal"))) return null;
                Boolean onTarget = getBoolean(event, "onTarget");
                if (onTarget != null) return onTarget ? "SHOT_ON_TARGET" : "SHOT_OFF_TARGET";
                return event.containsKey("isGoal") ? "SHOT_OFF_TARGET" : null;
            }
            if (event.containsKey("homeTeamName") && event.containsKey("awayTeamName")) {
                return "MATCH_START";
            }
            Integer minute = getInt(event, "minute");
            if (event.containsKey("homeGoals") && event.containsKey("awayGoals")
                    && minute != null && minute >= 90) {
                return "MATCH_END";
            }
            return null;
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        if (normalized.endsWith("_EVENT")) {
            normalized = normalized.substring(0, normalized.length() - "_EVENT".length());
        }
        if ("GOAL".equals(normalized)) return normalized;
        if ("YELLOW_CARD".equals(normalized) || "RED_CARD".equals(normalized)
                || "CARD".equals(normalized)) return normalized;
        if ("SHOT".equals(normalized)) {
            Boolean onTarget = getBoolean(event, "onTarget");
            if (onTarget != null) return onTarget ? "SHOT_ON_TARGET" : "SHOT_OFF_TARGET";
            if (event.containsKey("isGoal") && !Boolean.TRUE.equals(getBoolean(event, "isGoal"))) {
                return "SHOT_OFF_TARGET";
            }
        }
        if ("MATCH_STARTED".equals(normalized) || "MATCHSTART".equals(normalized)) return "MATCH_START";
        if ("MATCH_ENDED".equals(normalized) || "MATCHEND".equals(normalized)) return "MATCH_END";
        if ("SHOTONTARGET".equals(normalized)) return "SHOT_ON_TARGET";
        if ("SHOTOFFTARGET".equals(normalized)) return "SHOT_OFF_TARGET";
        return normalized;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private Double firstDouble(Map<String, Object> event, String... keys) {
        for (String key : keys) {
            Double value = getDouble(event, key);
            if (value != null) return value;
        }
        return null;
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
