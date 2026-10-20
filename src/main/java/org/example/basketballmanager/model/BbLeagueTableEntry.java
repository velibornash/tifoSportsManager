package org.example.basketballmanager.model;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BbLeagueTableEntry {

    private Long teamId;
    private String teamName;
    private String teamShortName;
    private String teamColor;
    private String hallName;
    private Integer position;
    private Integer played;
    private Integer wins;
    private Integer losses;
    private Integer points;
    private Integer pointsFor;
    private Integer pointsAgainst;
    private Integer pointDiff;
    private java.util.List<String> form;

    public int compareTo(BbLeagueTableEntry other) {
        if (!this.points.equals(other.points)) return other.points - this.points;
        return other.pointDiff - this.pointDiff;
    }
}