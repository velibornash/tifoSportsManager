package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;

@Entity
@Getter
@Setter
public class FreeKickEvent extends MatchEvent {
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Player taker;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Player player;

    private boolean direct;

    private boolean dangerous;

    @Override
    public void apply() {
    }

    @Override
    public String getDescription() {
        String takerName = taker != null ? taker.getName() : "Unknown";
        return minute + "' 🎯 Free kick - " + takerName + (direct ? " (direct)" : " (indirect)");
    }
}
