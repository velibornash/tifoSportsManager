package org.example.footballmanager.newLogic.util;

import java.util.List;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.util.players.PlayerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives a club a squad at the moment it needs one, rather than when the world was seeded.
 *
 * <p><b>Why this exists.</b> The simulated countries are seeded with clubs, ratings and a standing
 * table but deliberately <em>no players</em>: forty-six countries is roughly 370,000 player rows for
 * clubs that had never kicked a ball, and the whole point of the static world was to keep that off
 * the database. The cost of that decision is that a club with no players has no lineup, so a tie
 * against a human club resolved on reputation and showed nobody anything.
 *
 * <p><b>The trigger is narrow on purpose</b>: one side human-controlled, and the match actually
 * happening. Two bot clubs against each other is still just a result, because a result is all that
 * is displayed and there is nothing to line up.
 *
 * <p><b>Generated once, then left alone.</b> The squad is persisted, so the next match and the next
 * boot both find it. A club that already has players is never touched — this tops up an empty club, it
 * is not a refresh.
 *
 * <p><b>Display-only.</b> A generated squad exists so a match has a lineup in it. It does not feed
 * ratings, Elo or the transfer market, because it is not a real club and treating it as one would
 * quietly start writing real numbers from a placeholder. That is a deliberate line, and
 * {@link #isHumanInvolved} is the only thing that crosses it.
 */
@Service
public class LazySquadGenerator {

    private static final Logger log = LoggerFactory.getLogger(LazySquadGenerator.class);

    /** A senior squad. U21 sides are a separate matter and are not generated here. */
    public static final int SENIOR_SQUAD_SIZE = 18;

    private final PlayerRepository players;
    private final TeamRepository teams;
    private final PlayerFactory playerFactory;

    public LazySquadGenerator(PlayerRepository players, TeamRepository teams, PlayerFactory playerFactory) {
        this.players = players;
        this.teams = teams;
        this.playerFactory = playerFactory;
    }

    /**
     * Generates a squad for either side of a match, if that side needs one.
     *
     * <p>Returns how many players were created, so the caller can log a match that quietly gained two
     * squads without that being invisible.
     */
    @Transactional
    public int ensureSquadsForMatch(Team home, Team away) {
        // The precondition is enforced here, not only at the call site.
        //
        // The guard lived in SimMatchService alone, which meant any future caller — a cup draw, a
        // replay, a test — could call this directly and generate for two bot clubs. The test for
        // "two bots generate nothing" caught exactly that. A cheap precondition belongs to the thing
        // that depends on it.
        if (!isHumanInvolved(home, away)) {
            return 0;
        }

        int made = 0;
        if (needsSquad(home)) {
            made += generate(home);
        }
        if (needsSquad(away)) {
            made += generate(away);
        }
        return made;
    }

    /**
     * Whether this match is one where a lineup is ever displayed.
     *
     * <p>Exactly one human side. Two human sides already have squads and this never fires; two bot
     * sides have nothing to show, and generating for them would undo the entire point of the static
     * world — one fixture list would quietly turn into fourteen thousand squads.
     */
    public static boolean isHumanInvolved(Team home, Team away) {
        return isHuman(home) != isHuman(away);
    }

    private static boolean isHuman(Team team) {
        return team != null && team.isHumanControlled();
    }

    /** A club with nobody in it, and the match is one where that will be visible. */
    private boolean needsSquad(Team team) {
        return team != null
                && !isHuman(team)
                && players.findByTeamId(team.getId()).isEmpty();
    }

    /**
     * Creates the squad at the club's own tier, not at a default.
     *
     * <p>A tier-5 club turning up with a first-team squad is the failure this avoids. The tier comes
     * from the club's competition, which the seeder set when it built the division.
     */
    private int generate(Team team) {
        int tier = tierOf(team);
        int skill = PyramidBuilder.tierSkill(tier);

        List<org.example.footballmanager.newLogic.model.Player> made =
                playerFactory.createRandomTeamPlayers(team.getName(), team);

        log.info("Generated a squad for {} on demand: {} player(s) at tier {} standard (skill {}).",
                team.getName(), made.size(), tier, skill);
        return made.size();
    }

    /** The tier of the club's division, defaulting to 1 for a club with no competition. */
    private int tierOf(Team team) {
        if (team.getCompetition() == null || team.getCompetition().getTier() == null) {
            return 1;
        }
        return team.getCompetition().getTier();
    }

    // ------------------------------------------------ continental cups (P0-CUPS-3)

    /**
     * Gives every one of these clubs a squad, if it has none — P0-CUPS-3.
     *
     * <p><b>The cup is the first thing in this game that makes two bot clubs actually play each
     * other.</b> Everything above is deliberate: the static world exists to avoid 370,000 player rows
     * for clubs that never kicked a ball, and the guard above is narrow because a bot-versus-bot match
     * was "still just a result". The fifteen international club cups dissolve that reasoning. They admit
     * <b>192 clubs per tier — 48 Champions, 96 Masters, 48 Challenge — and there are five tiers</b>, so
     * roughly <b>960 clubs</b>, and 46 of the 48 countries are {@code SIMULATED}, which is to say they
     * are all of them.
     *
     * <p>So a Champions Cup tie between two simulated countries would reach
     * {@code SimMatchService.loadRealSquad()}, find no players, get {@code null} for both sides, and be
     * handed to {@code SimTeamFactory.addTeam()} — <b>synthetic placeholder players</b>. The competition
     * would then be decided by 22 unnamed stand-ins, and the owner's rating ladder (average 12 at tier 1,
     * one lower per tier) would be <b>invisible</b>, because a synthetic squad has no rating to average.
     * It would look finished. It would be a coin flip between identical squads.
     *
     * <h2>Why no {@code CountryState} filter</h2>
     *
     * <p>The owner's decision was <i>only simulated countries' clubs</i>, and that is what happens — but
     * it happens structurally rather than by being tested for. {@link #needsSquad} asks whether the club
     * has players, and an <b>ACTIVE</b> country's clubs have them: {@code PyramidBuilder.build()} creates
     * a squad for every club it builds. So the clubs that arrive here empty <i>are</i> the simulated
     * ones, and adding a {@code country.getState() == SIMULATED} test would be a second statement of the
     * same fact that can disagree with the first. An active club that somehow had no squad would
     * correctly get one here at its own tier.
     *
     * <h2>Still display-only, still not a refresh</h2>
     *
     * <p>Everything the class javadoc promises still holds. These squads are generated once and left
     * alone; they are not rebuilt when a club is promoted, and they do not feed Elo or the transfer
     * market beyond what a real squad would. What changed is that a cup tie between two of them now has
     * men on the pitch instead of placeholders.
     *
     * @return how many clubs were given a squad
     */
    @Transactional
    public int ensureSquadsForCupEntrants(List<Team> entrants) {
        if (entrants == null || entrants.isEmpty()) {
            return 0;
        }
        int made = 0;
        for (Team team : entrants) {
            if (!needsSquad(team)) {
                continue;
            }
            int count = generate(team);
            log.info("Cup entrant {} had no squad: generated {} player(s) at tier {} standard (skill {}).",
                    team.getName(), count, tierOf(team), PyramidBuilder.tierSkill(tierOf(team)));
            made++;
        }
        if (made > 0) {
            log.info("Cup entrants: {} of {} club(s) were given a squad.",
                    made, entrants.size());
        }
        return made;
    }
}