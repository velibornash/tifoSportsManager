package org.example.footballmanager.model.event;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Player;

@Getter
@Setter
@Entity
public class SubstitutionEvent extends MatchEvent {
    @ManyToOne
    private Player playerIn;
    @ManyToOne
    private Player playerOut;

    @Override
    public void apply() {
        match.getSubstitutions().add(this);
    }
    @Override
    public String getDescription() {
        return minute + "' IZLAZI " + playerOut.getName() + ", ULAZI " + playerIn.getName();
    }
}