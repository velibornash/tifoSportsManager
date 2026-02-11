package org.example.footballmanager.model.event;

import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import jakarta.persistence.Entity;
@Entity
@Getter
@Setter

public class YellowCardEvent extends MatchEvent {
    @ManyToOne
    private Team team;
    @ManyToOne
    private Player player;

    @Override
    public void apply() {
        match.getAllMatchEvents().add(this);
        match.getYellowCards().add(this);
    }

    @Override
    public String getDescription() {
        return String.format("%d' \uD83D\uDFE8 %s", getMinute(), player.getName());
    }
}
