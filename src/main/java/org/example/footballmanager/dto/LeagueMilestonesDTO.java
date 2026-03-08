package org.example.footballmanager.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeagueMilestonesDTO {
    private Integer seasonYear;
    private MilestoneLeaderDTO topScorer;
    private MilestoneLeaderDTO topAssist;
    private MatchMilestoneDTO biggestWin;
    private MatchMilestoneDTO biggestLoss;
    private AttendanceMilestoneDTO attendance;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MilestoneLeaderDTO {
        private String playerName;
        private String teamName;
        private Integer value;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MatchMilestoneDTO {
        private Long matchId;
        private String teamName;
        private String opponentName;
        private Integer teamGoals;
        private Integer opponentGoals;
        private Integer goalMargin;
        private String summary;
        private String context;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttendanceMilestoneDTO {
        private Integer averageAttendance;
        private Integer highestAttendance;
        private String highestMatchLabel;
        private Integer lowestAttendance;
        private String lowestMatchLabel;
        private String insight;
    }
}