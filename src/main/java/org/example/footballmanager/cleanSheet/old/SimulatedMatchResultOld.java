package org.example.footballmanager.cleanSheet.old;

import lombok.Builder;
import lombok.Getter;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.event.MatchEvent;
import org.example.footballmanager.model.CompetitionEntry;

import java.util.List;

@Getter
@Builder
public class SimulatedMatchResultOld {
    private final Match match;
    private final List<MatchEvent> events;
    private final CompetitionEntry homeEntryUpdate;
    private final CompetitionEntry awayEntryUpdate;
    private final String summary;
    private final Integer homeGoals;
    private final Integer awayGoals;
}