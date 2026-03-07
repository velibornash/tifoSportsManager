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
public class YellowCardEvent extends MatchEvent {
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Player player;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Team team;

    @Override
    public void apply() {
    }

    @Override
    public String getDescription() {
        return String.format("%d' 🟨 %s", getMinute(), player.getName());
    }
}
