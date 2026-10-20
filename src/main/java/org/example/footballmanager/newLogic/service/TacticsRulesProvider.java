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

import java.util.List;
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
     * Every role key the engine can name, across every layout in the catalog.
     *
     * <p>Derived from the catalog rather than from 4-4-2's eleven, because the engine now plays the
     * formation a club actually uses: {@code RealSquadFactory.slotOrderFor(formation)} reads the same
     * nine layouts. It was a literal once and guarded by a test asserting it matched
     * {@code SLOT_ORDER}; that is the wrong shape, because the answer changes when the engine does.
     *
     * <p>A profile is accepted when its keys are a subset of the union <em>and</em> it is playable in the
     * formation it claims — see {@link #playableKeys}.
     */
    /**
     * The nine layouts the catalog holds. Written once so nothing has to enumerate them again.
     *
     * <p><b>Declared before {@link #ENGINE_SLOT_KEYS} on purpose.</b> The keys are built by a static
     * method that reads this, and Java initialises static fields in declaration order — the first version
     * had it the other way round and the class died in its initialiser with
     * {@code FORMATIONS is null}, which took the whole application context with it.
     */
    static final java.util.List<String> FORMATIONS = java.util.List.of(
            "4-4-2", "4-3-3", "4-2-3-1", "4-1-4-1", "3-5-2", "5-3-2", "3-4-3", "4-5-1", "5-4-1");

    static final java.util.Set<String> ENGINE_SLOT_KEYS = allCatalogSlotKeys();

    private static java.util.Set<String> allCatalogSlotKeys() {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (String formation : FORMATIONS) {
            keys.addAll(java.util.Arrays.asList(
                    org.example.footballmanager.newLogic.sim.RealSquadFactory.slotOrderFor(formation)));
        }
        return java.util.Set.copyOf(keys);
    }

    /**
     * The role keys playable in one formation — the engine's eleven for <em>that</em> shape.
     *
     * <p>This is the check that matters, and the union above is not enough: a 4-3-3 profile keyed
     * {@code CM/WL/WR/ST} is in the union, but applying it to 4-4-2 players would put a striker in a
     * left winger's slot. So a profile is measured against its own formation.
     */
    static java.util.Set<String> playableKeys(String formation) {
        return java.util.Set.of(
                org.example.footballmanager.newLogic.sim.RealSquadFactory.slotOrderFor(formation));
    }

    private final TeamTacticsProfileRepository profiles;
    private final TeamRepository teams;
    private final org.example.footballmanager.newLogic.repository.TacticRepository tactics;
    private final TacticsRules fallback;

    /**
     * Parsed rules, keyed by <b>tactic id</b>.
     *
     * <p>Keyed by tactic rather than by club because a club now has several: one cache slot per club
     * would make the second tactic evict the first and every match would re-parse whichever it happened
     * not to want. The id space is separate from the team id space, which matters here — this is the same
     * class of confusion that produced the P0 fixture/match defect, and the keys are different kinds of
     * thing, so they are never mixed in one map.
     */
    private final Map<Long, TacticsRules> cache = new ConcurrentHashMap<>();

    /** Which tactic is a club's default, or absent when it has none. Kept out of {@link #cache}. */
    private final Map<Long, Long> defaultTacticByTeam = new ConcurrentHashMap<>();

    /** Which club a cached tactic belongs to, so one club's edit can drop all of its entries. */
    private final Map<Long, Long> tacticOwner = new ConcurrentHashMap<>();

    /**
     * Rules for clubs that hold no tactic, keyed by <b>club id</b>.
     *
     * <p>A second map rather than a shared one, because the two id spaces overlap: club 5's tactic 5 and
     * club 5 itself are both 5, and putting both in one map would let a club's cached legacy profile be
     * served as though it were a tactic's rules. Nothing about this feature should reintroduce the
     * confusion the P0 defect came from.
     *
     * <p>It exists because dropping the legacy read path would switch the one club that has a profile back
     * to the bundled rules, and because that path was cached and must stay cached: it reads a profile of
     * around 132 KB, and uncached it would be re-read on every match construction for every club that has
     * not been given a tactic.
     */
    private final Map<Long, TacticsRules> legacyCache = new ConcurrentHashMap<>();

    public TacticsRulesProvider(TeamTacticsProfileRepository profiles, TeamRepository teams,
                                org.example.footballmanager.newLogic.repository.TacticRepository tactics) {
        this.profiles = profiles;
        this.teams = teams;
        this.tactics = tactics;
        // The bundled export the owner produced while building the engine. It is the engine's floor, not
        // a club's tactics, and the log line below says so once rather than on every match.
        this.fallback = new TacticsRules();
        log.info("Tactics fallback in use: source={} rules={} formation={}",
                fallback.getSource(), fallback.getRuleCount(), TacticsRules.FORMATION);
    }

    /**
     * The rules for a team's <b>default</b> tactic, or the bundled fallback.
     *
     * <p>Never null: the engine has to run a match whatever the editor holds, and a null here would put
     * the choice back where it started. A club with no default falls back exactly as it did before the
     * library existed — forgetting to set one is not an error state, it is the ordinary state of a club
     * nobody has configured.
     */
    @Transactional(readOnly = true)
    public TacticsRules forTeam(Long teamId) {
        if (teamId == null) {
            return fallback;
        }
        Long tacticId = defaultTacticByTeam.computeIfAbsent(teamId, this::resolveDefaultTacticId);
        if (tacticId == null) {
            return legacyCache.computeIfAbsent(teamId, this::loadFromLegacyProfile);
        }
        return forTactic(teamId, tacticId);
    }

    /**
     * The rules for one named tactic of one club.
     *
     * <p>The tactic id is checked against the club, so a caller holding a tactic from another club is
     * refused rather than quietly given another club's rules. The two id spaces overlap — club 1's tactic
     * 1 and club 2's tactic 1 are both 1 — and a simulation that picked up the wrong one would be a
     * manager's team playing somebody else's shape, which is the exact shape of the P0 defect.
     */
    @Transactional(readOnly = true)
    public TacticsRules forTactic(Long teamId, Long tacticId) {
        if (teamId == null || tacticId == null) {
            return fallback;
        }
        return cache.computeIfAbsent(tacticId, id -> load(teamId, id));
    }

    /** The club's default tactic id, or null when it has none. */
    private Long resolveDefaultTacticId(Long teamId) {
        List<org.example.footballmanager.newLogic.model.tactics.Tactic> defaults =
                tactics.findDefaultsForTeam(teamId);
        if (defaults.isEmpty()) {
            return null;
        }
        if (defaults.size() > 1) {
            // The service makes this impossible. If it has happened anyway, taking the lowest id is a
            // decision rather than a preference, and the warning says the invariant is broken.
            log.warn("{} has {} default tactics; the service allows one. Using the oldest.",
                    teamName(teamId, null), defaults.size());
        }
        return defaults.get(0).getId();
    }

    private TacticsRules load(Long teamId, Long tacticId) {
        var tactic = tactics.findByIdAndTeamId(tacticId, teamId).orElse(null);
        if (tactic == null) {
            log.warn("{} has no tactic {}; the bundled fallback applies.", teamName(teamId, null), tacticId);
            return fallback;
        }
        tacticOwner.put(tacticId, teamId);
        return rulesFor(teamId, tactic.getFormation(), tactic.getRulesJson(),
                "tactics editor (" + tactic.getName() + ")");
    }

    /**
     * The pre-library read path: one {@code TeamTacticsProfile} per club.
     *
     * <p>Still here because {@link #forTeam} falls back to it while a club holds no tactic, and dropping
     * it would silently switch the one club that has a profile back to the bundled rules. It goes when
     * every club holds a tactic, not before.
     */
    private TacticsRules loadFromLegacyProfile(Long teamId) {
        TeamTacticsProfile profile = profiles.findByTeamId(teamId).orElse(null);
        if (profile == null) {
            log.debug("{} has no tactical profile; the bundled fallback applies.",
                    teamName(teamId, null));
            return fallback;
        }
        return rulesFor(teamId, profile.getFormation(), profile.getRulesJson(),
                "tactics editor (" + profile.getFormation() + ")");
    }

    /**
     * The rule a set of authored rules produces, or the fallback when they cannot be played.
     *
     * <p>Formation validation is here, once, rather than in each of the two readers above: a profile keyed
     * to slots its own formation does not have is a rule nothing will ever read, and it is reported and
     * refused rather than quietly played in the wrong shape.
     */
    private TacticsRules rulesFor(Long teamId, String formation, String rulesJson, String sourceLabel) {
        String formationLabel = formation == null ? null : formation.trim();
        TacticsRules rules = TacticsRules.fromProfileJson(rulesJson, formationLabel, sourceLabel);
        if (rules == null) {
            log.warn("{} has a tactical profile for {} whose rules could not be read; the bundled "
                            + "fallback applies.", teamName(teamId, formationLabel), formationLabel);
            return fallback;
        }

        // Against ITS OWN formation, not the union of all nine. A 4-3-3 profile keyed CM/WL/WR/ST is
        // perfectly playable in 4-3-3 and nonsense in 4-4-2.
        java.util.Set<String> playable = playableKeys(formationLabel);
        var unknown = slotKeysIn(rulesJson).stream()
                .filter(key -> !playable.contains(key))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            // Reported, not applied. The engine's players are 4-4-2 roles and a rule keyed to a slot it
            // has no player for is a rule nothing will ever read.
            log.warn("{} is authored in {} with slot keys that formation does not have ({}). Its shape is "
                            + "not applied; the bundled 4-4-2 fallback is. Named so it is a visible gap "
                            + "rather than a club that quietly plays the wrong formation.",
                    teamName(teamId, formationLabel), formationLabel, unknown);
            return fallback;
        }

        log.info("{} plays its own {} tactics: {} rules from the tactical editor.",
                teamName(teamId, formationLabel), formationLabel, rules.getRuleCount());
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

    /**
     * Drops a club's cached rules so an edit takes effect without a restart.
     *
     * <p>Drops the default resolution <b>and</b> every tactic cached for that club. Dropping only the
     * default would leave the edited tactic's rules sitting in the cache under its own id, and the next
     * match would read the old shape — an edit that saved and did nothing, which is the failure mode this
     * class has already had once.
     *
     * <p>Precise, not {@code cache.clear()}: with 14,723 clubs the parsed rules are shared, and one
     * club's edit throwing away every other club's would turn a save into a full re-parse of the world.
     * Every cached tactic has an owner entry — {@link #load} records it before returning — so the club's
     * own entries can be found and only those dropped.
     */
    public void evict(Long teamId) {
        if (teamId == null) {
            return;
        }
        defaultTacticByTeam.remove(teamId);
        legacyCache.remove(teamId);
        tacticOwner.forEach((tacticId, owner) -> {
            if (java.util.Objects.equals(owner, teamId)) {
                cache.remove(tacticId);
            }
        });
        tacticOwner.entrySet().removeIf(entry -> java.util.Objects.equals(entry.getValue(), teamId));
    }

    /**
     * Drops every club's cached rules.
     *
     * <p>For a whole-world change — every club being given its first default tactic — where evicting one
     * club at a time would mean one pass over the world to find out which clubs exist and another to evict
     * them. Deliberately blunt and deliberately rare: the ordinary edit path calls {@link #evict(Long)},
     * which touches one club.
     */
    public void evictAll() {
        cache.clear();
        legacyCache.clear();
        defaultTacticByTeam.clear();
        tacticOwner.clear();
    }

    /**
     * Drops one tactic, for an edit to that tactic.
     *
     * <p>Does not touch the club's default resolution: editing a tactic cannot change which one it is, and
     * resolving that again on the next match would be a query for nothing.
     */
    public void evict(Long teamId, Long tacticId) {
        if (tacticId != null) {
            cache.remove(tacticId);
            tacticOwner.remove(tacticId);
        }
    }
}
