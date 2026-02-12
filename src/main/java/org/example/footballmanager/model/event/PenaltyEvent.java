package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import jakarta.persistence.Entity;
import org.springframework.stereotype.Component;

@Entity
@Getter
@Setter
@Slf4j
@Component
public class PenaltyEvent extends MatchEvent {
    @ManyToOne
    @JsonIgnore
    private Team team;
    @ManyToOne
    @JsonIgnore
    private Player taker;
    private boolean scored;
    @SneakyThrows
    @Override
    public void apply() {
        match.getAllMatchEvents().add(this);
        match.getPenalties().add(this);
    }

    @Override
    public String getDescription() {
        return minute + "' Penal - " + taker.getName() + (scored ? " ✅" : " ❌");
    }
}
