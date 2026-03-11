package org.example.footballmanager.dto;

import lombok.Data;

@Data
public class MatchEventFlatDTO {
    private Long matchId;
    private String matchDate;
    private String homeTeam;
    private Integer homeGoals;
    private String awayTeam;
    private Integer awayGoals;

    private Integer matchMinute;
    private String eventType;

    private String scorer;
    private String assistant;
    private String scoreAfterGoal;
    private Boolean goalScored;

    private String scoreTeam;

    private String possessionTeam;
    private String yellowCardTeam;
    private String redCardTeam;
    private String penaltyTeam;
    private String cornerTeam;
    private String freeKickTeam;
    private String eventTeam;

    private String cornerTaker;
    private String freeKickTaker;
    private String penaltyTaker;
    private Boolean penaltyScored;

    private String redCardPlayer;
    private String yellowCardPlayer;

    private String shotOnTargetPlayer;
    private String shotOffTargetPlayer;
    private String shotOnTargetTeam;
    private String shotOffTargetTeam;
    private Double xG;
    private String substitutionTeam;
    private String playerOutName;
    private String playerInName;
    private String injuryTeam;
    private String injuryPlayer;

    private String homeFormation;
    private String awayFormation;
}
