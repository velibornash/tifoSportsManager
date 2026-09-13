package org.example.footballmanager.demo.service.proposal.recording;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Complete match recording for JSON export / viewer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MatchRecording {
    private final String matchId;
    private final List<MatchEvent> events;
    private final List<MatchSnapshot> snapshots;
    private final int homeGoals;
    private final int awayGoals;

    public MatchRecording(String matchId, List<MatchEvent> events, List<MatchSnapshot> snapshots) {
        this.matchId = matchId;
        this.events = events;
        this.snapshots = snapshots;
        this.homeGoals = 0;
        this.awayGoals = 0;
    }

    public String getMatchId() { return matchId; }
    public List<MatchEvent> getEvents() { return events; }
    public List<MatchSnapshot> getSnapshots() { return snapshots; }
    public int getHomeGoals() { return homeGoals; }
    public int getAwayGoals() { return awayGoals; }
}