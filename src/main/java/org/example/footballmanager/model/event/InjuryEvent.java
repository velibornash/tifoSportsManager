package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Player;

@Entity
@Getter
@Setter
public class InjuryEvent extends MatchEvent {
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Player player;

    @Override
    public void apply() {
    }

    @Override
    public String getDescription() {
        return minute + "' ❌ Injury - " + player.getName();
    }
}
