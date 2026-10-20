package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.tactics.TeamTacticsProfile;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.TeamTacticsProfileRepository;
import org.example.footballmanager.newLogic.sim.RealSquadFactory;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.tactics.TacticsRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cluster F #1: a club's tactical editor shape is the shape its players actually play.
 *
 * <p>The engine built its own {@link TacticsRules} and read
 * {@code team_tactics_profile WHERE team_id = 1 AND formation = '4-4-2'} over a raw JDBC connection of its
 * own. So **all 14,880 clubs ran team 1's tactics**; a profile saved as 4-3-3 was silently not found; and
 * a missing profile, a wrong password and an unparseable profile were all the same swallowed exception —
 * which is why the bundled fallback export looked like a working feature rather than a fallback.
 *
 * <p>The owner built the engine against that export, so the engine and the export already speak the same
 * slot vocabulary. {@link TacticsRulesProvider} now derives its accepted-key set from
 * {@code RealSquadFactory.SLOT_ORDER} rather than keeping a second copy, so there is nothing to drift.
 *
 * <h2>Why the fixture is hand-written json</h2>
 *
 * <p>Shaped like the five real profiles in {@code var/tactics-editor-profiles.json}: 11 slots, both
 * possession contexts, one rule per ball-state cell. A fixture that is merely valid json would pass
 * against a reader that cannot read what the editor writes.
 */
class TacticsRulesProviderTest extends BaseTest {

    /** 4-4-2 — the vocabulary the engine can name, which is what the fallback export was authored in. */
    private static final String[] ENGINE_SLOTS = RealSquadFactory.SLOT_ORDER;

    /** 4-3-3 — what four of the owner's five saved profiles actually use. */
    private static final String[] SLOTS_433 = {
            "GK", "DL", "DCL", "DCR", "DR", "WL", "CML", "CMR", "WR", "CM", "ST"};

    @Autowired private TacticsRulesProvider provider;
    @Autowired private TeamTacticsProfileRepository profiles;
    @Autowired private TeamRepository teams;
    @Autowired private CountryRepository countries;

    @Test
    @DisplayName("the accepted slot keys are derived from the catalog, so the engine can play all nine")
    void theAcceptedKeysAreDerivedFromTheCatalog() {
        // Nine layouts x eleven slots. A key set that was a literal would silently cap the engine at
        // whichever formation it was written for, and the failure is silent: a rule keyed to a slot no
        // player wears is a rule nothing reads.
        java.util.Set<String> fromCatalog = new java.util.LinkedHashSet<>();
        for (String formation : TacticsRulesProvider.FORMATIONS) {
            fromCatalog.addAll(Arrays.stream(RealSquadFactory.slotOrderFor(formation))
                    .collect(Collectors.toSet()));
        }
        assertEquals(fromCatalog, TacticsRulesProvider.ENGINE_SLOT_KEYS,
                "the accepted slot keys are not the catalog's. Either the provider is capped at one "
                        + "formation or the engine has grown a role the provider will not accept.");
        for (String formation : TacticsRulesProvider.FORMATIONS) {
            assertEquals(11, RealSquadFactory.slotOrderFor(formation).length,
                    formation + " does not have eleven slots. Eleven is a constant of the sport — if this "
                            + "changes, the engine's assumption that every formation fits an XI is wrong.");
        }
    }

    @Test
    @DisplayName("a formation's keys are its own, so a 4-3-3 profile is not judged as 4-4-2")
    void aProfileIsJudgedAgainstItsOwnFormation() {
        java.util.Set<String> fortyFourTwo = TacticsRulesProvider.playableKeys("4-4-2");
        java.util.Set<String> fortyThreeThree = TacticsRulesProvider.playableKeys("4-3-3");

        assertTrue(fortyFourTwo.contains("STL") && !fortyFourTwo.contains("ST"),
                "4-4-2's keys should name a striker STL");
        assertTrue(fortyThreeThree.contains("ST") && !fortyThreeThree.contains("STL"),
                "4-3-3's keys should name a striker ST");
        assertTrue(fortyThreeThree.contains("CM") && fortyFourTwo.stream().noneMatch("CM"::equals),
                "4-3-3 has a holding midfielder CM and 4-4-2 does not — that difference is the whole "
                        + "reason a 4-3-3 profile could not be played before");
    }

    @Test
    @Transactional
    @DisplayName("a club plays its own editor tactics, not team 1's")
    void aClubPlaysItsOwnTactics() {
        Team club = aClub("Own tactics");
        saveProfile(club, "4-4-2", rulesJson(ENGINE_SLOTS, 0));

        TacticsRules rules = provider.forTeam(club.getId());

        assertNotEquals(0, rules.getRuleCount(),
                "the provider returned no rules for a club that has a profile, so the bundled fallback is "
                        + "being served and the wiring has changed nothing");
        assertTrue(rules.getSource().contains("tactics editor"),
                "the rules did not come from the tactical editor; source was: " + rules.getSource());
    }

    @Test
    @Transactional
    @DisplayName("two clubs authored differently get different rules")
    void twoClubsGetTheirOwnRules() {
        // <b>On the rule targets, not the anchors.</b> My first version asserted the anchors differed and
        // it was wrong: anchors come from the FORMATION by design, so two clubs both playing 4-4-2
        // correctly share them. What a profile owns is where each role goes for each ball position, and
        // that is what proves one club is not being handed another's shape.
        Team one = aClub("Alpha");
        Team two = aClub("Beta");
        saveProfile(one, "4-4-2", rulesJson(ENGINE_SLOTS, 0));
        saveProfile(two, "4-4-2", rulesJson(ENGINE_SLOTS, 1));

        TacticsRules first = provider.forTeam(one.getId());
        TacticsRules second = provider.forTeam(two.getId());

        assertNotEquals(0, first.getRuleCount(), "Alpha has a profile and got no rules");
        assertNotEquals(0, second.getRuleCount(), "Beta has a profile and got no rules");

        // CELL_2_3: a ball at 3.5, 4.5 maps to that editor cell, and both profiles have a rule for it.
        Position ball = new Position(3.5, 4.5);
        Position alphaTarget = first.desiredCell("STL", ball, "HOME");
        Position betaTarget = second.desiredCell("STL", ball, "HOME");

        assertNotEquals(alphaTarget.getRow(), betaTarget.getRow(),
                "two clubs authored a row apart got the same target for the same role and ball. Under the "
                        + "old loader both read team 1's profile, so every club in the world shared a shape — "
                        + "which is the whole defect.");
    }

    @Test
    @Transactional
    @DisplayName("a club with no profile gets the bundled fallback")
    void aClubWithNoProfileFallsBack() {
        Team club = aClub("No profile");

        TacticsRules rules = provider.forTeam(club.getId());

        assertTrue(rules.getRuleCount() > 0, "a club with no profile must still get a usable shape");
        assertTrue(!rules.getSource().contains("tactics editor"),
                "a club with no profile was served the editor's rules; source was: " + rules.getSource());
    }

    @Test
    @Transactional
    @DisplayName("a 4-3-3 profile is now played, and a mislabelled one is still refused")
    void aProfileIsAcceptedInItsOwnFormationAndRefusedUnderAWrongLabel() {
        // Four of the owner's five saved profiles are 4-3-3. They used to be refused, because the engine
        // only had 4-4-2's eleven role keys and every one of their rules was keyed to a slot nothing wore.
        Team honest = aClub("Four three three");
        saveProfile(honest, "4-3-3", rulesJson(SLOTS_433, 0));

        TacticsRules played = provider.forTeam(honest.getId());

        assertTrue(played.getSource().contains("tactics editor"),
                "a 4-3-3 profile is still refused. Its keys are CM/WL/WR/ST and the engine now builds an "
                        + "XI wearing exactly those for a 4-3-3, so there is nothing left to refuse on. "
                        + "Source was: " + played.getSource());
        assertNotEquals(0, played.getRuleCount(), "the 4-3-3 profile produced no rules");

        // The check stays per formation, so a profile that uses 4-3-3 keys while LABELLING itself 4-4-2
        // is a mismatch worth naming rather than applying: a midfielder would stand where a winger
        // belongs and nobody would be a striker.
        Team mislabelled = aClub("Mislabelled");
        saveProfile(mislabelled, "4-4-2", rulesJson(SLOTS_433, 0));

        assertTrue(!provider.forTeam(mislabelled.getId()).getSource().contains("tactics editor"),
                "a profile using 4-3-3 role keys was applied under a 4-4-2 label.");
    }

    @Test
    @Transactional
    @DisplayName("the same club is asked once, and an edit can be picked up")
    void cachingIsPerClubAndEvictable() {
        Team club = aClub("Cached");
        saveProfile(club, "4-4-2", rulesJson(ENGINE_SLOTS, 0));

        TacticsRules first = provider.forTeam(club.getId());
        assertSame(first, provider.forTeam(club.getId()),
                "the provider re-read a profile it had already read. The old loader re-read a 132 KB "
                        + "export on every single match construction.");

        provider.evict(club.getId());
        assertNotEquals(System.identityHashCode(first),
                System.identityHashCode(provider.forTeam(club.getId())),
                "evict did not drop the cached rules, so an edit in the editor could not take effect "
                        + "without a restart");
    }

    // --- fixture ---

    private Team aClub(String label) {
        Country country = new Country();
        country.setName("ZZ Tactics " + label + " " + UUID.randomUUID());
        country.setIsoCode("T" + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        Team team = new Team();
        team.setName("ZZ Tactics club " + label + " " + UUID.randomUUID());
        team.setCountry(country);
        team.setType(CompetitionTeamType.CLUB);
        return teams.save(team);
    }

    private void saveProfile(Team team, String formation, String rulesJson) {
        TeamTacticsProfile profile = new TeamTacticsProfile();
        profile.setTeam(team);
        profile.setFormation(formation);
        profile.setStyle("Balanced");
        profile.setRulesJson(rulesJson);
        profile.setVersion(1L);
        profiles.save(profile);
    }

    /**
     * Editor-shaped json: every slot, both possession contexts, one rule per ball-state cell.
     *
     * @param rowShift moves the targets down a row, so two clubs built with different shifts are
     *                 genuinely different shapes rather than the same rules twice
     */
    private String rulesJson(String[] slots, int rowShift) {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (String slot : slots) {
            for (String context : new String[]{"WE_HAVE_BALL", "OPPONENT_HAS_BALL"}) {
                for (int row = 0; row < 7; row++) {
                    if (!first) {
                        json.append(',');
                    }
                    first = false;
                    json.append("{\"slotKey\":\"").append(slot)
                       .append("\",\"ballStateKey\":\"CELL_").append(row).append("_3\"")
                       .append(",\"possessionContext\":\"").append(context)
                       .append("\",\"targetCellKey\":\"CELL_")
                       .append(Math.min(6, row + rowShift)).append("_3\"}");
                }
            }
        }
        return json.append(']').toString();
    }
}
