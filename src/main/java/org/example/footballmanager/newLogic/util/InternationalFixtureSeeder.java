package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.GameDay;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * International football between national sides (owner, 2026-09-29).
 *
 * <p>The day-1 slot in the owner's schedule is "International 20:45", and the qualifier window is
 * week 6 on days 1, 2, 3, 5 and 7. This draws a single round of pairings across the senior national
 * teams, on day 1 of week 6.
 *
 * <p><b>Only sides with a squad are entered.</b> A national team exists for every seeded country, but
 * a country with no clubs has no players to call up, so its side is a name on a roster and not a team
 * that can play. Entering it would produce fixtures that either simulate to nothing or crash, and
 * both look like the simulator is broken rather than like the world is unseeded.
 *
 * <p><b>It is a single round, not the owner's 8-group qualifying structure.</b> That structure needs
 * 48 nations; there are 9 seeded and 1 with a squad. The pairings here are what can honestly be drawn
 * today, and the real group structure is recorded in the backlog behind the 48-country seed.
 */
@Component
public class InternationalFixtureSeeder {

    private static final Logger log = LoggerFactory.getLogger(InternationalFixtureSeeder.class);

    /** The owner's qualifier week. */
    static final int QUALIFIER_WEEK = 6;
    /** Day 1 of the game week, 20:45. */
    static final int KICKOFF_HOUR = 20;
    static final int KICKOFF_MINUTE = 45;
    private static final LocalDate SEASON_START = LocalDate.of(2026, 7, 1);

    private final CompetitionRepository competitions;
    private final MatchFixtureRepository fixtures;
    private final TeamRepository teams;
    private final PlayerRepository players;

    public InternationalFixtureSeeder(CompetitionRepository competitions,
                                      MatchFixtureRepository fixtures, TeamRepository teams,
                                      PlayerRepository players) {
        this.competitions = competitions;
        this.fixtures = fixtures;
        this.teams = teams;
        this.players = players;
    }

    @Transactional
    public void seedIfMissing(int seasonYear) {
        if (competitions.findAll().stream().anyMatch(c -> c.getType() == CompetitionType.INTERNATIONAL)) {
            return;
        }

        Competition international = new Competition();
        international.setName("Internationals");
        international.setType(CompetitionType.INTERNATIONAL);
        international.setTeamType(CompetitionTeamType.NATIONAL_TEAM);
        Competition saved = competitions.save(international);

        // findByType, not findClubTeamsForOperations: the club query returns no national sides at
        // all, which read as "no squads seeded" rather than "you asked the wrong repository".
        List<Team> nationalSides = teams.findByType(
                org.example.footballmanager.newLogic.model.CompetitionTeamType.NATIONAL_TEAM);

        List<Team> entrants = new ArrayList<>();
        int withoutSquad = 0;
        for (Team team : nationalSides) {
            if (team.getId() == null || team.getCountry() == null) {
                continue;
            }
            // U-21 sides are national teams too, but the senior internationals are a senior competition.
            if (!team.getName().endsWith("National Team")) {
                continue;
            }
            if (players.findByTeamId(team.getId()).isEmpty()) {
                withoutSquad++;
            } else {
                entrants.add(team);
            }
        }

        if (entrants.size() < 2) {
            log.info("Internationals: {} senior side(s) exist, {} have a squad, so {} tie(s) can be "
                            + "drawn. More countries need seeding.", nationalSides.size(), entrants.size(), 0);
            return;
        }

        int made = 0;
        for (int i = 0; i + 1 < entrants.size(); i += 2) {
            Team home = entrants.get(i);
            Team away = entrants.get(i + 1);
            MatchFixture fixture = new MatchFixture();
            fixture.setCompetition(saved);
            fixture.setHomeTeam(home);
            fixture.setAwayTeam(away);
            fixture.setSeasonYear(seasonYear);
            fixture.setRoundNumber(1);
            fixture.setWeekNumber(QUALIFIER_WEEK);
            fixture.setDayNumber(GameDay.INTERNATIONAL_DAY);
            fixture.setPlayed(false);
            fixture.setMatchDate(LocalDateTime.of(
                    SEASON_START.plusWeeks(QUALIFIER_WEEK - 1L).plusDays(GameDay.INTERNATIONAL_DAY - 1L),
                    java.time.LocalTime.of(KICKOFF_HOUR, KICKOFF_MINUTE)));
            fixtures.save(fixture);
            made++;
        }
        log.info("Internationals: {} tie(s) drawn for week {} day {} from {} playable national sides "
                        + "({} exist, {} without a squad).",
                made, QUALIFIER_WEEK, GameDay.INTERNATIONAL_DAY, entrants.size(), nationalSides.size(), withoutSquad);
    }
}
