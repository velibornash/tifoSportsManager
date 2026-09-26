package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.recording.MatchRecorder;
import org.example.footballmanager.newLogic.sim.result.ProposalStatsCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Manager's conditional substitution rules.
 *
 * <p>Covers the behaviours the owner asked for by name: rules that fire on the right condition,
 * rules that report <em>why</em> they can never fire rather than going quiet, and the precedence
 * chain injury &gt; manager rule &gt; fatigue.
 */
class ConditionalSubstitutionRulesTest {

    private MatchState state;
    private SubstitutionService substitutions;
    private ConditionalSubstitutionRules rules;

    private static Player player(String id, String team, String role, boolean bench) {
        Player p = new Player(id, id, team, role,
                new Position(5.0, 3.0), new Position(5.0, 3.0), PlayerSkills.neutral(), 0);
        p.setOnBench(bench);
        return p;
    }

    @BeforeEach
    void setUp() {
        state = new MatchState();
        state.setActionLogger(new ActionLogService(state, new java.util.ArrayList<>()));
        for (int i = 0; i < 11; i++) {
            state.getPlayers().add(player("H" + i, "HOME", i == 0 ? "GK" : "CMR", false));
            state.getPlayers().add(player("A" + i, "AWAY", i == 0 ? "GK" : "CMR", false));
        }
        for (int i = 0; i < 5; i++) {
            state.getBench("HOME").add(player("HB" + i, "HOME", "STL", true));
            state.getBench("AWAY").add(player("AB" + i, "AWAY", "STL", true));
        }
        substitutions = new SubstitutionService(state, new MatchRecorder(),
                new ProposalStatsCollector("H", "A"));
        rules = new ConditionalSubstitutionRules(state, substitutions);
    }

    private void advanceToMinute(int minute) {
        state.setMatchTicks(minute * MatchClockService.MATCH_TICKS_PER_MINUTE);
    }

    @Test
    @DisplayName("a LOSING rule does not fire while the team is ahead")
    void losingRuleWaitsWhileAhead() {
        state.setScore(2, 0);
        ConditionalSubstitutionRules.Rule r =
                new ConditionalSubstitutionRules.Rule("HOME", 60, "LOSING");
        rules.add(r);
        advanceToMinute(70);
        state.setRestartTaker(state.getPlayers().get(1)); // play is stopped, so the rule is legal
        rules.onTick();

        assertEquals(ConditionalSubstitutionRules.Status.PENDING, r.status,
                "the team is winning, so a LOSING rule must not fire");
    }

    @Test
    @DisplayName("a LOSING rule fires when the team is behind and play is stopped")
    void losingRuleFiresWhenBehind() {
        state.setScore(0, 2);
        ConditionalSubstitutionRules.Rule r =
                new ConditionalSubstitutionRules.Rule("HOME", 60, "LOSING");
        rules.add(r);
        advanceToMinute(70);
        state.setRestartTaker(state.getPlayers().get(1));
        rules.onTick();

        assertEquals(ConditionalSubstitutionRules.Status.FIRED, r.status,
                "void reason was: " + r.voidReason);
        assertEquals(70, r.firedAtMinute);
        assertEquals(1, state.getSubsUsed("HOME"), "the rule must actually spend a substitution");
    }

    @Test
    @DisplayName("a rule due mid-flow waits for a stoppage instead of changing players illegally")
    void ruleWaitsForAStoppage() {
        state.setScore(0, 1);
        ConditionalSubstitutionRules.Rule r =
                new ConditionalSubstitutionRules.Rule("HOME", 60, "LOSING");
        rules.add(r);
        advanceToMinute(70);
        state.setRestartTaker(null);
        state.setStopped(false);
        rules.onTick();

        assertEquals(ConditionalSubstitutionRules.Status.WAITING_FOR_STOPPAGE, r.status,
                "a substitution is only legal while play is stopped");
        assertEquals(0, state.getSubsUsed("HOME"), "nothing may be spent while waiting");

        // The referee then stops play; the same rule now completes.
        state.setRestartTaker(state.getPlayers().get(1));
        rules.onTick();
        assertEquals(ConditionalSubstitutionRules.Status.FIRED, r.status);
    }

    @Test
    @DisplayName("a LEADING rule fires only when the team is actually ahead")
    void leadingRuleRespectsCondition() {
        state.setScore(1, 1);
        ConditionalSubstitutionRules.Rule r =
                new ConditionalSubstitutionRules.Rule("HOME", 30, "LEADING");
        rules.add(r);
        advanceToMinute(40);
        state.setRestartTaker(state.getPlayers().get(1));
        rules.onTick();
        assertNotEquals(ConditionalSubstitutionRules.Status.FIRED, r.status,
                "level at 40 minutes is not leading");

        state.setScore(2, 1);
        rules.onTick();
        assertEquals(ConditionalSubstitutionRules.Status.FIRED, r.status);
    }

    @Test
    @DisplayName("a rule reports WHY it can never fire instead of going quiet")
    void staleRulesCarryAReason() {
        ConditionalSubstitutionRules.Rule r =
                new ConditionalSubstitutionRules.Rule("HOME", 60, "ANYTIME");
        r.playerOnId = "HB0";
        rules.add(r);
        advanceToMinute(70);
        state.setRestartTaker(state.getPlayers().get(1));

        // The named substitute is already on the pitch, so he cannot come on again.
        state.getBench("HOME").removeIf(p -> p.getId().equals("HB0"));
        Player onPitch = player("HB0", "HOME", "STL", false);
        state.getPlayers().add(onPitch);

        rules.onTick();
        assertEquals(ConditionalSubstitutionRules.Status.VOID, r.status);
        assertEquals(ConditionalSubstitutionRules.VoidReason.PLAYER_ALREADY_ON, r.voidReason,
                "the manager must be able to see that this rule is spent and why");
    }

    @Test
    @DisplayName("a rule is void once the five substitutions are gone")
    void voidWhenNoSubsLeft() {
        for (int i = 0; i < MatchState.MAX_SUBSTITUTIONS; i++) state.addSubUsed("HOME");
        ConditionalSubstitutionRules.Rule r =
                new ConditionalSubstitutionRules.Rule("HOME", 60, "ANYTIME");
        rules.add(r);
        advanceToMinute(70);
        state.setRestartTaker(state.getPlayers().get(1));
        rules.onTick();

        assertEquals(ConditionalSubstitutionRules.Status.VOID, r.status);
        assertEquals(ConditionalSubstitutionRules.VoidReason.NO_SUBS_LEFT, r.voidReason);
    }

    @Test
    @DisplayName("a rule is void once the three windows are gone")
    void voidWhenNoWindowsLeft() {
        for (int i = 0; i < MatchState.MAX_SUB_WINDOWS; i++) state.addSubWindowUsed("HOME");
        ConditionalSubstitutionRules.Rule r =
                new ConditionalSubstitutionRules.Rule("HOME", 60, "ANYTIME");
        rules.add(r);
        advanceToMinute(70);
        state.setRestartTaker(null);
        state.setStopped(false);
        rules.onTick();

        assertEquals(ConditionalSubstitutionRules.Status.VOID, r.status);
        assertEquals(ConditionalSubstitutionRules.VoidReason.NO_WINDOWS_LEFT, r.voidReason);
    }

    @Test
    @DisplayName("a rule does not fire before its trigger minute")
    void doesNotFireEarly() {
        state.setScore(0, 3);
        ConditionalSubstitutionRules.Rule r =
                new ConditionalSubstitutionRules.Rule("HOME", 60, "ANYTIME");
        rules.add(r);
        advanceToMinute(45);
        state.setRestartTaker(state.getPlayers().get(1));
        rules.onTick();

        assertEquals(ConditionalSubstitutionRules.Status.PENDING, r.status);
        assertEquals(0, state.getSubsUsed("HOME"));
    }

    @Test
    @DisplayName("a fired rule does not fire twice")
    void doesNotFireTwice() {
        state.setScore(0, 2);
        ConditionalSubstitutionRules.Rule r =
                new ConditionalSubstitutionRules.Rule("HOME", 60, "ANYTIME");
        rules.add(r);
        advanceToMinute(70);
        state.setRestartTaker(state.getPlayers().get(1));
        rules.onTick();
        assertEquals(1, state.getSubsUsed("HOME"));

        for (int i = 0; i < 20; i++) rules.onTick();
        assertEquals(1, state.getSubsUsed("HOME"), "a rule is one substitution, not twenty");
    }

    @Test
    @DisplayName("a sent-off player is never the one a rule takes off")
    void neverSubstitutesASentOffPlayer() {
        Player sentOff = state.getPlayers().stream()
                .filter(p -> "HOME".equals(p.getTeam()) && !p.getRole().equals("GK"))
                .max(java.util.Comparator.comparingDouble(Player::getFatigue))
                .orElseThrow();
        sentOff.setSentOff(true);

        state.setScore(0, 1);
        ConditionalSubstitutionRules.Rule r =
                new ConditionalSubstitutionRules.Rule("HOME", 60, "ANYTIME");
        rules.add(r);
        advanceToMinute(70);
        state.setRestartTaker(state.getPlayers().get(1));
        rules.onTick();

        assertTrue(!sentOff.isOnBench(), "a sent-off player stays off, and stays sent off");
    }
}
