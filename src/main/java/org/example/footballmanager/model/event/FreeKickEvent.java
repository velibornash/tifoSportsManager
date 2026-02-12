package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import jakarta.persistence.Entity;
@Entity
@Getter
@Setter
public class FreeKickEvent extends MatchEvent {
    @ManyToOne
    @JsonIgnore
    private Team team;
    private boolean direct; // true = šut direktno, false = centaršut
    private boolean dangerous;

    @ManyToOne(cascade = CascadeType.ALL)
    @JsonIgnore
    private Player taker;

    @ManyToOne(cascade = CascadeType.ALL)
    @JsonIgnore
    private Player player;
    @Override
    public void apply() {
        match.getAllMatchEvents().add(this);
        match.getFreeKicks().add(this);
    }

    @Override
    public String getDescription() {
        return minute + "' Slobodan udarac - " + taker.getName() + (direct ? " (direktan)" : " (indirektan)");
    }
}
