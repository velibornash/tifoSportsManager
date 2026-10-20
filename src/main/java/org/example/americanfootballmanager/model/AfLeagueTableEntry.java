package org.example.americanfootballmanager.model;

import lombok.*;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AfLeagueTableEntry {

    private Long teamId;
    private String teamName;
    private String teamShortName;
    private String teamColor;
    private String stadiumName;
    private Integer position;
    private Integer played;
    private Integer wins;
    private Integer losses;
    private Integer points;
    private Integer pointsFor;
    private Integer pointsAgainst;
    private Integer pointDiff;
    private List<String> form;

    public int compareTo(AfLeagueTableEntry other) {
        if (!this.points.equals(other.points)) return other.points - this.points;
        return other.pointDiff - this.pointDiff;
    }
}
