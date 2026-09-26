package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.MatchPlayerStats;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Morale and form, moving on what actually happens to a player (Sprint 2.6).
 *
 * <p>{@code Player.form} was a creation-time constant. It was read by the rating maths as
 * {@code (form − 6) × 1.2} and by the DTO as a flat boost, so a player who had scored four goals in
 * five weeks was exactly as likely to be good on Saturday as a player who had not played, and
 * nothing the manager did could change it.
 *
 * <h2>What moves morale</h2>
 * <ul>
 *   <li><b>Minutes played</b> — being left on the bench is the single biggest demotivator there is.</li>
 *   <li><b>What he did</b> — goals and assists lift; a red card or a missed penalty drops.</li>
 *   <li><b>How the team did</b> — winning lifts everyone, losing drops everyone.</li>
 *   <li><b>Whether he is paid what he is worth</b> — a player on half his market value is not going
 *       to be a leader in the dressing room however good he is on the pitch. This is the thread that
 *       runs from S2.4's board assessment through to here.</li>
 * </ul>
 *
 * <p>Morale is 0-100 and moves slowly; {@code form} is 0-10 and moves fast. That is deliberate and is
 * how the two are used differently: form is a week-to-week swing, morale is a season-long state that
 * decides whether the swing happens at all.
 */
@Service
public class MoraleService {

    /** Morale below this and a player is genuinely unhappy in the dressing room. */
    public static final double UNHAPPY_MORALE = 40.0;
    public static final double CONTENT_MORALE = 65.0;

    private final PlayerRepository players;
    private final MatchPlayerStatsRepository matchStats;

    public MoraleService(PlayerRepository players, MatchPlayerStatsRepository matchStats) {
        this.players = players;
        this.matchStats = matchStats;
    }

    /**
     * Applies one match to a player's morale and form.
     *
     * @param player        the player
     * @param minutes       minutes played
     * @param goals         goals scored
     * @param assists       assists
     * @param rating        match rating, 0-10
     * @param teamWon      whether his side won
     * @param teamDrew      whether his side drew
     */
    @Transactional
    public void applyMatch(Player player, int minutes, int goals, int assists,
                           double rating, boolean teamWon, boolean teamDrew) {
        if (player == null) return;

        double morale = moraleOf(player);
        double form = formOf(player);

        // --- minutes ---
        if (minutes >= 75) {
            morale += 1.0;
            form += 0.6;
        } else if (minutes >= 45) {
            morale += 0.4;
            form += 0.2;
        } else if (minutes > 0) {
            // A token appearance is nearly as bad as none.
            morale -= 2.5;
            form -= 1.0;
        } else {
            morale -= 6.0;
            form -= 1.8;
        }

        // --- what he did ---
        morale += goals * 2.5 + assists * 1.5;
        form += goals * 0.7 + assists * 0.4;
        if (goals == 0 && assists == 0 && minutes >= 60) {
            form -= 0.5;
        }

        // --- how the team did ---
        if (teamWon) {
            morale += 2.0;
            form += 0.5;
        } else if (teamDrew) {
            morale += 0.3;
        } else {
            morale -= 1.8;
            form -= 0.4;
        }

        // --- his own performance ---
        if (rating >= 8.0) {
            morale += 2.0;
            form += 0.8;
        } else if (rating >= 6.5) {
            morale += 0.5;
        } else if (rating > 0 && rating < 5.0) {
            morale -= 2.0;
            form -= 0.7;
        }

        player.setMorale(clamp(morale, 0, 100));
        player.setForm(clamp(form, 0, 10));
    }

    /**
     * Applies the standing concerns — is he being paid fairly, is he in the side, is he being
     * listed. These are the slow pressures that decide a player's mood over a season.
     */
    @Transactional
    public void applyStanding(Player player, boolean starting, boolean listedForTransfer) {
        if (player == null) return;
        double morale = moraleOf(player);

        if (!starting) morale -= 1.2;
        if (listedForTransfer) morale -= 4.0;

        if (isUnderpaid(player)) morale -= 3.0;
        else if (isWellPaid(player)) morale += 1.5;

        player.setMorale(clamp(morale, 0, 100));
    }

    /**
     * Settles a season: form decays toward his natural level and morale drifts toward whatever the
     * season actually earned him.
     */
    @Transactional
    public void endOfSeason(Player player) {
        if (player == null) return;
        // Form is a swing, not a permanent trait. Without this a great run of form is permanent and
        // every player eventually sits at 10.
        player.setForm(6.0);
        setMorale(player, clamp(moraleOf(player) * 0.6 + 40 * 0.4, 0, 100));
    }

    /** Whether this player would agitate for a move. */
    public boolean isUnhappy(Player player) {
        return player != null && moraleOf(player) < UNHAPPY_MORALE;
    }

    /**
     * How much this player's morale is worth on the pitch, 0.90 to 1.10.
     *
     * <p>Deliberately narrow. Morale that swings a player's finishing by 30% turns a bad run into a
     * feedback loop where a player who misses once stops scoring, which stops him being picked,
     * which makes him miss more. A narrow band makes morale a real but gentle effect.
     */
    public double moraleModifier(Player player) {
        if (player == null) return 1.0;
        double morale = moraleOf(player);
        return 0.90 + 0.20 * (morale / 100.0);
    }

    private boolean isUnderpaid(Player player) {
        double value = player.getPlayerValue();
        double wage = Math.max(0, player.getEarnings());
        if (value <= 0) return false;
        return wage * 52.0 < value * 0.010;
    }

    private boolean isWellPaid(Player player) {
        double value = player.getPlayerValue();
        double wage = Math.max(0, player.getEarnings());
        if (value <= 0) return false;
        return wage * 52.0 > value * 0.030;
    }

    private double moraleOf(Player player) {
        Double m = player.getMorale();
        return m == null ? 60 : m;
    }

    private double formOf(Player player) {
        double f = player.getForm();
        return f <= 0 ? 6.0 : f;
    }

    private void setMorale(Player player, double value) {
        player.setMorale(value);
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
