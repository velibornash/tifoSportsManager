package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Match;

@Getter
@Setter
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = GoalEvent.class, name = "goal"),
        @JsonSubTypes.Type(value = YellowCardEvent.class, name = "yellow card"),
        @JsonSubTypes.Type(value = RedCardEvent.class, name = "red card"),
        @JsonSubTypes.Type(value = PenaltyEvent.class, name = "penalty"),
        @JsonSubTypes.Type(value = InjuryEvent.class, name = "injury"),
        @JsonSubTypes.Type(value = OffsideEvent.class, name = "offside"),
        @JsonSubTypes.Type(value = CornerEvent.class, name = "corner"),
        @JsonSubTypes.Type(value = VARReviewEvent.class, name = "var review"),
        @JsonSubTypes.Type(value = ShotOnTargetEvent.class, name = "shot on target"),
        @JsonSubTypes.Type(value = ShotOffTargetEvent.class, name = "shot off target"),
        @JsonSubTypes.Type(value = FreeKickEvent.class, name = "free kick"),
        @JsonSubTypes.Type(value = SubstitutionEvent.class, name = "substitution"),
        @JsonSubTypes.Type(value = MatchEndedEvent.class, name = "match ended"),
        @JsonSubTypes.Type(value = MatchStartEvent.class, name = "match started")

})
public abstract class MatchEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JsonIgnore   // ← Sprečava ciklus Match → Events → Match
    protected Match match;

    @Column(name = "event_minute")
    protected int minute;

    public abstract void apply();

    public String getDescription() {
        return "Event at " + minute + "'";
    }
}