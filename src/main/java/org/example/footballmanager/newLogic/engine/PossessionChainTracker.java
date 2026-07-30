package org.example.footballmanager.newLogic.engine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import org.example.footballmanager.newLogic.model.event.PossessionEndEvent;
import org.example.footballmanager.newLogic.model.event.PossessionStartEvent;
import org.example.footballmanager.newLogic.model.event.MatchEvent;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class PossessionChainTracker {
    private long currentChainId = 0;
    private int chainStartTick = 0;
    private int chainStartMinute = 0;
    private int passCount = 0;
    private String possessingTeamSide = null;
    private boolean active = false;
    private double chainStartX = 0;
    private double chainStartY = 0;
    
    private final List<PossessionStats> possessionHistory = new ArrayList<>();
    
    public void startChain(String teamSide, int minute, int tick, double x, double y, List<MatchEvent> events) {
        if (active) {
            endChain("new_possession", minute, tick, x, y, events);
        }
        
        currentChainId++;
        chainStartTick = tick;
        chainStartMinute = minute;
        chainStartX = x;
        chainStartY = y;
        passCount = 0;
        possessingTeamSide = teamSide;
        active = true;
        
        String description = String.format("%s gains possession", teamSide.equals("HOME") ? "Home" : "Away");
        events.add(new PossessionStartEvent(minute, tick, currentChainId, teamSide, description, x, y));
    }
    
    public void endChain(String reason, int minute, int tick, double x, double y, List<MatchEvent> events) {
        if (!active) return;
        
        String description = String.format("%s possession ended after %d passes (%s)", 
            possessingTeamSide.equals("HOME") ? "Home" : "Away", passCount, reason);
        
        events.add(new PossessionEndEvent(minute, tick, currentChainId, possessingTeamSide, 
            passCount, reason, description, x, y));
        
        possessionHistory.add(new PossessionStats(currentChainId, possessingTeamSide, passCount, 
            chainStartMinute, chainStartTick, minute, tick));
        
        active = false;
        possessingTeamSide = null;
    }
    
    public void incrementPass() {
        if (active) {
            passCount++;
        }
    }
    
    public long getCurrentChainId() {
        return currentChainId;
    }
    
    public boolean isActive() {
        return active;
    }
    
    public String getPossessingTeamSide() {
        return possessingTeamSide;
    }
    
    public int getPassCount() {
        return passCount;
    }
    
    public List<PossessionStats> getPossessionHistory() {
        return new ArrayList<>(possessionHistory);
    }
    
    public record PossessionStats(
        long chainId,
        String teamSide,
        int passCount,
        int startMinute,
        int startTick,
        int endMinute,
        int endTick
    ) {}
}
