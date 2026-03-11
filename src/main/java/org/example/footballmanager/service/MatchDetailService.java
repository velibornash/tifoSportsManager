package org.example.footballmanager.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.MatchEventFlatDTO;
import org.example.footballmanager.repository.MatchRepository;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings("unchecked")
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchDetailService {

    private final MatchRepository matchRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public List<MatchEventFlatDTO> getMatchEventsFlat(Long matchId) {

        log.info("Request for match details, ID: {}", matchId);

        boolean exists = matchRepository.existsById(matchId);
        log.info("Does match ID {} exist in database? {}", matchId, exists);

        if (!exists) {
            log.warn("Match ID {} does not exist in database.", matchId);
            throw new RuntimeException("Match not found: " + matchId);
        }

        log.info("Executing native query for matchId={}", matchId);

        List<Object[]> results = entityManager.createNativeQuery("""
        SELECT m.id MatchID, m.match_date MatchDate, hot.name HomeTeam, m.home_goals HomeGoals, awt.name AwayTeam, m.away_goals AwayGoals,
        me.id EventId,
        me.event_minute MatchMinute, me.event_type EventType,
        scorer.name Scorer, assistant.name Assistant, ge.score_after_goal ScoreAfterGoal, ge.scored GoalScored, scoreTeam.name ScoreTeam,
        possesionTeam.name PossesionTeam, yellowCardTeam.name YellowCardTeam, redCardTeam.name RedCardteam,
        penaltyTeam.name PenaltyTeam,cornerTeam.name CornerTeam,freeKickTeam.name FreeKickTeam,
        cornerTaker.name CornerTaker,fkTaker.name FreeKickTaker,
        hle.formation HomeFormation, ale.formation AwayFormation,
        penaltyTaker.name PenaltyTaker, pe.scored PenaltyScored,
        redCardPlayer.name ReadCardPlayer, yellowCardPlayer.name YellowCardPlayer,
        shotOnTarget.name ShotOnTargetPlayer, shotOffTarget.name ShotOffTargetPlayer,
        shotOnTargetTeam.name ShotOnTargetTeam, shotOffTargetTeam.name ShotOffTargetTeam,
        COALESCE(ge.xg, shone.xg, shoffe.xg) EventXg,
        substitutionTeam.name SubstitutionTeam, playerOut.name PlayerOutName, playerIn.name PlayerInName,
        injuryTeam.name InjuryTeam, injuryPlayer.name InjuryPlayer
        
        FROM match m
        left join match_event me on m.id=me.match_id
        left join goal_event ge on me.id=ge.id
        left join chance_event che on me.id=che.id
        left join corner_event coe on me.id=coe.id
        left join free_kick_event fke on me.id=fke.id
        left join injury_event ie on me.id=ie.id
        left join offside_event oe on me.id=oe.id
        left join penalty_event pe on me.id=pe.id
        left join red_card_event re on me.id=re.id
        left join shot_off_target_event shoffe on me.id=shoffe.id
        left join shot_on_target_event shone on me.id=shone.id
        left join substitution_event sube on me.id=sube.id
        left join yellow_card_event ye on me.id=ye.id
        left join varreview_event ve on me.id=ve.id
        left join team hot on hot.id=m.home_team_id
        left join team awt on awt.id=m.away_team_id
        left join lineup hle on m.home_lineup_id=hle.id
        left join lineup ale on m.away_lineup_id=ale.id
        left join player scorer on scorer.id=ge.scorer_id
        left join player assistant on assistant.id=ge.assistant_id
        left join team scoreTeam on scoreTeam.id=ge.team_id
        left join team possesionTeam on possesionTeam.id=che.team_id
        left join team yellowCardTeam on yellowCardTeam.id=ye.team_id
        left join team redCardTeam on redCardTeam.id=re.team_id
        left join team penaltyTeam on penaltyTeam.id=pe.team_id
        left join team cornerTeam on cornerTeam.id=coe.team_id
        left join team freeKickTeam on freeKickteam.id=fke.team_id
        left join player cornerTaker on coe.player_id=cornerTaker.id
        left join player fkTaker on fke.taker_id=fkTaker.id
        left join player penaltyTaker on pe.taker_id=penaltyTaker.id
        left join player redCardPlayer on re.player_id=redCardPlayer.id
        left join player yellowCardPlayer on ye.player_id=yellowCardPlayer.id
        left join player shotOnTarget on shone.shooter_id=shotOnTarget.id
        left join player shotOffTarget on shoffe.shooter_id=shotOffTarget.id
        left join team shotOnTargetTeam on shotOnTargetTeam.id=shone.team_id
        left join team shotOffTargetTeam on shotOffTargetTeam.id=shoffe.team_id
        left join team substitutionTeam on substitutionTeam.id=sube.team_id
        left join player playerOut on playerOut.id=sube.player_out_id
        left join player playerIn on playerIn.id=sube.player_in_id
        left join player injuryPlayer on injuryPlayer.id=ie.player_id
        left join team injuryTeam on injuryTeam.id=injuryPlayer.team_id
        WHERE m.id = :matchId
        ORDER BY me.event_minute asc, me.id asc
        """)
                .setParameter("matchId", matchId)
                .getResultList();

        log.info("Query returned {} rows", results.size());

        List<MatchEventFlatDTO> dtos = new ArrayList<>();
        Set<Long> seenEventIds = new LinkedHashSet<>();
        Set<String> seenGoalSignatures = new LinkedHashSet<>();
        for (int rowIndex = 0; rowIndex < results.size(); rowIndex++) {
            Object[] row = results.get(rowIndex);
            try {
                MatchEventFlatDTO dto = new MatchEventFlatDTO();
                int i = 0;

                dto.setMatchId(safeLong(row[i++]));
                Object dateObj = row[i++];
                dto.setMatchDate(dateObj instanceof java.sql.Timestamp
                        ? ((java.sql.Timestamp) dateObj).toLocalDateTime().toString()
                        : null);

                dto.setHomeTeam(safeString(row[i++]));
                dto.setHomeGoals(safeInt(row[i++]));
                dto.setAwayTeam(safeString(row[i++]));
                dto.setAwayGoals(safeInt(row[i++]));

                Long eventId = safeLong(row[i++]);
                if (eventId != null && !seenEventIds.add(eventId)) {
                    continue;
                }

                dto.setMatchMinute(safeInt(row[i++]));
                dto.setEventType(safeString(row[i++]));
                dto.setScorer(safeString(row[i++]));
                dto.setAssistant(safeString(row[i++]));
                dto.setScoreAfterGoal(safeString(row[i++]));
                dto.setGoalScored(safeBoolean(row[i++]));
                dto.setScoreTeam(safeString(row[i++]));
                dto.setPossessionTeam(safeString(row[i++]));
                dto.setYellowCardTeam(safeString(row[i++]));
                dto.setRedCardTeam(safeString(row[i++]));
                dto.setPenaltyTeam(safeString(row[i++]));
                dto.setCornerTeam(safeString(row[i++]));
                dto.setFreeKickTeam(safeString(row[i++]));
                dto.setCornerTaker(safeString(row[i++]));
                dto.setFreeKickTaker(safeString(row[i++]));
                dto.setHomeFormation(safeString(row[i++]));
                dto.setAwayFormation(safeString(row[i++]));
                dto.setPenaltyTaker(safeString(row[i++]));
                dto.setPenaltyScored(safeBoolean(row[i++]));
                dto.setRedCardPlayer(safeString(row[i++]));
                dto.setYellowCardPlayer(safeString(row[i++]));
                dto.setShotOnTargetPlayer(safeString(row[i++]));
                dto.setShotOffTargetPlayer(safeString(row[i++]));
                dto.setShotOnTargetTeam(safeString(row[i++]));
                dto.setShotOffTargetTeam(safeString(row[i++]));
                dto.setXG(safeDouble(row[i++]));
                dto.setSubstitutionTeam(safeString(row[i++]));
                dto.setPlayerOutName(safeString(row[i++]));
                dto.setPlayerInName(safeString(row[i++]));
                dto.setInjuryTeam(safeString(row[i++]));
                dto.setInjuryPlayer(safeString(row[i]));

                if ("GoalEvent".equals(dto.getEventType())) {
                    String goalSignature = String.join("|",
                            String.valueOf(dto.getMatchMinute()),
                            safeString(dto.getScorer()),
                            safeString(dto.getAssistant()),
                            safeString(dto.getScoreAfterGoal()),
                            String.valueOf(dto.getGoalScored())
                    );
                    if (!seenGoalSignatures.add(goalSignature)) {
                        continue;
                    }
                }

                String eventType = dto.getEventType();
                String team = null;

                if ("ChanceEvent".equals(eventType)) {
                    team = dto.getPossessionTeam();
                } else if ("ShotOnTargetEvent".equals(eventType)) {
                    team = dto.getShotOnTargetTeam();
                } else if ("ShotOffTargetEvent".equals(eventType)) {
                    team = dto.getShotOffTargetTeam();
                } else if ("GoalEvent".equals(eventType)) {
                    team = dto.getScoreTeam();
                }
                else if ("CornerEvent".equals(eventType)) {
                     team = dto.getCornerTeam();
                } else if ("FreeKickEvent".equals(eventType)) {
                    team = dto.getFreeKickTeam();
                } else if ("PenaltyEvent".equals(eventType)) {
                    team = dto.getPenaltyTeam();
                } else if ("YellowCardEvent".equals(eventType)) {
                    team = dto.getYellowCardTeam();
                } else if ("RedCardEvent".equals(eventType)) {
                    team = dto.getRedCardTeam();
                } else if ("SubstitutionEvent".equals(eventType)) {
                    team = dto.getSubstitutionTeam();
                } else if ("InjuryEvent".equals(eventType)) {
                    team = dto.getInjuryTeam();
                }
                dto.setEventTeam(team);
                dtos.add(dto);
            } catch (Exception ex) {
                log.error("Row mapping error at index {} for matchId={}: {}", rowIndex, matchId, ex.getMessage(), ex);
            }
        }

        log.info("Successfully mapped {} events", dtos.size());
        return dtos;
    }

    // Helper metode (ostaju iste)
    private Long safeLong(Object obj) { return obj != null ? ((Number) obj).longValue() : null; }
    private Integer safeInt(Object obj) { return obj != null ? ((Number) obj).intValue() : null; }
    private Double safeDouble(Object obj) { return obj != null ? ((Number) obj).doubleValue() : null; }
    private String safeString(Object obj) { return obj != null ? obj.toString() : null; }
    private Boolean safeBoolean(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Boolean b) {
            return b;
        }
        if (obj instanceof Number n) {
            return n.intValue() != 0;
        }
        return Boolean.parseBoolean(obj.toString());
    }
}

