package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;

@Getter
@Setter
@Entity
public class SubstitutionEvent extends MatchEvent {
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Player playerIn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Player playerOut;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Team team;

    @Override
    public void apply() {
    }

    @Override
    public String getDescription() {
        String outName = playerOut != null ? playerOut.getName() : "Unknown";
        String inName = playerIn != null ? playerIn.getName() : "Unknown";
        return minute + "' Substitution - " + outName + " off, " + inName + " on";
    }
}
