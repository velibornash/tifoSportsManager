package org.example.footballmanager.dto;

import lombok.Data;
import org.example.footballmanager.model.event.*;

@Data
public class AllEventDTO {

    private String type;
    private int minute;
    private String player;
    private String team;

    public static AllEventDTO fromGoalEvent(GoalEvent g) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("Goal");
        dto.setMinute(g.getMinute());
        dto.setPlayer(g.getScorer() != null ? g.getScorer().getName() : "N/A");
        dto.setTeam(g.getTeam().getName());
        return dto;
    }

    public static AllEventDTO fromChanceEvent(ChanceEvent c) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("Chance");
        dto.setMinute(c.getMinute());
        dto.setPlayer(c.getPlayer() != null ? c.getPlayer().getName() : "N/A");
        dto.setTeam(c.getTeam().getName());
        return dto;
    }

    public static AllEventDTO fromYellowCardEvent(YellowCardEvent y) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("YellowCard");
        dto.setMinute(y.getMinute());
        dto.setPlayer(y.getPlayer() != null ? y.getPlayer().getName() : "N/A");
        dto.setTeam(y.getPlayer() != null ? y.getPlayer().getTeam().getName() : "N/A");
        return dto;
    }

    public static AllEventDTO fromRedCardEvent(RedCardEvent r) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("RedCard");
        dto.setMinute(r.getMinute());
        dto.setPlayer(r.getPlayer() != null ? r.getPlayer().getName() : "N/A");
        dto.setTeam(r.getPlayer() != null ? r.getPlayer().getTeam().getName() : "N/A");
        return dto;
    }

    public static AllEventDTO fromPenaltyEvent(PenaltyEvent p) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("Penalty");
        dto.setMinute(p.getMinute());
        dto.setPlayer(p.getTaker() != null ? p.getTaker().getName() : "N/A");
        dto.setTeam(p.getTeam() != null ? p.getTeam().getName() : "N/A");
        return dto;
    }

    public static AllEventDTO fromFreeKickEvent(FreeKickEvent f) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("FreeKick");
        dto.setMinute(f.getMinute());
        dto.setPlayer(f.getPlayer() != null ? f.getPlayer().getName() : "N/A");
        dto.setTeam(f.getTeam() != null ? f.getTeam().getName() : "N/A");
        return dto;
    }

    public static AllEventDTO fromOffsideEvent(OffsideEvent o) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("Offside");
        dto.setMinute(o.getMinute());
        dto.setPlayer(o.getPlayer() != null ? o.getPlayer().getName() : "N/A");
        dto.setTeam(o.getPlayer().getTeam() != null ? o.getPlayer().getTeam().getName() : "N/A");
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
        dto.setPlayer(s.getPlayerOut() != null ? s.getPlayerOut().getName() : "N/A");
        dto.setTeam(s.getPlayerIn().getTeam().getName() != null ? s.getPlayerIn().getTeam().getName() : "N/A");
        return dto;
    }

    public static AllEventDTO fromVARReviewEvent(VARReviewEvent v) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("VARReview");
        dto.setMinute(v.getMinute());
        return dto;
    }

    public static AllEventDTO fromShotOnTargetEvent(ShotOnTargetEvent s) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("ShotOnTarget");
        dto.setMinute(s.getMinute());
        dto.setPlayer(s.getShooter() != null ? s.getShooter().getName() : "N/A");
        dto.setTeam(s.getTeam() != null ? s.getTeam().getName() : "N/A");
        return dto;
    }

    public static AllEventDTO fromShotOffTargetEvent(ShotOffTargetEvent s) {
        AllEventDTO dto = new AllEventDTO();
        dto.setType("ShotOffTarget");
        dto.setMinute(s.getMinute());
        dto.setPlayer(s.getShooter() != null ? s.getShooter().getName() : "N/A");
        dto.setTeam(s.getTeam() != null ? s.getTeam().getName() : "N/A");
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