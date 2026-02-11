package org.example.footballmanager.model.event;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class MatchStartEvent extends MatchEvent{

    @Override
    public void apply() {
        match.setStarted(true);
        match.getAllMatchEvents().add(this);
    }

    @Override
    public String getDescription() {

        return String.format("\uD83C\uDFC1 %d' Match Started: %s %d - %d %s",
                minute,
                match.getHomeTeam().getName(),
                match.getHomeGoals(),
                match.getAwayGoals(),
                match.getAwayTeam().getName());
    }
}
