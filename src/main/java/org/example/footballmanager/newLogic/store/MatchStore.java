package org.example.footballmanager.newLogic.store;

import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class MatchStore {

    private final AtomicLong matchIdCounter = new AtomicLong(1000);
    private final Map<Long, Match> matches = new ConcurrentHashMap<>();
    private final Map<Long, MatchResult> results = new ConcurrentHashMap<>();

    public long createMatch(Match match) {
        long id = matchIdCounter.getAndIncrement();
        match.setId(id);
        matches.put(id, match);
        return id;
    }

    public Match getMatch(long id) {
        return matches.get(id);
    }

    public void storeResult(long matchId, MatchResult result) {
        results.put(matchId, result);
    }

    public MatchResult getResult(long matchId) {
        return results.get(matchId);
    }

    public boolean hasResult(long matchId) {
        return results.containsKey(matchId);
    }
}
