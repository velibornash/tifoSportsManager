package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
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
        return minute + "' IZLAZI " + playerOut.getName() + ", ULAZI " + playerIn.getName();
    }
}