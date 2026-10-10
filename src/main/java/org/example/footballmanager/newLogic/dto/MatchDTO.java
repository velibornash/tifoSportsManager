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
     * The two club ids, by name of the field.
     *
     * <p>The DTO already carried {@code homeTeam} and {@code awayTeam} as <em>names</em>, so a screen that
     * needed to know whether the manager's own club was playing at home had to compare strings. Name
     * comparison has been burned repeatedly in this codebase — T1-13b added a team id to the qualifying
     * table for exactly this reason — and the match view was living with the consequence: it read
     * {@code homeTeamId} off the lineups payload, which is null until the match is played, so an unplayed
     * fixture reported no home club at all.
     *
     * <p>Both are read straight off the entity, so no call site changes and no lookup is introduced.
     */
    private Long homeTeamId;
    private Long awayTeamId;

    /**
     * The {@code Match} this thing was played into, or null if it has not been played.
     *
     * <p><b>Added because the frontend could not otherwise know.</b> {@code /matches/by-fixture/{id}} sets
     * {@link #id} to the <em>fixture</em> id, and the id spaces overlap: fixture 5 and match 5 are both 5.
     * A caller that passed that id to a match endpoint — {@code /match-stats/lineups/5},
     * {@code /api/zox/match-stats/5} — got <b>a different club's played game</b>. That is not a
     * hypothetical: it is the defect the owner reported, a fixture for OFK Omladinac v SK Teleoptik City
     * rendering the lineups of GFK Bor 1945 v SK Kragujevac.
     *
     * <p>It is a separate field rather than an overload of {@code id} <b>on purpose</b>. Overloading
     * {@code id} would make the one thing that caused this bug invisible: a caller could not tell which
     * space it was holding, and would keep passing it to both kinds of endpoint. The name says which.
     *
     * <p>Backed by {@code MatchFixture.playedMatch}, which is a unique indexed column — one played match
     * belongs to at most one fixture — so this is an exact answer and not a lookup by convention.
     */
    private Long playedMatchId;

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
        dto.setHomeTeamId(fixture.getHomeTeam() != null ? fixture.getHomeTeam().getId() : null);
        dto.setAwayTeamId(fixture.getAwayTeam() != null ? fixture.getAwayTeam().getId() : null);
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
        // The one field that tells a caller whether a match endpoint may be called at all. Null here is
        // the honest answer for a fixture that has not been played, and it is the answer the frontend
        // needs in order to stop asking.
        dto.setPlayedMatchId(fixture.getPlayedMatch() == null ? null : fixture.getPlayedMatch().getId());
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

        // Setters, not the positional constructor.
        //
        // `@AllArgsConstructor` hands out a 16-argument constructor whose arguments are
        // `Integer,Integer,String,String,Integer,Integer,...` - four of them interchangeable
        // nullables in a row. Adding `playedMatchId` broke this call at compile time, and the fix
        // chosen by reflex would have been to append one more `null` and move on. That is how
        // `seasonNumber` ends up holding a `dayNumber` and nothing notices, because all four are
        // still integers and the DTO still compiles.
        //
        // Named setters make the field order irrelevant, and a field added tomorrow cannot silently
        // shift every argument after it.
        MatchDTO dto = new MatchDTO();
        dto.setId(match.getId());
        dto.setHomeTeam(match.getHomeTeam() != null ? match.getHomeTeam().getName() : "TBD");
        dto.setAwayTeam(match.getAwayTeam() != null ? match.getAwayTeam().getName() : "TBD");
        dto.setHomeTeamId(match.getHomeTeam() != null ? match.getHomeTeam().getId() : null);
        dto.setAwayTeamId(match.getAwayTeam() != null ? match.getAwayTeam().getId() : null);
        dto.setHomeGoals(homeGoals);
        dto.setAwayGoals(awayGoals);
        dto.setMatchDate(formattedDate);
        dto.setSeasonDayLabel(buildSeasonDayLabel(match.getSeasonYear(), match.getDayNumber(), match.getMatchDate()));
        dto.setSeasonNumber(match.getSeasonYear());
        dto.setDayNumber(match.getDayNumber());
        dto.setWeekNumber(match.getWeekNumber());
        dto.setCompetitionName(competition != null ? competition.getName() : null);
        dto.setCompetitionType(competition != null && competition.getType() != null
                ? competition.getType().name() : null);
        dto.setResultHidden(resultHidden);
        dto.setResultRevealed(resultRevealed);
        dto.setReplayId(match.getReplayId());
        return dto;
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
