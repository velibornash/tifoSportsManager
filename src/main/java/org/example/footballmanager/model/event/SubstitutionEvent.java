package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Player;

@Getter
@Setter
@Entity
public class SubstitutionEvent extends MatchEvent {
    @ManyToOne
    @JsonIgnore
    private Player playerIn;
    @ManyToOne
    @JsonIgnore
    private Player playerOut;

    @Override
    public void apply() {

    }
    @Override
    public String getDescription() {
        return minute + "' IZLAZI " + playerOut.getName() + ", ULAZI " + playerIn.getName();
    }
}