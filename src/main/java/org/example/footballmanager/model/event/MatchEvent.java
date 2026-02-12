package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Match;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

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
        @JsonSubTypes.Type(value = YellowCardEvent.class, name = "yellowCard"),
        @JsonSubTypes.Type(value = RedCardEvent.class, name = "redCard"),
        @JsonSubTypes.Type(value = PenaltyEvent.class, name = "penalty"),
        @JsonSubTypes.Type(value = InjuryEvent.class, name = "injury"),
        @JsonSubTypes.Type(value = OffsideEvent.class, name = "offside"),
        @JsonSubTypes.Type(value = CornerEvent.class, name = "corner"),
        @JsonSubTypes.Type(value = VARReviewEvent.class, name = "varReview"),
        @JsonSubTypes.Type(value = ShotOnTargetEvent.class, name = "shotOnTarget"),
        @JsonSubTypes.Type(value = ShotOffTargetEvent.class, name = "shotOffTarget"),
        @JsonSubTypes.Type(value = FreeKickEvent.class, name = "freeKick"),
        @JsonSubTypes.Type(value = SubstitutionEvent.class, name = "substitution"),
        @JsonSubTypes.Type(value = MatchEndedEvent.class, name = "matchEnded"),
        @JsonSubTypes.Type(value = MatchStartEvent.class, name = "matchStarted")

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

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;      // kada je prvi put upisan

    @UpdateTimestamp
    private LocalDateTime updatedAt;      // kada je poslednji put menjan
    public abstract void apply();

    public String getDescription() {
        return "Event at " + minute + "'";
    }
}