package org.example.footballtextmanager.engine;

import org.example.footballtextmanager.model.CSPlayer;
import org.example.footballtextmanager.model.CSTrainingFocus;

import java.util.List;
import java.util.Random;

/**
 * Squad development between rounds.
 *
 * <p>Deliberately small. The brief for this mode was "do not overbuild match preparation, but the
 * basic skills should be able to move a result" — and skills already feed
 * {@code CSMatchSimulator.getPositionalSkill}. What was missing was any way to move them: no code
 * path ever raised a skill after seeding, so every squad was frozen at whatever the seeder rolled
 * on the first boot and the Golden Boot was the only progression in the game.
 *
 * <p>Three rules keep it honest rather than generous:
 *
 * <ul>
 *   <li><b>Age decides how much is left to learn.</b> A 19-year-old gains several times what a 29
 *       year-old does, and nothing at all past {@link #LEARNING_AGE_LIMIT}. Without this a manager
 *       could grind a 34-year-old up to the same level as the academy.</li>
 *   <li><b>Diminishing returns near the ceiling.</b> Skills sit on a 1–20 scale, and the gain
 *       shrinks as a skill approaches the top. Without it, three seasons of any focus would push
 *       every attribute to 20 and flatten the differences between players.</li>
 *   <li><b>Position decides what the focus reaches.</b> An attack session does not much for a
 *       centre-back, so the emphasis is weighted by role instead of sprayed uniformly.</li>
 * </ul>
 *
 * <p>Rates are fractions of a point per round and are rounded to whole points, because the stored
 * scale is integral. The effect is deliberately slow: a season of consistent focus is worth a few
 * points, which is visible in a rating and not enough to erase a scouting mistake.
 */
public class CSTrainer {

    /** Nothing is learned past this age. */
    private static final int LEARNING_AGE_LIMIT = 30;

    /** Skills live on a 1–20 scale; gains vanish as a skill approaches this. */
    private static final int SKILL_CEILING = 20;

    /** Largest single-round gain, in whole skill points, for the best possible case. */
    private static final double MAX_GAIN_PER_ROUND = 2.0;

    private final Random rnd;

    public CSTrainer() {
        this(new Random());
    }

    public CSTrainer(Random rnd) {
        this.rnd = rnd;
    }

    /**
     * Applies one round of development and reports how many players moved.
     *
     * @return number of players whose rating actually changed, so the caller can put one honest
     *         line in the inbox instead of claiming a development for players who sat out
     */
    public int applyRound(List<CSPlayer> squad, CSTrainingFocus focus) {
        if (squad == null || squad.isEmpty()) return 0;

        CSTrainingFocus chosen = focus == null ? CSTrainingFocus.BALANCED : focus;
        int improved = 0;

        for (CSPlayer player : squad) {
            if (player == null) continue;
            int before = player.getRating();

            applyFocus(player, chosen);
            applyMaturation(player);

            player.setRating(player.calculateRating());
            if (player.getRating() != before) improved++;
        }
        return improved;
    }

    private void applyFocus(CSPlayer player, CSTrainingFocus focus) {
        String position = player.getPosition() == null ? "MID" : player.getPosition();
        boolean keeper = "GK".equals(position);

        switch (focus) {
            case ATTACK -> {
                // Forwards and wingers feel an attack session most; a keeper barely.
                int weight = switch (position) {
                    case "ATT" -> 10;
                    case "WNG" -> 8;
                    case "MID" -> 6;
                    case "DEF" -> 3;
                    default -> 1;
                };
                gain(player, "pace", weight);
                gain(player, "shooting", weight);
                gain(player, "technique", weight);
            }
            case DEFENCE -> {
                int weight = switch (position) {
                    case "DEF" -> 10;
                    case "MID" -> 6;
                    case "GK" -> 8;
                    case "WNG" -> 3;
                    default -> 2;
                };
                gain(player, "defending", weight);
                gain(player, "stamina", weight);
                if (keeper) gain(player, "goalkeeper", 10);
            }
            case CREATION -> {
                int weight = switch (position) {
                    case "MID" -> 10;
                    case "WNG" -> 8;
                    case "ATT" -> 5;
                    case "DEF" -> 4;
                    default -> 1;
                };
                gain(player, "passing", weight);
                gain(player, "playmaker", weight);
                gain(player, "technique", Math.max(2, weight - 2));
            }
            case BALANCED -> {
                // A thin spread across everything: the slow, safe default.
                gain(player, "pace", 3);
                gain(player, "passing", 3);
                gain(player, "technique", 3);
                gain(player, "defending", 3);
                gain(player, "stamina", 3);
            }
        }
    }

    /**
     * A player still gets a little polish from match experience regardless of the focus, so a
     * squad never stops developing simply because the manager set BALANCED.
     */
    private void applyMaturation(CSPlayer player) {
        gain(player, "stamina", 2);
    }

    /**
     * One training step for a single attribute.
     *
     * @param weight 1–10; how much this focus reaches this player. Zero means "untouched".
     */
    private void gain(CSPlayer player, String attribute, int weight) {
        if (weight <= 0) return;

        double learnable = learnableFactor(player.getAge());
        if (learnable <= 0) return;

        int current = read(player, attribute);
        if (current >= SKILL_CEILING) return;

        // Diminishing returns: the last points are the hardest.
        double headroom = (SKILL_CEILING - current) / (double) SKILL_CEILING;

        double delta = learnable * (weight / 10.0) * headroom * MAX_GAIN_PER_ROUND;
        // Round, and only then apply, so a tiny delta cannot quietly accumulate as a fraction.
        int whole = (int) Math.floor(delta);
        if (whole <= 0) {
            // Below one point. Award it only sometimes, so low-value growth is not free but
            // not automatic either.
            if (rnd.nextDouble() < delta) whole = 1;
        }
        if (whole <= 0) return;

        write(player, attribute, Math.min(SKILL_CEILING, current + whole));
    }

    /** 1.0 at 18, tapering to 0 at {@link #LEARNING_AGE_LIMIT}. */
    private static double learnableFactor(int age) {
        if (age >= LEARNING_AGE_LIMIT) return 0.0;
        if (age <= 18) return 1.0;
        return (double) (LEARNING_AGE_LIMIT - age) / (LEARNING_AGE_LIMIT - 18);
    }

    private static int read(CSPlayer player, String attribute) {
        return switch (attribute) {
            case "pace" -> player.getPace();
            case "passing" -> player.getPassing();
            case "shooting" -> player.getShooting();
            case "technique" -> player.getTechnique();
            case "playmaker" -> player.getPlaymaker();
            case "defending" -> player.getDefending();
            case "stamina" -> player.getStamina();
            case "goalkeeper" -> player.getGoalkeeper();
            default -> 0;
        };
    }

    private static void write(CSPlayer player, String attribute, int value) {
        switch (attribute) {
            case "pace" -> player.setPace(value);
            case "passing" -> player.setPassing(value);
            case "shooting" -> player.setShooting(value);
            case "technique" -> player.setTechnique(value);
            case "playmaker" -> player.setPlaymaker(value);
            case "defending" -> player.setDefending(value);
            case "stamina" -> player.setStamina(value);
            case "goalkeeper" -> player.setGoalkeeper(value);
            default -> { /* unreachable: read() and write() share one attribute vocabulary */ }
        }
    }
}