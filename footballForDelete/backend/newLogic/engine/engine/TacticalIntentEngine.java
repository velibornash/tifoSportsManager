package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.MatchState;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;
import org.example.footballmanager.newLogic.model.TacticRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Tactical Intent Engine - Implements the threat override system from PlayerTrackingDemo
 * 
 * Pipeline:
 * 1. Update tactical positions from TacticRules (based on ball zone)
 * 2. Detect threats (ball in adjacent zone)
 * 3. Select closest player to press (only ONE player per team)
 * 4. Override desiredPosition for pressing player
 * 5. Set desiredPosition = tacticalPosition for others
 */
public class TacticalIntentEngine {
    
    private static final Logger log = LoggerFactory.getLogger(TacticalIntentEngine.class);
    
    private final ZonePositionCalculator zoneCalculator;
    
    public TacticalIntentEngine(ZonePositionCalculator zoneCalculator) {
        this.zoneCalculator = zoneCalculator;
    }
    
    /**
     * Update all player intents and desired positions based on tactical rules and threat detection
     */
    public void updateIntents(MatchState state, TacticRules homeTactics, TacticRules awayTactics) {
        // Step 1: Update zone calculator with current ball position
        zoneCalculator.updateTargets(state, homeTactics, awayTactics);
        
        // Step 2: Set tactical positions for all players
        updateTacticalPositions(state);
        
        // Step 3: Process threat detection for each team separately
        processTeamThreats(state, "HOME");
        processTeamThreats(state, "AWAY");
    }
    
    /**
     * Set tacticalPosition for all players from ZonePositionCalculator
     */
    private void updateTacticalPositions(MatchState state) {
        for (PlayerSnapshot snap : state.playerSnapshots) {
            String slotKey = state.playerSlotKeys.get(snap.playerId());
            
            double[] tacticalTarget;
            if (slotKey != null) {
                tacticalTarget = zoneCalculator.getTarget(snap.playerId(), slotKey);
            } else {
                // Fallback for players without slot assignment - use a default position based on their actual position
                tacticalTarget = fallbackTacticalTarget(snap);
            }
            
            snap.setTacticalPosition(tacticalTarget[0], tacticalTarget[1]);
            snap.setDesiredPosition(tacticalTarget[0], tacticalTarget[1]);
            snap.setIntent(PlayerSnapshot.Intent.RETURN_TO_SHAPE);
            snap.setReason(slotKey != null ? "Tactical Editor" : "Fallback position");
        }
    }
    
    private double[] fallbackTacticalTarget(PlayerSnapshot snap) {
        // Default positions based on actual position and team side
        boolean home = snap.teamSide().equals("HOME");
        double baseX = home ? 50.0 : 50.0;
        double baseY = 50.0;
        
        switch (snap.position()) {
            case GK:
                baseX = home ? 5.0 : 95.0;
                baseY = 50.0;
                break;
            case DEF:
                baseX = home ? 20.0 : 80.0;
                baseY = snap.y(); // Keep current Y
                break;
            case MID:
                baseX = home ? 50.0 : 50.0;
                baseY = snap.y();
                break;
            case WNG:
                baseX = home ? 60.0 : 40.0;
                baseY = snap.y() < 50.0 ? 15.0 : 85.0;
                break;
            case ATT:
                baseX = home ? 75.0 : 25.0;
                baseY = snap.y();
                break;
        }
        
        return new double[]{baseX, baseY};
    }
    
    /**
     * Process threat detection for a single team
     * Selects closest player to press, others return to shape
     */
    private void processTeamThreats(MatchState state, String teamSide) {
        // Find all players in this team who have ball in adjacent zone
        List<PlayerSnapshot> playersInRange = new ArrayList<>();
        
        for (PlayerSnapshot snap : state.playerSnapshots) {
            if (!snap.teamSide().equals(teamSide)) continue;
            
            if (isAdjacentToBall(snap, state)) {
                playersInRange.add(snap);
            }
        }
        
        // If multiple players in range, select only the closest one to press
        if (!playersInRange.isEmpty()) {
            PlayerSnapshot closestPlayer = selectClosestPlayer(playersInRange, state);
            
            // Update all players in this team
            for (PlayerSnapshot snap : state.playerSnapshots) {
                if (!snap.teamSide().equals(teamSide)) continue;
                
                if (snap == closestPlayer) {
                    // This player presses the ball
                    snap.setIntent(PlayerSnapshot.Intent.PRESS);
                    snap.setDesiredPosition(state.ball.x(), state.ball.y());
                    snap.setReason("Ball in adjacent zone (closest)");
                } else {
                    // Other players return to tactical position
                    snap.setIntent(PlayerSnapshot.Intent.RETURN_TO_SHAPE);
                    snap.setDesiredPosition(snap.tacticalPosition()[0], snap.tacticalPosition()[1]);
                    snap.setReason("Tactical Editor (teammate pressing)");
                }
            }
        }
    }
    
    /**
     * Check if player is in adjacent zone to the ball
     * Adjacent means within ~15 units (one grid cell)
     */
    private boolean isAdjacentToBall(PlayerSnapshot snap, MatchState state) {
        double distToBall = snap.distanceToPoint(state.ball.x(), state.ball.y());
        return distToBall <= 15.0;
    }
    
    /**
     * Select the closest player to the ball from the list
     */
    private PlayerSnapshot selectClosestPlayer(List<PlayerSnapshot> players, MatchState state) {
        PlayerSnapshot closest = null;
        double minDistance = Double.MAX_VALUE;
        
        for (PlayerSnapshot p : players) {
            double dist = p.distanceToPoint(state.ball.x(), state.ball.y());
            if (dist < minDistance) {
                minDistance = dist;
                closest = p;
            }
        }
        
        return closest;
    }
}
