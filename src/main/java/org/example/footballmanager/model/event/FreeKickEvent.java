package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
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
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Team team;
    private boolean direct; // true = šut direktno, false = centaršut
    private boolean dangerous;

    @ManyToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore
    private Player taker;

    @ManyToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore
    private Player player;
    @Override
    public void apply() {

    }

    @Override
    public String getDescription() {
        return minute + "' Slobodan udarac - " + taker.getName() + (direct ? " (direktan)" : " (indirektan)");
    }
}
