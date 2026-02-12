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

    // Zajednička polja za igrača (popunjavaju se kada postoji relevantan igrač)
    private String playerName;
    private Integer playerAge;
    private Double playerHeight;      // u metrima (npr. 1.85)
    private Double playerWeight;      // u kg (npr. 78.5)
    private Integer playerTotalGoals;
    private Integer playerTotalAssists;
    private String playerPosition;    // "STRIKER", "MIDFIELDER", itd.
    private Integer playerRating;

    // Dodatna polja koja nisu vezana za igrača, ali mogu biti korisna
    private String teamName;
}