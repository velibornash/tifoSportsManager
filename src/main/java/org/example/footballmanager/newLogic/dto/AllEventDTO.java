package org.example.footballmanager.newLogic.dto;

import lombok.Data;
import org.example.footballmanager.newLogic.model.event.*;

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
        dto.setMinute(g.minute());
        dto.setScorerName(g.scorerName() != null ? g.scorerName() : "N/A");
        dto.setAssistantName(g.assistantName() != null ? g.assistantName() : "N/A");
        dto.setTeam(g.teamSide());
        dto.setTeamName(g.teamSide());
        return dto;
    }

    public static AllEventDTO fromChanceEvent(ChanceEvent c) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("Possession");
        dto.setMinute(c.getMinute());
        dto.setPlayer(c.getPlayer() != null ? c.getPlayer().getName() : "N/A");
        dto.setTeam(c.getTeam() != null ? c.getTeam().getName() : "N/A");
        dto.setTeamName(c.getTeam() != null ? c.getTeam().getName() : "N/A");
        return dto;
    }

    public static AllEventDTO fromYellowCardEvent(YellowCardEvent y) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("YellowCard");
        dto.setMinute(y.getMinute());
        dto.setPlayer(y.getPlayer() != null ? y.getPlayer().getName() : "N/A");
        dto.setTeam(y.getTeam() != null ? y.getTeam().getName() : "N/A");
        dto.setTeamName(y.getTeam() != null ? y.getTeam().getName() : "N/A");
        return dto;
    }

    public static AllEventDTO fromRedCardEvent(RedCardEvent r) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("RedCard");
        dto.setMinute(r.getMinute());
        dto.setPlayer(r.getPlayer() != null ? r.getPlayer().getName() : "N/A");
        dto.setTeam(r.getTeam() != null ? r.getTeam().getName() : "N/A");
        dto.setTeamName(r.getTeam() != null ? r.getTeam().getName() : "N/A");
        return dto;
    }

    public static AllEventDTO fromPenaltyEvent(PenaltyEvent p) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("Penalty");
        dto.setMinute(p.minute());
        dto.setPlayer(p.takerName() != null ? p.takerName() : "N/A");
        dto.setTeam(p.teamSide() != null ? p.teamSide() : "N/A");
        dto.setTeamName(p.teamSide() != null ? p.teamSide() : "N/A");
        return dto;
    }

    public static AllEventDTO fromFreeKickEvent(FreeKickEvent f) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("FreeKick");
        dto.setMinute(f.getMinute());
        dto.setPlayer(f.getTaker() != null ? f.getTaker().getName() : "N/A");
        dto.setTakerName(f.getTaker() != null ? f.getTaker().getName() : "N/A");
        dto.setTeam(f.getTeam() != null ? f.getTeam().getName() : "N/A");
        dto.setTeamName(f.getTeam() != null ? f.getTeam().getName() : "N/A");
        return dto;
    }

    public static AllEventDTO fromOffsideEvent(OffsideEvent o) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("Offside");
        dto.setMinute(o.minute());
        dto.setPlayer(o.playerName() != null ? o.playerName() : "N/A");
        dto.setTeam(o.teamSide() != null ? o.teamSide() : "N/A");
        dto.setTeamName(o.teamSide() != null ? o.teamSide() : "N/A");
        return dto;
    }

    public static AllEventDTO fromCornerEvent(CornerEvent c) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("Corner");
        dto.setMinute(c.getMinute());
        dto.setPlayer(c.getTaker() != null ? c.getTaker().getName() : "N/A");
        dto.setTeam(c.getTeam() != null ? c.getTeam().getName() : "N/A");
        return dto;
    }

    public static AllEventDTO fromSubstitutionEvent(SubstitutionEvent s) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("Substitution");
        dto.setMinute(s.minute());
        dto.setPlayerOutName(s.playerOutName() != null ? s.playerOutName() : "N/A");
        dto.setPlayerInName(s.playerInName() != null ? s.playerInName() : "N/A");
        dto.setTeam(s.teamSide() != null ? s.teamSide() : "N/A");
        dto.setTeamName(s.teamSide() != null ? s.teamSide() : "N/A");
        return dto;
    }

    public static AllEventDTO fromVARReviewEvent(VarReviewEvent v) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("VARReview");
        dto.setMinute(v.minute());
        dto.setDecision(v.decision());
        return dto;
    }

    public static AllEventDTO fromShotOnTargetEvent(ShotOnTargetEvent s) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("ShotOnTarget");
        dto.setMinute(s.getMinute());
        dto.setPlayer(s.getShooter() != null ? s.getShooter().getName() : "N/A");
        dto.setShooterName(s.getShooter() != null ? s.getShooter().getName() : "N/A");
        dto.setTeam(s.getTeam() != null ? s.getTeam().getName() : "N/A");
        dto.setTeamName(s.getTeam() != null ? s.getTeam().getName() : "N/A");
        return dto;
    }

    public static AllEventDTO fromShotOffTargetEvent(ShotOffTargetEvent s) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("ShotOffTarget");
        dto.setMinute(s.getMinute());
        dto.setPlayer(s.getShooter() != null ? s.getShooter().getName() : "N/A");
        dto.setShooterName(s.getShooter() != null ? s.getShooter().getName() : "N/A");
        dto.setTeam(s.getTeam() != null ? s.getTeam().getName() : "N/A");
        dto.setTeamName(s.getTeam() != null ? s.getTeam().getName() : "N/A");
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
        dto.setMinute(i.minute());
        dto.setPlayer(i.playerName() != null ? i.playerName() : "N/A");
        dto.setTeam(i.teamSide() != null ? i.teamSide() : "N/A");
        return dto;
    }
}
