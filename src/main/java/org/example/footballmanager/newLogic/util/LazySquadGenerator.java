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
}