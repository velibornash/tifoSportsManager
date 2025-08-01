package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.simulator.MatchContext;

import java.util.List;

@Getter
@Setter
@MappedSuperclass
public abstract class MatchEvent {

    @Column(name = "goal_minute", nullable = false)
    protected int minute;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(cascade = CascadeType.PERSIST)
    @JsonBackReference
    protected Match match;

    private String playerPosition;

    private String type;

    @ManyToOne(cascade = CascadeType.PERSIST)
    protected Team team;

    private String ballPosition;

    public String getDescription() {
        return type + " at minute " + minute;
    }

    @Column(name = "key_event")
    protected boolean keyEvent;

    @Column(name = "visualize")
    protected boolean visualize;

    @Column(name = "impact")
    protected String impact; // HIGH, MEDIUM, LOW

    public abstract void apply(MatchContext context);
}