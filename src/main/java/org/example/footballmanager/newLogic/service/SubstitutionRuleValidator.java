package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Whether a conditional substitution rule can ever fire, and if not, why not. (T1-16.)
 *
 * <p><b>What this is for.</b> {@code PUT /api/sim/fixtures/{id}/substitution-plan} writes
 * {@code String.valueOf(rules)} straight into the plan. The screen's dropdowns mean the UI cannot name
 * somebody who is not in the squad, but the API can, and it accepts anything. The engine then catches it
 * — {@code ConditionalSubstitutionRules} marks the rule {@code VOID} with a
 * {@link org.example.footballmanager.newLogic.sim.engine.ConditionalSubstitutionRules.VoidReason} —
 * <b>during the match</b>, where the manager cannot see it, because nothing reads {@code voidReason}
 * back. The result is a typo that becomes a silently dead instruction, discovered never.
 *
 * <p><b>So this refuses the save.</b> A rule that cannot fire is not a plan; it is a mistake, and the
 * cheapest moment to tell the manager is before kickoff while they are still looking at the screen.
 *
 * <p><b>What is deliberately NOT refused</b>, because the engine treats it as legal:
 * <ul>
 *   <li>an empty {@code playerOnId} — "let the engine choose the best available"</li>
 *   <li>an empty {@code playerOffId} — "let the engine choose"</li>
 * </ul>
 * Both are meaningful instructions, and the board's own note says so. Refusing them would break the
 * feature's main use.
 *
 * <p><b>On the condition.</b> The engine's switch has {@code default -> true}, so an unknown condition
 * silently becomes ANYTIME. That is exactly the class of defect this task exists to remove, so an
 * unrecognised condition is refused rather than quietly reinterpreted.
 */
@Service
public class SubstitutionRuleValidator {

    /** The conditions {@code ConditionalSubstitutionRules.conditionMet} actually handles. */
    static final Set<String> KNOWN_CONDITIONS =
            Set.of("ANYTIME", "LOSING", "DRAWING", "LEADING");

    /** A match runs 90 minutes; a rule past the end can never be reached. */
    static final int MAX_TRIGGER_MINUTE = 90;

    private final SquadRegistrationService squads;

    public SubstitutionRuleValidator(SquadRegistrationService squads) {
        this.squads = squads;
    }

    /**
     * Checks every rule in a plan against the fixture's own teams.
     *
     * @return one rejection per offending rule, empty when the whole plan can fire. Never null.
     */
    public List<Rejection> validate(List<?> rules, MatchFixture fixture) {
        List<Rejection> rejections = new ArrayList<>();
        if (rules == null || rules.isEmpty()) return rejections;

        // The squad is fetched once per team, not once per rule: a five-rule plan is one fixture and
        // the same two clubs, and this runs on a request path.
        Map<String, Set<Long>> squadIds = new java.util.HashMap<>();
        Map<String, String> teamNames = new java.util.HashMap<>();
        Map<String, String> sideToKey = new java.util.HashMap<>();
        Team home = fixture == null ? null : fixture.getHomeTeam();
        Team away = fixture == null ? null : fixture.getAwayTeam();
        if (home != null) sideToKey.put("HOME", key(home));
        if (away != null) sideToKey.put("AWAY", key(away));
        for (Team team : List.of(home, away)) {
            if (team == null) continue;
            String key = key(team);
            teamNames.put(key, team.getName());
            if (!squadIds.containsKey(key)) {
                squadIds.put(key, idsInSquad(team));
            }
        }

        int index = 0;
        for (Object raw : rules) {
            index++;
            if (!(raw instanceof Map<?, ?> rule)) {
                rejections.add(new Rejection(index, "NOT_A_RULE",
                        "Rule " + index + " is not a substitution instruction."));
                continue;
            }
            rejections.addAll(checkOne(index, rule, squadIds, teamNames, sideToKey));
        }
        return rejections;
    }

    private List<Rejection> checkOne(int index, Map<?, ?> rule,
                                     Map<String, Set<Long>> squadIds,
                                     Map<String, String> teamNames,
                                     Map<String, String> sideToKey) {
        List<Rejection> out = new ArrayList<>();

        String team = text(rule.get("team"));
        String teamKey = keyFor(team, teamNames, sideToKey);
        if (teamKey == null) {
            out.add(new Rejection(index, "UNKNOWN_TEAM",
                    "Rule " + index + " names team '" + (team.isEmpty() ? "(none)" : team)
                            + "', which is not a side of this fixture. Use HOME or AWAY."));
            // Every remaining check is about a squad, and there is no squad to check against.
            return out;
        }
        Set<Long> squad = squadIds.getOrDefault(teamKey, Set.of());

        // -- the trigger minute ---------------------------------------------------------------
        Integer minute = number(rule.get("triggerMinute"));
        if (minute == null) {
            out.add(new Rejection(index, "NO_TRIGGER_MINUTE",
                    "Rule " + index + " has no trigger minute, so it can never fire."));
        } else if (minute < 0 || minute > MAX_TRIGGER_MINUTE) {
            out.add(new Rejection(index, "MINUTE_OUT_OF_RANGE",
                    "Rule " + index + " triggers at minute " + minute + ", outside a "
                            + MAX_TRIGGER_MINUTE + "-minute match."));
        }

        // -- the condition --------------------------------------------------------------------
        String condition = text(rule.get("condition"));
        if (!condition.isEmpty() && !KNOWN_CONDITIONS.contains(condition.toUpperCase(Locale.ROOT))) {
            // The engine's `default -> true` would silently make this ANYTIME. A rule that says what
            // it wants must not be re-interpreted into something else.
            out.add(new Rejection(index, "UNKNOWN_CONDITION",
                    "Rule " + index + " has condition '" + condition + "', which is not one of "
                            + String.join(", ", KNOWN_CONDITIONS.stream().sorted().toList()) + "."));
        }

        // -- the players ----------------------------------------------------------------------
        // Empty means "the engine chooses", which is a legal instruction, so only a named player is
        // checked.
        String onId = text(rule.get("playerOnId"));
        if (!onId.isEmpty()) {
            Long parsed = asLong(onId);
            if (parsed == null) {
                out.add(new Rejection(index, "PLAYER_ID_NOT_A_NUMBER",
                        "Rule " + index + " names player on '" + onId + "', which is not a player id."));
            } else if (!squad.contains(parsed)) {
                out.add(new Rejection(index, "PLAYER_NOT_IN_SQUAD",
                        "Rule " + index + " brings on player " + parsed + ", who is not in the "
                                + teamNames.getOrDefault(teamKey, team) + " squad. He may have been "
                                + "sold, released, or the id may be a typo."));
            }
        }

        String offId = text(rule.get("playerOffId"));
        if (!offId.isEmpty()) {
            Long parsed = asLong(offId);
            if (parsed == null) {
                out.add(new Rejection(index, "PLAYER_ID_NOT_A_NUMBER",
                        "Rule " + index + " names player off '" + offId + "', which is not a player id."));
            } else if (!squad.contains(parsed)) {
                out.add(new Rejection(index, "PLAYER_NOT_IN_SQUAD",
                        "Rule " + index + " takes off player " + parsed + ", who is not in the "
                                + teamNames.getOrDefault(teamKey, team) + " squad. He may have been "
                                + "sold, released, or the id may be a typo."));
            } else if (onId.equals(offId)) {
                out.add(new Rejection(index, "SAME_PLAYER_ON_AND_OFF",
                        "Rule " + index + " brings on and takes off the same player (" + parsed + ")."));
            }
        }

        return out;
    }

    private Set<Long> idsInSquad(Team team) {
        Set<Long> ids = new LinkedHashSet<>();
        if (team == null || team.getId() == null) return ids;
        List<Player> available = squads.availablePlayers(team.getId());
        if (available == null) return ids;
        for (Player p : available) {
            if (p != null && p.getId() != null) ids.add(p.getId());
        }
        return ids;
    }

    /**
     * The team's identity as a stored rule refers to it.
     *
     * <p><b>It is {@code "HOME"} or {@code "AWAY"}, not a club name.</b> This was almost a second
     * shipped defect: the first version of this validator matched the rule's {@code team} against the
     * fixture's club names and would have rejected <b>every valid plan the UI can produce</b> —
     * {@code substitution-plan-view.js} sends {@code team: 'HOME'}, and the engine's
     * {@code scoreFor} / {@code other} compare against exactly those two literals.
     *
     * <p>So a rule's team is a <b>side</b>, and the side is resolved to the fixture's actual club here.
     * A name is also accepted, because a hand-written API call may carry one and rejecting it would be
     * an unhelpful surprise rather than a useful guard — but it is matched against this fixture's two
     * clubs only, never the whole database.
     */
    private static String keyFor(String team, Map<String, String> teamNames,
                                Map<String, String> sideToKey) {
        if (team == null || team.isEmpty()) return null;
        String t = team.trim();
        String bySide = sideToKey.get(t.toUpperCase(Locale.ROOT));
        if (bySide != null) return bySide;
        for (Map.Entry<String, String> e : teamNames.entrySet()) {
            if (e.getValue().equalsIgnoreCase(t)) return e.getKey();
        }
        return null;
    }

    private static String key(Team team) {
        return team.getId() == null ? team.getName() : String.valueOf(team.getId());
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Integer number(Object value) {
        if (value instanceof Number n) return n.intValue();
        String s = text(value);
        if (s.isEmpty()) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long asLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * One reason a rule cannot fire.
     *
     * @param index   1-based position in the submitted plan, so the message points at the right row
     * @param code    stable machine-readable reason, for the frontend and for tests
     * @param message the sentence shown to the manager
     */
    public record Rejection(int index, String code, String message) { }
}