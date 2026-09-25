package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.engine.MatchSimulator;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchResult;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;
import org.example.footballmanager.newLogic.model.event.MatchEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MatchLiveSession {

    public final long matchId;
    public final Match match;
    public final MatchSimulator simulator;
    public final AtomicBoolean broadcasting = new AtomicBoolean(true);
    public final AtomicInteger completedMinutes = new AtomicInteger(0);

    private MatchResult result;

    public MatchLiveSession(long matchId, Match match, MatchSimulator simulator) {
        this.matchId = matchId;
        this.match = match;
        this.simulator = simulator;
    }

    public void setResult(MatchResult result) {
        this.result = result;
        this.broadcasting.set(false);
    }

    public MatchResult getResult() {
        return result;
    }

    public boolean isFinished() {
        return !broadcasting.get();
    }

    public List<PlayerSnapshot> getPlayerSnapshots() {
        return simulator.getState().playerSnapshots;
    }

    public record LivePlayer(
        long id,
        String name,
        String teamSide,
        String position,
        double x,
        double y,
        double desiredX,
        double desiredY,
        String intent,
        boolean isCarrier
    ) {}

    public record LiveBall(
        double x,
        double y,
        double z,
        boolean inTransit,
        Long carrierId
    ) {}
}