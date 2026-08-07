package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.engine.ZonePositionCalculator;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.TacticRules;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that ball-zone / target-cell conversion is mirrored correctly for the
 * AWAY team. Away progress 0 must mean "own goal" (x near 90) and progress 4 must
 * mean "opponent goal" (x near 10) — the exact mirror of HOME (progress 0 = x near
 * 10, progress 4 = x near 90).
 */
public class AwayZoneConversionTest {

    private Player wng() {
        Player p = new Player();
        p.setId(100L);
        p.setName("Test WNG");
        p.setPosition(Position.WNG);
        Skills s = new Skills();
        s.setPace(15);
        s.setTechnique(15);
        s.setPassing(15);
        s.setStamina(15);
        p.setSkills(s);
        return p;
    }

    private TacticRules rules() {
        List<String> slots = ZonePositionCalculator.buildSlotKeys("4-3-3", null);
        return MatchOrchestrator.generateDefaultTactics(slots);
    }

    private double awayTargetX(int ballXBand, int ballYBand, boolean inPoss, String slot) {
        double[] t = ZonePositionCalculator.tacticalTarget(wng(), "AWAY", inPoss,
            ballXBand, ballYBand, slot, rules());
        return t[0];
    }

    private double homeTargetX(int ballXBand, int ballYBand, boolean inPoss, String slot) {
        double[] t = ZonePositionCalculator.tacticalTarget(wng(), "HOME", inPoss,
            ballXBand, ballYBand, slot, rules());
        return t[0];
    }

    @Test
    void awayWngDefendsDeepWhenBallIsInOwnBox() {
        // Ball physically at x~90 (away keeper) -> away ball cell progress 0.
        // Defending WNG must drop back into his own half, NOT stand at x=10.
        double x = awayTargetX(4, 2, false, "WL");
        assertTrue(x > 50, "away WNG should defend deep (x=" + x + "), not x<50");
    }

    @Test
    void awayWngAttacksWhenBallIsAtOpponentGoal() {
        // Ball physically at x~10 (home keeper) -> away ball cell progress 4.
        // Attacking WNG must be at the very end of the attack (x near 10).
        double x = awayTargetX(0, 2, true, "WL");
        assertTrue(x < 50, "away WNG should be at attacking end (x=" + x + "), not x>50");
    }

    @Test
    void homeWngMirrorsAway() {
        // HOME: ball at own box (x~10) -> defending target in own half.
        assertTrue(homeTargetX(0, 2, false, "WL") < 50, "home WNG defends near own half");
        // HOME: ball at away goal (x~90) -> attacking target at attacking end.
        assertTrue(homeTargetX(4, 2, true, "WL") > 50, "home WNG attacks at far end");
    }

    @Test
    void awayAttackEndAndDefenseEndAreOpposite() {
        // Away target with ball at away's goal must be far right; with ball at
        // home's goal must be far left — the two ends of the pitch.
        double defend = awayTargetX(4, 2, false, "WL");
        double attack = awayTargetX(0, 2, true, "WL");
        assertTrue(attack < defend,
            "away attacking end (x=" + attack + ") should be left of defending end (x=" + defend + ")");
    }
}
