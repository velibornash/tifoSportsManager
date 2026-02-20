package org.example.footballmanager.dto;

import lombok.Data;
import org.example.footballmanager.model.event.*;

@Data
public class AllEventDTO {

    private String type;
    private int minute;
    private String player;
    private String team;
    private String scorerName;
    private String assistantName;
    private String teamName;
    private String scoreAfterGoal;
    private boolean scored;
    private boolean dangerous;
    private String playerInName;
    private String playerOutName;
    private String playerName;
    private String takerName;
    private String shooterName;
    private String decision;

    public static AllEventDTO fromGoalEvent(GoalEvent g) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("Goal");
        dto.setMinute(g.getMinute());
        dto.setScorerName(g.getScorer() != null ? g.getScorer().getName() : "N/A");
        dto.setAssistantName(g.getAssistant() != null ? g.getAssistant().getName() : "N/A");
        dto.setTeam(g.getTeam().getName());
        dto.setTeamName(g.getTeam().getName());
        return dto;
    }

    public static AllEventDTO fromChanceEvent(ChanceEvent c) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("Possession");
        dto.setMinute(c.getMinute());
        dto.setPlayer(c.getPlayer() != null ? c.getPlayer().getName() : "N/A");
        dto.setTeam(c.getTeam().getName());
        dto.setTeamName(c.getTeam().getName());
        return dto;
    }

    public static AllEventDTO fromYellowCardEvent(YellowCardEvent y) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("YellowCard");
        dto.setMinute(y.getMinute());
        dto.setPlayer(y.getPlayer() != null ? y.getPlayer().getName() : "N/A");
        dto.setTeam(y.getPlayer() != null ? y.getPlayer().getTeam().getName() : "N/A");
        dto.setTeam(y.getTeam().getName());
        dto.setTeamName(y.getTeam().getName());
        return dto;
    }

    public static AllEventDTO fromRedCardEvent(RedCardEvent r) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("RedCard");
        dto.setMinute(r.getMinute());
        dto.setPlayer(r.getPlayer() != null ? r.getPlayer().getName() : "N/A");
        dto.setTeam(r.getPlayer() != null ? r.getPlayer().getTeam().getName() : "N/A");
        dto.setTeam(r.getTeam().getName());
        dto.setTeamName(r.getTeam().getName());
        return dto;
    }

    public static AllEventDTO fromPenaltyEvent(PenaltyEvent p) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("Penalty");
        dto.setMinute(p.getMinute());
        dto.setPlayer(p.getTaker() != null ? p.getTaker().getName() : "N/A");
        dto.setTeam(p.getTeam() != null ? p.getTeam().getName() : "N/A");
        dto.setTeamName(p.getTeam().getName());
        return dto;
    }

    public static AllEventDTO fromFreeKickEvent(FreeKickEvent f) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("FreeKick");
        dto.setMinute(f.getMinute());
        dto.setPlayer(f.getPlayer() != null ? f.getPlayer().getName() : "N/A");
        dto.setTakerName(f.getTaker() != null ? f.getTaker().getName() : "N/A");
        dto.setTeam(f.getTeam() != null ? f.getTeam().getName() : "N/A");
        dto.setTeamName(f.getTeam().getName());
        return dto;
    }

    public static AllEventDTO fromOffsideEvent(OffsideEvent o) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("Offside");
        dto.setMinute(o.getMinute());
        dto.setPlayer(o.getPlayer() != null ? o.getPlayer().getName() : "N/A");
        dto.setTeam(o.getPlayer().getTeam() != null ? o.getPlayer().getTeam().getName() : "N/A");
        dto.setTeamName(o.getPlayer().getTeam().getName());
        return dto;
    }

    public static AllEventDTO fromCornerEvent(CornerEvent c) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("Corner");
        dto.setMinute(c.getMinute());
        dto.setPlayer(c.getPlayer() != null ? c.getPlayer().getName() : "N/A");
        dto.setTeam(c.getTeam() != null ? c.getTeam().getName() : "N/A");
        return dto;
    }

    public static AllEventDTO fromSubstitutionEvent(SubstitutionEvent s) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("Substitution");
        dto.setMinute(s.getMinute());
        dto.setPlayerOutName(s.getPlayerOut() != null ? s.getPlayerOut().getName() : "N/A");
        dto.setPlayerInName(s.getPlayerIn() != null ? s.getPlayerIn().getName() : "N/");
        dto.setTeam(s.getTeam() != null ? s.getTeam().getName() : "N/A");
        dto.setTeamName(s.getTeam().getName());
        return dto;
    }

    public static AllEventDTO fromVARReviewEvent(VARReviewEvent v) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("VARReview");
        dto.setMinute(v.getMinute());
        dto.setDecision(v.getDecision());
        return dto;
    }

    public static AllEventDTO fromShotOnTargetEvent(ShotOnTargetEvent s) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("ShotOnTarget");
        dto.setMinute(s.getMinute());
        dto.setPlayer(s.getShooter() != null ? s.getShooter().getName() : "N/A");
        dto.setShooterName(s.getShooter().getName());
        dto.setTeam(s.getTeam() != null ? s.getTeam().getName() : "N/A");
        dto.setTeamName(s.getTeam().getName());
        return dto;
    }

    public static AllEventDTO fromShotOffTargetEvent(ShotOffTargetEvent s) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("ShotOffTarget");
        dto.setMinute(s.getMinute());
        dto.setPlayer(s.getShooter() != null ? s.getShooter().getName() : "N/A");
        dto.setShooterName(s.getShooter().getName());
        dto.setTeam(s.getTeam() != null ? s.getTeam().getName() : "N/A");
        dto.setTeamName(s.getTeam().getName());
        return dto;
    }

    public static AllEventDTO fromMatchStartEvent(MatchStartEvent m) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("MatchStart");
        dto.setMinute(0);
        dto.setPlayer(null);
        dto.setTeam(null);
        return dto;
    }

    public static AllEventDTO fromMatchEndedEvent(MatchEndedEvent m) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("MatchEnd");
        dto.setMinute(90);
        dto.setPlayer(null);
        dto.setTeam(null);
        return dto;
    }

    public static AllEventDTO fromInjuryEvent(InjuryEvent i) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("Injury");
        dto.setMinute(i.getMinute());
        dto.setPlayer(i.getPlayer() != null ? i.getPlayer().getName() : "N/A");
        dto.setTeam(i.getPlayer() != null ? i.getPlayer().getTeam().getName() : "N/A");
        return dto;
    }
}