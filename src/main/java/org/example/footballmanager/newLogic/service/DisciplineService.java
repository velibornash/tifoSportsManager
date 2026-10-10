package org.example.footballmanager.newLogic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerDiscipline;
import org.example.footballmanager.newLogic.repository.PlayerDisciplineRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Cards, and what they cost (owner ruling, 2026-10-10).
 *
 * <p>Two rules, and they are deliberately different in scope:
 *
 * <ul>
 *   <li><b>A red card bans the player from the first next <em>official</em> match</b> — everything except
 *       a friendly. The owner said so directly. It is club-wide, not competition-scoped, because the rule
 *       is about the club and not about the competition the card happened to be in.</li>
 *   <li><b>Yellows accumulate within the league:</b> three earns one match, six earns two, nine earns
 *       three. These are served <em>in the league</em>, because that is where they were earned.</li>
 * </ul>
 *
 * <p><b>The counter resets when the bans are served, not when they are earned.</b> Resetting at three
 * would mean six and nine could never be reached at all, and two thirds of the owner's rule would be
 * dead letters.
 *
 * <p><b>Keyed by season,</b> so the end-of-season reset is structural rather than a sweep somebody has to
 * remember to run.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DisciplineService {

    private final PlayerDisciplineRepository records;
    private final PlayerRepository players;

    /**
     * Records the cards a player picked up in one match.
     *
     * @param competition the competition he was playing in; null for a friendly, where yellows do not
     *                    accumulate and a red card still stands
     */
    @Transactional
    public void record(Long playerId, Integer season, Competition competition,
                       int yellowCards, int redCards) {

        if (playerId == null || season == null || (yellowCards <= 0 && redCards <= 0)) {
            return;
        }

        if (redCards > 0) {
            PlayerDiscipline clubWide = clubWideRecord(playerId, season);
            clubWide.setBansOwed(clubWide.getBansOwed() + redCards);
            clubWide.setUpdatedAt(LocalDateTime.now());
            records.save(clubWide);
        }

        if (yellowCards > 0 && isOfficial(competition)) {
            PlayerDiscipline league = competitionRecord(playerId, season, competition);
            int before = PlayerDiscipline.leagueBansEarnedBy(league.getYellowCards());
            league.setYellowCards(league.getYellowCards() + yellowCards);
            int after = PlayerDiscipline.leagueBansEarnedBy(league.getYellowCards());
            if (after > before) {
                // Earned, not served: the counter keeps its place so the next band is reachable.
                league.setBansOwed(league.getBansOwed() + (after - before));
            }
            league.setUpdatedAt(LocalDateTime.now());
            records.save(league);
        }
    }

    /**
     * Whether this player may play this fixture, and why not when he may not.
     *
     * <p>Both rules are consulted, because both can bar the same player: a red-card ban is club-wide and
     * blocks the next official match of any sort, while a yellow ban only blocks this competition.
     */
    @Transactional(readOnly = true)
    public Optional<String> suspensionFor(Long playerId, Integer season, MatchFixture fixture) {
        if (playerId == null || season == null || fixture == null) {
            return Optional.empty();
        }
        Competition competition = fixture.getCompetition();
        boolean official = isOfficial(competition);

        if (official) {
            Optional<PlayerDiscipline> clubWide = records.findByPlayerIdAndSeasonAndCompetitionIsNull(
                    playerId, season);
            if (clubWide.isPresent() && clubWide.get().getBansOwed() > 0) {
                return Optional.of("suspended — sent off in his last official match");
            }
        }

        if (competition != null && competition.getId() != null) {
            Optional<PlayerDiscipline> league = records
                    .findByPlayerIdAndSeasonAndCompetitionId(playerId, season, competition.getId());
            if (league.isPresent() && league.get().getBansOwed() > 0) {
                return Optional.of("suspended — " + league.get().getBansOwed()
                        + " match ban" + (league.get().getBansOwed() == 1 ? "" : "es")
                        + " in this competition");
            }
        }
        return Optional.empty();
    }

    /**
     * Takes one ban off this player for this fixture, if he has one.
     *
     * <p>Called as the match is set up, so a ban is served by playing — or by being selected and then
     * removed for any other reason, which is the same thing from the record's point of view. There is no
     * release: a ban is served, not cancelled.
     */
    @Transactional
    public void serve(Long playerId, Integer season, MatchFixture fixture) {
        if (playerId == null || season == null || fixture == null) {
            return;
        }
        Competition competition = fixture.getCompetition();

        if (isOfficial(competition)) {
            records.findByPlayerIdAndSeasonAndCompetitionIsNull(playerId, season)
                    .filter(record -> record.getBansOwed() > 0)
                    .ifPresent(record -> {
                        record.setBansOwed(record.getBansOwed() - 1);
                        record.setUpdatedAt(LocalDateTime.now());
                        records.save(record);
                    });
        }

        if (competition != null && competition.getId() != null) {
            records.findByPlayerIdAndSeasonAndCompetitionId(playerId, season, competition.getId())
                    .filter(record -> record.getBansOwed() > 0)
                    .ifPresent(record -> {
                        record.setBansOwed(record.getBansOwed() - 1);
                        record.setBansServed(record.getBansServed() + 1);
                        record.setUpdatedAt(LocalDateTime.now());
                        // The cycle is complete once everything the yellows have earned has been served.
                        // Only then does the counter start again, which is what keeps six and nine
                        // reachable in the first place.
                        if (record.getBansServed() >= PlayerDiscipline.leagueBansEarnedBy(record.getYellowCards())) {
                            record.setYellowCards(0);
                            record.setBansServed(0);
                        }
                        records.save(record);
                    });
        }
    }

    /** The record, for a manager asking why somebody is unavailable. */
    @Transactional(readOnly = true)
    public Optional<PlayerDiscipline> recordFor(Long playerId, Integer season, Competition competition) {
        if (playerId == null || season == null) {
            return Optional.empty();
        }
        return competition == null || competition.getId() == null
                ? records.findByPlayerIdAndSeasonAndCompetitionIsNull(playerId, season)
                : records.findByPlayerIdAndSeasonAndCompetitionId(playerId, season, competition.getId());
    }

    /**
     * <b>Everything except a friendly is official</b> (owner, 2026-10-10).
     *
     * <p>Which is a question about the fixture's <em>competition</em>, not about a competition type:
     * {@code CompetitionType} has no FRIENDLY value, because a friendly is a fixture that belongs to no
     * competition at all. {@code MatchType.ofCompetition(null)} is FRIENDLY for exactly that reason.
     *
     * <p>So "official" means "plays into something", and the test is one null check. An exhibition is
     * simulated inline and has no fixture, so it never reaches here either way.
     */
    public static boolean isOfficial(Competition competition) {
        return competition != null;
    }

    /** True when this fixture is one a suspension can bar the player from. */
    public static boolean isOfficialFixture(MatchFixture fixture) {
        return fixture != null && isOfficial(fixture.getCompetition());
    }

    private PlayerDiscipline clubWideRecord(Long playerId, Integer season) {
        Player player = players.findById(playerId).orElseThrow(
                () -> new IllegalArgumentException("Unknown player " + playerId));
        return records.findByPlayerIdAndSeasonAndCompetitionIsNull(playerId, season)
                .orElseGet(() -> {
                    PlayerDiscipline record = new PlayerDiscipline();
                    record.setPlayer(player);
                    record.setSeason(season);
                    record.setCompetition(null);
                    return record;
                });
    }

    private PlayerDiscipline competitionRecord(Long playerId, Integer season, Competition competition) {
        Player player = players.findById(playerId).orElseThrow(
                () -> new IllegalArgumentException("Unknown player " + playerId));
        return records.findByPlayerIdAndSeasonAndCompetitionId(playerId, season, competition.getId())
                .orElseGet(() -> {
                    PlayerDiscipline record = new PlayerDiscipline();
                    record.setPlayer(player);
                    record.setSeason(season);
                    record.setCompetition(competition);
                    return record;
                });
    }

    /** Everything a manager might want to show about a player's discipline this season. */
    @Transactional(readOnly = true)
    public List<PlayerDiscipline> allFor(Long playerId, Integer season) {
        return records.findByPlayerIdAndSeason(playerId, season);
    }
}