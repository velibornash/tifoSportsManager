package org.example.footballmanager.model.event;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class MatchEndedEvent extends MatchEvent {
    @Override
    public void apply() {

    }
    @Override
    public String getDescription() {

        return String.format("\uD83C\uDFC1 %d' Match Ended: %s %d - %d %s",
                minute,
                match.getHomeTeam().getName(),
                match.getHomeGoals(),
                match.getAwayGoals(),
                match.getAwayTeam().getName());
    }
}