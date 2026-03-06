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
public class InterceptionEvent extends MatchEvent {
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Team team;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Player interceptor;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Player originalPasser;
    
    @Override
    public void apply() {
    }
    
    @Override
    public String getDescription() {
        return String.format("%d' 🛡️ Interception - %s",
                getMinute(),
                interceptor != null ? interceptor.getName() : "?");
    }
}
