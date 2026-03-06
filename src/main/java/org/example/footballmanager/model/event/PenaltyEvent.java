package org.example.footballmanager.model.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.springframework.stereotype.Component;

@Entity
@Getter
@Setter
@Slf4j
@Component
public class PenaltyEvent extends MatchEvent {
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Player taker;

    private boolean scored;

    @SneakyThrows
    @Override
    public void apply() {

    }

    @Override
    public String getDescription() {
        String takerName = taker != null ? taker.getName() : "Unknown";
        return minute + "' Penalty - " + takerName + (scored ? " scored" : " missed");
    }
}
