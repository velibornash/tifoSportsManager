package org.example.footballmanager.model.event;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Player;

@Getter
@Setter
@Entity
public class InjuryEvent extends MatchEvent {
    @ManyToOne
    private Player player;

    @Override
    public void apply() {
        match.getInjuries().add(this);
    }
    @Override
    public String getDescription() {
        return minute + "' ❌ Povreda: " + player.getName();
    }
}