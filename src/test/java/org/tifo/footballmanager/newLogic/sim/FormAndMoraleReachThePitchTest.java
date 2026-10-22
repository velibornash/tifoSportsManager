package org.tifo.footballmanager.newLogic.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.Position;
import org.tifo.footballmanager.newLogic.model.SkillName;
import org.tifo.footballmanager.newLogic.model.Skills;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A player's form and morale reach the pitch — and only a little.
 *
 * <p><b>What was wrong.</b> Until 2026-10-22 the form and the morale were written, read,
 * displayed, and had <b>no effect on the football at all</b>. The engine read a player's eight
 * skills and nothing else: a player in the form of his life and a miserable one were identical on
 * the pitch. The form decided how he was <em>rated</em> and whether he was <em>picked</em>; the
 * morale decided how fast he <em>trained</em>. Neither touched a pass.
 *
 * <p><b>Why the funnel.</b> {@code RealSquadFactory} is documented as <em>"the single point where a
 * real player's ability becomes the engine's ability"</em>, so a factor applied there cannot be
 * bypassed by any other path into a match.
 *
 * <p><b>Why small.</b> The owner: <em>"ne pre-dramaticno ali uticu"</em> — not over-dramatic, but
 * they affect. So a player at the top of the form scale is a few percent better and a player in a
 * bad run a few percent worse, and the two are added rather than multiplied, so the worst case in a
 * real world is around −6%.
 */
class FormAndMoraleReachThePitchTest {

    private static final double SKILL = 12.0;

    @Test
    @DisplayName("a player in peak form is given more ability than one in a bad run")
    void theFormReachesTheEngine() {
        Player hot = player(1L, "Hot", Position.ATT, 10.0, 80.0);
        Player cold = player(2L, "Cold", Position.ATT, 2.0, 30.0);

        List<org.tifo.footballmanager.newLogic.sim.model.Player> squad =
                RealSquadFactory.buildSquadFromPlayers(squadOf(hot, cold), "HOME", 1.0, "4-4-2");

        org.tifo.footballmanager.newLogic.sim.model.Player hotSim = sim(squad, 1L);
        org.tifo.footballmanager.newLogic.sim.model.Player coldSim = sim(squad, 2L);

        assertTrue(hotSim.getSkills().striker() > coldSim.getSkills().striker(),
                "the player in peak form must be the better one on the pitch. He is on "
                        + hotSim.getSkills().striker() + " and the other on "
                        + coldSim.getSkills().striker() + ".");
    }

    @Test
    @DisplayName("the factor is the player's own, and it is small")
    void theFactorIsThePlayersOwn() {
        Player hot = player(1L, "Hot", Position.ATT, 10.0, 80.0);
        Player cold = player(2L, "Cold", Position.ATT, 2.0, 30.0);

        List<org.tifo.footballmanager.newLogic.sim.model.Player> squad =
                RealSquadFactory.buildSquadFromPlayers(squadOf(hot, cold), "HOME", 1.0, "4-4-2");

        assertEquals(SKILL * hot.performanceModifier(), sim(squad, 1L).getSkills().striker(), 0.0001,
                "the engine's ability is the player's own ability times his own factor");
        assertEquals(SKILL * cold.performanceModifier(), sim(squad, 2L).getSkills().striker(), 0.0001,
                "and the same for the other one");
    }

    @Test
    @DisplayName("the effect is a few percent, not a rewrite")
    void theEffectIsSmall() {
        Player best = player(1L, "Best", Position.ATT, 10.0, 100.0);
        Player worst = player(2L, "Worst", Position.ATT, 0.0, 0.0);
        Player normal = player(3L, "Normal", Position.ATT, 6.0, 60.0);

        assertEquals(1.0, normal.performanceModifier(), 0.0001,
                "a player at the natural form and the neutral morale is exactly himself — the factor "
                        + "must not move anybody who is doing nothing in particular");
        assertTrue(best.performanceModifier() <= 1.10 && best.performanceModifier() > 1.0,
                "the best case is a few percent better, not a different player. He is on "
                        + best.performanceModifier() + ".");
        assertTrue(worst.performanceModifier() >= 0.90 && worst.performanceModifier() < 1.0,
                "and the worst case a few percent worse. He is on " + worst.performanceModifier() + ".");
    }

    @Test
    @DisplayName("the morale moves it as well as the form")
    void theMoraleMovesItToo() {
        Player content = player(1L, "Content", Position.ATT, 6.0, 95.0);
        Player miserable = player(2L, "Miserable", Position.ATT, 6.0, 5.0);

        assertTrue(content.performanceModifier() > miserable.performanceModifier(),
                "at the same form, the content player is the better one: "
                        + content.performanceModifier() + " against "
                        + miserable.performanceModifier() + ".");
    }

    // ── a squad of this test's own ─────────────────────────────────────────────────────────────────

    /** Eleven players, so the factory has a squad to build. */
    private List<Player> squadOf(Player... specials) {
        List<Player> players = new ArrayList<>(List.of(specials));
        for (int i = players.size(); i < 11; i++) {
            players.add(player(100L + i, "Filler " + i, Position.MID, 6.0, 60.0));
        }
        return players;
    }

    private org.tifo.footballmanager.newLogic.sim.model.Player sim(
            List<org.tifo.footballmanager.newLogic.sim.model.Player> squad, Long id) {
        return squad.stream()
                .filter(p -> String.valueOf(id).equals(p.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no player " + id + " in the squad"));
    }

    private Player player(Long id, String name, Position position, double form, double morale) {
        Skills skills = new Skills();
        for (SkillName skill : SkillName.values()) {
            skills.setSkill(skill, (int) SKILL);
        }
        skills.initializeExactFromVisibleIfNeeded();

        Player player = new Player();
        player.setId(id);
        player.setName(name);
        player.setPosition(position);
        player.setSkills(skills);
        player.setForm(form);
        player.setMorale(morale);
        return player;
    }
}
