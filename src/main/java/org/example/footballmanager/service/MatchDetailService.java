package org.example.footballmanager.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.MatchEventFlatDTO;
import org.example.footballmanager.repository.MatchRepository;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("unchecked")
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchDetailService {

    private final MatchRepository matchRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public List<MatchEventFlatDTO> getMatchEventsFlat(Long matchId) {

        log.info("Zahtev za detalje meča ID: {}", matchId);

        boolean exists = matchRepository.existsById(matchId);
        log.info("Meč ID {} postoji u bazi? {}", matchId, exists);

        if (!exists) {
            log.warn("Meč ID {} NE POSTOJI u bazi!", matchId);
            throw new RuntimeException("Match not found: " + matchId);
        }

        log.info("Izvršavam native upit za matchId={}", matchId);

        List<Object[]> results = entityManager.createNativeQuery("""
        SELECT m.id MatchID, m.match_date MatchDate, hot.name HomeTeam, m.home_goals HomeGoals, awt.name AwayTeam, m.away_goals AwayGoals,
        me.event_minute MatchMinute, me.event_type EventType,
        scorer.name Scorer, assistant.name Assistant, ge.score_after_goal ScoreAfterGoal, scoreTeam.name ScoreTeam,
        possesionTeam.name PossesionTeam, yellowCardTeam.name YellowCardTeam, redCardTeam.name RedCardteam,
        penaltyTeam.name PenaltyTeam,cornerTeam.name CornerTeam,freeKickTeam.name FreeKickTeam,
        cornerTaker.name CornerTaker,fkTaker.name FreeKickTaker,
        hle.formation HomeFormation, ale.formation AwayFormation,
        penaltyTaker.name PenaltyTaker, pe.scored PenaltyScored,
        redCardPlayer.name ReadCardPlayer, yellowCardPlayer.name YellowCardPlayer,
        shotOnTarget.name ShotOnTargetPlayer, shotOffTarget.name ShotOffTargetPlayer,
        shotOnTargetTeam.name ShotOnTargetTeam, shotOffTargetTeam.name ShotOffTargetTeam
        
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
        WHERE m.id = :matchId
        ORDER BY me.event_minute asc
        """)
                .setParameter("matchId", matchId)
                .getResultList();

        log.info("Upit vratio {} redova", results.size());

        List<MatchEventFlatDTO> dtos = new ArrayList<>();
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
                dto.setMatchMinute(safeInt(row[i++]));
                dto.setEventType(safeString(row[i++]));
                dto.setScorer(safeString(row[i++]));
                dto.setAssistant(safeString(row[i++]));
                dto.setScoreAfterGoal(safeString(row[i++]));
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
                dto.setShotOffTargetTeam(safeString(row[i]));

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
                }
                dto.setEventTeam(team);
                dtos.add(dto);
            } catch (Exception ex) {
                log.error("Greška pri mapiranju reda {} za matchId={}: {}", rowIndex, matchId, ex.getMessage(), ex);
            }
        }

        log.info("Uspešno mapirano {} eventa", dtos.size());
        return dtos;
    }

    // Helper metode (ostaju iste)
    private Long safeLong(Object obj) { return obj != null ? ((Number) obj).longValue() : null; }
    private Integer safeInt(Object obj) { return obj != null ? ((Number) obj).intValue() : null; }
    private String safeString(Object obj) { return obj != null ? obj.toString() : null; }
    private Boolean safeBoolean(Object obj) { return obj != null ? (Boolean) obj : null; }
}