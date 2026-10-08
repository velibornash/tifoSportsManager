package org.example.footballmanager.newLogic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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

    /**
     * The medals this club has won, newest season first (P2-TROPHY-1).
     *
     * <p>Empty for a league-wide milestone read, which is about a competition rather than a club, and
     * empty for a club that has won nothing yet — both are honest answers rather than nulls.
     */
    private List<TrophyMilestoneDTO> trophies;

    /** One medal: its colour, the competition it was won in, and the season. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrophyMilestoneDTO {
        private String competitionName;
        private Integer seasonYear;
        private String medal;
    }

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