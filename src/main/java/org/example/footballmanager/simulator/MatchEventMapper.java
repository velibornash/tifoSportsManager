package org.example.footballmanager.simulator;

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

                if (g.getScorer() != null) {
                    Player p = g.getScorer();
                    dto.setPlayerName(p.getName());
                    dto.setPlayerAge(p.getAge());
                    dto.setPlayerHeight(p.getHeight());
                    dto.setPlayerWeight(p.getWeight());
                    dto.setPlayerTotalGoals(p.getTotalGoals());
                    dto.setPlayerTotalAssists(p.getTotalAssists());
                    dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                    dto.setPlayerRating(p.getRating());
                }

                dto.setScorerName(g.getScorer() != null ? g.getScorer().getName() : null);
                dto.setAssistantName(g.getAssistant() != null ? g.getAssistant().getName() : null);
                dto.setTeamName(g.getTeam() != null ? g.getTeam().getName() : null);
                dto.setScoreAfterGoal(g.getScoreAfterGoal());

                return dto;

            }
            case YellowCardEvent y -> {
                YellowCardEventDTO dto = new YellowCardEventDTO();
                dto.setType("yellowCard");
                dto.setMinute(y.getMinute());
                dto.setDescription(y.getDescription());

                if (y.getPlayer() != null) {
                    Player p = y.getPlayer();
                    dto.setPlayerName(p.getName());
                    dto.setPlayerAge(p.getAge());
                    dto.setPlayerHeight(p.getHeight());
                    dto.setPlayerWeight(p.getWeight());
                    dto.setPlayerTotalGoals(p.getTotalGoals());
                    dto.setPlayerTotalAssists(p.getTotalAssists());
                    dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                    dto.setPlayerRating(p.getRating());
                }

                dto.setPlayerName(y.getPlayer() != null ? y.getPlayer().getName() : null);
                dto.setTeamName(y.getPlayer() != null && y.getPlayer().getTeam() != null ?
                        y.getPlayer().getTeam().getName() : null);
                return dto;

            }
            case RedCardEvent r -> {
                RedCardEventDTO dto = new RedCardEventDTO();
                dto.setType("redCard");
                dto.setMinute(r.getMinute());
                dto.setDescription(r.getDescription());

                if (r.getPlayer() != null) {
                    Player p = r.getPlayer();
                    dto.setPlayerName(p.getName());
                    dto.setPlayerAge(p.getAge());
                    dto.setPlayerHeight(p.getHeight());
                    dto.setPlayerWeight(p.getWeight());
                    dto.setPlayerTotalGoals(p.getTotalGoals());
                    dto.setPlayerTotalAssists(p.getTotalAssists());
                    dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                    dto.setPlayerRating(p.getRating());
                }
                return dto;

            }
            case InjuryEvent i -> {
                InjuryEventDTO dto = new InjuryEventDTO();
                dto.setType("injury");
                dto.setMinute(i.getMinute());
                dto.setDescription(i.getDescription());

                if (i.getPlayer() != null) {
                    Player p = i.getPlayer();
                    dto.setPlayerName(p.getName());
                    dto.setPlayerAge(p.getAge());
                    dto.setPlayerHeight(p.getHeight());
                    dto.setPlayerWeight(p.getWeight());
                    dto.setPlayerTotalGoals(p.getTotalGoals());
                    dto.setPlayerTotalAssists(p.getTotalAssists());
                    dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                    dto.setPlayerRating(p.getRating());
                }
                return dto;

            }
            case PenaltyEvent p -> {
                PenaltyEventDTO dto = new PenaltyEventDTO();
                dto.setType("penalty");
                dto.setMinute(p.getMinute());
                dto.setDescription(p.getDescription());

                if (p.getTaker() != null) {
                    Player pl = p.getTaker();
                    dto.setPlayerName(pl.getName());
                    dto.setPlayerAge(pl.getAge());
                    dto.setPlayerHeight(pl.getHeight());
                    dto.setPlayerWeight(pl.getWeight());
                    dto.setPlayerTotalGoals(pl.getTotalGoals());
                    dto.setPlayerTotalAssists(pl.getTotalAssists());
                    dto.setPlayerPosition(pl.getPosition() != null ? pl.getPosition().name() : null);
                    dto.setPlayerRating(pl.getRating());
                }
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

                if (s.getPlayerOut() != null) {
                    Player p = s.getPlayerOut();
                    dto.setPlayerName(p.getName());
                    dto.setPlayerAge(p.getAge());
                    dto.setPlayerHeight(p.getHeight());
                    dto.setPlayerWeight(p.getWeight());
                    dto.setPlayerTotalGoals(p.getTotalGoals());
                    dto.setPlayerTotalAssists(p.getTotalAssists());
                    dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                    dto.setPlayerRating(p.getRating());
                }

                dto.setPlayerOutName(s.getPlayerOut() != null ? s.getPlayerOut().getName() : null);
                dto.setPlayerInName(s.getPlayerIn() != null ? s.getPlayerIn().getName() : null);
                dto.setTeamName(s.getPlayerOut() != null && s.getPlayerOut().getTeam() != null ?
                        s.getPlayerOut().getTeam().getName() : null);
                return dto;

            }
            case OffsideEvent o -> {
                OffsideEventDTO dto = new OffsideEventDTO();
                dto.setType("offside");
                dto.setMinute(o.getMinute());
                dto.setDescription(o.getDescription());

                if (o.getPlayer() != null) {
                    Player p = o.getPlayer();
                    dto.setPlayerName(p.getName());
                    dto.setPlayerAge(p.getAge());
                    dto.setPlayerHeight(p.getHeight());
                    dto.setPlayerWeight(p.getWeight());
                    dto.setPlayerTotalGoals(p.getTotalGoals());
                    dto.setPlayerTotalAssists(p.getTotalAssists());
                    dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                    dto.setPlayerRating(p.getRating());
                }
                dto.setPlayerName(o.getPlayer() != null ? o.getPlayer().getName() : null);
                dto.setTeamName(o.getPlayer() != null && o.getPlayer().getTeam() != null ?
                        o.getPlayer().getTeam().getName() : null);
                return dto;

            }
            case CornerEvent c -> {
                CornerEventDTO dto = new CornerEventDTO();
                dto.setType("corner");
                dto.setMinute(c.getMinute());
                dto.setDescription(c.getDescription());
                dto.setTeamName(c.getTeam() != null ? c.getTeam().getName() : null);
                if (c.getPlayer() != null) {
                    Player p = c.getPlayer();
                    dto.setPlayerName(p.getName());
                    dto.setPlayerAge(p.getAge());
                    dto.setPlayerHeight(p.getHeight());
                    dto.setPlayerWeight(p.getWeight());
                    dto.setPlayerTotalGoals(p.getTotalGoals());
                    dto.setPlayerTotalAssists(p.getTotalAssists());
                    dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                    dto.setPlayerRating(p.getRating());
                }
                dto.setPlayerName(c.getPlayer() != null ? c.getPlayer().getName() : null);
                dto.setTakerName(c.getPlayer() != null ? c.getPlayer().getName() : null);
                dto.setTeamName(c.getTeam() != null ? c.getTeam().getName() : null);
                return dto;

            }
            case FreeKickEvent f -> {
                FreeKickEventDTO dto = new FreeKickEventDTO();
                dto.setType("freeKick");
                dto.setMinute(f.getMinute());
                dto.setDescription(f.getDescription());
                if (f.getPlayer() != null) {
                    Player p = f.getTaker();
                    dto.setPlayerName(p.getName());
                    dto.setPlayerAge(p.getAge());
                    dto.setPlayerHeight(p.getHeight());
                    dto.setPlayerWeight(p.getWeight());
                    dto.setPlayerTotalGoals(p.getTotalGoals());
                    dto.setPlayerTotalAssists(p.getTotalAssists());
                    dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                    dto.setPlayerRating(p.getRating());
                }
                if (f.getTaker() != null) {
                    Player p = f.getTaker();
                    dto.setPlayerName(p.getName());
                    dto.setPlayerAge(p.getAge());
                    dto.setPlayerHeight(p.getHeight());
                    dto.setPlayerWeight(p.getWeight());
                    dto.setPlayerTotalGoals(p.getTotalGoals());
                    dto.setPlayerTotalAssists(p.getTotalAssists());
                    dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                    dto.setPlayerRating(p.getRating());
                }
                dto.setPlayerName(f.getPlayer() != null ? f.getPlayer().getName() : null);
                dto.setTakerName(f.getTaker() != null ? f.getTaker().getName() : null);
                dto.setTeamName(f.getPlayer() != null && f.getPlayer().getTeam() != null ?
                        f.getPlayer().getTeam().getName() : null);
                return dto;

            }
            case ShotOnTargetEvent s -> {
                ShotOnTargetEventDTO dto = new ShotOnTargetEventDTO();
                dto.setType("shotOnTarget");
                dto.setMinute(s.getMinute());
                dto.setDescription(s.getDescription());

                if (s.getShooter() != null) {
                    Player p = s.getShooter();
                    dto.setPlayerName(p.getName());
                    dto.setPlayerAge(p.getAge());
                    dto.setPlayerHeight(p.getHeight());
                    dto.setPlayerWeight(p.getWeight());
                    dto.setPlayerTotalGoals(p.getTotalGoals());
                    dto.setPlayerTotalAssists(p.getTotalAssists());
                    dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                    dto.setPlayerRating(p.getRating());
                }
                dto.setPlayerName(s.getShooter() != null ? s.getShooter().getName() : null);
                dto.setTeamName(s.getTeam() != null ? s.getTeam().getName() : null);
                return dto;

            }
            case ShotOffTargetEvent s -> {
                ShotOffTargetEventDTO dto = new ShotOffTargetEventDTO();
                dto.setType("shotOffTarget");
                dto.setMinute(s.getMinute());
                dto.setDescription(s.getDescription());

                if (s.getShooter() != null) {
                    Player p = s.getShooter();
                    dto.setPlayerName(p.getName());
                    dto.setPlayerAge(p.getAge());
                    dto.setPlayerHeight(p.getHeight());
                    dto.setPlayerWeight(p.getWeight());
                    dto.setPlayerTotalGoals(p.getTotalGoals());
                    dto.setPlayerTotalAssists(p.getTotalAssists());
                    dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                    dto.setPlayerRating(p.getRating());
                }
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
                return dto;

            }
            case ChanceEvent c -> {
                ChanceEventDTO dto = new ChanceEventDTO();
                dto.setType("chance");
                dto.setMinute(c.getMinute());
                dto.setDescription(c.getDescription());

                if (c.getPlayer() != null) {
                    Player p = c.getPlayer();
                    dto.setPlayerName(p.getName());
                    dto.setPlayerAge(p.getAge());
                    dto.setPlayerHeight(p.getHeight());
                    dto.setPlayerWeight(p.getWeight());
                    dto.setPlayerTotalGoals(p.getTotalGoals());
                    dto.setPlayerTotalAssists(p.getTotalAssists());
                    dto.setPlayerPosition(p.getPosition() != null ? p.getPosition().name() : null);
                    dto.setPlayerRating(p.getRating());
                }
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

        log.warn("Nepoznat event tip za DTO: {}", event.getClass().getSimpleName());
        return null;
    }
}
