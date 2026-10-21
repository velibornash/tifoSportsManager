package org.tifo.footballmanager.newLogic.dto;

import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.Position;

/**
 * A player as <b>another</b> club's manager may see him (owner, 2026-10-21).
 *
 * <p>The owner asked for a squad screen any manager can open on any club, and named exactly what it
 * shows: <i>"svaki user bi trebalo da moze da klikne na squad za drugi tim samo ga vidi bez skilova,
 * vidi formu, rejting, apps, goals, assists"</i> — form, rating, appearances, goals, assists, and
 * <b>no skills</b>.
 *
 * <h2>Why this is a type of its own and not a {@link PlayerDTO} with fields blanked</h2>
 *
 * <p>{@link PlayerDTO} carries the player's whole skill set as {@code int} and {@code double} fields —
 * pace, shooting, passing, technique, defending, stamina, goalkeeper, playmaker, their exact values,
 * and an {@code overall} derived from them. Primitives cannot hold "unknown", so the only way to hide
 * a skill in that shape is a sentinel like {@code 0}, which is a lie: a real skill can be zero.
 * Making them nullable would change a payload the whole app reads, including three tests that spell
 * the {@link Player} constructor out.
 *
 * <p>A response that <b>has no skill fields</b> cannot leak a skill, whatever a future screen decides
 * to render. That is the property worth having, and it is why the away squad gets its own type rather
 * than a flag on the existing one.
 *
 * <p>Talent is absent for the same reason it is null on {@link PlayerDTO}: it is paid information, and
 * {@code PlusFeatureService.canSee} decides it at the controller. A club's own manager reading his own
 * squad still goes through {@link PlayerDTO}; this type is only ever filled for a club he does not own.
 */
public record PublicSquadMemberDTO(
        Long id,
        String name,
        int age,
        String position,
        double form,
        int rating,
        int appearances,
        int goals,
        int assists,
        Double averageRating,
        boolean injured,
        int injuryDaysRemaining
) {

    /**
     * One player of an away squad, from his own row and his match record.
     *
     * <p>{@code appearances} is the number of statistic rows the club has for him and
     * {@code averageRating} is their mean, which is the same pair the owning manager's squad screen
     * computes — the away view is not a second measurement of the same season, only a narrower one.
     * {@code goals} and {@code assists} are the player's season totals rather than a sum over the
     * statistics rows, because that is the number the owner named and the one the squad screen shows
     * for a manager's own club.
     */
    public static PublicSquadMemberDTO from(Player player, int appearances, Double averageRating) {
        Position position = player.getPositionEnum() != null ? player.getPositionEnum() : Position.MID;
        return new PublicSquadMemberDTO(
                player.getId(),
                player.getName(),
                player.getAge(),
                position.name(),
                player.getForm(),
                player.getRating(),
                appearances,
                player.getTotalGoals(),
                player.getTotalAssists(),
                averageRating,
                player.isInjured(),
                player.getInjuryDaysRemaining());
    }
}
