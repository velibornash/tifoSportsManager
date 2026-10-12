package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The eleven and the bench, chosen for a manager who does not want to choose them (owner, 2026-10-11).
 *
 * <p>The owner: *"squad isključi 11 i klupa"*. Twenty minutes before kickoff the useful thing to offer is
 * the best available eleven so he can change two players, not an empty form.
 *
 * <p><b>A short squad is an answer, not a fault.</b> OFK Omladinac has thirteen players, deliberately.
 * Eleven to start and two on the bench is the correct suggestion for thirteen, so that is what this
 * returns — and no warning, because nothing has gone wrong. A suggester that demanded a full bench of
 * seven would refuse to suggest anything for the club the owner actually manages, which is the shape of
 * feature that exists and is never useful.
 *
 * <p><b>A goalkeeper is not optional.</b> Eleven outfield players is a completed-looking suggestion for a
 * side that cannot keep a ball in play, so the keeper is picked first and the rest of the shape is filled
 * around it. With no keeper in the squad at all, the suggestion says so rather than quietly fielding one
 * short.
 *
 * <p>Ordering is by rating within each line of the pitch, and the lines are picked in a fixed order, so
 * the same squad always produces the same eleven. A suggestion that reshuffled between two clicks would
 * make the manager's two changes land on different players.
 */
@Service
public class LineupSuggester {

    private final SquadRegistrationService squads;

    public LineupSuggester(SquadRegistrationService squads) {
        this.squads = squads;
    }

    /**
     * One suggested player, as plain values.
     *
     * <p><b>A value, not a {@code Player}.</b> The suggester runs in its own read-only transaction, so
     * returning entities hands the caller a squad whose every lazy reference is already dead — the same
     * *"could not initialize proxy Team#1 - no Session"* that cost 775 matchday fixtures, arriving here
     * instead. The id and the name are all a suggestion needs; saving goes through the ordinary lineup
     * endpoint, which resolves the players in its own session.
     */
    public record Pick(Long id, String name, Position position, int rating) {
        static Pick of(Player player) {
            return new Pick(player.getId(), player.getName(),
                    player.getPosition(), player.getRating());
        }
    }

    /**
     * One suggestion: the eleven, the bench, and anything the manager should know about it.
     *
     * @param starters the eleven, or empty when the squad cannot field eleven
     * @param bench everyone left over, up to the bench limit
     * @param warnings what to say about it — **empty is the normal, correct answer**
     */
    public record Suggestion(List<Pick> starters, List<Pick> bench, List<String> warnings) {
        public boolean complete() {
            return starters.size() == MatchLineupService.STARTERS;
        }

        /** The starter ids, in order, for saving. */
        public List<Long> starterIds() {
            return starters.stream().map(Pick::id).toList();
        }

        /** The bench ids, in order, for saving. */
        public List<Long> benchIds() {
            return bench.stream().map(Pick::id).toList();
        }
    }

    /**
     * The eleven slots, in the order they are filled: a keeper, four at the back, four in midfield, two
     * up front.
     *
     * <p><b>Exactly eleven entries, because this is eleven slots and not a list of positions.</b> The
     * first version had thirteen — one per position the club might field — and consequently suggested
     * thirteen starters, which the page then refused to save. A position a squad happens to have more of
     * is not a reason to field more of them; a slot whose own line is empty borrows, and that is the only
     * thing that ever adds a player from the wrong line.
     */
    private static final List<Position> SHAPE = List.of(
            Position.GK,
            Position.DEF, Position.DEF, Position.DEF, Position.DEF,
            Position.MID, Position.MID, Position.MID, Position.MID,
            Position.ATT, Position.ATT);

    /**
     * The best available eleven and the rest of the squad.
     *
     * <p>Draws on {@link SquadRegistrationService#availablePlayers}, not on "every player the club owns",
     * so a player loaned <em>away</em> is not suggested. Offering a match-day squad of somebody who is not
     * here is the kind of suggestion that costs a goal.
     */
    @Transactional(readOnly = true)
    public Suggestion forTeam(Long teamId) {
        List<String> warnings = new ArrayList<>();
        List<Player> squad = new ArrayList<>(squads.availablePlayers(teamId));

        if (squad.isEmpty()) {
            return new Suggestion(List.of(), List.of(),
                    List.of("This club has no players available, so there is no eleven to suggest."));
        }
        if (squad.size() < MatchLineupService.STARTERS) {
            return new Suggestion(List.of(), List.of(),
                    List.of("This club has " + squad.size() + " player"
                            + (squad.size() == 1 ? "" : "s") + " and a side needs "
                            + MatchLineupService.STARTERS + " to start."));
        }

        Map<Position, List<Player>> byPosition = new EnumMap<>(Position.class);
        for (Position position : Position.values()) {
            byPosition.put(position, new ArrayList<>());
        }
        for (Player player : squad) {
            Position position = player.getPosition() == null ? Position.MID : player.getPosition();
            byPosition.get(position).add(player);
        }
        byPosition.values().forEach(list -> list.sort(
                Comparator.comparingInt((Player p) -> p.getRating() == 0 ? 0 : p.getRating())
                        .reversed()
                        .thenComparing(p -> p.getName() == null ? "" : p.getName())));

        List<Player> starters = new ArrayList<>();
        List<Player> used = new ArrayList<>();

        boolean hasKeeper = !byPosition.get(Position.GK).isEmpty();
        if (!hasKeeper) {
            warnings.add("No goalkeeper in this squad - the best available player has been put in goal, "
                    + "but the club should sign one.");
        }

        // **A cursor per position, not a repeated get(0).** Taking the head of the list for every slot
        // without removing it put the same best defender in all four defensive slots: eleven starters
        // containing four or five of the same player, which then left a bench of players who were also
        // "starting". Distinctness is the whole of what makes a suggestion a side.
        Map<Position, Integer> cursor = new EnumMap<>(Position.class);

        for (Position position : SHAPE) {
            List<Player> candidates = byPosition.getOrDefault(position, List.of());
            int at = cursor.getOrDefault(position, 0);
            if (at < candidates.size()) {
                Player chosen = candidates.get(at);
                cursor.put(position, at + 1);
                starters.add(chosen);
                used.add(chosen);
                continue;
            }
            // This line has nobody in it. Borrow the best player not already picked, rather than
            // returning nine and calling it eleven.
            Player borrowed = bestUnused(squad, used);
            if (borrowed == null) {
                break;
            }
            starters.add(borrowed);
            used.add(borrowed);
        }

        if (starters.size() < MatchLineupService.STARTERS) {
            return new Suggestion(List.of(), List.of(),
                    List.of("This squad cannot make an eleven from the players available."));
        }

        List<Player> bench = squad.stream()
                .filter(p -> !used.contains(p))
                .sorted(Comparator.comparingInt((Player p) -> p.getRating() == 0 ? 0 : p.getRating())
                        .reversed())
                .limit(MatchLineupService.MAX_BENCH)
                .toList();

        return new Suggestion(
                starters.stream().map(Pick::of).toList(),
                bench.stream().map(Pick::of).toList(),
                warnings);
    }

    private static Player bestUnused(List<Player> squad, List<Player> used) {
        Player best = null;
        for (Player player : squad) {
            if (used.contains(player)) {
                continue;
            }
            if (best == null
                    || (player.getRating() != 0 && player.getRating() > best.getRating())) {
                best = player;
            }
        }
        return best;
    }
}