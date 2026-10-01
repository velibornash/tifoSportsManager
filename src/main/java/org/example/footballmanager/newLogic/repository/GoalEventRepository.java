package org.example.footballmanager.newLogic.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.event.GoalEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Goals, read from the match's own event log.
 *
 * <p><b>This was a stub that returned an empty list from every method</b>, and two screens depended on
 * it: the League tab's top scorers and top assists, and the Club milestones' top scorer and top assist.
 * All four read "—" over a season of football that had plainly been played — while the match view, reading
 * the same match a different way, listed every goal and assist correctly. The data was never missing. It
 * was being asked for from a repository that answered "nothing" to every question.
 *
 * <h2>Why the event log and not a table</h2>
 *
 * <p>Because the event log is the record. The engine writes every tick's events into
 * {@code match.event_json}, and the table this class used to imply has no writer anywhere — the audit's
 * "52 event classes with no table" is literally this one. Writing goals into a second table would create
 * two records of one fact and something else to keep in step, which is how a page ends up showing a goal
 * the match view says was never scored.
 *
 * <p>So goals are read where they are written, and there is nothing to keep in step.
 */
@Component
public class GoalEventRepository {

    private final MatchRepository matchRepository;
    private final ObjectMapper objectMapper;

    public GoalEventRepository(MatchRepository matchRepository, ObjectMapper objectMapper) {
        this.matchRepository = matchRepository;
        this.objectMapper = objectMapper;
    }

    /** Every goal in one match, in the order it was scored. */
    public List<GoalEvent> findByMatchId(Long matchId) {
        if (matchId == null) {
            return List.of();
        }
        return matchRepository.findById(matchId)
                .map(this::goalsOf)
                .orElseGet(List::of);
    }

    /**
     * Every goal in one competition's matches across a season.
     *
     * <p>The {@code ScoredTrue} suffix survives from the stub and every goal in the log is a scored goal —
     * an own goal is recorded as a goal against the conceding side, not as an absence of one. Kept so the
     * two callers did not both need changing.
     */
    public List<GoalEvent> findByMatchCompetitionIdAndMatchSeasonYearAndScoredTrue(Long competitionId,
                                                                                  Integer seasonYear) {
        if (competitionId == null || seasonYear == null) {
            return List.of();
        }
        List<GoalEvent> goals = new ArrayList<>();
        for (Match match : matchRepository.findByCompetitionIdAndSeasonYear(competitionId, seasonYear)) {
            if (match.isPlayed()) {
                goals.addAll(goalsOf(match));
            }
        }
        return goals;
    }

    /** Every goal in the season, across every competition. */
    public List<GoalEvent> findByMatchSeasonYearAndScoredTrue(Integer seasonYear) {
        if (seasonYear == null) {
            return List.of();
        }
        List<GoalEvent> goals = new ArrayList<>();
        for (Match match : seasonRepositoryMatches(seasonYear)) {
            if (match.isPlayed()) {
                goals.addAll(goalsOf(match));
            }
        }
        return goals;
    }

    /**
     * Every played match of a season.
     *
     * <p>Walks week by week through the one season query the repository already has. Written out rather
     * than assumed into a derived query, because a wrong derivation here would be a silent empty list -
     * which is the failure this class used to cause on purpose.
     */
    private List<Match> seasonRepositoryMatches(int seasonYear) {
        List<Match> all = new ArrayList<>();
        for (int week = 1; week <= 12; week++) {
            all.addAll(matchRepository.findBySeasonYearAndWeekNumber(seasonYear, week));
        }
        return all;
    }

    /**
     * The GOAL entries of one match's event log.
     *
     * <p>Absent or malformed JSON yields no goals rather than an exception. A page that lists scorers must
     * not be the thing that makes a match unviewable, and a match with no event log simply has not been
     * played.
     */
    private List<GoalEvent> goalsOf(Match match) {
        String json = match == null ? null : match.getEventJson();
        if (json == null || json.isBlank() || json.trim().startsWith("[")) {
            if (json == null || json.isBlank()) {
                return List.of();
            }
        }
        JsonNode events;
        try {
            events = objectMapper.readTree(json);
        } catch (Exception unparseable) {
            return List.of();
        }
        if (json.trim().equals("[]") || !events.isArray()) {
            return List.of();
        }

        List<GoalEvent> goals = new ArrayList<>();
        for (JsonNode event : events) {
            if (!isGoal(event)) {
                continue;
            }
            Long scorerId = asPlayerId(event, "playerId");
            if (scorerId == null) {
                // A goal nobody is attached to cannot be credited to anybody, and crediting it by name
                // would put a row in the top-scorer table that no player can be found from.
                continue;
            }
            goals.add(new GoalEvent(
                    event.path("minute").asInt(0),
                    event.path("tick").asInt(0),
                    scorerId,
                    firstNonBlank(event, "scorerName", "playerName"),
                    asPlayerId(event, "assistantId"),
                    firstNonBlank(event, "assistantName"),
                    text(event, "teamSide"),
                    event.path("xG").asDouble(0.0),
                    event.path("homeScoreAfter").asInt(0),
                    event.path("awayScoreAfter").asInt(0)));
        }
        return goals;
    }

    private boolean isGoal(JsonNode event) {
        String type = text(event, "type");
        // MatchEventType.GOAL, matched by name: the enum is declared inside MatchEvent.java rather than
        // its own file, so importing it would not resolve.
        return type != null && type.toUpperCase().contains("GOAL");
    }

    private String firstNonBlank(JsonNode event, String... fields) {
        for (String field : fields) {
            String value = text(event, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode event, String field) {
        JsonNode value = event.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    /**
     * A real player id, or null.
     *
     * <p>Ids are written into the event log as text and may be the engine's synthetic placeholders
     * ("HOME-1") for a player with no database row. Those are not people, and letting one through would
     * put a scorer in a table who cannot be clicked through to.
     */
    private Long asPlayerId(JsonNode event, String field) {
        String value = text(event, field);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException notAPlayer) {
            return null;
        }
    }
}