package org.example.footballtextmanager.engine;

import org.example.footballtextmanager.model.CSPlayStyle;
import org.example.footballtextmanager.model.CSPosition;
import org.example.footballtextmanager.model.CSPlayer;
import org.example.footballtextmanager.model.CSTactics;
import org.example.footballtextmanager.model.CSTeam;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Who the opposition is likely to play.
 *
 * <p>Until this existed, every club except the manager's own played a hardcoded 4-4-2 in
 * {@code CSPlayStyle.BALANCED}. Two consequences, and only the first was visible:
 *
 * <ul>
 *   <li>the whole league played the same way, so scouting an opponent told you nothing and the
 *       tactical screen had one column of information;</li>
 *   <li>worse, once style became a real trade-off, every AI club sat at the neutral option. The
 *       manager could pick ATTACKING and face eleven BALANCED opponents, which flatters the new
 *       model and makes the league read as evenly coached.</li>
 * </ul>
 *
 * <p>Two properties are deliberate:
 *
 * <ul>
 *   <li><b>Deterministic.</b> Everything is derived from the club id and its roster, never from a
 *       rolling {@code Random}. The same club must play the same way in round 3 and round 30,
 *       otherwise the league table stops being reproducible — the same reason the match simulator
 *       seeds from fixture identity.</li>
 *   <li><b>Suited to the squad.</b> The shape is snapped to the nearest standard formation that
 *       the club's own players can actually execute, rather than picked arbitrarily. A club whose
 *       players are all midfielders should not be handed a back three; that would add a random
 *       handicap dressed up as tactics.</li>
 * </ul>
 */
public final class CSOpponentProfile {

    /**
     * Standard shapes: name, then how many go in defence, midfield and attack.
     *
     * <p><b>Every row must sum to ten outfielders.</b> Two did not. {@code 4-2-3-1} was recorded as
     * 4-2-3 and {@code 4-1-4-1} as 4-1-4, both of which describe ten players only if the lone
     * striker is left out of the count — so both rows summed to nine. Harmless while the numbers
     * were only compared against, and a team of ten the moment they are used to build a starting
     * eleven. The striker is now included: {@code 4-2-3-1} is 4-5-1 outfield (two holding plus
     * three advanced is five in midfield) and {@code 4-1-4-1} likewise.
     *
     * <p>{@link #assertFormationsArePlayable()} checks this at class initialisation rather than
     * trusting a comment.
     */
    private static final String[][] FORMATIONS = {
            {"4-4-2", "4", "4", "2"},
            {"4-3-3", "4", "3", "3"},
            {"4-2-3-1", "4", "5", "1"},
            {"3-5-2", "3", "5", "2"},
            {"5-3-2", "5", "3", "2"},
            {"4-1-4-1", "4", "5", "1"}
    };

    private static final int OUTFIELD_PER_TEAM = 10;

    static {
        assertFormationsArePlayable();
    }

    /** Fails fast at startup if any row cannot field eleven players. */
    private static void assertFormationsArePlayable() {
        for (String[] formation : FORMATIONS) {
            int total = Integer.parseInt(formation[1])
                    + Integer.parseInt(formation[2])
                    + Integer.parseInt(formation[3]);
            if (total != OUTFIELD_PER_TEAM) {
                throw new IllegalStateException(
                        "Formation " + formation[0] + " fields " + (total + 1)
                                + " players, not 11. Every formation must sum to "
                                + OUTFIELD_PER_TEAM + " outfielders.");
            }
        }
    }

    /**
     * The outfield slots a formation uses, best to worst: {@code n} defenders, {@code n}
     * midfielders, {@code n} attackers. Always ten, so the eleven is always eleven.
     */
    public static List<CSPosition> outfieldSlotsFor(String formation) {
        String[] row = rowFor(formation);
        List<CSPosition> slots = new ArrayList<>(OUTFIELD_PER_TEAM);
        for (int i = 0; i < Integer.parseInt(row[1]); i++) slots.add(CSPosition.DEF);
        for (int i = 0; i < Integer.parseInt(row[2]); i++) slots.add(CSPosition.MID);
        for (int i = 0; i < Integer.parseInt(row[3]); i++) slots.add(CSPosition.ATT);
        return slots;
    }

    /** The formation row for a name, falling back to 4-4-2 for anything unrecognised. */
    private static String[] rowFor(String formation) {
        for (String[] candidate : FORMATIONS) {
            if (candidate[0].equals(formation)) return candidate;
        }
        return FORMATIONS[0];
    }

    private CSOpponentProfile() {
    }

    /**
     * The tactics this club will use. Deterministic in {@code team} and {@code roster}.
     *
     * @param roster may be null or empty, in which case the club falls back to a 4-4-2 and a
     *               style derived from its id alone
     */
    public static CSTactics tacticsFor(CSTeam team, List<CSPlayer> roster) {
        return CSTactics.builder()
                .formation(formationFor(team, roster))
                .style(styleFor(team))
                .build();
    }

    /**
     * Nearest standard formation to the squad's actual shape.
     *
     * <p>Wingeders count towards both midfield and attack, which is what makes a 4-4-2 squad read
     * as four midfielders and two strikers rather than as six midfielders.
     *
     * <p>The shape is derived, never declared. {@code CSTeam} used to carry a {@code formation}
     * field that the mapper hardcoded to "4-4-2" for every club, and when this method honoured a
     * stated shape the whole league inherited it. A field that nothing writes is not a preference,
     * it is a trap.
     */
    static String formationFor(CSTeam team, List<CSPlayer> roster) {
        if (roster == null || roster.isEmpty()) return "4-4-2";

        int defenders = 0;
        int midfielders = 0;
        int attackers = 0;
        for (CSPlayer player : roster) {
            if (player == null) continue;
            switch (Objects.requireNonNullElse(player.getPosition(), "MID")) {
                case "GK" -> { /* keepers are not part of the outfield shape */ }
                case "DEF" -> defenders++;
                case "MID" -> midfielders++;
                case "WNG" -> {
                    midfielders++;
                    attackers++;
                }
                case "ATT" -> attackers++;
                default -> midfielders++;
            }
        }

        String best = "4-4-2";
        double bestDistance = Double.MAX_VALUE;
        for (String[] candidate : FORMATIONS) {
            double distance = Math.abs(defenders - Integer.parseInt(candidate[1])) * 2.3
                    + Math.abs(midfielders - Integer.parseInt(candidate[2])) * 1.8
                    + Math.abs(attackers - Integer.parseInt(candidate[3])) * 2.0;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate[0];
            }
        }
        return best;
    }

    /**
     * Style from the club id.
     *
     * <p>Division rather than a hash: a hash of a small integer clusters, and dividing by an odd
     * number spreads consecutive ids across all four styles without clustering.
     *
     * <p>Deliberately not influenced by form, results or the manager's own choices. A club that
     * changed its entire philosophy after three results would be unreadable, and it would make
     * the league table depend on the manager's mood.
     */
    static CSPlayStyle styleFor(CSTeam team) {
        long id = team == null || team.getId() == null ? 0L : team.getId();
        int bucket = (int) Math.floorMod(id, 4L);
        return switch (bucket) {
            case 0 -> CSPlayStyle.BALANCED;
            case 1 -> CSPlayStyle.ATTACKING;
            case 2 -> CSPlayStyle.DEFENSIVE;
            default -> CSPlayStyle.COUNTER;
        };
    }

    /** One-line summary for the manager's scouting view. */
    public static String describe(CSTeam team, List<CSPlayer> roster) {
        CSTactics tactics = tacticsFor(team, roster);
        return tactics.getFormation() + " · " + tactics.getStyle();
    }
}