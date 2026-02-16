package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Player;

@Getter
@Setter
@Entity
public class OffsideEvent extends MatchEvent {
    @ManyToOne
    @JsonIgnore
    private Player player;

    @Override
    public void apply() {
        match.getOffsides().add(this);
        match.getAllMatchEvents().add(this);
    }
    @Override
    public String getDescription() {
        return String.format("\uD83D\uDEA9 %d' Offside: %s", minute, player.getName());
    }
}
