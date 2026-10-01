package org.example.footballmanager.newLogic.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.Match;
import java.time.LocalDateTime;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class MatchDTO {
    private Long id;
    private String homeTeam;
    private String awayTeam;
    // Boxed, not primitive. A hidden result has no score to report, and "0" is a very convincing lie -
    // a 0-0 that was never played looks exactly like a goalless draw. Null means "not yours yet".
    private Integer homeGoals;
    private Integer awayGoals;
    private String matchDate;
    /** The game's own clock, e.g. "Season 1 · Day 3 · 18:00". Null when the calendar slot is unknown. */
    private String seasonDayLabel;
    private Integer seasonNumber;
    private Integer dayNumber;
    private Integer weekNumber;
    private String competitionName;
    /** League, Cup, Friendly, International - the owner asked for this on the match header. */
    private String competitionType;
    private boolean resultHidden;
    private boolean resultRevealed;
    private Long replayId;

    /**
     * The same DTO for a fixture — a match that has not been played.
     *
     * <p>A {@code Match} row is born when a match is played, so a seeded world holds 2,790 fixtures and
     * 155 matches. Anything that wants to show "the match before kickoff" therefore had nothing to load,
     * and the next match on the dashboard had to go somewhere else entirely.
     *
     * <p><b>The goals are null and stay null.</b> A 0-0 that was never played is indistinguishable from
     * a goelless draw, and the DTO already makes that point about hidden results; the same argument
     * applies harder here, because nothing about a fixture has been decided at all.
     */
    public static MatchDTO unplayed(Long id, org.example.footballmanager.newLogic.model.MatchFixture fixture) {
        MatchDTO dto = new MatchDTO();
        dto.setId(id);
        dto.setHomeTeam(fixture.getHomeTeam() != null ? fixture.getHomeTeam().getName() : null);
        dto.setAwayTeam(fixture.getAwayTeam() != null ? fixture.getAwayTeam().getName() : null);
        dto.setHomeGoals(null);
        dto.setAwayGoals(null);
        dto.setMatchDate(fixture.getMatchDate() != null ? fixture.getMatchDate().toString() : null);
        dto.setSeasonNumber(fixture.getSeasonYear());
        dto.setWeekNumber(fixture.getWeekNumber());
        dto.setDayNumber(fixture.getDayNumber());
        if (fixture.getCompetition() != null) {
            dto.setCompetitionName(fixture.getCompetition().getName());
            dto.setCompetitionType(fixture.getCompetition().getType() == null
                    ? null : fixture.getCompetition().getType().name());
        }
        // Nothing is hidden about a match that has not happened, and nothing has been revealed either.
        dto.setResultHidden(false);
        dto.setResultRevealed(false);
        dto.setReplayId(null);
        return dto;
    }

    public static MatchDTO from(Match match) {
        return from(match, null);
    }

    public static MatchDTO from(Match match, Long viewerTeamId) {
        String formattedDate = match.getMatchDate() != null
                ? match.getMatchDate().toString().substring(0, 16).replace("T", " ")  // npr. "2026-02-20 12:00"
                : "N/A";

        boolean viewerIsHome = viewerTeamId != null
                && match.getHomeTeam() != null
                && viewerTeamId.equals(match.getHomeTeam().getId());
        boolean viewerIsAway = viewerTeamId != null
                && match.getAwayTeam() != null
                && viewerTeamId.equals(match.getAwayTeam().getId());

        boolean resultRevealed = true;
        if (viewerIsHome) {
            resultRevealed = match.isHomeResultRevealed();
        } else if (viewerIsAway) {
            resultRevealed = match.isAwayResultRevealed();
        }

        boolean resultHidden = match.isPlayed() && (viewerIsHome || viewerIsAway) && !resultRevealed;

        // The mask lives here, not in the renderers. Three screens read this DTO and a fourth was
        // written today; masking at each one means the next screen that forgets leaks the score over
        // the wire even if it renders nothing.
        Integer homeGoals = resultHidden ? null : match.getHomeGoals();
        Integer awayGoals = resultHidden ? null : match.getAwayGoals();

        Competition competition = match.getCompetition();

        return new MatchDTO(
                match.getId(),
                match.getHomeTeam() != null ? match.getHomeTeam().getName() : "TBD",
                match.getAwayTeam() != null ? match.getAwayTeam().getName() : "TBD",
                homeGoals,
                awayGoals,
                formattedDate,
                buildSeasonDayLabel(match.getSeasonYear(), match.getDayNumber(), match.getMatchDate()),
                match.getSeasonYear(),
                match.getDayNumber(),
                match.getWeekNumber(),
                competition != null ? competition.getName() : null,
                competition != null && competition.getType() != null ? competition.getType().name() : null,
                resultHidden,
                resultRevealed,
                match.getReplayId()
        );
    }

    /**
     * "Season 1 · Day 3 · 18:00" - the in-game slot, not the wall clock.
     *
     * <p>The owner wants both: the real time the match was played and the season it belongs to. They
     * are different facts, and the season one is the one the game's own calendar is built on.
     *
     * <p>The hour comes from the match date because that is the slot the fixture was placed in; the
     * season and day come from the fixture's own columns. Anything unknown is left out rather than
     * rendered as a zero, so a missing day does not read as "Day 0".
     */
    static String buildSeasonDayLabel(Integer seasonNumber, Integer dayNumber, LocalDateTime matchDate) {
        if (seasonNumber == null) {
            return null;
        }
        StringBuilder label = new StringBuilder("Season ").append(seasonNumber);
        if (dayNumber != null) {
            label.append(" · Day ").append(dayNumber);
        }
        if (matchDate != null) {
            label.append(" · ").append(String.format("%02d:%02d", matchDate.getHour(), matchDate.getMinute()));
        }
        return label.toString();
    }

}
