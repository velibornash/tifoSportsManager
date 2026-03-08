package org.example.footballmanager.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;

@Data
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = GoalEventDTO.class, name = "goal"),
        @JsonSubTypes.Type(value = YellowCardEventDTO.class, name = "yellowCard"),
        @JsonSubTypes.Type(value = RedCardEventDTO.class, name = "redCard"),
        @JsonSubTypes.Type(value = PenaltyEventDTO.class, name = "penalty"),
        @JsonSubTypes.Type(value = SubstitutionEventDTO.class, name = "substitution"),
        @JsonSubTypes.Type(value = InjuryEventDTO.class, name = "injury"),
        @JsonSubTypes.Type(value = OffsideEventDTO.class, name = "offside"),
        @JsonSubTypes.Type(value = CornerEventDTO.class, name = "corner"),
        @JsonSubTypes.Type(value = ThrowInEventDTO.class, name = "throwIn"),
        @JsonSubTypes.Type(value = GoalKickEventDTO.class, name = "goalKick"),
        @JsonSubTypes.Type(value = FreeKickEventDTO.class, name = "freeKick"),
        @JsonSubTypes.Type(value = ShotOnTargetEventDTO.class, name = "shotOnTarget"),
        @JsonSubTypes.Type(value = ShotOffTargetEventDTO.class, name = "shotOffTarget"),
        @JsonSubTypes.Type(value = VARReviewEventDTO.class, name = "varReview"),
        @JsonSubTypes.Type(value = ChanceEventDTO.class, name = "chance"),
        @JsonSubTypes.Type(value = MatchStartedDTO.class, name = "matchStarted"),
        @JsonSubTypes.Type(value = MatchEndedDTO.class, name = "matchEnded")
})
public abstract class MatchEventDTO {
    private String type;
    private int minute;
    private String description;
    private String clockLabel;
    private String displayCategory;
    private String importance;
    private boolean keyEvent;
    private Double xG;

    private String playerName;
    private Integer playerAge;
    private Double playerHeight;
    private Double playerWeight;
    private Integer playerTotalGoals;
    private Integer playerTotalAssists;
    private String playerPosition;
    private Integer playerRating;

    private String teamName;
    private String secondaryPlayerName;
    private String targetPlayerName;
    private String outcome;
}
