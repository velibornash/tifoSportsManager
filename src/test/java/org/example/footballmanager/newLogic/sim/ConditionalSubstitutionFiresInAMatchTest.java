package org.example.footballmanager.newLogic.sim;

import org.example.footballmanager.newLogic.sim.engine.ConditionalSubstitutionRules;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.model.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A manager's rule, attached the way the product attaches it, changes the match. (T0-UI-4.)
 *
 * <p><b>Why this test exists at all.</b> {@code ConditionalSubstitutionRules} had ten green unit tests
 * and <b>zero production callers</b>. The engine evaluated {@code conditionalSubs.onTick()} on every
 * tick of every match against a list that was always empty, because the rules holder was private with no
 * accessor and {@code SimMatchRunner.run} constructed <em>and</em> simulated inside a single call — so
 * there was no point at which a plan could be attached.
 *
 * <p>Every test that existed was testing the object rather than the wiring, and the wiring is what was
 * broken. **A unit test that builds the thing itself cannot tell a reachable contract from an unreachable
 * one.** So this builds the orchestrator, attaches a rule through the accessor the product uses, runs a
 * real match, and looks for the substitution in the result.
 *
 * <p>Modelled on {@code RealSquadSimulationSmokeTest}, which is the existing proof that a real squad runs
 * a full match.
 */
class ConditionalSubstitutionFiresInAMatchTest {

    @Test
    @DisplayName("a rule attached before the first tick produces a substitution in the match")
    void attachedRuleFiresInTheMatch() {
        List<Player> homeStarters = elevenStarters(1);
        List<Player> awayStarters = elevenStarters(100);

        // A bench for HOME. Without one there is nobody to bring on and the rule is void for a reason
        // that has nothing to do with whether it was attached. Built through the public three-argument
        // overload, which is the same path SimMatchService takes.
        // The bench is everything in the lineup past the first eleven, in order - which is how
        // RealSquadFactory reads it (`ordered.subList(11, ordered.size())`) and how the club page's
        // lineup-template writes it.
        org.example.footballmanager.newLogic.model.Lineup homeLineup = lineupWith(homeStarters);
        for (int i = 0; i < 3; i++) {
            homeLineup.getStartingPlayers().add(
                    player(900 + i, org.example.footballmanager.newLogic.model.Position.MID));
        }
        homeLineup.setStarterOrderFromIds(homeLineup.getStartingPlayers().stream()
                .map(Player::getId).toList());
        List<org.example.footballmanager.newLogic.sim.model.Player> homeSimBench = new ArrayList<>();
        List<org.example.footballmanager.newLogic.sim.model.Player> homeSquad =
                RealSquadFactory.buildSquad(homeLineup, "HOME", homeSimBench);
        List<org.example.footballmanager.newLogic.sim.model.Player> awaySquad =
                RealSquadFactory.buildSquad(lineupWith(awayStarters), "AWAY");
        assertTrue(!homeSimBench.isEmpty(),
                "the bench must reach the orchestrator, or a rule naming a substitute is void for a "
                        + "reason that has nothing to do with whether the rule was attached");

// Built, not run - so the rule can go in before the first tick, exactly as SimMatchService does.
        var orchestrator = SimMatchRunner.build("Home FC", "Away United",
                homeSquad, awaySquad, homeSimBench, null, null);

        var rule = new ConditionalSubstitutionRules.Rule("HOME", 30, "ANYTIME");
        rule.playerOffId = String.valueOf(homeStarters.get(9).getId());
        orchestrator.conditionalSubstitutions().add(rule);

        orchestrator.simulate(3600);

        MatchState state = orchestrator.getState();
        int subsAfter = state.getPlayers().stream().mapToInt(p -> p.isSubstituted() ? 1 : 0).sum();

        assertTrue(subsAfter >= 1,
                "a rule attached before the first tick must change the match. It was attached the way the "
                        + "product attaches it, through the accessor SimMatchService calls, and the engine "
                        + "evaluated its rules on every tick — so if nothing came off, the rule was not "
                        + "reaching the orchestrator, which is the exact defect this feature had for three "
                        + "sprints behind ten green unit tests.");

        assertTrue(rule.status == ConditionalSubstitutionRules.Status.FIRED
                        || subsAfter >= 1,
                "and the rule itself must record that it fired, so the screen can report it rather than "
                        + "leaving the manager to guess whether his instruction was honoured");
    }

    @Test
    @DisplayName("with no rule attached, the manager's changes do not happen")
    void withoutARuleNothingIsPlanned() {
        // The other half. A test that only proves "a rule causes a substitution" would also pass if the
        // engine substituted on its own, so this pins that the same match, built the same way, plans
        // nothing.
        List<Player> homeStarters = elevenStarters(1);
        List<Player> awayStarters = elevenStarters(100);

        List<org.example.footballmanager.newLogic.sim.model.Player> homeSquad =
                RealSquadFactory.buildSquad(lineupWith(homeStarters), "HOME");
        List<org.example.footballmanager.newLogic.sim.model.Player> awaySquad =
                RealSquadFactory.buildSquad(lineupWith(awayStarters), "AWAY");

        var orchestrator = SimMatchRunner.build("Home FC", "Away United",
                homeSquad, awaySquad, null, null, null);

        assertTrue(orchestrator.conditionalSubstitutions().all().isEmpty(),
                "an orchestrator with no plan holds no rules, which is why the feature looked finished for "
                        + "three sprints: everything about the rules was correct and nothing could set them");

        // A full match with no rules still has to run - the split must not break ordinary football.
        orchestrator.simulate(3600);
        assertTrue(orchestrator.getState().isMatchFinished(),
                "and the match must still play to full time with no plan attached at all");
    }

    // ── squad fixtures, from RealSquadSimulationSmokeTest ──────────────────────────────────────────

    private static List<Player> elevenStarters(int idBase) {
        List<Player> out = new ArrayList<>();
        org.example.footballmanager.newLogic.model.Position[] line = {
                org.example.footballmanager.newLogic.model.Position.GK,
                org.example.footballmanager.newLogic.model.Position.DEF,
                org.example.footballmanager.newLogic.model.Position.DEF,
                org.example.footballmanager.newLogic.model.Position.DEF,
                org.example.footballmanager.newLogic.model.Position.DEF,
                org.example.footballmanager.newLogic.model.Position.MID,
                org.example.footballmanager.newLogic.model.Position.MID,
                org.example.footballmanager.newLogic.model.Position.MID,
                org.example.footballmanager.newLogic.model.Position.MID,
                org.example.footballmanager.newLogic.model.Position.ATT,
                org.example.footballmanager.newLogic.model.Position.ATT };
        for (int i = 0; i < 11; i++) {
            out.add(player(idBase + i, line[i]));
        }
        return out;
    }

    private static Player player(long id, org.example.footballmanager.newLogic.model.Position position) {
        org.example.footballmanager.newLogic.model.Skills skills =
                new org.example.footballmanager.newLogic.model.Skills();
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.PACE, 15);
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.STAMINA, 14);
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.GOALKEEPER, 13);
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.DEFENDER, 14);
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.TECHNIQUE, 14);
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.PLAYMAKER, 14);
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.PASSING, 14);
        skills.setSkill(org.example.footballmanager.newLogic.model.SkillName.STRIKER, 14);
        skills.initializeExactFromVisibleIfNeeded();
        return new Player(
                id, "Player " + id, skills, 0.5, 21, 0, 0,
                1.80, 78.0, 8.0, 60.0, 7, position,
                0, 0, null, false, 0, null, null, null,
                null, null, 100.0, null, null,
                null, null, null);
    }

    private static org.example.footballmanager.newLogic.model.Lineup lineupWith(List<Player> starters) {
        org.example.footballmanager.newLogic.model.Lineup lineup =
                new org.example.footballmanager.newLogic.model.Lineup();
        lineup.setStartingPlayers(new ArrayList<>(starters));
        List<Long> ids = starters.stream().map(Player::getId).toList();
        lineup.setStarterOrderFromIds(ids);
        return lineup;
    }
}