package org.example.footballmanager.newLogic.sim;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 4.3 — proof that the head coach's effect actually reaches the engine.
 *
 * <p>Every other coach-related test in this sprint passes happily against a build where the factor
 * is computed and then thrown away, because the rule is right and the wiring is not. That is the
 * exact failure this sprint has hit twice already — a goalkeeping coach who did nothing, a wage that
 * left the account for no effect. So this asserts the outcome at the far end: a good coach's players
 * are measurably better <b>inside the sim</b>, not merely in a field on an entity.
 */
class HeadCoachReachesTheEngineTest {

    private static final int SQUAD = 11;

    private static List<Player> dbSquad(int skill) {
        List<Player> squad = new ArrayList<>();
        Position[] order = {
                Position.GK, Position.DEF, Position.DEF, Position.DEF, Position.DEF,
                Position.MID, Position.MID, Position.MID,
                Position.ATT, Position.ATT, Position.WNG
        };
        for (int i = 0; i < SQUAD; i++) {
            Player p = new Player();
            p.setId((long) (i + 1));
            p.setName("P" + (i + 1));
            p.setPosition(order[i]);
            Skills s = new Skills();
            for (SkillName name : SkillName.values()) {
                s.setSkill(name, skill);
            }
            p.setSkills(s);
            squad.add(p);
        }
        return squad;
    }

    private static double averageDefending(List<org.example.footballmanager.newLogic.sim.model.Player> xi) {
        return xi.stream().mapToDouble(p -> p.getSkills().defender()).average().orElseThrow();
    }

    @Test
    @DisplayName("A club with no head coach produces exactly the players it always did")
    void neutralIsUnchanged() {
        List<org.example.footballmanager.newLogic.sim.model.Player> xi =
                RealSquadFactory.buildSquadFromPlayers(dbSquad(10), "H", 1.0);
        assertEquals(SQUAD, xi.size());
        assertEquals(10.0, averageDefending(xi), 1e-9,
                "a factor of 1.0 changed the squad, so the default path is not neutral");
    }

    @Test
    @DisplayName("A good head coach's players are measurably better inside the simulation")
    void aGoodCoachReachesTheEngine() {
        var neutral = RealSquadFactory.buildSquadFromPlayers(dbSquad(10), "H", 1.0);
        var coached = RealSquadFactory.buildSquadFromPlayers(dbSquad(10), "H", 1.06);

        assertTrue(averageDefending(coached) > averageDefending(neutral),
                "a 6% head coach produced identical players inside the sim - the factor is not applied");
        assertEquals(10.6, averageDefending(coached), 1e-6, "the factor was not applied at full size");
    }

    @Test
    @DisplayName("A bad head coach's players are measurably worse inside the simulation")
    void aBadCoachReachesTheEngine() {
        var neutral = RealSquadFactory.buildSquadFromPlayers(dbSquad(10), "H", 1.0);
        var badlyCoached = RealSquadFactory.buildSquadFromPlayers(dbSquad(10), "H", 0.94);

        assertTrue(averageDefending(badlyCoached) < averageDefending(neutral),
                "a poor head coach improved his players");
    }

    @Test
    @DisplayName("The effect is applied to every skill, not just one")
    void everySkillIsAffected() {
        var neutral = RealSquadFactory.buildSquadFromPlayers(dbSquad(10), "H", 1.0).get(0).getSkills();
        var coached = RealSquadFactory.buildSquadFromPlayers(dbSquad(10), "H", 1.06).get(0).getSkills();

        for (PlayerSkills skills : List.of(neutral, coached)) {
            assertTrue(skills.keeper() > 0);
        }
        assertEquals(neutral.keeper() * 1.06, coached.keeper(), 1e-6);
        assertEquals(neutral.striker() * 1.06, coached.striker(), 1e-6);
        assertEquals(neutral.passing() * 1.06, coached.passing(), 1e-6);
        assertEquals(neutral.playmaking() * 1.06, coached.playmaking(), 1e-6);
        assertEquals(neutral.technique() * 1.06, coached.technique(), 1e-6);
        assertEquals(neutral.pace() * 1.06, coached.pace(), 1e-6);
    }

    @Test
    @DisplayName("A coach cannot push anybody past the top of the scale")
    void theScaleIsStillRespected() {
        var xi = RealSquadFactory.buildSquadFromPlayers(dbSquad(20), "H", 1.06);
        for (var p : xi) {
            for (double value : List.of(p.getSkills().pace(), p.getSkills().striker(),
                    p.getSkills().defender(), p.getSkills().keeper())) {
                assertTrue(value <= 20.0,
                        "a coach pushed a skill to " + value + ", past the top of the scale");
            }
        }
    }

    @Test
    @DisplayName("A nonsensical factor is ignored rather than zeroing a squad")
    void nonsenseFactorIsIgnored() {
        var xi = RealSquadFactory.buildSquadFromPlayers(dbSquad(10), "H", 0.0);
        assertEquals(10.0, averageDefending(xi), 1e-9,
                "a factor of zero produced a squad of zeroes instead of falling back to neutral");
    }
}
