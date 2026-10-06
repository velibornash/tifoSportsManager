package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionScope;
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

    private final CompetitionRepository competitions;
    private final MatchFixtureRepository fixtures;
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final org.example.footballmanager.newLogic.repository.GameClockRepository clocks;

    /**
     * The country's senior internationals, by what they are rather than by which row comes first.
     *
     * <p>Matches on {@code type == INTERNATIONAL} and a scope that is not
     * {@link CompetitionScope#INTERNATIONAL} — the Champions, Masters and Challenge cups are also
     * INTERNATIONAL by type and are drawn by their own code on their own calendar. Selecting "the first
     * INTERNATIONAL row" would let this seeder draw a continental club cup.
     */
    private Competition nationalInternationals() {
        return competitions.findFirstNationalScoped(
                CompetitionType.INTERNATIONAL, CompetitionScope.INTERNATIONAL,
                org.springframework.data.domain.Limit.of(1)).orElse(null);
    }

    /** True when this competition already has a drawn tie for the season. The record of a draw. */
    private boolean hasFixturesFor(Competition competition, int seasonYear) {
        return fixtures.countByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalse(
                competition.getId(), seasonYear, 1) > 0;
    }

    private Competition createInternationals() {
        Competition international = new Competition();
        international.setName("Internationals");
        international.setType(CompetitionType.INTERNATIONAL);
        international.setTeamType(CompetitionTeamType.NATIONAL_TEAM);
        international.setScope(CompetitionScope.NATIONAL);
        return competitions.save(international);
    }

    /** The running world's season start, which is what a fixture date is measured from. */
    private LocalDateTime seasonStart() {
        return clocks.findById(1L)
                .map(org.example.footballmanager.newLogic.model.GameClock::getCurrentDate)
                .orElse(LocalDateTime.of(2026, 7, 1, 12, 0));
    }

    public InternationalFixtureSeeder(CompetitionRepository competitions,
                                      MatchFixtureRepository fixtures, TeamRepository teams,
                                      PlayerRepository players,
                                      org.example.footballmanager.newLogic.repository.GameClockRepository clocks) {
        this.competitions = competitions;
        this.fixtures = fixtures;
        this.teams = teams;
        this.players = players;
        this.clocks = clocks;
    }

    @Transactional
    public void seedIfMissing(int seasonYear) {
        Competition existing = nationalInternationals();

        // **Idempotent by fixture count, not by the competition existing.**
        //
        // This used to open with "does any INTERNATIONAL competition exist", and it saved the competition
        // on the line after — so the first run created it, found fewer than two sides with a squad, and
        // returned. The competition was committed, so **every later run took the guard and returned
        // immediately**: the seeder could never draw, and nothing anywhere reported a failure.
        // `MatchdayJob` then found the empty competition every week, found no fixtures, and was marked
        // DONE — so `job_run` recorded a successful international matchday that played nothing, for ever.
        //
        // The fixtures are the record of whether the draw happened, which is the rule `CupFixtureSeeder`
        // and `NationalTeamSeeder` both already use. A flag is a place for a re-run to be quietly wrong.
        if (existing != null && hasFixturesFor(existing, seasonYear)) {
            return;
        }

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
            // **Nothing is created on this path, deliberately.** Creating an empty competition here is
            // what made the seeder unable to run again, and an empty one is also what MatchdayJob finds
            // and reports as a successful matchday with no fixtures. A world that is not ready to be
            // drawn is left exactly as it was, so a later seeding pass can still draw it.
            log.warn("Internationals: {} senior side(s) exist, {} have a squad, so no tie can be drawn "
                            + "(two are needed). Nothing was created; this will run again once more sides "
                            + "have squads.", nationalSides.size(), entrants.size());
            return;
        }

        Competition saved = existing != null ? existing : createInternationals();

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
            // Labelled a friendly, on the owner's word: "scheduled like a club friendly". The competition
            // type is what the day-1 matchday job selects on, so the round stays playable either way -
            // and this way it also carries the behaviour of a friendly: reduced injury risk, and nothing
            // added to a player's career record.
            //
            // Deliberately *not* excluded from the national Elo replay. `RatingEngine.nationalK` already
            // weights a competition with no national stage at `NationalStage.OTHER` - the lowest bucket,
            // written for exactly this - so a warm-up moves a rating a little rather than not at all.
            // Excluding it would be a second opinion about the weighting, in the wrong place.
            fixture.setMatchType(org.example.footballmanager.newLogic.model.MatchType.FRIENDLY);
            // Measured from the game clock's season start, not a literal. B4: every season's qualifier
            // was stamped 2026-07-01, and once the clock passed 2026-07-06 no international fell inside the
            // two-day recovery window, so zone loads were written and never read and RecoveryJob reported
            // zero for ever. The league path already used clock.getCurrentDate() and this now matches it.
            fixture.setMatchDate(LocalDateTime.of(
                    seasonStart().toLocalDate()
                            .plusWeeks(QUALIFIER_WEEK - 1L)
                            .plusDays(GameDay.INTERNATIONAL_DAY - 1L),
                    java.time.LocalTime.of(KICKOFF_HOUR, KICKOFF_MINUTE)));
            fixtures.save(fixture);
            made++;
        }
        log.info("Internationals: {} tie(s) drawn for week {} day {} from {} playable national sides "
                        + "({} exist, {} without a squad).",
                made, QUALIFIER_WEEK, GameDay.INTERNATIONAL_DAY, entrants.size(), nationalSides.size(), withoutSquad);
    }
}
