package org.example.footballmanager.util.events;

import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.*;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.event.*;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MatchEventMapper {

    public MatchEventDTO toDto(MatchEvent event) {
        switch (event) {
            case null -> {
                return null;
            }
            case GoalEvent g -> {
                GoalEventDTO dto = new GoalEventDTO();
                dto.setType("goal");
                dto.setMinute(g.getMinute());
                dto.setDescription(g.getDescription());
                fillPlayerFields(dto, g.getScorer());
                dto.setScorerName(g.getScorer() != null ? g.getScorer().getName() : null);
                dto.setAssistantName(g.getAssistant() != null ? g.getAssistant().getName() : null);
                dto.setTeamName(g.getTeam() != null ? g.getTeam().getName() : null);
                dto.setScoreAfterGoal(g.getScoreAfterGoal());
                dto.setScored(g.isScored());
                return dto;
            }
            case YellowCardEvent y -> {
                YellowCardEventDTO dto = new YellowCardEventDTO();
                dto.setType("yellowCard");
                dto.setMinute(y.getMinute());
                dto.setDescription(y.getDescription());
                fillPlayerFields(dto, y.getPlayer());
                dto.setPlayerName(y.getPlayer() != null ? y.getPlayer().getName() : null);
                dto.setTeamName(y.getPlayer() != null && y.getPlayer().getTeam() != null ? y.getPlayer().getTeam().getName() : null);
                return dto;
            }
            case RedCardEvent r -> {
                RedCardEventDTO dto = new RedCardEventDTO();
                dto.setType("redCard");
                dto.setMinute(r.getMinute());
                dto.setDescription(r.getDescription());
                fillPlayerFields(dto, r.getPlayer());
                return dto;
            }
            case InjuryEvent i -> {
                InjuryEventDTO dto = new InjuryEventDTO();
                dto.setType("injury");
                dto.setMinute(i.getMinute());
                dto.setDescription(i.getDescription());
                fillPlayerFields(dto, i.getPlayer());
                return dto;
            }
            case PenaltyEvent p -> {
                PenaltyEventDTO dto = new PenaltyEventDTO();
                dto.setType("penalty");
                dto.setMinute(p.getMinute());
                dto.setDescription(p.getDescription());
                fillPlayerFields(dto, p.getTaker());
                dto.setTakerName(p.getTaker() != null ? p.getTaker().getName() : null);
                dto.setTeamName(p.getTeam() != null ? p.getTeam().getName() : null);
                dto.setScored(p.isScored());
                return dto;
            }
            case SubstitutionEvent s -> {
                SubstitutionEventDTO dto = new SubstitutionEventDTO();
                dto.setType("substitution");
                dto.setMinute(s.getMinute());
                dto.setDescription(s.getDescription());
                fillPlayerFields(dto, s.getPlayerOut());
                dto.setPlayerOutName(s.getPlayerOut() != null ? s.getPlayerOut().getName() : null);
                dto.setPlayerInName(s.getPlayerIn() != null ? s.getPlayerIn().getName() : null);
                dto.setTeamName(s.getPlayerOut() != null && s.getPlayerOut().getTeam() != null ? s.getPlayerOut().getTeam().getName() : null);
                return dto;
            }
            case OffsideEvent o -> {
                OffsideEventDTO dto = new OffsideEventDTO();
                dto.setType("offside");
                dto.setMinute(o.getMinute());
                dto.setDescription(o.getDescription());
                fillPlayerFields(dto, o.getPlayer());
                dto.setPlayerName(o.getPlayer() != null ? o.getPlayer().getName() : null);
                dto.setTeamName(o.getPlayer() != null && o.getPlayer().getTeam() != null ? o.getPlayer().getTeam().getName() : null);
                return dto;
            }
            case CornerEvent c -> {
                CornerEventDTO dto = new CornerEventDTO();
                dto.setType("corner");
                dto.setMinute(c.getMinute());
                dto.setDescription(c.getDescription());
                fillPlayerFields(dto, c.getPlayer());
                dto.setPlayerName(c.getPlayer() != null ? c.getPlayer().getName() : null);
                dto.setTakerName(c.getPlayer() != null ? c.getPlayer().getName() : null);
                dto.setTeamName(c.getTeam() != null ? c.getTeam().getName() : null);
                return dto;
            }
            case ThrowInEvent t -> {
                ThrowInEventDTO dto = new ThrowInEventDTO();
                dto.setType("throwIn");
                dto.setMinute(t.getMinute());
                dto.setDescription(t.getDescription());
                fillPlayerFields(dto, t.getTaker());
                dto.setPlayerName(t.getTaker() != null ? t.getTaker().getName() : null);
                dto.setTakerName(t.getTaker() != null ? t.getTaker().getName() : null);
                dto.setTeamName(t.getTeam() != null ? t.getTeam().getName() : null);
                return dto;
            }
            case GoalKickEvent gk -> {
                GoalKickEventDTO dto = new GoalKickEventDTO();
                dto.setType("goalKick");
                dto.setMinute(gk.getMinute());
                dto.setDescription(gk.getDescription());
                fillPlayerFields(dto, gk.getGoalkeeper());
                dto.setPlayerName(gk.getGoalkeeper() != null ? gk.getGoalkeeper().getName() : null);
                dto.setGoalkeeperName(gk.getGoalkeeper() != null ? gk.getGoalkeeper().getName() : null);
                dto.setTeamName(gk.getTeam() != null ? gk.getTeam().getName() : null);
                return dto;
            }
            case FreeKickEvent f -> {
                FreeKickEventDTO dto = new FreeKickEventDTO();
                dto.setType("freeKick");
                dto.setMinute(f.getMinute());
                dto.setDescription(f.getDescription());
                fillPlayerFields(dto, f.getTaker());
                dto.setPlayerName(f.getPlayer() != null ? f.getPlayer().getName() : null);
                dto.setTakerName(f.getTaker() != null ? f.getTaker().getName() : null);
                dto.setTeamName(f.getPlayer() != null && f.getPlayer().getTeam() != null ? f.getPlayer().getTeam().getName() : null);
                return dto;
            }
            case ShotOnTargetEvent s -> {
                ShotOnTargetEventDTO dto = new ShotOnTargetEventDTO();
                dto.setType("shotOnTarget");
                dto.setMinute(s.getMinute());
                dto.setDescription(s.getDescription());
                fillPlayerFields(dto, s.getShooter());
                dto.setPlayerName(s.getShooter() != null ? s.getShooter().getName() : null);
                dto.setTeamName(s.getTeam() != null ? s.getTeam().getName() : null);
                return dto;
            }
            case ShotOffTargetEvent s -> {
                ShotOffTargetEventDTO dto = new ShotOffTargetEventDTO();
                dto.setType("shotOffTarget");
                dto.setMinute(s.getMinute());
                dto.setDescription(s.getDescription());
                fillPlayerFields(dto, s.getShooter());
                dto.setPlayerName(s.getShooter() != null ? s.getShooter().getName() : null);
                dto.setTeamName(s.getTeam() != null ? s.getTeam().getName() : null);
                return dto;
            }
            case VARReviewEvent v -> {
                VARReviewEventDTO dto = new VARReviewEventDTO();
                dto.setType("varReview");
                dto.setMinute(v.getMinute());
                dto.setDescription(v.getDescription());
                dto.setDecision(v.getDecision());
                dto.setOverturnReason(v.getOverturnReason());
                if (v.getReviewedGoalEvent() != null) {
                    fillPlayerFields(dto, v.getReviewedGoalEvent().getScorer());
                    dto.setReviewTarget("goal");
                    dto.setTeamName(v.getReviewedGoalEvent().getTeam() != null ? v.getReviewedGoalEvent().getTeam().getName() : null);
                    dto.setPlayerName(v.getReviewedGoalEvent().getScorer() != null ? v.getReviewedGoalEvent().getScorer().getName() : null);
                } else if (v.getReviewedPenaltyEvent() != null) {
                    fillPlayerFields(dto, v.getReviewedPenaltyEvent().getTaker());
                    dto.setReviewTarget("penalty");
                    dto.setTeamName(v.getReviewedPenaltyEvent().getTeam() != null ? v.getReviewedPenaltyEvent().getTeam().getName() : null);
                    dto.setPlayerName(v.getReviewedPenaltyEvent().getTaker() != null ? v.getReviewedPenaltyEvent().getTaker().getName() : null);
                } else if (v.getReviewedOffsideEvent() != null) {
                    fillPlayerFields(dto, v.getReviewedOffsideEvent().getPlayer());
                    dto.setReviewTarget("offside");
                    dto.setTeamName(v.getReviewedOffsideEvent().getPlayer() != null && v.getReviewedOffsideEvent().getPlayer().getTeam() != null
                            ? v.getReviewedOffsideEvent().getPlayer().getTeam().getName()
                            : null);
                    dto.setPlayerName(v.getReviewedOffsideEvent().getPlayer() != null ? v.getReviewedOffsideEvent().getPlayer().getName() : null);
                } else {
                    dto.setReviewTarget("incident");
                }
                return dto;
            }
            case ChanceEvent c -> {
                ChanceEventDTO dto = new ChanceEventDTO();
                dto.setType("chance");
                dto.setMinute(c.getMinute());
                dto.setDescription(c.getDescription());
                fillPlayerFields(dto, c.getPlayer());
                dto.setPlayerName(c.getPlayer() != null ? c.getPlayer().getName() : null);
                dto.setTeamName(c.getTeam() != null ? c.getTeam().getName() : null);
                dto.setDangerous(c.isDangerous());
                return dto;
            }
            case MatchStartEvent ms -> {
                MatchStartedDTO dto = new MatchStartedDTO();
                dto.setType("matchStarted");
                dto.setMinute(ms.getMinute());
                dto.setDescription(ms.getDescription());
                dto.setHomeTeamName(ms.getMatch().getHomeTeam().getName());
                dto.setAwayTeamName(ms.getMatch().getAwayTeam().getName());
                return dto;
            }
            case MatchEndedEvent me -> {
                MatchEndedDTO dto = new MatchEndedDTO();
                dto.setType("matchEnded");
                dto.setMinute(me.getMinute());
                dto.setDescription(me.getDescription());
                dto.setHomeTeamName(me.getMatch().getHomeTeam().getName());
                dto.setAwayTeamName(me.getMatch().getAwayTeam().getName());
                dto.setHomeGoals(me.getMatch().getHomeGoals());
                dto.setAwayGoals(me.getMatch().getAwayGoals());
                return dto;
            }
            default -> {
            }
        }

        log.warn("Unknown event type for DTO mapping: {}", event.getClass().getSimpleName());
        return null;
    }

    private void fillPlayerFields(MatchEventDTO dto, Player player) {
        if (player == null) {
            return;
        }
        dto.setPlayerName(player.getName());
        dto.setPlayerAge(player.getAge());
        dto.setPlayerHeight(player.getHeight());
        dto.setPlayerWeight(player.getWeight());
        dto.setPlayerTotalGoals(player.getTotalGoals());
        dto.setPlayerTotalAssists(player.getTotalAssists());
        dto.setPlayerPosition(player.getPosition() != null ? player.getPosition().name() : null);
        dto.setPlayerRating(player.getRating());
    }
}
