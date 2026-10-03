package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.tactics.TeamTacticsProfile;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.TeamTacticsProfileRepository;
import org.example.footballmanager.newLogic.sim.tactics.TacticsRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The tactical editor's rules, as the engine needs them — per club.
 *
 * <h2>What this replaces</h2>
 *
 * <p>The engine used to build its own {@link TacticsRules} by opening a raw JDBC connection outside
 * Spring and running
 * {@code SELECT rules_json FROM team_tactics_profile WHERE team_id = 1 AND formation = '4-4-2'},
 * with its own hardcoded URL, user and password, every time a match was constructed. Three things were
 * wrong with that at once:
 *
 * <ul>
 *   <li><b>{@code team_id = 1} for every match in the world.</b> All 14,880 clubs ran team 1's tactics.
 *       That is the defect this service exists to end.</li>
 *   <li><b>{@code formation = '4-4-2'}</b> as a literal, so a profile saved as anything else was
 *       silently not found — four of the five saved profiles are 4-3-3.</li>
 *   <li><b>{@code catch (Exception) { return null; }}</b>, which made a wrong password, a missing table,
 *       a dropped column and "this club has no tactics" the same silent nothing. That is how a whole
 *       feature stayed invisible: the engine fell back to the bundled JSON and reported no problem.</li>
 * </ul>
 *
 * <p>The standalone launchers — the diagnostics, the viewer, the exporter — still use that path, because
 * they deliberately run outside Spring. Those keep {@code TacticsRules}' own loader.
 *
 * <h2>What is honestly reported</h2>
 *
 * <p>A club with no profile, a profile whose json will not parse, and a profile authored in a slot
 * vocabulary the engine cannot name are three different situations, and this service says which, by
 * club name. The last one is the important one: the engine's role vocabulary is
 * {@code GK, DL, DCL, DCR, DR, ML, CML, CMR, MR, STL, STR} — 4-4-2's eleven keys — and a 4-3-3 profile
 * is authored in {@code CM, WL, WR, ST} instead. Applying one to the other would give a striker a
 * left winger's cell, so it is reported rather than applied.
 *
 * <p>Results are cached per team id for the life of the JVM. A tactics profile is edited on a screen, not
 * during a match, and the previous code re-read a 132 KB JSON on <em>every</em> match construction.
 */
@Service
public class TacticsRulesProvider {

    private static final Logger log = LoggerFactory.getLogger(TacticsRulesProvider.class);

    /**
     * The role keys the engine can name — <b>derived from {@code RealSquadFactory.SLOT_ORDER}</b>, which
     * is the one definition of it.
     *
     * <p>It was a literal here once, and the pair was guarded by a test asserting they were equal. That is
     * the wrong shape: a second copy of a rule plus a test watching it is one more thing to keep in step,
     * and the failure is silent — a rule keyed to a slot no player occupies is a rule nothing reads.
     * Deriving it means there is nothing to drift.
     */
    static final java.util.Set<String> ENGINE_SLOT_KEYS =
            java.util.Set.of(org.example.footballmanager.newLogic.sim.RealSquadFactory.SLOT_ORDER);

    private final TeamTacticsProfileRepository profiles;
    private final TeamRepository teams;
    private final TacticsRules fallback;
    private final Map<Long, TacticsRules> cache = new ConcurrentHashMap<>();

    public TacticsRulesProvider(TeamTacticsProfileRepository profiles, TeamRepository teams) {
        this.profiles = profiles;
        this.teams = teams;
        // The bundled export the owner produced while building the engine. It is the engine's floor, not
        // a club's tactics, and the log line below says so once rather than on every match.
        this.fallback = new TacticsRules();
        log.info("Tactics fallback in use: source={} rules={} formation={}",
                fallback.getSource(), fallback.getRuleCount(), TacticsRules.FORMATION);
    }

    /**
     * The rules for a team, or the bundled fallback when it has none it can be given.
     *
     * <p>Never null: the engine has to run a match whatever the editor holds, and a null here would put
     * the choice back where it started.
     */
    @Transactional(readOnly = true)
    public TacticsRules forTeam(Long teamId) {
        if (teamId == null) {
            return fallback;
        }
        return cache.computeIfAbsent(teamId, this::load);
    }

    private TacticsRules load(Long teamId) {
        TeamTacticsProfile profile = profiles.findByTeamId(teamId).orElse(null);
        if (profile == null) {
            log.debug("{} has no tactical profile; the bundled fallback applies.",
                    teamName(teamId, null));
            return fallback;
        }

        String formation = profile.getFormation();
        TacticsRules rules = TacticsRules.fromProfileJson(
                profile.getRulesJson(), formation, "tactics editor (" + formation + ")");
        if (rules == null) {
            log.warn("{} has a tactical profile for {} whose rules could not be read; the bundled "
                            + "fallback applies.", teamName(teamId, formation), formation);
            return fallback;
        }

        var unknown = slotKeysIn(profile.getRulesJson()).stream()
                .filter(key -> !ENGINE_SLOT_KEYS.contains(key))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            // Reported, not applied. The engine's players are 4-4-2 roles and a rule keyed to a slot it
            // has no player for is a rule nothing will ever read.
            log.warn("{} is authored in {} with slot keys the engine cannot name ({}). Its shape is not "
                            + "applied; the bundled 4-4-2 fallback is. Named so it is a visible gap rather "
                            + "than a club that quietly plays the wrong formation.",
                    teamName(teamId, formation), formation, unknown);
            return fallback;
        }

        log.info("{} plays its own {} tactics: {} rules from the tactical editor.",
                teamName(teamId, formation), formation, rules.getRuleCount());
        return rules;
    }

    /** The distinct slot keys in a profile's json, or empty when it will not parse. */
    private java.util.Set<String> slotKeysIn(String rulesJson) {
        if (rulesJson == null || rulesJson.isBlank()) {
            return java.util.Set.of();
        }
        java.util.Set<String> keys = new java.util.TreeSet<>();
        var matcher = java.util.regex.Pattern.compile("\"slotKey\"\\s*:\\s*\"([^\"]+)\"").matcher(rulesJson);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    private String teamName(Long teamId, String formation) {
        return teams.findById(teamId)
                .map(team -> team.getName() + " (" + teamId + ")")
                .orElse("team " + teamId);
    }

    /** Drops a club's cached rules so an edit takes effect without a restart. */
    public void evict(Long teamId) {
        if (teamId != null) {
            cache.remove(teamId);
        }
    }
}
